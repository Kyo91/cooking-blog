package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import cookingblog.service.UpdatePhotoInput
import org.http4s.*
import org.http4s.dsl.io.*

private[pages] trait BrowserPhotoRoutes {
  self: BrowserPageRoutes =>

  protected def photoRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "photos" / rawPhotoId =>
      (recipeId(rawRecipeId), mealId(rawMealId), photoId(rawPhotoId))
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
                case Right(_)    => redirectToRecipe(id(recipeIdValue))
                case Left(error) => formFailure(error)
              }
          }
        }

    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "photos" / rawPhotoId / "delete" =>
      (recipeId(rawRecipeId), mealId(rawMealId), photoId(rawPhotoId))
        .mapN((_, _, _))
        .fold(notFoundPage) { case (recipeIdValue, mealIdValue, photoIdValue) =>
          browserMutation(session, request) { _ =>
            photoService.deletePhoto(recipeIdValue, mealIdValue, photoIdValue).flatMap {
              case Right(_)    => redirectToRecipe(id(recipeIdValue))
              case Left(error) => formFailure(error)
            }
          }
        }

    case request @ POST -> Root / "recipes" / rawRecipeId / "primary-photo" / rawPhotoId =>
      (recipeId(rawRecipeId), photoId(rawPhotoId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, photoIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.selectPrimaryPhoto(recipeIdValue, photoIdValue).flatMap {
              case Right(_)    => redirectToRecipe(id(recipeIdValue))
              case Left(error) => formFailure(error)
            }
          }
      }
  }
}
