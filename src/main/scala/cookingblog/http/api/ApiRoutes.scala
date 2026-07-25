package cookingblog.http.api

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.{AuthenticatedSession, SessionManager}
import cookingblog.domain.*
import cookingblog.http.api.ApiJson.given
import cookingblog.service.*
import cookingblog.service.ApiError.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

final class ApiRoutes(
    service: RecipeApiService,
    sessionManager: SessionManager[IO]
) {
  private val csrfHeader = CIString("X-CSRF-Token")

  def routes(session: AuthenticatedSession): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ POST -> Root / "api" / "v1" / "recipes" =>
        mutation(session, request) {
          decode[CreateRecipeInput](request).flatMap(
            _.traverse(service.createRecipe).map(_.flatten).flatMap(created(_, Status.Created))
          )
        }

      case GET -> Root / "api" / "v1" / "recipes" / rawRecipeId =>
        withRecipeId(rawRecipeId)(id => service.getRecipe(id).flatMap(ok))

      case request @ GET -> Root / "api" / "v1" / "recipes" =>
        val params = request.uri.query.params
        val sort =
          params
            .get("sort")
            .fold[Either[ApiError, RecipeSort]](
              Right(RecipeSort.Recent)
            ) {
              case RecipeSort.Recent.value  => Right(RecipeSort.Recent)
              case RecipeSort.Updated.value => Right(RecipeSort.Updated)
              case RecipeSort.Title.value   => Right(RecipeSort.Title)
              case _                        =>
                Left(
                  Validation(
                    Map("sort" -> List("must be recent, updated, or title"))
                  )
                )
            }
        val limit =
          params
            .get("limit")
            .fold[Either[ApiError, Int]](Right(20))(
              _.toIntOption.toRight(
                Validation(Map("limit" -> List("must be an integer")))
              )
            )
        (sort, limit).mapN((_, _)) match {
          case Left(error)                    => errorResponse(error)
          case Right((sortValue, limitValue)) =>
            service
              .listRecipes(
                params.get("q"),
                sortValue,
                limitValue,
                params.get("cursor")
              )
              .flatMap(ok)
        }

      case request @ PATCH -> Root / "api" / "v1" / "recipes" / rawRecipeId =>
        mutation(session, request) {
          withRecipeId(rawRecipeId) { id =>
            decode[UpdateRecipeInput](request).flatMap(
              _.traverse(service.updateRecipe(id, _))
                .map(_.flatten)
                .flatMap(ok)
            )
          }
        }

      case request @ DELETE -> Root / "api" / "v1" / "recipes" / rawRecipeId =>
        mutation(session, request) {
          withRecipeId(rawRecipeId)(id => service.deleteRecipe(id).flatMap(noContent))
        }

      case request @ POST -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "meals" =>
        mutation(session, request) {
          withRecipeId(rawRecipeId) { recipeId =>
            decode[CreateMealInput](request).flatMap(
              _.traverse(service.createMeal(recipeId, _))
                .map(_.flatten)
                .flatMap(created(_, Status.Created))
            )
          }
        }

      case GET -> Root / "api" / "v1" / "recipes" / rawRecipeId /
          "meals" / rawMealId =>
        withIds(rawRecipeId, rawMealId) { (recipeId, mealId) =>
          service.getMeal(recipeId, mealId).flatMap(ok)
        }

      case request @ PATCH -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "meals" / rawMealId =>
        mutation(session, request) {
          withIds(rawRecipeId, rawMealId) { (recipeId, mealId) =>
            decode[UpdateMealInput](request).flatMap(
              _.traverse(service.updateMeal(recipeId, mealId, _))
                .map(_.flatten)
                .flatMap(ok)
            )
          }
        }

      case request @ DELETE -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "meals" / rawMealId =>
        mutation(session, request) {
          withIds(rawRecipeId, rawMealId) { (recipeId, mealId) =>
            service.deleteMeal(recipeId, mealId).flatMap(noContent)
          }
        }

      case request @ POST -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "references" =>
        mutation(session, request) {
          withRecipeId(rawRecipeId) { recipeId =>
            decode[CreateReferenceInput](request).flatMap(
              _.traverse(service.createReference(recipeId, _))
                .map(_.flatten)
                .flatMap(created(_, Status.Created))
            )
          }
        }

      case request @ PATCH -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "references" / rawReferenceId =>
        mutation(session, request) {
          withReferenceIds(rawRecipeId, rawReferenceId) { (recipeId, referenceId) =>
            decode[UpdateReferenceInput](request).flatMap(
              _.traverse(service.updateReference(recipeId, referenceId, _))
                .map(_.flatten)
                .flatMap(ok)
            )
          }
        }

      case request @ DELETE -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "references" / rawReferenceId =>
        mutation(session, request) {
          withReferenceIds(rawRecipeId, rawReferenceId) { (recipeId, referenceId) =>
            service.deleteReference(recipeId, referenceId).flatMap(noContent)
          }
        }

      case request @ POST -> Root / "api" / "v1" / "recipes" /
          rawRecipeId / "references" / rawReferenceId / "scrape" =>
        mutation(session, request) {
          withReferenceIds(rawRecipeId, rawReferenceId) { (recipeId, referenceId) =>
            service
              .retryReference(recipeId, referenceId)
              .flatMap(created(_, Status.Accepted))
          }
        }
    }

  private def mutation(
      session: AuthenticatedSession,
      request: Request[IO]
  )(response: => IO[Response[IO]]): IO[Response[IO]] =
    request.headers.get(csrfHeader).map(_.head.value) match {
      case None         => invalidCsrf
      case Some(secret) =>
        sessionManager.validateCsrf(session, secret).flatMap {
          case true  => response
          case false => invalidCsrf
        }
    }

  private def invalidCsrf: IO[Response[IO]] =
    IO.pure(
      Response[IO](Status.Forbidden)
        .withEntity(
          Json.obj(
            "code" -> Json.fromString("invalid_csrf"),
            "message" -> Json.fromString("Invalid CSRF token")
          )
        )
    )

  private def decode[A: Decoder](request: Request[IO]): IO[Either[ApiError, A]] =
    request
      .asJsonDecode[A]
      .attempt
      .map(
        _.leftMap(_ => Validation(Map("body" -> List("must be valid JSON for this endpoint"))))
      )

  private def withRecipeId(
      raw: String
  )(use: RecipeId => IO[Response[IO]]): IO[Response[IO]] =
    RecipeId.parse(raw) match {
      case Left(_) =>
        errorResponse(Validation(Map("recipeId" -> List("must be a UUID"))))
      case Right(id) => use(id)
    }

  private def withIds(
      rawRecipeId: String,
      rawMealId: String
  )(use: (RecipeId, MealId) => IO[Response[IO]]): IO[Response[IO]] =
    (RecipeId.parse(rawRecipeId), MealId.parse(rawMealId)) match {
      case (Right(recipeId), Right(mealId)) => use(recipeId, mealId)
      case (Left(_), _)                     =>
        errorResponse(Validation(Map("recipeId" -> List("must be a UUID"))))
      case (_, Left(_)) =>
        errorResponse(Validation(Map("mealId" -> List("must be a UUID"))))
    }

  private def withReferenceIds(
      rawRecipeId: String,
      rawReferenceId: String
  )(use: (RecipeId, ReferenceId) => IO[Response[IO]]): IO[Response[IO]] =
    (RecipeId.parse(rawRecipeId), ReferenceId.parse(rawReferenceId)) match {
      case (Right(recipeId), Right(referenceId)) => use(recipeId, referenceId)
      case (Left(_), _)                          =>
        errorResponse(Validation(Map("recipeId" -> List("must be a UUID"))))
      case (_, Left(_)) =>
        errorResponse(
          Validation(Map("referenceId" -> List("must be a UUID")))
        )
    }

  private def ok[A: Encoder](
      result: Either[ApiError, A]
  ): IO[Response[IO]] =
    result.fold(errorResponse, value => Ok(value.asJson))

  private def created[A: Encoder](
      result: Either[ApiError, A],
      status: Status
  ): IO[Response[IO]] =
    result.fold(
      errorResponse,
      value => IO.pure(Response[IO](status).withEntity(value.asJson))
    )

  private def noContent(
      result: Either[ApiError, Unit]
  ): IO[Response[IO]] =
    result.fold(errorResponse, _ => NoContent())

  private def errorResponse(error: ApiError): IO[Response[IO]] = {
    val (status, code, message, fields) =
      error match {
        case Validation(fieldErrors) =>
          (
            Status.BadRequest,
            "validation_error",
            "Request validation failed",
            Some(fieldErrors)
          )
        case ApiError.NotFound(resource) =>
          (
            Status.NotFound,
            "not_found",
            s"${resource.capitalize} not found",
            None
          )
        case ApiError.Conflict(detail) =>
          (Status.Conflict, "conflict", detail, None)
        case InvalidRelationship(detail) =>
          (Status.Conflict, "invalid_relationship", detail, None)
      }
    val base =
      List(
        "code" -> Json.fromString(code),
        "message" -> Json.fromString(message)
      )
    val json =
      Json.obj(
        (base ++ fields.toList.map(value => "fieldErrors" -> value.asJson))*
      )
    IO.pure(Response[IO](status).withEntity(json))
  }
}
