package cookingblog.http.templates

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import cookingblog.domain.*
import cookingblog.http.pages.{BrowserRecipe, BrowserReference}
import cookingblog.repository.DoobieRepositories
import cookingblog.service.*
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags.{h2 as htmlH2}
import scalatags.Text.tags2.{article, details, main, section, style, summary as detailsSummary, title}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.annotation.targetName

/** Server-rendered browser page queries, components, and templates. */
private[http] trait BrowserPageTemplates {
  protected def recipeService: RecipeApiService
  protected def photoService: PhotoService
  protected def transactor: Transactor[IO]
  protected def sourceRow(reference: RecipeReference): Frag =
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

  protected def confirmationForm(
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

  protected def renderHome(
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

  protected def recipeEditPage(id: RecipeId, session: AuthenticatedSession): IO[Response[IO]] =
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

  protected def recipeDetailPage(id: RecipeId, csrfToken: String): IO[Response[IO]] =
    recipeDetail(id).flatMap {
      case None         => notFoundPage
      case Some(detail) => Ok(detailPage(detail, csrfToken), `Content-Type`(MediaType.text.html))
    }

  protected def recipeDetail(id: RecipeId): IO[Option[BrowserRecipe]] = {
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

  protected def recipeKeywords(id: RecipeId): IO[List[String]] =
    DoobieRepositories.keywords.listByRecipe(id).map(_.map(_.keyword)).transact(transactor)
  protected def recipeReferences(id: RecipeId): IO[List[RecipeReference]] =
    DoobieRepositories.references.listByRecipe(id).transact(transactor)

  protected def recipeCards(recipes: List[Recipe], query: String): Frag =
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

  protected def recipeForm(
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

  protected def mealForm(recipe: Recipe, meal: Option[Meal], csrfToken: String): String = {
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

  protected def detailPage(detail: BrowserRecipe, csrfToken: String): String = {
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

  protected def referenceView(
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

  protected def mealView(meal: Meal, photos: List[Photo], csrfToken: String): Frag = {
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

  protected def nav(session: AuthenticatedSession): Frag = header(
    a(cls := "brand", href := "/", "Cooking Blog"),
    form(
      method := "post",
      action := "/logout",
      input(tpe := "hidden", name := "csrf_token", value := session.csrfSecret.getOrElse("")),
      button(cls := "link-button", tpe := "submit", "Sign out")
    )
  )
  def loginPage(error: Option[String]): String = page(
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
  protected def notFoundPage: IO[Response[IO]] = NotFound(
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

  protected def page(titleText: String, content: Frag*): String =
    page(titleText, content, includeScript = true)
  protected def page(titleText: String, content: Seq[Frag], includeScript: Boolean): String =
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

  protected def recipeId(raw: String): Option[RecipeId] = RecipeId.parse(raw).toOption
  protected def mealId(raw: String): Option[MealId] = MealId.parse(raw).toOption
  protected def browserSort(raw: Option[String]): RecipeSort = raw match {
    case Some(RecipeSort.Updated.value) => RecipeSort.Updated
    case Some(RecipeSort.Title.value)   => RecipeSort.Title
    case _                              => RecipeSort.Recent
  }
  @targetName("recipeIdText")
  protected def id(value: RecipeId): String = RecipeId.value(value).toString
  @targetName("mealIdText")
  protected def id(value: MealId): String = MealId.value(value).toString
  @targetName("photoIdText")
  protected def id(value: PhotoId): String = PhotoId.value(value).toString
  @targetName("referenceIdText")
  protected def id(value: ReferenceId): String = ReferenceId.value(value).toString
  protected def date(value: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, uuuu").withZone(ZoneOffset.UTC).format(value)
  protected def localDateTime(value: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC).format(value)
  protected def summary(value: String): String =
    if (value.length <= 130) value else value.take(127) + "..."
  protected def url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  protected val styles =
    """:root{font-family:system-ui,sans-serif;color:#20231f;background:#fbfaf6;line-height:1.45}*{box-sizing:border-box}body{margin:0}main,header{max-width:1100px;margin:auto;padding:1rem}header{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #dedbd1}.brand{font-weight:800;color:inherit;text-decoration:none}h1{line-height:1.1}a{color:#295c43}.button,button{border:1px solid #295c43;border-radius:.5rem;background:#fff;color:#173c2b;padding:.65rem .85rem;font:inherit;text-decoration:none;cursor:pointer}.primary{background:#295c43;color:#fff}.link-button{border:0;padding:0;background:none}:focus-visible{outline:3px solid #1d6fb8;outline-offset:3px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.page-heading,.detail-heading{display:flex;justify-content:space-between;gap:1rem;align-items:start}.page-heading .primary{font-size:1.5rem;line-height:1}.recipe-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1rem;margin-top:1rem}.recipe-card,.meal,.reference,.empty-state{border:1px solid #dedbd1;border-radius:.75rem;background:#fff;overflow:hidden;padding:1rem}.recipe-card{padding:0}.recipe-card img{width:100%;height:150px;object-fit:cover;background:#e9e7df}.recipe-card div{padding:0 1rem 1rem}.recipe-card h2{margin-bottom:.25rem}.recipe-card p{margin:.4rem 0}.muted,.hint{color:#62685f}.error,.form-error{color:#a72626}.form-page{max-width:680px}form{display:grid;gap:.65rem}input,textarea{width:100%;font:inherit;padding:.7rem;border:1px solid #989b92;border-radius:.4rem}textarea{min-height:8rem}.inline-form{display:flex;gap:.5rem}.inline-form input{flex:1}.actions{display:flex;flex-wrap:wrap;gap:.5rem}.hero-photo{width:100%;max-height:480px;object-fit:cover;background:#e9e7df;border-radius:.75rem}.chips{display:flex;gap:.4rem;flex-wrap:wrap;padding:0;list-style:none}.chips li,.status{background:#e7f1e8;border-radius:999px;padding:.2rem .55rem;font-size:.9rem}.meal{margin:.8rem 0}.meal>div:first-child{display:flex;justify-content:space-between;align-items:center}.meal-photos,.photo-previews{display:flex;gap:.5rem;flex-wrap:wrap}.meal-photos figure{margin:0;width:110px}.meal-photos img,.photo-previews img{width:110px;height:90px;object-fit:cover;border-radius:.4rem}.meal-photos figcaption{font-size:.8rem}.reference{margin:.5rem 0}.login{max-width:420px;margin-top:8vh}@media(max-width:600px){main,header{padding:.8rem}.detail-heading,.page-heading{flex-direction:column}.actions{width:100%}.actions .button{flex:1;text-align:center}.inline-form{flex-direction:column}.recipe-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}}"""

}
