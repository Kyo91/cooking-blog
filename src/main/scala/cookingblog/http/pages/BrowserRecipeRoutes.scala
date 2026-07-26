package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import cookingblog.domain.RecipeId
import cookingblog.repository.DoobieRepositories
import cookingblog.service.{ApiError, CreateRecipeInput, CreateReferenceInput, UpdateRecipeInput}
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location

private[pages] trait BrowserRecipeRoutes {
  self: BrowserPageRoutes =>

  protected def recipeRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "recipes" =>
      browserMutation(session, request) { form =>
        recipeService.createRecipe(createRecipeInput(form)).flatMap {
          case Right(recipe) =>
            createFormReferences(recipe.id, form).flatMap {
              case Right(_)    => redirectToRecipe(id(recipe.id))
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
                case Right(_)    => redirectToRecipe(id(recipeIdValue))
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

  private def createFormReferences(
      recipeIdValue: RecipeId,
      form: UrlForm
  ): IO[Either[ApiError, Unit]] =
    formValues(form, "source_kind").zipWithIndex.foldLeftM[IO, Either[ApiError, Unit]](Right(())) {
      case (left @ Left(_), _)       => IO.pure(left)
      case (Right(_), (kind, index)) =>
        val urlValue = formValues(form, "source_url").lift(index).filter(_.nonEmpty)
        val citationValue = formValues(form, "source_citation").lift(index).filter(_.nonEmpty)
        if (urlValue.isEmpty && citationValue.isEmpty) {
          IO.pure(Right(()))
        } else {
          recipeService
            .createReference(
              recipeIdValue,
              CreateReferenceInput(kind, urlValue, citationValue, None)
            )
            .map(_.map(_ => ()))
        }
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
}
