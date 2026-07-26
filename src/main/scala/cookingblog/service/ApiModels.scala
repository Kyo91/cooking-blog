package cookingblog.service

import cookingblog.domain.*

import java.time.Instant

/** Expected application failures that map consistently to JSON and HTML HTTP responses. */
sealed trait ApiError extends Product with Serializable

object ApiError {
  final case class Validation(fieldErrors: Map[String, List[String]]) extends ApiError
  final case class NotFound(resource: String) extends ApiError
  final case class Conflict(message: String) extends ApiError
  final case class InvalidRelationship(message: String) extends ApiError
  final case class UnsupportedMedia(message: String) extends ApiError
  final case class PayloadTooLarge(message: String) extends ApiError
  final case class UnavailableDependency(message: String) extends ApiError
}

enum RecipeSort(val value: String) {
  case Recent extends RecipeSort("recent")
  case Updated extends RecipeSort("updated")
  case Title extends RecipeSort("title")
}

final case class RecipePage(
    items: List[Recipe],
    nextCursor: Option[String]
)

final case class ReferenceScrapeStatus(
    referenceId: ReferenceId,
    importStatus: String,
    latestJob: Option[ScrapeJob],
    document: Option[ScrapedDocument]
)

final case class CreateRecipeInput(
    title: String,
    description: Option[String],
    keywords: Option[String]
)
final case class UpdateRecipeInput(
    title: Option[String],
    description: Option[String],
    keywords: Option[String]
)
final case class CreateMealInput(notes: Option[String], cookedAt: Instant)
final case class UpdateMealInput(
    notes: Option[String],
    cookedAt: Option[Instant]
)
final case class CreateReferenceInput(
    kind: String,
    url: Option[String],
    citation: Option[String],
    displayName: Option[String]
)
final case class UpdateReferenceInput(
    url: Option[String],
    citation: Option[String],
    displayName: Option[String]
)
final case class UpdatePhotoInput(comment: Option[String])
