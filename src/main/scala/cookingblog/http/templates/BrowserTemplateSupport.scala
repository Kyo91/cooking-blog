package cookingblog.http.templates

import cats.effect.IO
import cookingblog.auth.AuthenticatedSession
import cookingblog.domain.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import scalatags.Text.Frag
import scalatags.Text.all.*
import scalatags.Text.attrs.{id as htmlId}
import scalatags.Text.tags2.{main, style, title}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.annotation.targetName

private[templates] trait BrowserTemplateSupport {
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
  protected def referenceId(raw: String): Option[ReferenceId] = ReferenceId.parse(raw).toOption
  protected def photoId(raw: String): Option[PhotoId] = PhotoId.parse(raw).toOption

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

  private val styles =
    """:root{font-family:system-ui,sans-serif;color:#20231f;background:#fbfaf6;line-height:1.45}*{box-sizing:border-box}body{margin:0}main,header{max-width:1100px;margin:auto;padding:1rem}header{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #dedbd1}.brand{font-weight:800;color:inherit;text-decoration:none}h1{line-height:1.1}a{color:#295c43}.button,button{border:1px solid #295c43;border-radius:.5rem;background:#fff;color:#173c2b;padding:.65rem .85rem;font:inherit;text-decoration:none;cursor:pointer}.primary{background:#295c43;color:#fff}.link-button{border:0;padding:0;background:none}:focus-visible{outline:3px solid #1d6fb8;outline-offset:3px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.page-heading,.detail-heading{display:flex;justify-content:space-between;gap:1rem;align-items:start}.page-heading .primary{font-size:1.5rem;line-height:1}.recipe-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:1rem;margin-top:1rem}.recipe-card,.meal,.reference,.empty-state{border:1px solid #dedbd1;border-radius:.75rem;background:#fff;overflow:hidden;padding:1rem}.recipe-card{padding:0}.recipe-card img{width:100%;height:150px;object-fit:cover;background:#e9e7df}.recipe-card div{padding:0 1rem 1rem}.recipe-card h2{margin-bottom:.25rem}.recipe-card p{margin:.4rem 0}.muted,.hint{color:#62685f}.error,.form-error{color:#a72626}.form-page{max-width:680px}form{display:grid;gap:.65rem}input,textarea{width:100%;font:inherit;padding:.7rem;border:1px solid #989b92;border-radius:.4rem}textarea{min-height:8rem}.inline-form{display:flex;gap:.5rem}.inline-form input{flex:1}.actions{display:flex;flex-wrap:wrap;gap:.5rem}.hero-photo{width:100%;max-height:480px;object-fit:cover;background:#e9e7df;border-radius:.75rem}.chips{display:flex;gap:.4rem;flex-wrap:wrap;padding:0;list-style:none}.chips li,.status{background:#e7f1e8;border-radius:999px;padding:.2rem .55rem;font-size:.9rem}.meal{margin:.8rem 0}.meal>div:first-child{display:flex;justify-content:space-between;align-items:center}.meal-photos,.photo-previews{display:flex;gap:.5rem;flex-wrap:wrap}.meal-photos figure{margin:0;width:110px}.meal-photos img,.photo-previews img{width:110px;height:90px;object-fit:cover;border-radius:.4rem}.meal-photos figcaption{font-size:.8rem}.reference{margin:.5rem 0}.login{max-width:420px;margin-top:8vh}@media(max-width:600px){main,header{padding:.8rem}.detail-heading,.page-heading{flex-direction:column}.actions{width:100%}.actions .button{flex:1;text-align:center}.inline-form{flex-direction:column}.recipe-grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}}"""
}
