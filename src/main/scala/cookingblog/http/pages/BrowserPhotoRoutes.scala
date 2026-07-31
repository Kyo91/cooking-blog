package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.domain.PhotoId
import cookingblog.auth.AuthenticatedSession
import cookingblog.service.UpdatePhotoInput
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

private[pages] trait BrowserPhotoRoutes {
  self: BrowserPageRoutes =>

  protected def photoRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "media" / rawPhotoId / "download" =>
      PhotoId
        .parse(rawPhotoId)
        .fold(
          _ => notFoundPage,
          photoIdValue =>
            photoService.media(photoIdValue, cookingblog.storage.PhotoVariant.Original).flatMap {
              case Left(_)      => notFoundPage
              case Right(media) =>
                Ok(media.body).map(
                  _.putHeaders(
                    Header.Raw(
                      CIString("Content-Disposition"),
                      s"attachment; filename=\"${safeDownloadName(media.photo.originalFilename)}\""
                    ),
                    Header.Raw(CIString("Cache-Control"), "private, no-store"),
                    Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
                    Header.Raw(CIString("Content-Type"), media.photo.contentType)
                  )
                )
            }
        )

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

  private def safeDownloadName(filename: String): String = {
    val normalized = filename
      .filter(character => character.isLetterOrDigit || ".-_".contains(character))
      .take(255)
    if (normalized.isEmpty || normalized == "." || normalized == "..") "photo" else normalized
  }
}
