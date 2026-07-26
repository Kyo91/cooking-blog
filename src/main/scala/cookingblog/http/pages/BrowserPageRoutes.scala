package cookingblog.http.pages

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.auth.{AuthenticatedSession, SessionManager}
import cookingblog.domain.*
import cookingblog.repository.DoobieRepositories
import cookingblog.service.*
import cookingblog.http.templates.BrowserPageTemplates
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `Content-Type`}
import scalatags.Text.all.*

import java.time.Instant

/** Browser-only routes and server-rendered pages. */
final class BrowserPageRoutes(
    sessionManager: SessionManager[IO],
    protected val recipeService: RecipeApiService,
    protected val photoService: PhotoService,
    protected val transactor: Transactor[IO]
) extends BrowserPageTemplates {
  def routes(session: AuthenticatedSession): HttpRoutes[IO] = HttpRoutes.of[IO] {
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

}
