package cookingblog.http

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.*
import cookingblog.config.AuthConfig
import cookingblog.domain.*
import cookingblog.http.api.ApiRoutes
import cookingblog.repository.DoobieRepositories
import cookingblog.service.*
import cookingblog.storage.PhotoStore
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, RequestId}
import org.typelevel.log4cats.Logger
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags.{h2 as htmlH2}
import scalatags.Text.tags2.{
  article,
  details,
  main,
  section,
  style,
  summary as detailsSummary,
  title
}

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
      case GET -> Root / "static" / "htmx-2.0.4.min.js" =>
        staticAsset("public/htmx.min.js", MediaType.application.javascript)
      case GET -> Root / "static" / "app-v1.js" =>
        staticAsset("public/app-v1.js", MediaType.application.javascript)
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
    case request @ POST -> Root / "recipes" =>
      browserMutation(session, request) { form =>
        recipeService.createRecipe(createRecipeInput(form)).flatMap {
          case Right(recipe) =>
            createFormReferences(recipe.id, form).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipe.id)}")))
              case Left(error) => formFailure(error)
            }
          case Left(error) => formFailure(error)
        }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId =>
      recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
        browserMutation(session, request) { form =>
          recipeService.updateRecipe(recipeIdValue, updateRecipeInput(form)).flatMap {
            case Right(_) =>
              replaceFormReferences(recipeIdValue, form).flatMap {
                case Right(_) =>
                  SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
                case Left(error) => formFailure(error)
              }
            case Left(error) => formFailure(error)
          }
        }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "delete" =>
      recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
        browserMutation(session, request) { _ =>
          recipeService.deleteRecipe(recipeIdValue).flatMap {
            case Right(_)    => SeeOther(Location(Uri.unsafeFromString("/")))
            case Left(error) => formFailure(error)
          }
        }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" =>
      recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
        browserMutation(session, request) { form =>
          mealInput(form).fold(
            formFailure,
            input =>
              recipeService.createMeal(recipeIdValue, input).flatMap {
                case Right(meal) =>
                  SeeOther(
                    Location(
                      Uri.unsafeFromString(
                        s"/recipes/${id(recipeIdValue)}/meals/${id(meal.id)}/edit"
                      )
                    )
                  )
                case Left(error) => formFailure(error)
              }
          )
        }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId =>
      (recipeId(rawRecipeId), mealId(rawMealId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, mealIdValue) =>
          browserMutation(session, request) { form =>
            mealUpdateInput(form).fold(
              formFailure,
              input =>
                recipeService.updateMeal(recipeIdValue, mealIdValue, input).flatMap {
                  case Right(_) =>
                    SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
                  case Left(error) => formFailure(error)
                }
            )
          }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "references" =>
      recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
        browserMutation(session, request) { form =>
          recipeService.createReference(recipeIdValue, referenceInput(form)).flatMap {
            case Right(_) =>
              SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
            case Left(error) => formFailure(error)
          }
        }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId =>
      (recipeId(rawRecipeId), ReferenceId.parse(rawReferenceId).toOption)
        .mapN((_, _))
        .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
          browserMutation(session, request) { form =>
            recipeService
              .updateReference(recipeIdValue, referenceIdValue, referenceUpdateInput(form))
              .flatMap {
                case Right(_) =>
                  SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
                case Left(error) => formFailure(error)
              }
          }
        }
    case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId / "delete" =>
      (recipeId(rawRecipeId), ReferenceId.parse(rawReferenceId).toOption)
        .mapN((_, _))
        .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.deleteReference(recipeIdValue, referenceIdValue).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
              case Left(error) => formFailure(error)
            }
          }
        }
    case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId / "scrape" =>
      (recipeId(rawRecipeId), ReferenceId.parse(rawReferenceId).toOption)
        .mapN((_, _))
        .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.retryReference(recipeIdValue, referenceIdValue).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
              case Left(error) => formFailure(error)
            }
          }
        }
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "delete" =>
      (recipeId(rawRecipeId), mealId(rawMealId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, mealIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.deleteMeal(recipeIdValue, mealIdValue).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
              case Left(error) => formFailure(error)
            }
          }
      }
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "photos" / rawPhotoId =>
      (recipeId(rawRecipeId), mealId(rawMealId), PhotoId.parse(rawPhotoId).toOption)
        .mapN((_, _, _))
        .fold(notFoundPage) { case (recipeIdValue, mealIdValue, photoIdValue) =>
          browserMutation(session, request) { form =>
            photoService
              .updateComment(
                recipeIdValue,
                mealIdValue,
                photoIdValue,
                UpdatePhotoInput(Some(formValue(form, "comment")))
              )
              .flatMap {
                case Right(_) =>
                  SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
                case Left(error) => formFailure(error)
              }
          }
        }
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "photos" / rawPhotoId / "delete" =>
      (recipeId(rawRecipeId), mealId(rawMealId), PhotoId.parse(rawPhotoId).toOption)
        .mapN((_, _, _))
        .fold(notFoundPage) { case (recipeIdValue, mealIdValue, photoIdValue) =>
          browserMutation(session, request) { _ =>
            photoService.deletePhoto(recipeIdValue, mealIdValue, photoIdValue).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
              case Left(error) => formFailure(error)
            }
          }
        }
    case request @ POST -> Root / "recipes" / rawRecipeId / "primary-photo" / rawPhotoId =>
      (recipeId(rawRecipeId), PhotoId.parse(rawPhotoId).toOption).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, photoIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.selectPrimaryPhoto(recipeIdValue, photoIdValue).flatMap {
              case Right(_) =>
                SeeOther(Location(Uri.unsafeFromString(s"/recipes/${id(recipeIdValue)}")))
              case Left(error) => formFailure(error)
            }
          }
      }
    case request @ GET -> Root =>
      val query = request.uri.query.params.get("q").getOrElse("")
      renderHome(query, browserSort(request.uri.query.params.get("sort")), session)
        .flatMap(Ok(_, `Content-Type`(MediaType.text.html)))
    case request @ GET -> Root / "recipes" / "search" =>
      val query = request.uri.query.params.get("q").getOrElse("")
      recipeService
        .listRecipes(Some(query), browserSort(request.uri.query.params.get("sort")), 50, None)
        .map {
          case Right(page) =>
            Ok(recipeCards(page.items, query).render, `Content-Type`(MediaType.text.html))
          case Left(_) =>
            BadRequest(
              p(cls := "error", "Search could not be completed.").render,
              `Content-Type`(MediaType.text.html)
            )
        }
        .flatten
    case request @ GET -> Root / "recipes" / "new" =>
      Ok(
        recipeForm(
          None,
          request.uri.query.params.get("title").getOrElse(""),
          "",
          "",
          Nil,
          session.csrfSecret.getOrElse("")
        ),
        `Content-Type`(MediaType.text.html)
      )
    case GET -> Root / "recipes" / rawRecipeId / "edit" =>
      recipeId(rawRecipeId).fold(notFoundPage)(id => recipeEditPage(id, session))
    case GET -> Root / "recipes" / rawRecipeId / "meals" / "new" =>
      recipeId(rawRecipeId).fold(notFoundPage)(id =>
        recipeService.getRecipe(id).flatMap {
          case Right(recipe) =>
            Ok(
              mealForm(recipe, None, session.csrfSecret.getOrElse("")),
              `Content-Type`(MediaType.text.html)
            )
          case Left(_) => notFoundPage
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
              Ok(
                mealForm(recipe, Some(meal), session.csrfSecret.getOrElse("")),
                `Content-Type`(MediaType.text.html)
              )
            case _ => notFoundPage
          }.flatten
      }
    case GET -> Root / "recipes" / rawRecipeId =>
      recipeId(rawRecipeId).fold(notFoundPage)(
        recipeDetailPage(_, session.csrfSecret.getOrElse(""))
      )
  }

  private def staticAsset(
      resource: String,
      mediaType: MediaType
  ): IO[Response[IO]] =
    IO.blocking(Option(getClass.getClassLoader.getResourceAsStream(resource))).flatMap {
      case None         => NotFound()
      case Some(stream) =>
        IO.blocking {
          try {
            stream.readAllBytes()
          } finally {
            stream.close()
          }
        }.flatMap(bytes => Ok(bytes, `Content-Type`(mediaType)))
    }

  private def browserMutation(
      session: AuthenticatedSession,
      request: Request[IO]
  )(use: UrlForm => IO[Response[IO]]): IO[Response[IO]] =
    request.as[UrlForm].flatMap { form =>
      form.values
        .get("csrf_token")
        .flatMap(_.headOption)
        .traverse(sessionManager.validateCsrf(session, _))
        .flatMap {
          case Some(true) => use(form)
          case _          =>
            Forbidden(
              p(cls := "form-error", role := "alert", "Invalid CSRF token.").render,
              `Content-Type`(MediaType.text.html)
            )
        }
    }

  private def createRecipeInput(form: UrlForm): CreateRecipeInput =
    CreateRecipeInput(
      formValue(form, "title"),
      Some(formValue(form, "description")),
      Some(formValue(form, "keywords"))
    )

  private def updateRecipeInput(form: UrlForm): UpdateRecipeInput =
    UpdateRecipeInput(
      Some(formValue(form, "title")),
      Some(formValue(form, "description")),
      Some(formValue(form, "keywords"))
    )

  private def mealInput(form: UrlForm): Either[ApiError, CreateMealInput] =
    parseInstant(formValue(form, "cookedAt")).map(value =>
      CreateMealInput(Some(formValue(form, "notes")), value)
    )

  private def mealUpdateInput(form: UrlForm): Either[ApiError, UpdateMealInput] =
    parseInstant(formValue(form, "cookedAt")).map(value =>
      UpdateMealInput(Some(formValue(form, "notes")), Some(value))
    )

  private def parseInstant(value: String): Either[ApiError, Instant] =
    scala.util
      .Try(Instant.parse(value))
      .toEither
      .leftMap(_ => ApiError.Validation(Map("cookedAt" -> List("must be a date and time"))))

  private def referenceInput(form: UrlForm): CreateReferenceInput = {
    val kind = formValue(form, "kind")
    CreateReferenceInput(
      kind,
      Option(formValue(form, "url")).filter(_.nonEmpty),
      Option(formValue(form, "citation")).filter(_.nonEmpty),
      Option(formValue(form, "displayName")).filter(_.nonEmpty)
    )
  }

  private def referenceUpdateInput(form: UrlForm): UpdateReferenceInput =
    UpdateReferenceInput(
      Option(formValue(form, "url")).filter(_.nonEmpty),
      Option(formValue(form, "citation")).filter(_.nonEmpty),
      Option(formValue(form, "displayName")).filter(_.nonEmpty)
    )

  private def createFormReferences(
      recipeIdValue: RecipeId,
      form: UrlForm
  ): IO[Either[ApiError, Unit]] =
    formValues(form, "source_kind").zipWithIndex.foldLeftM[IO, Either[ApiError, Unit]](Right(())) {
      case (left @ Left(_), _)       => IO.pure(left)
      case (Right(_), (kind, index)) =>
        val urlValue = formValues(form, "source_url").lift(index).filter(_.nonEmpty)
        val citationValue = formValues(form, "source_citation").lift(index).filter(_.nonEmpty)
        if (urlValue.isEmpty && citationValue.isEmpty) IO.pure(Right(()))
        else
          recipeService
            .createReference(
              recipeIdValue,
              CreateReferenceInput(kind, urlValue, citationValue, None)
            )
            .map(_.map(_ => ()))
    }

  private def replaceFormReferences(
      recipeIdValue: RecipeId,
      form: UrlForm
  ): IO[Either[ApiError, Unit]] = {
    import DoobieRepositories.*
    references
      .listByRecipe(recipeIdValue)
      .transact(transactor)
      .flatMap(
        _.traverse_(reference => recipeService.deleteReference(recipeIdValue, reference.id).void)
      ) *> createFormReferences(recipeIdValue, form)
  }

  private def formValue(form: UrlForm, name: String): String =
    form.values.get(name).flatMap(_.headOption).getOrElse("")
  private def formValues(form: UrlForm, name: String): List[String] =
    form.values.get(name).fold(List.empty[String])(_.toList)

  private def formFailure(error: ApiError): IO[Response[IO]] =
    BadRequest(
      p(cls := "form-error", role := "alert", formError(error)).render,
      `Content-Type`(MediaType.text.html)
    )
  private def formError(error: ApiError): String = error match {
    case ApiError.Validation(fields)             => fields.values.flatten.mkString("; ")
    case ApiError.Conflict(message)              => message
    case ApiError.NotFound(resource)             => s"$resource was not found"
    case ApiError.InvalidRelationship(message)   => message
    case ApiError.UnsupportedMedia(message)      => message
    case ApiError.PayloadTooLarge(message)       => message
    case ApiError.UnavailableDependency(message) => message
  }

  private def sourceRow(reference: RecipeReference): Frag =
    div(
      cls := "source-row",
      select(
        name := "source_kind",
        option(value := "url", selected := (reference.kind == ReferenceKind.Url), "Recipe URL"),
        option(value := "book", selected := (reference.kind == ReferenceKind.Book), "Book citation")
      ),
      input(
        name := "source_url",
        tpe := "url",
        value := reference.url.getOrElse(""),
        placeholder := "https://example.com/recipe"
      ),
      input(
        name := "source_citation",
        value := reference.citation.getOrElse(""),
        placeholder := "Book title, author, page"
      ),
      button(cls := "remove-source", tpe := "button", "Remove source")
    )

  private def confirmationForm(
      actionUrl: String,
      csrfToken: String,
      message: String,
      labelText: String
  ): Frag =
    form(
      method := "post",
      action := actionUrl,
      cls := "confirmation-form",
      attr("data-confirm") := message,
      input(tpe := "hidden", name := "csrf_token", value := csrfToken),
      button(cls := "danger", tpe := "submit", labelText)
    )

  private def renderHome(
      query: String,
      sort: RecipeSort,
      session: AuthenticatedSession
  ): IO[String] =
    recipeService
      .listRecipes(Option(query).filter(_.trim.nonEmpty), sort, 50, None)
      .map {
        case Right(results) =>
          page(
            "Recipes",
            nav(session),
            main(
              div(
                cls := "page-heading",
                div(h1("Recipes"), p("Find something you have made, then capture the next time.")),
                a(
                  cls := "button primary",
                  htmlId := "new-recipe",
                  href := "/recipes/new",
                  aria.label := "Create a new recipe",
                  "+"
                )
              ),
              label(`for` := "recipe-search", "Search recipes"),
              input(
                htmlId := "recipe-search",
                name := "q",
                tpe := "search",
                value := query,
                placeholder := "grilled chicken, weeknight, sous vide",
                autocomplete := "off",
                attr("hx-get") := "/recipes/search",
                attr("hx-trigger") := "input changed delay:250ms",
                attr("hx-target") := "#recipe-results",
                attr("hx-swap") := "innerHTML",
                attr("hx-include") := "#recipe-sort",
                autofocus
              ),
              label(`for` := "recipe-sort", "Order recipes by"),
              select(
                htmlId := "recipe-sort",
                name := "sort",
                attr("hx-get") := "/recipes/search",
                attr("hx-trigger") := "change",
                attr("hx-target") := "#recipe-results",
                attr("hx-swap") := "innerHTML",
                attr("hx-include") := "#recipe-search",
                option(
                  value := RecipeSort.Recent.value,
                  selected := (sort == RecipeSort.Recent),
                  "Most recently cooked"
                ),
                option(
                  value := RecipeSort.Updated.value,
                  selected := (sort == RecipeSort.Updated),
                  "Last updated"
                ),
                option(
                  value := RecipeSort.Title.value,
                  selected := (sort == RecipeSort.Title),
                  "Title"
                )
              ),
              p(htmlId := "search-status", cls := "muted", aria.live := "polite"),
              section(
                htmlId := "recipe-results",
                cls := "recipe-grid",
                recipeCards(results.items, query)
              )
            )
          )
        case Left(_) =>
          page(
            "Recipes",
            nav(session),
            main(h1("Recipes"), p(cls := "error", "Recipes could not be loaded."))
          )
      }

  private def recipeEditPage(id: RecipeId, session: AuthenticatedSession): IO[Response[IO]] =
    (recipeService.getRecipe(id), recipeKeywords(id), recipeReferences(id)).mapN {
      case (Right(recipe), keywords, references) =>
        Ok(
          recipeForm(
            Some(recipe),
            recipe.title,
            recipe.description,
            keywords.mkString(", "),
            references,
            session.csrfSecret.getOrElse("")
          ),
          `Content-Type`(MediaType.text.html)
        )
      case _ => notFoundPage
    }.flatten

  private def recipeDetailPage(id: RecipeId, csrfToken: String): IO[Response[IO]] =
    recipeDetail(id).flatMap {
      case None         => notFoundPage
      case Some(detail) => Ok(detailPage(detail, csrfToken), `Content-Type`(MediaType.text.html))
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
  private def recipeReferences(id: RecipeId): IO[List[RecipeReference]] =
    DoobieRepositories.references.listByRecipe(id).transact(transactor)

  private def recipeCards(recipes: List[Recipe], query: String): Frag =
    if (recipes.isEmpty) {
      div(
        cls := "empty-state",
        htmlH2("No recipes found"),
        p("Try another phrase, or start a recipe with this search."),
        a(
          cls := "button primary",
          href := s"/recipes/new?title=${url(query)}",
          s"Create “${query.trim}”"
        )
      )
    } else
      frag(recipes.map { recipe =>
        article(
          cls := "recipe-card",
          img(
            src := s"/media/recipes/${id(recipe.id)}/primary?variant=thumbnail",
            alt := "",
            attr("loading") := "lazy"
          ),
          div(
            htmlH2(a(href := s"/recipes/${id(recipe.id)}", recipe.title)),
            p(summary(recipe.description)),
            p(
              cls := "muted",
              recipe.lastMadeAt.fold("Not cooked yet")(instant => s"Last made ${date(instant)}")
            )
          )
        )
      })

  private def recipeForm(
      recipe: Option[Recipe],
      titleValue: String,
      descriptionValue: String,
      keywords: String,
      sources: List[RecipeReference],
      csrfToken: String
  ): String = {
    val editing = recipe.nonEmpty
    val heading = if (editing) "Edit recipe" else "New recipe"
    val actionUrl = recipe.fold("/recipes")(value => s"/recipes/${id(value.id)}")
    val back = recipe.fold("/")(value => s"/recipes/${id(value.id)}")
    page(
      heading,
      main(
        cls := "form-page",
        a(href := back, "← Back"),
        h1(heading),
        form(
          method := "post",
          action := actionUrl,
          attr("data-recipe-form") := "true",
          attr("data-html-form") := "true",
          input(tpe := "hidden", name := "csrf_token", value := csrfToken),
          label(`for` := "title", "Title"),
          input(
            htmlId := "title",
            name := "title",
            required,
            maxlength := 200,
            aria.describedby := "form-errors",
            value := titleValue
          ),
          label(`for` := "description", "Description"),
          textarea(
            htmlId := "description",
            name := "description",
            maxlength := 10000,
            aria.describedby := "form-errors",
            descriptionValue
          ),
          label(`for` := "keywords", "Keywords"),
          input(
            htmlId := "keywords",
            name := "keywords",
            aria.describedby := "form-errors",
            value := keywords,
            placeholder := "sous vide, chicken, bbq"
          ),
          p(cls := "hint", "Separate keywords with commas. Multi-word keywords are kept together."),
          div(
            htmlId := "recipe-sources",
            aria.live := "polite",
            htmlH2("Sources"),
            p(cls := "hint", "Add one or more recipe URLs or book citations."),
            sources.map(sourceRow),
            button(htmlId := "add-recipe-source", tpe := "button", "Add source")
          ),
          p(htmlId := "form-errors", cls := "form-error", aria.live := "polite", role := "alert"),
          button(
            cls := "primary",
            tpe := "submit",
            if (editing) "Save changes" else "Create recipe"
          )
        )
      )
    )
  }

  private def mealForm(recipe: Recipe, meal: Option[Meal], csrfToken: String): String = {
    val existing = meal.map(value => s"/recipes/${id(recipe.id)}/meals/${id(value.id)}")
    val targetUrl = existing.getOrElse(s"/recipes/${id(recipe.id)}/meals")
    val cookedAt = meal.fold(Instant.now())(_.cookedAt)
    page(
      if (meal.nonEmpty) "Edit meal" else "Cooked it",
      main(
        cls := "form-page",
        a(href := s"/recipes/${id(recipe.id)}", s"← ${recipe.title}"),
        h1(if (meal.nonEmpty) "Edit cooking entry" else "Record a cooking entry"),
        form(
          htmlId := "meal-form",
          method := "post",
          action := targetUrl,
          attr("data-meal-form") := "true",
          attr("data-recipe-id") := id(recipe.id),
          attr("data-meal-id") := meal.map(value => id(value.id)).getOrElse(""),
          input(tpe := "hidden", name := "csrf_token", value := csrfToken),
          label(`for` := "cooked-at", "When did you cook it?"),
          input(
            htmlId := "cooked-at",
            name := "cookedAt",
            tpe := "datetime-local",
            required,
            aria.describedby := "form-errors",
            value := localDateTime(cookedAt)
          ),
          label(`for` := "notes", "Notes"),
          textarea(
            htmlId := "notes",
            name := "notes",
            maxlength := 10000,
            aria.describedby := "form-errors",
            placeholder := "What worked? What would you change?",
            meal.map(_.notes).getOrElse("")
          ),
          label(`for` := "photos", "Photos"),
          input(
            htmlId := "photos",
            name := "photo",
            tpe := "file",
            accept := "image/jpeg,image/png,image/webp",
            aria.describedby := "form-errors upload-progress",
            multiple
          ),
          div(htmlId := "photo-previews", cls := "photo-previews", aria.live := "polite"),
          p(cls := "hint", "JPEG, PNG, or WebP, up to 10 MB each."),
          p(htmlId := "form-errors", cls := "form-error", aria.live := "polite"),
          p(htmlId := "upload-progress", cls := "muted", aria.live := "polite"),
          button(cls := "primary", tpe := "submit", "Save cooking entry")
        )
      )
    )
  }

  private def detailPage(detail: BrowserRecipe, csrfToken: String): String = {
    val recipe = detail.recipe
    val primary = img(
      cls := "hero-photo",
      src := s"/media/recipes/${id(recipe.id)}/primary?variant=display",
      alt := s"Photo of ${recipe.title}"
    )
    val keywords =
      if (detail.keywords.isEmpty) frag()
      else ul(cls := "chips", detail.keywords.map(value => li(value)))
    val references =
      if (detail.references.isEmpty) p(cls := "muted", "No sources yet.")
      else frag(detail.references.map(referenceView(_, recipe.id, csrfToken)))
    val meals =
      if (detail.meals.isEmpty) div(cls := "empty-state", p("No cooking entries yet."))
      else frag(detail.meals.map(mealView(_, detail.photos, csrfToken)))
    page(
      recipe.title,
      main(
        a(href := "/", "← All recipes"),
        div(
          cls := "detail-heading",
          div(h1(recipe.title), p(recipe.description), keywords),
          div(
            cls := "actions",
            a(cls := "button", href := s"/recipes/${id(recipe.id)}/edit", "Edit recipe"),
            a(
              cls := "button primary",
              href := s"/recipes/${id(recipe.id)}/meals/new",
              "Record meal"
            ),
            confirmationForm(
              s"/recipes/${id(recipe.id)}/delete",
              csrfToken,
              "Permanently delete this recipe and all of its cooking history?",
              "Delete recipe"
            )
          )
        ),
        primary,
        section(
          htmlH2("Sources and imports"),
          div(htmlId := "references", references),
          form(
            method := "post",
            action := s"/recipes/${id(recipe.id)}/references",
            cls := "reference-form",
            attr("data-html-form") := "true",
            input(tpe := "hidden", name := "csrf_token", value := csrfToken),
            label(`for` := "reference-kind", "Add a source"),
            select(
              htmlId := "reference-kind",
              name := "kind",
              option(value := "url", "Recipe URL"),
              option(value := "book", "Book citation")
            ),
            div(
              cls := "inline-form",
              input(
                htmlId := "reference-url",
                name := "url",
                tpe := "url",
                aria.describedby := "reference-form-errors",
                placeholder := "https://example.com/recipe"
              ),
              input(
                htmlId := "reference-citation",
                name := "citation",
                aria.describedby := "reference-form-errors",
                placeholder := "Book title, author, page",
                hidden
              ),
              button(tpe := "submit", "Add source")
            ),
            p(
              cls := "hint",
              "URL imports begin in the background. Book citations are saved as written."
            ),
            p(
              htmlId := "reference-form-errors",
              cls := "form-error",
              aria.live := "polite",
              role := "alert"
            )
          )
        ),
        section(htmlH2("Cooking history"), meals)
      )
    )
  }

  private def referenceView(
      value: BrowserReference,
      recipeId: RecipeId,
      csrfToken: String
  ): Frag = {
    val reference = value.reference
    val displayLabel =
      reference.displayName.orElse(reference.url).orElse(reference.citation).getOrElse("Reference")
    val importInfo = reference.kind match {
      case ReferenceKind.Book =>
        reference.citation.fold(frag())(citation => p(citation))
      case ReferenceKind.Url =>
        val status = value.job.map(_.status).fold("pending") {
          case ScrapeJobStatus.Succeeded => "complete"
          case other                     => other.databaseValue
        }
        val content = value.document.fold(frag())(document =>
          details(detailsSummary("Imported text"), p(document.contentText))
        )
        val statusLabel =
          if (status == "pending" || status == "running") {
            span(
              cls := "status",
              aria.live := "polite",
              attr("data-import-active") := "true",
              status
            )
          } else {
            span(cls := "status", aria.live := "polite", status)
          }
        frag(
          p(statusLabel, " ", value.job.flatMap(_.lastError).getOrElse("")),
          content
        )
    }
    val endpoint = s"/recipes/${id(recipeId)}/references/${id(reference.id)}"
    val retry = Option.when(reference.kind == ReferenceKind.Url)(
      confirmationForm(s"$endpoint/scrape", csrfToken, "Retry this import now?", "Retry import")
    )
    val field = reference.kind match {
      case ReferenceKind.Url =>
        input(
          htmlId := s"reference-${id(reference.id)}",
          name := "url",
          tpe := "url",
          aria.describedby := s"reference-errors-${id(reference.id)}",
          scalatags.Text.attrs.value := reference.url.getOrElse(""),
          required
        )
      case ReferenceKind.Book =>
        input(
          htmlId := s"reference-${id(reference.id)}",
          name := "citation",
          aria.describedby := s"reference-errors-${id(reference.id)}",
          scalatags.Text.attrs.value := reference.citation.getOrElse(""),
          required
        )
    }
    article(
      cls := "reference",
      h3(displayLabel),
      importInfo,
      form(
        method := "post",
        action := endpoint,
        attr("data-html-form") := "true",
        input(tpe := "hidden", name := "csrf_token", scalatags.Text.attrs.value := csrfToken),
        label(
          `for` := s"reference-${id(reference.id)}",
          if (reference.kind == ReferenceKind.Url) "Recipe URL" else "Book citation"
        ),
        field,
        p(
          htmlId := s"reference-errors-${id(reference.id)}",
          cls := "form-error",
          aria.live := "polite",
          role := "alert"
        ),
        button(tpe := "submit", "Save source")
      ),
      retry,
      confirmationForm(
        s"$endpoint/delete",
        csrfToken,
        "Permanently delete this source?",
        "Delete source"
      )
    )
  }

  private def mealView(meal: Meal, photos: List[Photo], csrfToken: String): Frag = {
    val mealPhotos = photos
      .filter(_.mealId == meal.id)
      .map { photo =>
        figure(
          img(
            src := s"/media/${id(photo.id)}?variant=thumbnail",
            alt := photo.comment.getOrElse(s"Photo from ${date(meal.cookedAt)}")
          ),
          figcaption(
            form(
              method := "post",
              action := s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/photos/${id(photo.id)}",
              attr("data-html-form") := "true",
              input(tpe := "hidden", name := "csrf_token", value := csrfToken),
              label(`for` := s"caption-${id(photo.id)}", "Caption"),
              input(
                htmlId := s"caption-${id(photo.id)}",
                name := "comment",
                aria.describedby := s"caption-errors-${id(photo.id)}",
                value := photo.comment.getOrElse(""),
                maxlength := 1000
              ),
              p(
                htmlId := s"caption-errors-${id(photo.id)}",
                cls := "form-error",
                aria.live := "polite",
                role := "alert"
              ),
              button(tpe := "submit", "Save caption")
            )
          ),
          div(
            cls := "photo-actions",
            confirmationForm(
              s"/recipes/${id(meal.recipeId)}/primary-photo/${id(photo.id)}",
              csrfToken,
              "Use this photo as the recipe's primary photo?",
              "Use as primary"
            ),
            confirmationForm(
              s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/photos/${id(photo.id)}/delete",
              csrfToken,
              "Permanently delete this photo?",
              "Delete photo"
            )
          )
        )
      }
    article(
      cls := "meal",
      div(
        h3(date(meal.cookedAt)),
        div(
          a(href := s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/edit", "Edit"),
          confirmationForm(
            s"/recipes/${id(meal.recipeId)}/meals/${id(meal.id)}/delete",
            csrfToken,
            "Permanently delete this cooking entry and its photos?",
            "Delete meal"
          )
        )
      ),
      p(meal.notes),
      div(cls := "meal-photos", mealPhotos)
    )
  }

  private def nav(session: AuthenticatedSession): Frag = header(
    a(cls := "brand", href := "/", "Cooking Blog"),
    form(
      method := "post",
      action := "/logout",
      input(tpe := "hidden", name := "csrf_token", value := session.csrfSecret.getOrElse("")),
      button(cls := "link-button", tpe := "submit", "Sign out")
    )
  )
  private def loginPage(error: Option[String]): String = page(
    "Sign in",
    Seq(
      main(
        cls := "login",
        h1("Cooking Blog"),
        p("Sign in to continue."),
        error.fold(frag())(message => p(cls := "error", message)),
        form(
          method := "post",
          action := "/login",
          label(`for` := "username", "Username"),
          input(
            htmlId := "username",
            name := "username",
            autocomplete := "username",
            required,
            autofocus
          ),
          label(`for` := "password", "Password"),
          input(
            htmlId := "password",
            name := "password",
            tpe := "password",
            autocomplete := "current-password",
            required
          ),
          button(cls := "primary", tpe := "submit", "Sign in")
        )
      )
    ),
    includeScript = false
  )
  private def notFoundPage: IO[Response[IO]] = NotFound(
    page(
      "Not found",
      main(
        h1("Not found"),
        p("The requested recipe no longer exists."),
        a(href := "/", "Back to recipes")
      )
    ),
    `Content-Type`(MediaType.text.html)
  )

  private def page(titleText: String, content: Frag*): String =
    page(titleText, content, includeScript = true)
  private def page(titleText: String, content: Seq[Frag], includeScript: Boolean): String =
    "<!doctype html>" + html(
      lang := "en",
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", attr("content") := "width=device-width, initial-scale=1"),
        title(s"$titleText · Cooking Blog"),
        style(raw(styles))
      ),
      body(
        p(htmlId := "global-status", cls := "sr-only", role := "status", aria.live := "polite"),
        content,
        Option.when(includeScript)(
          frag(
            script(src := "/static/htmx-2.0.4.min.js", defer),
            script(src := "/static/app-v1.js", defer)
          )
        )
      )
    ).render
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
  private def browserSort(raw: Option[String]): RecipeSort = raw match {
    case Some(RecipeSort.Updated.value) => RecipeSort.Updated
    case Some(RecipeSort.Title.value)   => RecipeSort.Title
    case _                              => RecipeSort.Recent
  }
  @targetName("recipeIdText")
  private def id(value: RecipeId): String = RecipeId.value(value).toString
  @targetName("mealIdText")
  private def id(value: MealId): String = MealId.value(value).toString
  @targetName("photoIdText")
  private def id(value: PhotoId): String = PhotoId.value(value).toString
  @targetName("referenceIdText")
  private def id(value: ReferenceId): String = ReferenceId.value(value).toString
  private def date(value: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, uuuu").withZone(ZoneOffset.UTC).format(value)
  private def localDateTime(value: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC).format(value)
  private def summary(value: String): String =
    if (value.length <= 130) value else value.take(127) + "..."
  private def url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

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
    """:root{font-family:system-ui,sans-serif;color:#20231f;background:#fbfaf6;line-height:1.45}*{box-sizing:border-box}body{margin:0}main,header{max-width:1100px;margin:auto;padding:1rem}header{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #dedbd1}.brand{font-weight:800;color:inherit;text-decoration:none}h1{line-height:1.1}a{color:#295c43}.button,button{border:1px solid #295c43;border-radius:.5rem;background:#fff;color:#173c2b;padding:.65rem .85rem;font:inherit;text-decoration:none;cursor:pointer}.primary{background:#295c43;color:#fff}.link-button{border:0;padding:0;background:none}:focus-visible{outline:3px solid #1d6fb8;outline-offset:3px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.page-heading,.detail-heading{display:flex;justify-content:space-between;gap:1rem;align-items:start}.page-heading .primary{font-size:1.5rem;line-height:1}.recipe-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1rem;margin-top:1rem}.recipe-card,.meal,.reference,.empty-state{border:1px solid #dedbd1;border-radius:.75rem;background:#fff;overflow:hidden;padding:1rem}.recipe-card{padding:0}.recipe-card img{width:100%;height:150px;object-fit:cover;background:#e9e7df}.recipe-card div{padding:0 1rem 1rem}.recipe-card h2{margin-bottom:.25rem}.recipe-card p{margin:.4rem 0}.muted,.hint{color:#62685f}.error,.form-error{color:#a72626}.form-page{max-width:680px}form{display:grid;gap:.65rem}input,textarea{width:100%;font:inherit;padding:.7rem;border:1px solid #989b92;border-radius:.4rem}textarea{min-height:8rem}.inline-form{display:flex;gap:.5rem}.inline-form input{flex:1}.actions{display:flex;flex-wrap:wrap;gap:.5rem}.hero-photo{width:100%;max-height:480px;object-fit:cover;background:#e9e7df;border-radius:.75rem}.chips{display:flex;gap:.4rem;flex-wrap:wrap;padding:0;list-style:none}.chips li,.status{background:#e7f1e8;border-radius:999px;padding:.2rem .55rem;font-size:.9rem}.meal{margin:.8rem 0}.meal>div:first-child{display:flex;justify-content:space-between;align-items:center}.meal-photos,.photo-previews{display:flex;gap:.5rem;flex-wrap:wrap}.meal-photos figure{margin:0;width:110px}.meal-photos img,.photo-previews img{width:110px;height:90px;object-fit:cover;border-radius:.4rem}.meal-photos figcaption{font-size:.8rem}.reference{margin:.5rem 0}.login{max-width:420px;margin-top:8vh}@media(max-width:600px){main,header{padding:.8rem}.detail-heading,.page-heading{flex-direction:column}.actions{width:100%}.actions .button{flex:1;text-align:center}.inline-form{flex-direction:column}.recipe-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}}"""
  @scala.annotation.nowarn("msg=unused private member")
  private val browserScript =
    """<script>(()=>{const csrf=()=>document.cookie.split('; ').find(v=>v.startsWith('cooking_blog_csrf='))?.split('=').slice(1).join('=')||'';const error=(f,m)=>{const e=f.querySelector('.form-error');if(e){e.textContent=m||'Please correct the highlighted fields.';e.tabIndex=-1;e.focus()}};const json=(f)=>Object.fromEntries(new FormData(f).entries());const api=async(url,method,body)=>{const r=await fetch(url,{method,headers:{'Content-Type':'application/json','X-CSRF-Token':csrf()},body:JSON.stringify(body)});if(!r.ok){let x={};try{x=await r.json()}catch(_){}throw Error(x.message||'Unable to save changes.')}return r.status===204?null:r.json()};document.querySelectorAll('.api-form[data-redirect]').forEach(f=>f.addEventListener('submit',async e=>{e.preventDefault();try{await api(f.dataset.api,f.dataset.method,json(f));location.href=f.dataset.redirect}catch(x){error(f,x.message)}}));const search=document.querySelector('#recipe-search');if(search){let timer;const link=document.querySelector('#new-recipe'),results=document.querySelector('#recipe-results'),status=document.querySelector('#search-status');const run=()=>{const q=search.value;link.href='/recipes/new?title='+encodeURIComponent(q);clearTimeout(timer);timer=setTimeout(async()=>{status.textContent='Searching…';try{results.innerHTML=await (await fetch('/recipes/search?q='+encodeURIComponent(q))).text();status.textContent=''}catch(_){status.textContent='Search failed. Try again.'}},250)};search.addEventListener('input',run);run()}const photos=document.querySelector('#photos');if(photos){photos.addEventListener('change',()=>{const box=document.querySelector('#photo-previews');box.innerHTML='';[...photos.files].forEach(file=>{const img=document.createElement('img');img.alt=file.name;img.src=URL.createObjectURL(file);box.append(img)})})}const meal=document.querySelector('#meal-form');if(meal){meal.addEventListener('submit',async e=>{e.preventDefault();const progress=document.querySelector('#upload-progress');try{const data=json(meal);data.cookedAt=new Date(data.cookedAt).toISOString();let result=await api(meal.dataset.api,meal.dataset.method,data);const mealId=meal.dataset.mealId||result.id;if(photos?.files.length){progress.textContent='Uploading photos…';const fd=new FormData();[...photos.files].forEach(p=>fd.append('photo',p));const r=await fetch(`/api/v1/recipes/${meal.dataset.recipeId}/meals/${mealId}/photos`,{method:'POST',headers:{'X-CSRF-Token':csrf()},body:fd});if(!r.ok)throw Error('Meal saved, but photo upload failed.');}location.href='/recipes/'+meal.dataset.recipeId}catch(x){error(meal,x.message);progress.textContent=''}})}document.querySelectorAll('.reference-form').forEach(f=>f.addEventListener('submit',async e=>{e.preventDefault();try{await api(`/api/v1/recipes/${f.dataset.recipeId}/references`,'POST',{kind:'url',url:f.url.value});location.reload()}catch(x){error(f,x.message)}}))})();</script>"""

  @scala.annotation.nowarn("msg=unused private member")
  private val browserEnhancements =
    """<script>(()=>{const csrf=()=>document.cookie.split('; ').find(v=>v.startsWith('cooking_blog_csrf='))?.split('=').slice(1).join('=')||'';const api=async(url,method,body)=>{const r=await fetch(url,{method,headers:{'Content-Type':'application/json','X-CSRF-Token':csrf()},body:body===undefined?undefined:JSON.stringify(body)});if(!r.ok){let x={};try{x=await r.json()}catch(_){}throw Error(x.message||'Unable to save changes.')}return r.status===204?null:r.json()};const report=(f,m)=>{const e=f.querySelector('.form-error');if(e){e.textContent=m;e.tabIndex=-1;e.focus()}};document.querySelectorAll('.api-form:not([data-redirect])').forEach(f=>f.addEventListener('submit',async e=>{e.preventDefault();try{await api(f.dataset.api,f.dataset.method,Object.fromEntries(new FormData(f).entries()));location.reload()}catch(x){report(f,x.message)}}));document.querySelectorAll('.mutation-button').forEach(b=>b.addEventListener('click',async()=>{if(b.dataset.confirm&&!confirm(b.dataset.confirm))return;try{await api(b.dataset.api,b.dataset.method);location.href=b.dataset.method==='DELETE'&&b.dataset.api.split('/').length===5?'/':location.href}catch(x){alert(x.message)}}));document.querySelectorAll('.enhanced-reference-form').forEach(f=>{const kind=f.kind,url=f.url,citation=f.citation;const toggle=()=>{const book=kind.value==='book';url.hidden=book;citation.hidden=!book;url.required=!book;citation.required=book};kind.addEventListener('change',toggle);toggle();f.addEventListener('submit',async e=>{e.preventDefault();try{const book=kind.value==='book';await api(`/api/v1/recipes/${f.dataset.recipeId}/references`,'POST',book?{kind:'book',citation:citation.value}:{kind:'url',url:url.value});location.reload()}catch(x){report(f,x.message)}})});if(document.querySelector('[data-import-active]'))setTimeout(()=>location.reload(),3000)})();</script>"""

  @scala.annotation.nowarn("msg=unused private member")
  private val browserSortScript =
    """<script>(()=>{const original=document.querySelector('#recipe-search'),sort=document.querySelector('#recipe-sort');if(!original||!sort)return;const search=original.cloneNode(true);original.replaceWith(search);let timer;const results=document.querySelector('#recipe-results'),status=document.querySelector('#search-status'),link=document.querySelector('#new-recipe');const run=()=>{const q=search.value,order=sort.value,params=new URLSearchParams({q,sort:order});link.href='/recipes/new?title='+encodeURIComponent(q);history.replaceState(null,'','/?'+params);clearTimeout(timer);timer=setTimeout(async()=>{status.textContent='Searching…';try{results.innerHTML=await (await fetch('/recipes/search?'+params)).text();status.textContent=''}catch(_){status.textContent='Search failed. Try again.'}},250)};search.addEventListener('input',run);sort.addEventListener('change',run)})();</script>"""

  @scala.annotation.nowarn("msg=unused private member")
  private val recipeSourceScript =
    """<script>(()=>{const original=document.querySelector('.api-form[data-source-entry]');if(!original)return;const form=original.cloneNode(true);original.replaceWith(form);const csrf=()=>document.cookie.split('; ').find(v=>v.startsWith('cooking_blog_csrf='))?.split('=').slice(1).join('=')||'';const api=async(url,method,body)=>{const r=await fetch(url,{method,headers:{'Content-Type':'application/json','X-CSRF-Token':csrf()},body:JSON.stringify(body)});if(!r.ok){let x={};try{x=await r.json()}catch(_){}throw Error(x.message||'Unable to save changes.')}return r.status===204?null:r.json()};const report=m=>{const e=form.querySelector('.form-error');e.textContent=m;e.tabIndex=-1;e.focus()};const sources=form.querySelector('#recipe-sources'),add=form.querySelector('#add-recipe-source');const source=()=>{const row=document.createElement('div');row.dataset.source='';row.className='source-row';const kind=document.createElement('select');kind.setAttribute('aria-label','Source type');kind.innerHTML='<option value="url">Recipe URL</option><option value="book">Book citation</option>';const url=document.createElement('input');url.type='url';url.placeholder='https://example.com/recipe';url.required=true;url.setAttribute('aria-label','Recipe URL');const citation=document.createElement('input');citation.placeholder='Book title, author, page';citation.hidden=true;citation.setAttribute('aria-label','Book citation');const remove=document.createElement('button');remove.type='button';remove.textContent='Remove source';remove.addEventListener('click',()=>row.remove());kind.addEventListener('change',()=>{const book=kind.value==='book';url.hidden=book;url.required=!book;citation.hidden=!book;citation.required=book});row.append(kind,url,citation,remove);sources.insertBefore(row,add)};add.addEventListener('click',source);form.addEventListener('submit',async e=>{e.preventDefault();try{const data=Object.fromEntries(new FormData(form).entries());const recipe=await api(form.dataset.api,form.dataset.method,data);for(const row of form.querySelectorAll('[data-source]')){const kind=row.querySelector('select').value,url=row.querySelector('input[type=url]').value,citation=row.querySelector('input:not([type=url])').value;await api(`/api/v1/recipes/${recipe.id}/references`,'POST',kind==='book'?{kind,citation}:{kind,url})}location.href='/recipes/'+recipe.id}catch(x){report(x.message)}})})();</script>"""
}
