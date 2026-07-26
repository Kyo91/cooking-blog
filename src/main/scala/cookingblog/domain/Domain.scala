package cookingblog.domain

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Type-safe wrapper around the UUID representation used by a domain aggregate. */
trait DomainId[A] {
  def apply(value: UUID): A
  def value(id: A): UUID

  final def random: A = apply(UUID.randomUUID())

  final def parse(raw: String): Either[String, A] =
    Try(UUID.fromString(raw)).toEither.left
      .map(_ => s"Invalid UUID: $raw")
      .map(apply)
}

opaque type RecipeId = UUID
object RecipeId extends DomainId[RecipeId] {
  override def apply(value: UUID): RecipeId = value
  override def value(id: RecipeId): UUID = id
}

opaque type MealId = UUID
object MealId extends DomainId[MealId] {
  override def apply(value: UUID): MealId = value
  override def value(id: MealId): UUID = id
}

opaque type PhotoId = UUID
object PhotoId extends DomainId[PhotoId] {
  override def apply(value: UUID): PhotoId = value
  override def value(id: PhotoId): UUID = id
}

opaque type ReferenceId = UUID
object ReferenceId extends DomainId[ReferenceId] {
  override def apply(value: UUID): ReferenceId = value
  override def value(id: ReferenceId): UUID = id
}

opaque type ScrapedDocumentId = UUID
object ScrapedDocumentId extends DomainId[ScrapedDocumentId] {
  override def apply(value: UUID): ScrapedDocumentId = value
  override def value(id: ScrapedDocumentId): UUID = id
}

opaque type ScrapeJobId = UUID
object ScrapeJobId extends DomainId[ScrapeJobId] {
  override def apply(value: UUID): ScrapeJobId = value
  override def value(id: ScrapeJobId): UUID = id
}

enum ReferenceKind(val databaseValue: String) {
  case Url extends ReferenceKind("url")
  case Book extends ReferenceKind("book")
}

object ReferenceKind {
  def fromDatabase(value: String): Either[String, ReferenceKind] =
    ReferenceKind.values
      .find(_.databaseValue == value)
      .toRight(s"Unknown reference kind: $value")
}

enum ScrapeJobStatus(val databaseValue: String) {
  case Pending extends ScrapeJobStatus("pending")
  case Running extends ScrapeJobStatus("running")
  case Succeeded extends ScrapeJobStatus("succeeded")
  case Failed extends ScrapeJobStatus("failed")
}

object ScrapeJobStatus {
  def fromDatabase(value: String): Either[String, ScrapeJobStatus] =
    ScrapeJobStatus.values
      .find(_.databaseValue == value)
      .toRight(s"Unknown scrape job status: $value")
}

/** Shared recipe aggregate; meals, references, keywords, and photos are related records. */
final case class Recipe(
    id: RecipeId,
    title: String,
    description: String,
    primaryPhotoId: Option[PhotoId],
    createdAt: Instant,
    updatedAt: Instant,
    lastMadeAt: Option[Instant]
)

/** One dated instance of cooking a recipe, kept separately from the recipe itself. */
final case class Meal(
    id: MealId,
    recipeId: RecipeId,
    notes: String,
    cookedAt: Instant,
    createdAt: Instant,
    updatedAt: Instant
)

/** Metadata for a locally stored photo whose bytes are owned by [[cookingblog.storage.PhotoStore]].
  */
final case class Photo(
    id: PhotoId,
    mealId: MealId,
    storageKey: String,
    originalFilename: String,
    contentType: String,
    byteSize: Long,
    width: Option[Int],
    height: Option[Int],
    comment: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

/** A user-supplied URL or book citation associated with a recipe. */
final case class RecipeReference(
    id: ReferenceId,
    recipeId: RecipeId,
    kind: ReferenceKind,
    url: Option[String],
    citation: Option[String],
    displayName: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

/** Sanitized, extracted text from a URL reference; raw HTML is deliberately not retained. */
final case class ScrapedDocument(
    id: ScrapedDocumentId,
    referenceId: ReferenceId,
    sourceUrl: String,
    resolvedUrl: Option[String],
    title: Option[String],
    contentText: String,
    contentHash: String,
    httpEtag: Option[String],
    httpLastModified: Option[String],
    scrapedAt: Instant,
    updatedAt: Instant
)

/** Durable work-queue entry for importing one URL reference. */
final case class ScrapeJob(
    id: ScrapeJobId,
    referenceId: ReferenceId,
    status: ScrapeJobStatus,
    attemptCount: Int,
    availableAt: Instant,
    claimedAt: Option[Instant],
    finishedAt: Option[Instant],
    lastError: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

/** Denormalized searchable projection rebuilt after recipe-related writes. */
final case class RecipeSearchDocument(
    recipeId: RecipeId,
    plainText: String,
    searchVector: String,
    updatedAt: Instant
)

/** A normalized user keyword used to improve recipe recall and ranking. */
final case class RecipeKeyword(
    id: UUID,
    recipeId: RecipeId,
    keyword: String
)

/** A recipe with its database-calculated relevance score for a query. */
final case class RecipeSearchResult(recipe: Recipe, rank: Double)
