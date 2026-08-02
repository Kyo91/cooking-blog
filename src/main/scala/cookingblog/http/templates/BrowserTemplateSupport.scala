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
import scalatags.Text.tags2.{details, main, summary as detailsSummary, title}

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

  protected def actionTray(actions: Frag*): Frag =
    div(cls := "action-tray", actions)

  protected def iconActionLink(
      hrefValue: String,
      accessibleName: String,
      icon: String,
      primary: Boolean = false,
      download: Boolean = false,
      visibleLabel: Option[String] = None
  ): Frag =
    a(
      cls := s"icon-action${
          if (primary) " primary-action" else ""
        }${if (visibleLabel.nonEmpty) " has-label" else ""}",
      href := hrefValue,
      Option.when(download)(attr("download") := ""),
      aria.label := accessibleName,
      attr("title") := accessibleName,
      span(cls := "action-glyph", aria.hidden := "true", icon),
      span(
        cls := visibleLabel.fold("sr-only")(_ => "action-label"),
        visibleLabel.getOrElse(accessibleName)
      )
    )

  protected def iconActionForm(
      actionUrl: String,
      csrfToken: String,
      accessibleName: String,
      icon: String,
      confirmation: Option[String] = None,
      primary: Boolean = false,
      visibleLabel: Option[String] = None,
      pressed: Option[Boolean] = None,
      disabledAction: Boolean = false
  ): Frag =
    form(
      method := "post",
      action := actionUrl,
      cls := (if (confirmation.nonEmpty) "icon-action-form confirmation-form"
              else "icon-action-form"),
      confirmation.fold(frag())(message => attr("data-confirm") := message),
      input(tpe := "hidden", name := "csrf_token", value := csrfToken),
      button(
        cls := s"icon-action${if (primary) " primary-action" else ""}${
            if (visibleLabel.nonEmpty) " has-label" else ""
          }",
        tpe := "submit",
        Option.when(disabledAction)(disabled),
        pressed.map(value => attr("aria-pressed") := value.toString),
        aria.label := accessibleName,
        attr("title") := accessibleName,
        span(cls := "action-glyph", aria.hidden := "true", icon),
        span(
          cls := visibleLabel.fold("sr-only")(_ => "action-label"),
          visibleLabel.getOrElse(accessibleName)
        )
      )
    )

  protected def overflowActionForm(
      actionUrl: String,
      csrfToken: String,
      accessibleName: String,
      icon: String,
      confirmation: Option[String] = None,
      danger: Boolean = false
  ): Frag =
    form(
      method := "post",
      action := actionUrl,
      cls := (if (confirmation.nonEmpty) "menu-action-form confirmation-form"
              else "menu-action-form"),
      confirmation.fold(frag())(message => attr("data-confirm") := message),
      input(tpe := "hidden", name := "csrf_token", value := csrfToken),
      button(
        cls := s"menu-action${if (danger) " danger-action" else ""}",
        tpe := "submit",
        span(cls := "action-glyph", aria.hidden := "true", icon),
        span(accessibleName)
      )
    )

  protected def overflowActionMenu(
      accessibleName: String,
      actions: Frag*
  ): Frag =
    details(
      cls := "overflow-menu",
      detailsSummary(
        cls := "icon-action has-label overflow-trigger",
        aria.label := accessibleName,
        attr("title") := accessibleName,
        span(cls := "action-glyph", aria.hidden := "true", "⋯"),
        span(cls := "action-label", "More")
      ),
      div(
        cls := "overflow-actions",
        attr("role") := "group",
        actions
      )
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
        link(rel := "stylesheet", href := "/static/app-v1.css")
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
}
