package cookingblog.repository

import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.storage.StorageKey
import cats.effect.IO
import cookingblog.database.Database.{affectedRows, instant}
import skunk.{Codec, Decoder, Session}
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.UUID

/** Table-oriented persistence boundary for recipe rows and their cached last-made timestamp. */
trait RecipeRepository[F[_]] {
  def create(recipe: Recipe): F[Unit]
  def find(id: RecipeId): F[Option[Recipe]]

  /** Existing-recipe mutations lock this row before reading/merging or changing child rows. Keep
    * the lock, source writes, and projection rebuilds in the same service transaction. Acquire
    * recipe locks before meal/photo/reference/document/job locks. New recipes are exclusively owned
    * by their creating transaction. Do not hold locks across external I/O.
    */
  def findForUpdate(id: RecipeId): F[Option[Recipe]]
  def updateDetails(
      id: RecipeId,
      title: String,
      description: String,
      updatedAt: Instant
  ): F[Option[Recipe]]
  def setPrimaryPhoto(id: RecipeId, photoId: PhotoId, updatedAt: Instant): F[Option[Recipe]]
  def delete(id: RecipeId): F[Boolean]
  def list: F[List[Recipe]]
  def refreshLastMadeAt(id: RecipeId, updatedAt: Instant): F[Boolean]
}

/** Table-oriented persistence boundary for cooking events. */
trait MealRepository[F[_]] {
  def create(meal: Meal): F[Unit]
  def find(id: MealId): F[Option[Meal]]
  def update(meal: Meal): F[Boolean]
  def delete(id: MealId): F[Boolean]
  def listByRecipe(recipeId: RecipeId): F[List[Meal]]
}

/** Table-oriented persistence boundary for photo metadata, not photo bytes. */
trait PhotoRepository[F[_]] {
  def create(photo: Photo): F[Unit]
  def find(id: PhotoId): F[Option[Photo]]
  def update(photo: Photo): F[Boolean]
  def delete(id: PhotoId): F[Boolean]
  def listByMeal(mealId: MealId): F[List[Photo]]
  def listByRecipe(recipeId: RecipeId): F[List[Photo]]
  def listAll: F[List[Photo]]
  def listStorageKeys: F[List[StorageKey]]
  def findPrimaryForRecipe(recipeId: RecipeId): F[Option[Photo]]
}

/** Table-oriented persistence boundary for recipe URL and book references. */
trait RecipeReferenceRepository[F[_]] {
  def create(reference: RecipeReference): F[Unit]
  def find(id: ReferenceId): F[Option[RecipeReference]]
  def update(reference: RecipeReference): F[Boolean]
  def delete(id: ReferenceId): F[Boolean]
  def listByRecipe(recipeId: RecipeId): F[List[RecipeReference]]
}

/** Table-oriented persistence boundary for extracted, sanitized import content. */
trait ScrapedDocumentRepository[F[_]] {
  def create(document: ScrapedDocument): F[Unit]
  def find(id: ScrapedDocumentId): F[Option[ScrapedDocument]]
  def findByReference(referenceId: ReferenceId): F[Option[ScrapedDocument]]
  def update(document: ScrapedDocument): F[Boolean]
  def delete(id: ScrapedDocumentId): F[Boolean]
}

/** Durable queue boundary for scrape jobs, including safe concurrent claiming and stale-job
  * recovery.
  */
trait ScrapeJobRepository[F[_]] {
  def create(job: ScrapeJob): F[Unit]
  def find(id: ScrapeJobId): F[Option[ScrapeJob]]
  def findLatestByReference(referenceId: ReferenceId): F[Option[ScrapeJob]]
  def claimNext(availableAt: Instant): F[Option[ScrapeJob]]
  def recoverStale(
      claimedBefore: Instant,
      recoveredAt: Instant,
      maximumAttempts: Int
  ): F[Int]
  def update(job: ScrapeJob): F[Boolean]
  def delete(id: ScrapeJobId): F[Boolean]
  def listByReference(referenceId: ReferenceId): F[List[ScrapeJob]]
  def countsByStatus: F[Map[String, Long]]
}

/** Maintains and queries the denormalized PostgreSQL full-text search projection. */
trait RecipeSearchDocumentRepository[F[_]] {
  def create(document: RecipeSearchDocument): F[Unit]
  def find(recipeId: RecipeId): F[Option[RecipeSearchDocument]]
  def update(document: RecipeSearchDocument): F[Boolean]
  def delete(recipeId: RecipeId): F[Boolean]
  def rebuildSearchDocument(recipeId: RecipeId, updatedAt: Instant): F[Unit]
  def search(query: String): F[List[RecipeSearchResult]]
}

/** Persists the normalized keyword set belonging to each recipe. */
trait RecipeKeywordRepository[F[_]] {
  def listByRecipe(recipeId: RecipeId): F[List[RecipeKeyword]]
  def replace(recipeId: RecipeId, keywords: List[String]): F[Unit]
}

final class SkunkRepositories(session: Session[IO]) {
  val recipes: RecipeRepository[IO] = new SkunkRecipeRepository(session)
  val meals: MealRepository[IO] = new SkunkMealRepository(session)
  val photos: PhotoRepository[IO] = new SkunkPhotoRepository(session)
  val references: RecipeReferenceRepository[IO] =
    new SkunkRecipeReferenceRepository(session)
  val scrapedDocuments: ScrapedDocumentRepository[IO] =
    new SkunkScrapedDocumentRepository(session)
  val scrapeJobs: ScrapeJobRepository[IO] = new SkunkScrapeJobRepository(session)
  val searchDocuments: RecipeSearchDocumentRepository[IO] =
    new SkunkRecipeSearchDocumentRepository(session)
  val keywords: RecipeKeywordRepository[IO] = new SkunkRecipeKeywordRepository(session)
}

private object RepositoryMapping {
  final case class PhotoRow(
      id: UUID,
      mealId: UUID,
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

  final case class ReferenceRow(
      id: UUID,
      recipeId: UUID,
      kind: String,
      url: Option[String],
      citation: Option[String],
      displayName: Option[String],
      createdAt: Instant,
      updatedAt: Instant
  )

  final case class ScrapedDocumentRow(
      id: UUID,
      referenceId: UUID,
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

  final case class ScrapeJobRow(
      id: UUID,
      referenceId: UUID,
      status: String,
      attemptCount: Int,
      availableAt: Instant,
      claimedAt: Option[Instant],
      finishedAt: Option[Instant],
      lastError: Option[String],
      createdAt: Instant,
      updatedAt: Instant
  )

  val recipeIdCodec: Codec[RecipeId] = uuid.imap(RecipeId.apply)(RecipeId.value)
  val mealIdCodec: Codec[MealId] = uuid.imap(MealId.apply)(MealId.value)
  val photoIdCodec: Codec[PhotoId] = uuid.imap(PhotoId.apply)(PhotoId.value)

  val recipeCodec: Codec[Recipe] =
    (recipeIdCodec *: text *: text *: photoIdCodec.opt *: instant *: instant *: instant.opt)
      .to[Recipe]

  val mealCodec: Codec[Meal] =
    (mealIdCodec *: recipeIdCodec *: text *: instant *: instant *: instant).to[Meal]

  val photoRowDecoder: Decoder[PhotoRow] =
    (uuid *: uuid *: text *: text *: text *: int8 *: int4.opt *: int4.opt *: text.opt *: instant *: instant)
      .to[PhotoRow]

  val referenceRowDecoder: Decoder[ReferenceRow] =
    (uuid *: uuid *: text *: text.opt *: text.opt *: text.opt *: instant *: instant)
      .to[ReferenceRow]

  val scrapedDocumentRowDecoder: Decoder[ScrapedDocumentRow] =
    (uuid *: uuid *: text *: text.opt *: text.opt *: text *: text *: text.opt *: text.opt *: instant *: instant)
      .to[ScrapedDocumentRow]

  val scrapeJobRowDecoder: Decoder[ScrapeJobRow] =
    (uuid *: uuid *: text *: int4 *: instant *: instant.opt *: instant.opt *: text.opt *: instant *: instant)
      .to[ScrapeJobRow]

  def photo(row: PhotoRow): Either[IllegalStateException, Photo] =
    StorageKey
      .parse(row.storageKey)
      .left
      .map(invalidStorageKey)
      .map(storageKey =>
        Photo(
          PhotoId(row.id),
          MealId(row.mealId),
          storageKey,
          row.originalFilename,
          row.contentType,
          row.byteSize,
          row.width,
          row.height,
          row.comment,
          row.createdAt,
          row.updatedAt
        )
      )

  def storageKey(value: String): Either[IllegalStateException, StorageKey] =
    StorageKey.parse(value).left.map(invalidStorageKey)

  private def invalidStorageKey(message: String): IllegalStateException =
    IllegalStateException(s"Invalid photos.storage_key: $message")

  def reference(row: ReferenceRow): RecipeReference = {
    RecipeReference(
      ReferenceId(row.id),
      RecipeId(row.recipeId),
      ReferenceKind
        .fromDatabase(row.kind)
        .fold(message => throw IllegalStateException(message), identity),
      row.url,
      row.citation,
      row.displayName,
      row.createdAt,
      row.updatedAt
    )
  }

  def scrapedDocument(row: ScrapedDocumentRow): ScrapedDocument =
    ScrapedDocument(
      ScrapedDocumentId(row.id),
      ReferenceId(row.referenceId),
      row.sourceUrl,
      row.resolvedUrl,
      row.title,
      row.contentText,
      row.contentHash,
      row.httpEtag,
      row.httpLastModified,
      row.scrapedAt,
      row.updatedAt
    )

  def scrapeJob(row: ScrapeJobRow): ScrapeJob = {
    ScrapeJob(
      ScrapeJobId(row.id),
      ReferenceId(row.referenceId),
      ScrapeJobStatus
        .fromDatabase(row.status)
        .fold(message => throw IllegalStateException(message), identity),
      row.attemptCount,
      row.availableAt,
      row.claimedAt,
      row.finishedAt,
      row.lastError,
      row.createdAt,
      row.updatedAt
    )
  }
}

private final class SkunkRecipeRepository(session: Session[IO]) extends RecipeRepository[IO] {
  import RepositoryMapping.*

  private val columns =
    sql"id, title, description, primary_photo_id, created_at, updated_at, last_made_at"

  override def create(recipe: Recipe): IO[Unit] =
    session
      .execute(sql"""
      insert into recipes ($columns) values ($recipeCodec)
    """.command)(recipe)
      .void

  override def find(id: RecipeId): IO[Option[Recipe]] =
    session
      .option(sql"""
      select $columns
      from recipes
      where id = $recipeIdCodec
    """.query(recipeCodec))(id)

  override def findForUpdate(id: RecipeId): IO[Option[Recipe]] =
    session
      .option(sql"""
      select $columns
      from recipes
      where id = $recipeIdCodec
      for update
    """.query(recipeCodec))(id)

  override def updateDetails(
      id: RecipeId,
      title: String,
      description: String,
      updatedAt: Instant
  ): IO[Option[Recipe]] =
    session
      .option(sql"""
      update recipes
      set title = $text, description = $text, updated_at = $instant
      where id = $recipeIdCodec
      returning $columns
    """.query(recipeCodec))((title, description, updatedAt, id))

  override def setPrimaryPhoto(
      id: RecipeId,
      photoId: PhotoId,
      updatedAt: Instant
  ): IO[Option[Recipe]] =
    session
      .option(sql"""
      update recipes
      set primary_photo_id = $photoIdCodec, updated_at = $instant
      where id = $recipeIdCodec
      returning $columns
    """.query(recipeCodec))((photoId, updatedAt, id))

  override def delete(id: RecipeId): IO[Boolean] =
    session
      .execute(sql"delete from recipes where id = $recipeIdCodec".command)(id)
      .map(affectedRows)
      .map(_ > 0)

  override def list: IO[List[Recipe]] =
    session
      .execute(sql"""
      select $columns
      from recipes
      order by coalesce(last_made_at, updated_at) desc, id
    """.query(recipeCodec))

  override def refreshLastMadeAt(
      id: RecipeId,
      updatedAt: Instant
  ): IO[Boolean] =
    session
      .execute(sql"""
      update recipes
      set last_made_at = (
            select max(cooked_at)
            from meals
            where recipe_id = $recipeIdCodec
          ),
          updated_at = $instant
      where id = $recipeIdCodec
    """.command)((id, updatedAt, id))
      .map(affectedRows)
      .map(_ > 0)
}

private final class SkunkMealRepository(session: Session[IO]) extends MealRepository[IO] {
  import RepositoryMapping.*

  private val columns = sql"id, recipe_id, notes, cooked_at, created_at, updated_at"

  override def create(meal: Meal): IO[Unit] =
    session
      .execute(sql"""
      insert into meals ($columns) values ($mealCodec)
    """.command)(meal)
      .void

  override def find(id: MealId): IO[Option[Meal]] =
    session
      .option(sql"""
      select $columns
      from meals
      where id = $mealIdCodec
    """.query(mealCodec))(id)

  override def update(meal: Meal): IO[Boolean] =
    session
      .execute(sql"""
      update meals
      set recipe_id = $recipeIdCodec,
          notes = $text,
          cooked_at = $instant,
          updated_at = $instant
      where id = $mealIdCodec
    """.command)(
        (
          meal.recipeId,
          meal.notes,
          meal.cookedAt,
          meal.updatedAt,
          meal.id
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def delete(id: MealId): IO[Boolean] =
    session
      .execute(sql"delete from meals where id = $mealIdCodec".command)(id)
      .map(affectedRows)
      .map(_ > 0)

  override def listByRecipe(recipeId: RecipeId): IO[List[Meal]] =
    session
      .execute(sql"""
      select $columns
      from meals
      where recipe_id = $recipeIdCodec
      order by cooked_at desc, id
    """.query(mealCodec))(recipeId)
}

private final class SkunkPhotoRepository(session: Session[IO]) extends PhotoRepository[IO] {
  import RepositoryMapping.*

  override def create(photo: Photo): IO[Unit] =
    session
      .execute(sql"""
      insert into photos (
        id, meal_id, storage_key, original_filename, content_type, byte_size,
        width, height, comment, created_at, updated_at
      ) values (
        $uuid,
        $uuid,
        $text,
        $text,
        $text,
        $int8,
        ${int4.opt},
        ${int4.opt},
        ${text.opt},
        $instant,
        $instant
      )
    """.command)(
        (
          PhotoId.value(photo.id),
          MealId.value(photo.mealId),
          StorageKey.value(photo.storageKey),
          photo.originalFilename,
          photo.contentType,
          photo.byteSize,
          photo.width,
          photo.height,
          photo.comment,
          photo.createdAt,
          photo.updatedAt
        )
      )
      .void

  override def find(id: PhotoId): IO[Option[Photo]] =
    session
      .option(sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      where id = $uuid
    """.query(photoRowDecoder))(PhotoId.value(id))
      .flatMap(_.traverse(photo).liftTo[IO])

  override def update(photo: Photo): IO[Boolean] =
    session
      .execute(sql"""
      update photos
      set meal_id = $uuid,
          storage_key = $text,
          original_filename = $text,
          content_type = $text,
          byte_size = $int8,
          width = ${int4.opt},
          height = ${int4.opt},
          comment = ${text.opt},
          updated_at = $instant
      where id = $uuid
    """.command)(
        (
          MealId.value(photo.mealId),
          StorageKey.value(photo.storageKey),
          photo.originalFilename,
          photo.contentType,
          photo.byteSize,
          photo.width,
          photo.height,
          photo.comment,
          photo.updatedAt,
          PhotoId.value(photo.id)
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def delete(id: PhotoId): IO[Boolean] =
    session
      .execute(sql"delete from photos where id = $uuid".command)(PhotoId.value(id))
      .map(affectedRows)
      .map(_ > 0)

  override def listByMeal(mealId: MealId): IO[List[Photo]] =
    session
      .execute(sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      where meal_id = $uuid
      order by created_at, id
    """.query(photoRowDecoder))(MealId.value(mealId))
      .flatMap(_.traverse(photo).liftTo[IO])

  override def listByRecipe(recipeId: RecipeId): IO[List[Photo]] =
    session
      .execute(sql"""
      select p.id, p.meal_id, p.storage_key, p.original_filename, p.content_type,
             p.byte_size, p.width, p.height, p.comment, p.created_at, p.updated_at
      from photos p
      join meals m on m.id = p.meal_id
      where m.recipe_id = $uuid
      order by m.cooked_at desc, p.created_at desc, p.id
    """.query(photoRowDecoder))(RecipeId.value(recipeId))
      .flatMap(_.traverse(photo).liftTo[IO])

  override def listAll: IO[List[Photo]] =
    session
      .execute(sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      order by id
    """.query(photoRowDecoder))
      .flatMap(_.traverse(photo).liftTo[IO])

  override def listStorageKeys: IO[List[StorageKey]] =
    session
      .execute(sql"select storage_key from photos".query(text))
      .flatMap(_.traverse(storageKey).liftTo[IO])

  override def findPrimaryForRecipe(
      recipeId: RecipeId
  ): IO[Option[Photo]] =
    session
      .option(sql"""
      select p.id, p.meal_id, p.storage_key, p.original_filename, p.content_type,
             p.byte_size, p.width, p.height, p.comment, p.created_at, p.updated_at
      from recipes r
      join meals m on m.recipe_id = r.id
      join photos p on p.meal_id = m.id
      where r.id = $uuid
      order by (p.id = r.primary_photo_id) desc,
               m.cooked_at desc,
               p.created_at desc,
               p.id
      limit 1
    """.query(photoRowDecoder))(RecipeId.value(recipeId))
      .flatMap(_.traverse(photo).liftTo[IO])
}

private final class SkunkRecipeReferenceRepository(session: Session[IO])
    extends RecipeReferenceRepository[IO] {
  import RepositoryMapping.*

  override def create(reference: RecipeReference): IO[Unit] =
    session
      .execute(sql"""
      insert into recipe_references (
        id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      ) values (
        $uuid,
        $uuid,
        $text,
        ${text.opt},
        ${text.opt},
        ${text.opt},
        $instant,
        $instant
      )
    """.command)(
        (
          ReferenceId.value(reference.id),
          RecipeId.value(reference.recipeId),
          reference.kind.databaseValue,
          reference.url,
          reference.citation,
          reference.displayName,
          reference.createdAt,
          reference.updatedAt
        )
      )
      .void

  override def find(id: ReferenceId): IO[Option[RecipeReference]] =
    session
      .option(sql"""
      select id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      from recipe_references
      where id = $uuid
    """.query(referenceRowDecoder))(ReferenceId.value(id))
      .map(_.map(reference))

  override def update(reference: RecipeReference): IO[Boolean] =
    session
      .execute(sql"""
      update recipe_references
      set recipe_id = $uuid,
          kind = $text,
          url = ${text.opt},
          citation = ${text.opt},
          display_name = ${text.opt},
          updated_at = $instant
      where id = $uuid
    """.command)(
        (
          RecipeId.value(reference.recipeId),
          reference.kind.databaseValue,
          reference.url,
          reference.citation,
          reference.displayName,
          reference.updatedAt,
          ReferenceId.value(reference.id)
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def delete(id: ReferenceId): IO[Boolean] =
    session
      .execute(sql"""
      delete from recipe_references
      where id = $uuid
    """.command)(ReferenceId.value(id))
      .map(affectedRows)
      .map(_ > 0)

  override def listByRecipe(
      recipeId: RecipeId
  ): IO[List[RecipeReference]] =
    session
      .execute(sql"""
      select id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      from recipe_references
      where recipe_id = $uuid
      order by created_at, id
    """.query(referenceRowDecoder))(RecipeId.value(recipeId))
      .map(_.map(reference))
}

private final class SkunkScrapedDocumentRepository(session: Session[IO])
    extends ScrapedDocumentRepository[IO] {
  import RepositoryMapping.*

  override def create(document: ScrapedDocument): IO[Unit] =
    session
      .execute(sql"""
      insert into scraped_documents (
        id, reference_id, source_url, resolved_url, title, content_text,
        content_hash, http_etag, http_last_modified, scraped_at, updated_at
      ) values (
        $uuid,
        $uuid,
        $text,
        ${text.opt},
        ${text.opt},
        $text,
        $text,
        ${text.opt},
        ${text.opt},
        $instant,
        $instant
      )
    """.command)(
        (
          ScrapedDocumentId.value(document.id),
          ReferenceId.value(document.referenceId),
          document.sourceUrl,
          document.resolvedUrl,
          document.title,
          document.contentText,
          document.contentHash,
          document.httpEtag,
          document.httpLastModified,
          document.scrapedAt,
          document.updatedAt
        )
      )
      .void

  override def find(
      id: ScrapedDocumentId
  ): IO[Option[ScrapedDocument]] =
    session
      .option(sql"$select where id = $uuid".query(scrapedDocumentRowDecoder))(
        ScrapedDocumentId.value(id)
      )
      .map(_.map(scrapedDocument))

  override def findByReference(
      referenceId: ReferenceId
  ): IO[Option[ScrapedDocument]] =
    session
      .option(sql"$select where reference_id = $uuid".query(scrapedDocumentRowDecoder))(
        ReferenceId.value(referenceId)
      )
      .map(_.map(scrapedDocument))

  override def update(document: ScrapedDocument): IO[Boolean] =
    session
      .execute(sql"""
      update scraped_documents
      set reference_id = $uuid,
          source_url = $text,
          resolved_url = ${text.opt},
          title = ${text.opt},
          content_text = $text,
          content_hash = $text,
          http_etag = ${text.opt},
          http_last_modified = ${text.opt},
          scraped_at = $instant,
          updated_at = $instant
      where id = $uuid
    """.command)(
        (
          ReferenceId.value(document.referenceId),
          document.sourceUrl,
          document.resolvedUrl,
          document.title,
          document.contentText,
          document.contentHash,
          document.httpEtag,
          document.httpLastModified,
          document.scrapedAt,
          document.updatedAt,
          ScrapedDocumentId.value(document.id)
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def delete(id: ScrapedDocumentId): IO[Boolean] =
    session
      .execute(sql"""
      delete from scraped_documents
      where id = $uuid
    """.command)(ScrapedDocumentId.value(id))
      .map(affectedRows)
      .map(_ > 0)

  private val select = sql"""
      select id, reference_id, source_url, resolved_url, title, content_text,
             content_hash, http_etag, http_last_modified, scraped_at, updated_at
      from scraped_documents
    """
}

private final class SkunkScrapeJobRepository(session: Session[IO]) extends ScrapeJobRepository[IO] {
  import RepositoryMapping.*

  override def create(job: ScrapeJob): IO[Unit] =
    session
      .execute(sql"""
      insert into scrape_jobs (
        id, reference_id, status, attempt_count, available_at, claimed_at,
        finished_at, last_error, created_at, updated_at
      ) values (
        $uuid,
        $uuid,
        $text,
        $int4,
        $instant,
        ${instant.opt},
        ${instant.opt},
        ${text.opt},
        $instant,
        $instant
      )
    """.command)(
        (
          ScrapeJobId.value(job.id),
          ReferenceId.value(job.referenceId),
          job.status.databaseValue,
          job.attemptCount,
          job.availableAt,
          job.claimedAt,
          job.finishedAt,
          job.lastError,
          job.createdAt,
          job.updatedAt
        )
      )
      .void

  override def find(id: ScrapeJobId): IO[Option[ScrapeJob]] =
    session
      .option(sql"$select where id = $uuid".query(scrapeJobRowDecoder))(ScrapeJobId.value(id))
      .map(_.map(scrapeJob))

  override def findLatestByReference(
      referenceId: ReferenceId
  ): IO[Option[ScrapeJob]] =
    session
      .option(sql"""$select
        where reference_id = $uuid
        order by created_at desc, id desc
        limit 1
      """.query(scrapeJobRowDecoder))(ReferenceId.value(referenceId))
      .map(_.map(scrapeJob))

  override def claimNext(availableAt: Instant): IO[Option[ScrapeJob]] =
    session
      .option(sql"""
      with candidate as (
        select pending.id
        from scrape_jobs pending
        where pending.status = 'pending'
          and pending.available_at <= $instant
          and not exists (
            select 1
            from scrape_jobs active
            where active.reference_id = pending.reference_id
              and active.status = 'running'
          )
        order by pending.available_at, pending.created_at, pending.id
        for update skip locked
        limit 1
      )
      update scrape_jobs job
      set status = 'running',
          attempt_count = job.attempt_count + 1,
          claimed_at = $instant,
          finished_at = null,
          last_error = null,
          updated_at = $instant
      from candidate
      where job.id = candidate.id
      returning job.id, job.reference_id, job.status, job.attempt_count,
                job.available_at, job.claimed_at, job.finished_at,
                job.last_error, job.created_at, job.updated_at
    """.query(scrapeJobRowDecoder))((availableAt, availableAt, availableAt))
      .map(_.map(scrapeJob))

  override def recoverStale(
      claimedBefore: Instant,
      recoveredAt: Instant,
      maximumAttempts: Int
  ): IO[Int] =
    session
      .execute(sql"""
      update scrape_jobs
      set status =
            case when attempt_count >= $int4 then 'failed'
                 else 'pending'
            end,
          available_at = $instant,
          claimed_at =
            case when attempt_count >= $int4 then claimed_at
                 else null
            end,
          finished_at =
            case when attempt_count >= $int4 then $instant
                 else null
            end,
          last_error = 'Recovered after the worker stopped before completing the job',
          updated_at = $instant
      where status = 'running'
        and claimed_at < $instant
    """.command)(
        (
          maximumAttempts,
          recoveredAt,
          maximumAttempts,
          maximumAttempts,
          recoveredAt,
          recoveredAt,
          claimedBefore
        )
      )
      .map(affectedRows)

  override def update(job: ScrapeJob): IO[Boolean] =
    session
      .execute(sql"""
      update scrape_jobs
      set reference_id = $uuid,
          status = $text,
          attempt_count = $int4,
          available_at = $instant,
          claimed_at = ${instant.opt},
          finished_at = ${instant.opt},
          last_error = ${text.opt},
          updated_at = $instant
      where id = $uuid
    """.command)(
        (
          ReferenceId.value(job.referenceId),
          job.status.databaseValue,
          job.attemptCount,
          job.availableAt,
          job.claimedAt,
          job.finishedAt,
          job.lastError,
          job.updatedAt,
          ScrapeJobId.value(job.id)
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def delete(id: ScrapeJobId): IO[Boolean] =
    session
      .execute(sql"delete from scrape_jobs where id = $uuid".command)(ScrapeJobId.value(id))
      .map(affectedRows)
      .map(_ > 0)

  override def listByReference(
      referenceId: ReferenceId
  ): IO[List[ScrapeJob]] =
    session
      .execute(
        sql"$select where reference_id = $uuid order by created_at, id".query(scrapeJobRowDecoder)
      )(ReferenceId.value(referenceId))
      .map(_.map(scrapeJob))

  override def countsByStatus: IO[Map[String, Long]] =
    session
      .execute(sql"""
        select status, count(*)
        from scrape_jobs
        group by status
      """.query(text *: int8))
      .map(_.toMap)

  private val select = sql"""
      select id, reference_id, status, attempt_count, available_at, claimed_at,
             finished_at, last_error, created_at, updated_at
      from scrape_jobs
    """
}

private final class SkunkRecipeSearchDocumentRepository(session: Session[IO])
    extends RecipeSearchDocumentRepository[IO] {
  override def create(document: RecipeSearchDocument): IO[Unit] =
    session
      .execute(sql"""
      insert into recipe_search_documents (
        recipe_id, plain_text, search_vector, updated_at
      ) values (
        $uuid,
        $text,
        to_tsvector('english', $text),
        $instant
      )
    """.command)(
        (
          RecipeId.value(document.recipeId),
          document.plainText,
          document.plainText,
          document.updatedAt
        )
      )
      .void

  override def find(
      recipeId: RecipeId
  ): IO[Option[RecipeSearchDocument]] =
    session
      .option(sql"""
      select recipe_id, plain_text, search_vector::text, updated_at
      from recipe_search_documents
      where recipe_id = $uuid
    """.query(uuid *: text *: text *: instant))(RecipeId.value(recipeId))
      .map(
        _.map { case (id, plainText, searchVector, updatedAt) =>
          RecipeSearchDocument(RecipeId(id), plainText, searchVector, updatedAt)
        }
      )

  override def update(document: RecipeSearchDocument): IO[Boolean] =
    session
      .execute(sql"""
      update recipe_search_documents
      set plain_text = $text,
          search_vector = to_tsvector('english', $text),
          updated_at = $instant
      where recipe_id = $uuid
    """.command)(
        (
          document.plainText,
          document.plainText,
          document.updatedAt,
          RecipeId.value(document.recipeId)
        )
      )
      .map(affectedRows)
      .map(_ > 0)

  override def rebuildSearchDocument(
      recipeId: RecipeId,
      updatedAt: Instant
  ): IO[Unit] =
    session
      .execute(sql"""
      insert into recipe_search_documents (
        recipe_id, plain_text, search_vector, updated_at
      )
      select
        recipe.id,
        concat_ws(
          E'\n',
          recipe.title,
          nullif(recipe.description, ''),
          keyword_values.keyword_text,
          reference_values.reference_text,
          meals.meal_text,
          scraped.scraped_text
        ),
        setweight(to_tsvector('english', recipe.title), 'A') ||
          setweight(to_tsvector('english', coalesce(keyword_values.keyword_text, '')), 'B') ||
          setweight(to_tsvector('english', concat_ws(E'\n', nullif(recipe.description, ''), reference_values.reference_text)), 'C') ||
          setweight(to_tsvector('english', concat_ws(E'\n', meals.meal_text, scraped.scraped_text)), 'D'),
        $instant
      from recipes recipe
      left join lateral (
        select string_agg(keyword.keyword, E'\n' order by lower(keyword.keyword), keyword.id)
          as keyword_text
        from recipe_keywords keyword
        where keyword.recipe_id = recipe.id
      ) keyword_values on true
      left join lateral (
        select string_agg(
          concat_ws(' ', reference.display_name, reference.url, reference.citation),
          E'\n'
          order by reference.created_at, reference.id
        ) as reference_text
        from recipe_references reference
        where reference.recipe_id = recipe.id
      ) reference_values on true
      left join lateral (
        select string_agg(meal.notes, E'\n' order by meal.cooked_at, meal.id)
          as meal_text
        from meals meal
        where meal.recipe_id = recipe.id
      ) meals on true
      left join lateral (
        select string_agg(
          concat_ws(' ', document.title, document.content_text),
          E'\n'
          order by document.scraped_at, document.id
        ) as scraped_text
        from recipe_references reference
        join scraped_documents document on document.reference_id = reference.id
        where reference.recipe_id = recipe.id
      ) scraped on true
      where recipe.id = $uuid
      on conflict (recipe_id) do update
      set plain_text = excluded.plain_text,
          search_vector = excluded.search_vector,
          updated_at = excluded.updated_at
    """.command)((updatedAt, RecipeId.value(recipeId)))
      .void

  override def search(query: String): IO[List[RecipeSearchResult]] =
    session
      .execute(sql"""
      with search_query as (
        select websearch_to_tsquery('english', $text) as value
      )
      select recipe.id, recipe.title, recipe.description, recipe.primary_photo_id,
             recipe.created_at, recipe.updated_at, recipe.last_made_at,
             (
               ts_rank_cd(document.search_vector, search_query.value, 32) * 10.0 +
               greatest(
                 similarity(recipe.title, $text),
                 similarity(document.plain_text, $text) * 0.35
               )
             )::float8 as rank
      from recipe_search_documents document
      join recipes recipe on recipe.id = document.recipe_id
      cross join search_query
      where document.search_vector @@ search_query.value
         or similarity(recipe.title, $text) >= 0.18
      order by rank desc, recipe.updated_at desc, recipe.id
    """.query((RepositoryMapping.recipeCodec ~ float8).map(RecipeSearchResult.apply.tupled)))(
        (query, query, query, query)
      )

  override def delete(recipeId: RecipeId): IO[Boolean] =
    session
      .execute(sql"""
      delete from recipe_search_documents
      where recipe_id = $uuid
    """.command)(RecipeId.value(recipeId))
      .map(affectedRows)
      .map(_ > 0)
}

private final class SkunkRecipeKeywordRepository(session: Session[IO])
    extends RecipeKeywordRepository[IO] {
  override def listByRecipe(recipeId: RecipeId): IO[List[RecipeKeyword]] =
    session
      .execute(sql"""
      select id, recipe_id, keyword
      from recipe_keywords
      where recipe_id = $uuid
      order by lower(keyword), id
    """.query(uuid *: uuid *: text))(RecipeId.value(recipeId))
      .map(_.map { case (id, storedRecipeId, keyword) =>
        RecipeKeyword(id, RecipeId(storedRecipeId), keyword)
      })

  override def replace(recipeId: RecipeId, keywords: List[String]): IO[Unit] =
    session
      .execute(sql"delete from recipe_keywords where recipe_id = $uuid".command)(
        RecipeId.value(recipeId)
      )
      .void *>
      keywords.traverse_ { keyword =>
        session
          .execute(sql"""
          insert into recipe_keywords (id, recipe_id, keyword)
          values ($uuid, $uuid, $text)
        """.command)((UUID.randomUUID(), RecipeId.value(recipeId), keyword))
          .void
      }
}
