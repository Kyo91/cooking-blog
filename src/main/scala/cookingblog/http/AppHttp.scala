package cookingblog.http

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.domain.*
import cookingblog.http.api.ApiRoutes
import cookingblog.repository.DoobieRepositories
import cookingblog.service.{PhotoCleanup, PhotoService, RecipeApiService, RecipeSort}
import cookingblog.storage.PhotoStore
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, RequestId}
import org.typelevel.log4cats.Logger

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.annotation.targetName

/** Authenticated server-rendered browser pages. Mutations intentionally go through the public API
  * from the small progressively-enhanced client script, keeping validation and CSRF handling in one
  * place.
  */
final class AppHttp(
    credentialsAuthenticator: CredentialsAuthenticator[IO],
    sessionManager: SessionManager[IO],
    transactor: Transactor[IO],
    authConfig: AuthConfig,
    photoStore: PhotoStore
)(using logger: Logger[IO]) {
  private val sessionCookieName = "cooking_blog_session"
  private val csrfCookieName = "cooking_blog_csrf"
  private val photoCleanup = PhotoCleanup(photoStore)
  private val photoService = PhotoService(transactor, photoStore, photoCleanup)
  private val recipeService = RecipeApiService(transactor, photoCleanup)
  private val apiRoutes = ApiRoutes(recipeService, photoService, sessionManager)

  def cleanupOrphanPhotos: IO[Int] = photoService.cleanupOrphans

  lazy val app: HttpApp[IO] =
    RequestId.httpApp(
      ErrorHandling.Recover.total(
        ErrorAction.log(
          routes,
          messageFailureLogAction = (throwable, message) => logger.warn(throwable)(message),
          serviceErrorLogAction = (throwable, message) => logger.error(throwable)(message)
        )
      )
    )

  private val routes: HttpApp[IO] = HttpApp[IO] { request =>
    publicRoutes(request).value.flatMap {
      case Some(response) => response.pure[IO]
      case None           => authenticate(request).flatMap(protectedResponse(request, _))
    }
  }

  private val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "login" => Ok(loginPage(None), `Content-Type`(MediaType.text.html))
    case request @ POST -> Root / "login" =>
      request.as[UrlForm].flatMap { form =>
        val username = form.values.get("username").flatMap(_.headOption).getOrElse("")
        val password = form.values.get("password").flatMap(_.headOption).getOrElse("")
        credentialsAuthenticator.authenticate(username, password).flatMap {
          case Some(principal) =>
            sessionManager.create(principal).flatMap { session =>
              SeeOther(Location(Uri.unsafeFromString("/"))).map(
                _.addCookie(sessionCookie(session)).addCookie(csrfCookie(session))
              )
            }
          case None =>
            IO.pure(
              Response[IO](Status.Unauthorized)
                .withEntity(loginPage(Some("Invalid username or password.")))
                .putHeaders(`Content-Type`(MediaType.text.html))
            )
        }
      }
  }

  private def protectedResponse(
      request: Request[IO],
      authenticatedSession: Option[AuthenticatedSession]
  ): IO[Response[IO]] =
    authenticatedSession match {
      case Some(session)                => protectedRoutes(session).orNotFound(request)
      case None if expectsHtml(request) => SeeOther(Location(Uri.unsafeFromString("/login")))
      case None                         =>
        IO.pure(
          Response[IO](Status.Unauthorized)
            .withEntity("""{"code":"unauthorized","message":"Authentication required"}""")
            .withContentType(`Content-Type`(MediaType.application.json))
        )
    }

  private def protectedRoutes(session: AuthenticatedSession): HttpRoutes[IO] =
    apiRoutes.routes(session) <+> browserRoutes(session) <+> HttpRoutes.of[IO] {
      case GET -> Root / "health" / "live" =>
        Ok("""{"status":"ok"}""").map(_.withContentType(`Content-Type`(MediaType.application.json)))
      case GET -> Root / "health" / "ready" =>
        (
          sql"select 1".query[Int].unique.transact(transactor).attempt,
          photoService.checkStoreWritable
        ).tupled.flatMap {
          case (Right(1), true) =>
            Ok("""{"status":"ready"}""").map(
              _.withContentType(`Content-Type`(MediaType.application.json))
            )
          case _ =>
            ServiceUnavailable("""{"status":"not_ready"}""").map(
              _.withContentType(`Content-Type`(MediaType.application.json))
            )
        }
      case request @ POST -> Root / "logout" =>
        request.as[UrlForm].flatMap { form =>
          form.values
            .get("csrf_token")
            .flatMap(_.headOption)
            .traverse(sessionManager.validateCsrf(session, _))
            .map(_.contains(true))
            .flatMap {
              case true =>
                sessionManager.invalidate(session.token) *> SeeOther(
                  Location(Uri.unsafeFromString("/login"))
                ).map(_.removeCookie(sessionCookieName).removeCookie(csrfCookieName))
              case false => Forbidden("Invalid CSRF token.")
            }
        }
    }

  private def browserRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root =>
      val query = request.uri.query.params.get("q").getOrElse("")
      renderHome(query, session).flatMap(Ok(_, `Content-Type`(MediaType.text.html)))
    case request @ GET -> Root / "recipes" / "search" =>
      val query = request.uri.query.params.get("q").getOrElse("")
      recipeService
        .listRecipes(Some(query), RecipeSort.Recent, 50, None)
        .map {
          case Right(page) =>
            Ok(recipeCards(page.items, query), `Content-Type`(MediaType.text.html))
          case Left(_) =>
            BadRequest(
              "<p class=\"error\">Search could not be completed.</p>",
              `Content-Type`(MediaType.text.html)
            )
        }
        .flatten
    case request @ GET -> Root / "recipes" / "new" =>
      Ok(
        recipeForm(None, request.uri.query.params.get("title").getOrElse(""), "", ""),
        `Content-Type`(MediaType.text.html)
      )
    case GET -> Root / "recipes" / rawRecipeId / "edit" =>
      recipeId(rawRecipeId).fold(notFoundPage)(id => recipeEditPage(id))
    case GET -> Root / "recipes" / rawRecipeId / "meals" / "new" =>
      recipeId(rawRecipeId).fold(notFoundPage)(id =>
        recipeService.getRecipe(id).flatMap {
          case Right(recipe) => Ok(mealForm(recipe, None), `Content-Type`(MediaType.text.html))
          case Left(_)       => notFoundPage
        }
      )
    case GET -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "edit" =>
      (recipeId(rawRecipeId), mealId(rawMealId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, mealIdValue) =>
          (
            recipeService.getRecipe(recipeIdValue),
            recipeService.getMeal(recipeIdValue, mealIdValue)
          ).mapN {
            case (Right(recipe), Right(meal)) =>
              Ok(mealForm(recipe, Some(meal)), `Content-Type`(MediaType.text.html))
            case _ => notFoundPage
          }.flatten
      }
    case GET -> Root / "recipes" / rawRecipeId =>
      recipeId(rawRecipeId).fold(notFoundPage)(recipeDetailPage)
  }

  private def renderHome(query: String, session: AuthenticatedSession): IO[String] =
    recipeService
      .listRecipes(Option(query).filter(_.trim.nonEmpty), RecipeSort.Recent, 50, None)
      .map {
        case Right(results) =>
          page(
            "Recipes",
            nav(
              session
            ) + s"""<main><div class=\"page-heading\"><div><h1>Recipes</h1><p>Find something you have made, then capture the next time.</p></div><a class=\"button primary\" id=\"new-recipe\" href=\"/recipes/new\" aria-label=\"Create a new recipe\">+</a></div><label for=\"recipe-search\">Search recipes</label><input id=\"recipe-search\" type=\"search\" value=\"${escape(
                query
              )}\" placeholder=\"grilled chicken, weeknight, sous vide\" autocomplete=\"off\" autofocus><p id=\"search-status\" class=\"muted\" aria-live=\"polite\"></p><section id=\"recipe-results\" class=\"recipe-grid\">${recipeCards(
                results.items,
                query
              )}</section></main>""",
            browserScript
          )
        case Left(_) =>
          page(
            "Recipes",
            nav(
              session
            ) + "<main><h1>Recipes</h1><p class=\"error\">Recipes could not be loaded.</p></main>",
            browserScript
          )
      }

  private def recipeEditPage(id: RecipeId): IO[Response[IO]] =
    (recipeService.getRecipe(id), recipeKeywords(id)).mapN {
      case (Right(recipe), keywords) =>
        Ok(
          recipeForm(Some(recipe), recipe.title, recipe.description, keywords.mkString(", ")),
          `Content-Type`(MediaType.text.html)
        )
      case _ => notFoundPage
    }.flatten

  private def recipeDetailPage(id: RecipeId): IO[Response[IO]] = recipeDetail(id).flatMap {
    case None         => notFoundPage
    case Some(detail) => Ok(detailPage(detail), `Content-Type`(MediaType.text.html))
  }

  private def recipeDetail(id: RecipeId): IO[Option[BrowserRecipe]] = {
    import DoobieRepositories.*
    (for {
      recipe <- recipes.find(id)
      result <- recipe.traverse { value =>
        (
          meals.listByRecipe(id),
          references.listByRecipe(id),
          photos.listByRecipe(id),
          keywords.listByRecipe(id)
        ).tupled.flatMap { case (mealRows, referenceRows, photoRows, keywordRows) =>
          referenceRows
            .traverse(reference =>
              (
                scrapeJobs.findLatestByReference(reference.id),
                scrapedDocuments.findByReference(reference.id)
              ).tupled.map(BrowserReference(reference, _, _))
            )
            .map(rows =>
              BrowserRecipe(value, mealRows, photoRows, keywordRows.map(_.keyword), rows)
            )
        }
      }
    } yield result).transact(transactor)
  }

  private def recipeKeywords(id: RecipeId): IO[List[String]] =
    DoobieRepositories.keywords.listByRecipe(id).map(_.map(_.keyword)).transact(transactor)

  private def recipeCards(recipes: List[Recipe], query: String): String =
    if (recipes.isEmpty) {
      s"""<div class=\"empty-state\"><h2>No recipes found</h2><p>Try another phrase, or start a recipe with this search.</p><a class=\"button primary\" href=\"/recipes/new?title=${url(
          query
        )}\">Create “${escape(query.trim)}”</a></div>"""
    } else
      recipes.map { recipe =>
        val image =
          s"<img src=\"/media/recipes/${id(recipe.id)}/primary?variant=thumbnail\" alt=\"\" loading=\"lazy\">"
        s"""<article class=\"recipe-card\">$image<div><h2><a href=\"/recipes/${id(
            recipe.id
          )}\">${escape(recipe.title)}</a></h2><p>${escape(
            summary(recipe.description)
          )}</p><p class=\"muted\">${recipe.lastMadeAt.fold("Not cooked yet")(instant =>
            s"Last made ${date(instant)}"
          )}</p></div></article>"""
      }.mkString

  private def recipeForm(
      recipe: Option[Recipe],
      titleValue: String,
      descriptionValue: String,
      keywords: String
  ): String = {
    val editing = recipe.nonEmpty
    val heading = if (editing) "Edit recipe" else "New recipe"
    val action = recipe.fold("/api/v1/recipes")(value => s"/api/v1/recipes/${id(value.id)}")
    val method = if (editing) "PATCH" else "POST"
    page(
      heading,
      s"""<main class=\"form-page\"><a href=\"${recipe.fold("/")(value =>
          s"/recipes/${id(value.id)}"
        )}\">← Back</a><h1>$heading</h1><form class=\"api-form\" data-api=\"$action\" data-method=\"$method\" data-redirect=\"${recipe
          .fold("/")(value =>
            s"/recipes/${id(value.id)}"
          )}\"><label for=\"title\">Title</label><input id=\"title\" name=\"title\" required maxlength=\"200\" value=\"${escape(
          titleValue
        )}\"><label for=\"description\">Description</label><textarea id=\"description\" name=\"description\" maxlength=\"10000\">${escape(
          descriptionValue
        )}</textarea><label for=\"keywords\">Keywords</label><input id=\"keywords\" name=\"keywords\" value=\"${escape(
          keywords
        )}\" placeholder=\"sous vide, chicken, bbq\"><p class=\"hint\">Separate keywords with commas. Multi-word keywords are kept together.</p><p class=\"form-error\" aria-live=\"polite\"></p><button class=\"primary\" type=\"submit\">${
          if (editing) "Save changes" else "Create recipe"
        }</button></form></main>""",
      browserScript
    )
  }

  private def mealForm(recipe: Recipe, meal: Option[Meal]): String = {
    val existing = meal.map(value => s"/api/v1/recipes/${id(recipe.id)}/meals/${id(value.id)}")
    val target = existing.getOrElse(s"/api/v1/recipes/${id(recipe.id)}/meals")
    val cookedAt = meal.fold(Instant.now())(_.cookedAt)
    page(
      if (meal.nonEmpty) "Edit meal" else "Cooked it",
      s"""<main class=\"form-page\"><a href=\"/recipes/${id(recipe.id)}\">← ${escape(
          recipe.title
        )}</a><h1>${
          if (meal.nonEmpty) "Edit cooking entry" else "Record a cooking entry"
        }</h1><form id=\"meal-form\" data-api=\"$target\" data-method=\"${
          if (meal.nonEmpty) "PATCH" else "POST"
        }\" data-recipe-id=\"${id(recipe.id)}\" data-meal-id=\"${meal
          .map(value => id(value.id))
          .getOrElse(
            ""
          )}\"><label for=\"cooked-at\">When did you cook it?</label><input id=\"cooked-at\" name=\"cookedAt\" type=\"datetime-local\" required value=\"${localDateTime(
          cookedAt
        )}\"><label for=\"notes\">Notes</label><textarea id=\"notes\" name=\"notes\" maxlength=\"10000\" placeholder=\"What worked? What would you change?\">${escape(
          meal.map(_.notes).getOrElse("")
        )}</textarea><label for=\"photos\">Photos</label><input id=\"photos\" name=\"photo\" type=\"file\" accept=\"image/jpeg,image/png,image/webp\" multiple><div id=\"photo-previews\" class=\"photo-previews\" aria-live=\"polite\"></div><p class=\"hint\">JPEG, PNG, or WebP, up to 10 MB each.</p><p class=\"form-error\" aria-live=\"polite\"></p><p id=\"upload-progress\" class=\"muted\" aria-live=\"polite\"></p><button class=\"primary\" type=\"submit\">Save cooking entry</button></form></main>""",
      browserScript
    )
  }

  private def detailPage(detail: BrowserRecipe): String = {
    val recipe = detail.recipe
    val primary =
      s"<img class=\"hero-photo\" src=\"/media/recipes/${id(recipe.id)}/primary?variant=display\" alt=\"Photo of ${escape(recipe.title)}\">"
    val keywords =
      if (detail.keywords.isEmpty) ""
      else
        detail.keywords
          .map(value => s"<li>${escape(value)}</li>")
          .mkString("<ul class=\"chips\">", "", "</ul>")
    val references =
      if (detail.references.isEmpty) "<p class=\"muted\">No sources yet.</p>"
      else detail.references.map(referenceView).mkString
    val meals =
      if (detail.meals.isEmpty) "<div class=\"empty-state\"><p>No cooking entries yet.</p></div>"
      else detail.meals.map(mealView(_, detail.photos)).mkString
    page(
      recipe.title,
      s"""<main><a href=\"/\">← All recipes</a><div class=\"detail-heading\"><div><h1>${escape(
          recipe.title
        )}</h1><p>${escape(
          recipe.description
        )}</p>$keywords</div><div class=\"actions\"><a class=\"button\" href=\"/recipes/${id(
          recipe.id
        )}/edit\">Edit recipe</a><a class=\"button primary\" href=\"/recipes/${id(
          recipe.id
        )}/meals/new\">Record meal</a></div></div>$primary<section><h2>Sources and imports</h2><div id=\"references\">$references</div><form class=\"reference-form\" data-recipe-id=\"${id(
          recipe.id
        )}\"><label for=\"reference-url\">Add a recipe URL</label><div class=\"inline-form\"><input id=\"reference-url\" name=\"url\" type=\"url\" placeholder=\"https://example.com/recipe\"><button type=\"submit\">Import</button></div><p class=\"form-error\" aria-live=\"polite\"></p></form></section><section><h2>Cooking history</h2>$meals</section></main>""",
      browserScript
    )
  }

  private def referenceView(value: BrowserReference): String = {
    val reference = value.reference
    val label =
      reference.displayName.orElse(reference.url).orElse(reference.citation).getOrElse("Reference")
    val importInfo = reference.kind match {
      case ReferenceKind.Book =>
        reference.citation.fold("")(citation => s"<p>${escape(citation)}</p>")
      case ReferenceKind.Url =>
        val status = value.job.map(_.status).fold("pending") {
          case ScrapeJobStatus.Succeeded => "complete"
          case other                     => other.databaseValue
        }
        val content = value.document.fold("")(document =>
          s"<details><summary>Imported text</summary><p>${escape(document.contentText)}</p></details>"
        )
        s"<p><span class=\"status\">${escape(status)}</span> ${escape(value.job.flatMap(_.lastError).getOrElse(""))}</p>$content"
    }
    s"<article class=\"reference\"><h3>${escape(label)}</h3>$importInfo</article>"
  }

  private def mealView(meal: Meal, photos: List[Photo]): String = {
    val mealPhotos = photos
      .filter(_.mealId == meal.id)
      .map { photo =>
        s"<figure><img src=\"/media/${id(photo.id)}?variant=thumbnail\" alt=\"${escape(photo.comment.getOrElse(s"Photo from ${date(meal.cookedAt)}"))}\"><figcaption>${escape(photo.comment.getOrElse(""))}</figcaption></figure>"
      }
      .mkString
    s"""<article class=\"meal\"><div><h3>${date(meal.cookedAt)}</h3><a href=\"/recipes/${id(
        meal.recipeId
      )}/meals/${id(meal.id)}/edit\">Edit</a></div><p>${escape(
        meal.notes
      )}</p><div class=\"meal-photos\">$mealPhotos</div></article>"""
  }

  private def nav(session: AuthenticatedSession): String =
    s"""<header><a href=\"/\" class=\"brand\">Cooking Blog</a><form method=\"post\" action=\"/logout\"><input type=\"hidden\" name=\"csrf_token\" value=\"${escape(
        session.csrfSecret.getOrElse("")
      )}\"><button class=\"link-button\" type=\"submit\">Sign out</button></form></header>"""
  private def loginPage(error: Option[String]): String = page(
    "Sign in",
    s"""<main class=\"login\"><h1>Cooking Blog</h1><p>Sign in to continue.</p>${error.fold("")(
        message => s"<p class=\"error\">${escape(message)}</p>"
      )}<form method=\"post\" action=\"/login\"><label for=\"username\">Username</label><input id=\"username\" name=\"username\" autocomplete=\"username\" required autofocus><label for=\"password\">Password</label><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" required><button class=\"primary\" type=\"submit\">Sign in</button></form></main>""",
    ""
  )
  private def notFoundPage: IO[Response[IO]] = NotFound(
    page(
      "Not found",
      "<main><h1>Not found</h1><p>The requested recipe no longer exists.</p><a href=\"/\">Back to recipes</a></main>",
      browserScript
    ),
    `Content-Type`(MediaType.text.html)
  )

  private def page(title: String, content: String, script: String): String =
    s"""<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${escape(
        title
      )} · Cooking Blog</title><style>$styles</style></head><body>$content$script</body></html>"""
  private def authenticate(request: Request[IO]): IO[Option[AuthenticatedSession]] = request.cookies
    .find(_.name == sessionCookieName)
    .fold(none[AuthenticatedSession].pure[IO])(cookie =>
      sessionManager
        .authenticate(cookie.content, request.cookies.find(_.name == csrfCookieName).map(_.content))
    )
  private def expectsHtml(request: Request[IO]): Boolean =
    !request.uri.path.renderString.startsWith("/api/") && request.headers
      .get[headers.Accept]
      .forall(_.values.exists(_.mediaRange.satisfiedBy(MediaType.text.html)))
  private def sessionCookie(session: IssuedSession): ResponseCookie = ResponseCookie(
    sessionCookieName,
    session.token,
    expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
    maxAge = Some(authConfig.sessionLifetime.toSeconds),
    path = Some("/"),
    secure = authConfig.cookieSecure,
    httpOnly = true,
    sameSite = Some(SameSite.Strict)
  )
  private def csrfCookie(session: IssuedSession): ResponseCookie = ResponseCookie(
    csrfCookieName,
    session.csrfSecret,
    expires = Some(HttpDate.unsafeFromInstant(session.expiresAt)),
    maxAge = Some(authConfig.sessionLifetime.toSeconds),
    path = Some("/"),
    secure = authConfig.cookieSecure,
    httpOnly = false,
    sameSite = Some(SameSite.Strict)
  )
  private def recipeId(raw: String): Option[RecipeId] = RecipeId.parse(raw).toOption
  private def mealId(raw: String): Option[MealId] = MealId.parse(raw).toOption
  @targetName("recipeIdText")
  private def id(value: RecipeId): String = RecipeId.value(value).toString
  @targetName("mealIdText")
  private def id(value: MealId): String = MealId.value(value).toString
  @targetName("photoIdText")
  private def id(value: PhotoId): String = PhotoId.value(value).toString
  private def date(value: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, uuuu").withZone(ZoneOffset.UTC).format(value)
  private def localDateTime(value: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC).format(value)
  private def summary(value: String): String =
    if (value.length <= 130) value else value.take(127) + "..."
  private def url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
  private def escape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

  private final case class BrowserRecipe(
      recipe: Recipe,
      meals: List[Meal],
      photos: List[Photo],
      keywords: List[String],
      references: List[BrowserReference]
  )
  private final case class BrowserReference(
      reference: RecipeReference,
      job: Option[ScrapeJob],
      document: Option[ScrapedDocument]
  )

  private val styles =
    """:root{font-family:system-ui,sans-serif;color:#20231f;background:#fbfaf6;line-height:1.45}*{box-sizing:border-box}body{margin:0}main,header{max-width:1100px;margin:auto;padding:1rem}header{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #dedbd1}.brand{font-weight:800;color:inherit;text-decoration:none}h1{line-height:1.1}a{color:#295c43}.button,button{border:1px solid #295c43;border-radius:.5rem;background:#fff;color:#173c2b;padding:.65rem .85rem;font:inherit;text-decoration:none;cursor:pointer}.primary{background:#295c43;color:#fff}.link-button{border:0;padding:0;background:none}.page-heading,.detail-heading{display:flex;justify-content:space-between;gap:1rem;align-items:start}.page-heading .primary{font-size:1.5rem;line-height:1}.recipe-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1rem;margin-top:1rem}.recipe-card,.meal,.reference,.empty-state{border:1px solid #dedbd1;border-radius:.75rem;background:#fff;overflow:hidden;padding:1rem}.recipe-card{padding:0}.recipe-card img{width:100%;height:150px;object-fit:cover;background:#e9e7df}.recipe-card div{padding:0 1rem 1rem}.recipe-card h2{margin-bottom:.25rem}.recipe-card p{margin:.4rem 0}.muted,.hint{color:#62685f}.error,.form-error{color:#a72626}.form-page{max-width:680px}form{display:grid;gap:.65rem}input,textarea{width:100%;font:inherit;padding:.7rem;border:1px solid #989b92;border-radius:.4rem}textarea{min-height:8rem}.inline-form{display:flex;gap:.5rem}.inline-form input{flex:1}.actions{display:flex;flex-wrap:wrap;gap:.5rem}.hero-photo{width:100%;max-height:480px;object-fit:cover;background:#e9e7df;border-radius:.75rem}.chips{display:flex;gap:.4rem;flex-wrap:wrap;padding:0;list-style:none}.chips li,.status{background:#e7f1e8;border-radius:999px;padding:.2rem .55rem;font-size:.9rem}.meal{margin:.8rem 0}.meal>div:first-child{display:flex;justify-content:space-between;align-items:center}.meal-photos,.photo-previews{display:flex;gap:.5rem;flex-wrap:wrap}.meal-photos figure{margin:0;width:110px}.meal-photos img,.photo-previews img{width:110px;height:90px;object-fit:cover;border-radius:.4rem}.meal-photos figcaption{font-size:.8rem}.reference{margin:.5rem 0}.login{max-width:420px;margin-top:8vh}@media(max-width:600px){main,header{padding:.8rem}.detail-heading,.page-heading{flex-direction:column}.actions{width:100%}.actions .button{flex:1;text-align:center}.inline-form{flex-direction:column}.recipe-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}}"""
  private val browserScript =
    """<script>(()=>{const csrf=()=>document.cookie.split('; ').find(v=>v.startsWith('cooking_blog_csrf='))?.split('=').slice(1).join('=')||'';const error=(f,m)=>{const e=f.querySelector('.form-error');if(e)e.textContent=m||'Please correct the highlighted fields.'};const json=(f)=>Object.fromEntries(new FormData(f).entries());const api=async(url,method,body)=>{const r=await fetch(url,{method,headers:{'Content-Type':'application/json','X-CSRF-Token':csrf()},body:JSON.stringify(body)});if(!r.ok){let x={};try{x=await r.json()}catch(_){}throw Error(x.message||'Unable to save changes.')}return r.status===204?null:r.json()};document.querySelectorAll('.api-form').forEach(f=>f.addEventListener('submit',async e=>{e.preventDefault();try{await api(f.dataset.api,f.dataset.method,json(f));location.href=f.dataset.redirect}catch(x){error(f,x.message)}}));const search=document.querySelector('#recipe-search');if(search){let timer;const link=document.querySelector('#new-recipe'),results=document.querySelector('#recipe-results'),status=document.querySelector('#search-status');const run=()=>{const q=search.value;link.href='/recipes/new?title='+encodeURIComponent(q);clearTimeout(timer);timer=setTimeout(async()=>{status.textContent='Searching…';try{results.innerHTML=await (await fetch('/recipes/search?q='+encodeURIComponent(q))).text();status.textContent=''}catch(_){status.textContent='Search failed. Try again.'}},250)};search.addEventListener('input',run);run()}const photos=document.querySelector('#photos');if(photos){photos.addEventListener('change',()=>{const box=document.querySelector('#photo-previews');box.innerHTML='';[...photos.files].forEach(file=>{const img=document.createElement('img');img.alt=file.name;img.src=URL.createObjectURL(file);box.append(img)})})}const meal=document.querySelector('#meal-form');if(meal){meal.addEventListener('submit',async e=>{e.preventDefault();const progress=document.querySelector('#upload-progress');try{const data=json(meal);data.cookedAt=new Date(data.cookedAt).toISOString();let result=await api(meal.dataset.api,meal.dataset.method,data);const mealId=meal.dataset.mealId||result.id;if(photos?.files.length){progress.textContent='Uploading photos…';const fd=new FormData();[...photos.files].forEach(p=>fd.append('photo',p));const r=await fetch(`/api/v1/recipes/${meal.dataset.recipeId}/meals/${mealId}/photos`,{method:'POST',headers:{'X-CSRF-Token':csrf()},body:fd});if(!r.ok)throw Error('Meal saved, but photo upload failed.');}location.href='/recipes/'+meal.dataset.recipeId}catch(x){error(meal,x.message);progress.textContent=''}})}document.querySelectorAll('.reference-form').forEach(f=>f.addEventListener('submit',async e=>{e.preventDefault();try{await api(`/api/v1/recipes/${f.dataset.recipeId}/references`,'POST',{kind:'url',url:f.url.value});location.reload()}catch(x){error(f,x.message)}}))})();</script>"""
}
