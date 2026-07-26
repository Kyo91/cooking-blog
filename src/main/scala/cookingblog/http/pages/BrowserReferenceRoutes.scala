package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import cookingblog.service.{CreateReferenceInput, UpdateReferenceInput}
import org.http4s.*
import org.http4s.dsl.io.*

private[pages] trait BrowserReferenceRoutes {
  self: BrowserPageRoutes =>

  protected def referenceRoutes(session: AuthenticatedSession): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ POST -> Root / "recipes" / rawRecipeId / "references" =>
        recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
          browserMutation(session, request) { form =>
            recipeService.createReference(recipeIdValue, referenceInput(form)).flatMap {
              case Right(_)    => redirectToRecipe(id(recipeIdValue))
              case Left(error) => formFailure(error)
            }
          }
        }

      case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId =>
        (recipeId(rawRecipeId), referenceId(rawReferenceId))
          .mapN((_, _))
          .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
            browserMutation(session, request) { form =>
              recipeService
                .updateReference(recipeIdValue, referenceIdValue, referenceUpdateInput(form))
                .flatMap {
                  case Right(_)    => redirectToRecipe(id(recipeIdValue))
                  case Left(error) => formFailure(error)
                }
            }
          }

      case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId / "delete" =>
        (recipeId(rawRecipeId), referenceId(rawReferenceId))
          .mapN((_, _))
          .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
            browserMutation(session, request) { _ =>
              recipeService.deleteReference(recipeIdValue, referenceIdValue).flatMap {
                case Right(_)    => redirectToRecipe(id(recipeIdValue))
                case Left(error) => formFailure(error)
              }
            }
          }

      case request @ POST -> Root / "recipes" / rawRecipeId / "references" / rawReferenceId / "scrape" =>
        (recipeId(rawRecipeId), referenceId(rawReferenceId))
          .mapN((_, _))
          .fold(notFoundPage) { case (recipeIdValue, referenceIdValue) =>
            browserMutation(session, request) { _ =>
              recipeService.retryReference(recipeIdValue, referenceIdValue).flatMap {
                case Right(_)    => redirectToRecipe(id(recipeIdValue))
                case Left(error) => formFailure(error)
              }
            }
          }
    }

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
}
