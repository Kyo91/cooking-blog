package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.AuthenticatedSession
import cookingblog.service.{ApiError, CreateMealInput, UpdateMealInput}
import org.http4s.*
import org.http4s.dsl.io.*

import java.time.Instant

private[pages] trait BrowserMealRoutes {
  self: BrowserPageRoutes =>

  protected def mealRoutes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" =>
      recipeId(rawRecipeId).fold(notFoundPage) { recipeIdValue =>
        browserMutation(session, request) { form =>
          mealInput(form).fold(
            formFailure,
            input =>
              recipeService.createMeal(recipeIdValue, input).flatMap {
                case Right(meal) =>
                  SeeOther(
                    org.http4s.headers.Location(
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
                  case Right(_)    => redirectToRecipe(id(recipeIdValue))
                  case Left(error) => formFailure(error)
                }
            )
          }
      }

    case request @ POST -> Root / "recipes" / rawRecipeId / "meals" / rawMealId / "delete" =>
      (recipeId(rawRecipeId), mealId(rawMealId)).mapN((_, _)).fold(notFoundPage) {
        case (recipeIdValue, mealIdValue) =>
          browserMutation(session, request) { _ =>
            recipeService.deleteMeal(recipeIdValue, mealIdValue).flatMap {
              case Right(_)    => redirectToRecipe(id(recipeIdValue))
              case Left(error) => formFailure(error)
            }
          }
      }
  }

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
}
