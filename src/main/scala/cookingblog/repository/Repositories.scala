package cookingblog.repository

import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.storage.StorageKey
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** Table-oriented persistence boundary for recipe rows and their cached last-made timestamp. */
trait RecipeRepository[F[_]] {
  def create(recipe: Recipe): F[Unit]
  def find(id: RecipeId): F[Option[Recipe]]
  def update(recipe: Recipe): F[Boolean]
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

object DoobieRepositories {
  val recipes: RecipeRepository[ConnectionIO] = DoobieRecipeRepository
  val meals: MealRepository[ConnectionIO] = DoobieMealRepository
  val photos: PhotoRepository[ConnectionIO] = DoobiePhotoRepository
  val references: RecipeReferenceRepository[ConnectionIO] =
    DoobieRecipeReferenceRepository
  val scrapedDocuments: ScrapedDocumentRepository[ConnectionIO] =
    DoobieScrapedDocumentRepository
  val scrapeJobs: ScrapeJobRepository[ConnectionIO] = DoobieScrapeJobRepository
  val searchDocuments: RecipeSearchDocumentRepository[ConnectionIO] =
    DoobieRecipeSearchDocumentRepository
  val keywords: RecipeKeywordRepository[ConnectionIO] = DoobieRecipeKeywordRepository
}

private object RepositoryMapping {
  final case class RecipeRow(
      id: UUID,
      title: String,
      description: String,
      primaryPhotoId: Option[UUID],
      createdAt: Instant,
      updatedAt: Instant,
      lastMadeAt: Option[Instant]
  )

  final case class MealRow(
      id: UUID,
      recipeId: UUID,
      notes: String,
      cookedAt: Instant,
      createdAt: Instant,
      updatedAt: Instant
  )

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

  def recipe(row: RecipeRow): Recipe =
    Recipe(
      RecipeId(row.id),
      row.title,
      row.description,
      row.primaryPhotoId.map(PhotoId(_)),
      row.createdAt,
      row.updatedAt,
      row.lastMadeAt
    )

  def meal(row: MealRow): Meal =
    Meal(
      MealId(row.id),
      RecipeId(row.recipeId),
      row.notes,
      row.cookedAt,
      row.createdAt,
      row.updatedAt
    )

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

private object DoobieRecipeRepository extends RecipeRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(recipe: Recipe): ConnectionIO[Unit] =
    sql"""
      insert into recipes (
        id, title, description, primary_photo_id, created_at, updated_at, last_made_at
      ) values (
        ${RecipeId.value(recipe.id)},
        ${recipe.title},
        ${recipe.description},
        ${recipe.primaryPhotoId.map(PhotoId.value)},
        ${recipe.createdAt},
        ${recipe.updatedAt},
        ${recipe.lastMadeAt}
      )
    """.update.run.void

  override def find(id: RecipeId): ConnectionIO[Option[Recipe]] =
    sql"""
      select id, title, description, primary_photo_id, created_at, updated_at, last_made_at
      from recipes
      where id = ${RecipeId.value(id)}
    """.query[RecipeRow].option.map(_.map(recipe))

  override def update(recipe: Recipe): ConnectionIO[Boolean] =
    sql"""
      update recipes
      set title = ${recipe.title},
          description = ${recipe.description},
          primary_photo_id = ${recipe.primaryPhotoId.map(PhotoId.value)},
          updated_at = ${recipe.updatedAt},
          last_made_at = ${recipe.lastMadeAt}
      where id = ${RecipeId.value(recipe.id)}
    """.update.run.map(_ > 0)

  override def delete(id: RecipeId): ConnectionIO[Boolean] =
    sql"delete from recipes where id = ${RecipeId.value(id)}".update.run.map(_ > 0)

  override def list: ConnectionIO[List[Recipe]] =
    sql"""
      select id, title, description, primary_photo_id, created_at, updated_at, last_made_at
      from recipes
      order by coalesce(last_made_at, updated_at) desc, id
    """.query[RecipeRow].to[List].map(_.map(recipe))

  override def refreshLastMadeAt(
      id: RecipeId,
      updatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""
      update recipes
      set last_made_at = (
            select max(cooked_at)
            from meals
            where recipe_id = ${RecipeId.value(id)}
          ),
          updated_at = $updatedAt
      where id = ${RecipeId.value(id)}
    """.update.run.map(_ > 0)
}

private object DoobieMealRepository extends MealRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(meal: Meal): ConnectionIO[Unit] =
    sql"""
      insert into meals (id, recipe_id, notes, cooked_at, created_at, updated_at)
      values (
        ${MealId.value(meal.id)},
        ${RecipeId.value(meal.recipeId)},
        ${meal.notes},
        ${meal.cookedAt},
        ${meal.createdAt},
        ${meal.updatedAt}
      )
    """.update.run.void

  override def find(id: MealId): ConnectionIO[Option[Meal]] =
    sql"""
      select id, recipe_id, notes, cooked_at, created_at, updated_at
      from meals
      where id = ${MealId.value(id)}
    """.query[MealRow].option.map(_.map(meal))

  override def update(meal: Meal): ConnectionIO[Boolean] =
    sql"""
      update meals
      set recipe_id = ${RecipeId.value(meal.recipeId)},
          notes = ${meal.notes},
          cooked_at = ${meal.cookedAt},
          updated_at = ${meal.updatedAt}
      where id = ${MealId.value(meal.id)}
    """.update.run.map(_ > 0)

  override def delete(id: MealId): ConnectionIO[Boolean] =
    sql"delete from meals where id = ${MealId.value(id)}".update.run.map(_ > 0)

  override def listByRecipe(recipeId: RecipeId): ConnectionIO[List[Meal]] =
    sql"""
      select id, recipe_id, notes, cooked_at, created_at, updated_at
      from meals
      where recipe_id = ${RecipeId.value(recipeId)}
      order by cooked_at desc, id
    """.query[MealRow].to[List].map(_.map(meal))
}

private object DoobiePhotoRepository extends PhotoRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(photo: Photo): ConnectionIO[Unit] =
    sql"""
      insert into photos (
        id, meal_id, storage_key, original_filename, content_type, byte_size,
        width, height, comment, created_at, updated_at
      ) values (
        ${PhotoId.value(photo.id)},
        ${MealId.value(photo.mealId)},
        ${StorageKey.value(photo.storageKey)},
        ${photo.originalFilename},
        ${photo.contentType},
        ${photo.byteSize},
        ${photo.width},
        ${photo.height},
        ${photo.comment},
        ${photo.createdAt},
        ${photo.updatedAt}
      )
    """.update.run.void

  override def find(id: PhotoId): ConnectionIO[Option[Photo]] =
    sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      where id = ${PhotoId.value(id)}
    """.query[PhotoRow].option.flatMap(_.traverse(photo).liftTo[ConnectionIO])

  override def update(photo: Photo): ConnectionIO[Boolean] =
    sql"""
      update photos
      set meal_id = ${MealId.value(photo.mealId)},
          storage_key = ${StorageKey.value(photo.storageKey)},
          original_filename = ${photo.originalFilename},
          content_type = ${photo.contentType},
          byte_size = ${photo.byteSize},
          width = ${photo.width},
          height = ${photo.height},
          comment = ${photo.comment},
          updated_at = ${photo.updatedAt}
      where id = ${PhotoId.value(photo.id)}
    """.update.run.map(_ > 0)

  override def delete(id: PhotoId): ConnectionIO[Boolean] =
    sql"delete from photos where id = ${PhotoId.value(id)}".update.run.map(_ > 0)

  override def listByMeal(mealId: MealId): ConnectionIO[List[Photo]] =
    sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      where meal_id = ${MealId.value(mealId)}
      order by created_at, id
    """.query[PhotoRow].to[List].flatMap(_.traverse(photo).liftTo[ConnectionIO])

  override def listByRecipe(recipeId: RecipeId): ConnectionIO[List[Photo]] =
    sql"""
      select p.id, p.meal_id, p.storage_key, p.original_filename, p.content_type,
             p.byte_size, p.width, p.height, p.comment, p.created_at, p.updated_at
      from photos p
      join meals m on m.id = p.meal_id
      where m.recipe_id = ${RecipeId.value(recipeId)}
      order by m.cooked_at desc, p.created_at desc, p.id
    """.query[PhotoRow].to[List].flatMap(_.traverse(photo).liftTo[ConnectionIO])

  override def listAll: ConnectionIO[List[Photo]] =
    sql"""
      select id, meal_id, storage_key, original_filename, content_type, byte_size,
             width, height, comment, created_at, updated_at
      from photos
      order by id
    """.query[PhotoRow].to[List].flatMap(_.traverse(photo).liftTo[ConnectionIO])

  override def listStorageKeys: ConnectionIO[List[StorageKey]] =
    sql"select storage_key from photos"
      .query[String]
      .to[List]
      .flatMap(_.traverse(storageKey).liftTo[ConnectionIO])

  override def findPrimaryForRecipe(
      recipeId: RecipeId
  ): ConnectionIO[Option[Photo]] =
    sql"""
      select p.id, p.meal_id, p.storage_key, p.original_filename, p.content_type,
             p.byte_size, p.width, p.height, p.comment, p.created_at, p.updated_at
      from recipes r
      join meals m on m.recipe_id = r.id
      join photos p on p.meal_id = m.id
      where r.id = ${RecipeId.value(recipeId)}
      order by (p.id = r.primary_photo_id) desc,
               m.cooked_at desc,
               p.created_at desc,
               p.id
      limit 1
    """.query[PhotoRow].option.flatMap(_.traverse(photo).liftTo[ConnectionIO])
}

private object DoobieRecipeReferenceRepository extends RecipeReferenceRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(reference: RecipeReference): ConnectionIO[Unit] =
    sql"""
      insert into recipe_references (
        id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      ) values (
        ${ReferenceId.value(reference.id)},
        ${RecipeId.value(reference.recipeId)},
        ${reference.kind.databaseValue},
        ${reference.url},
        ${reference.citation},
        ${reference.displayName},
        ${reference.createdAt},
        ${reference.updatedAt}
      )
    """.update.run.void

  override def find(id: ReferenceId): ConnectionIO[Option[RecipeReference]] =
    sql"""
      select id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      from recipe_references
      where id = ${ReferenceId.value(id)}
    """.query[ReferenceRow].option.map(_.map(reference))

  override def update(reference: RecipeReference): ConnectionIO[Boolean] =
    sql"""
      update recipe_references
      set recipe_id = ${RecipeId.value(reference.recipeId)},
          kind = ${reference.kind.databaseValue},
          url = ${reference.url},
          citation = ${reference.citation},
          display_name = ${reference.displayName},
          updated_at = ${reference.updatedAt}
      where id = ${ReferenceId.value(reference.id)}
    """.update.run.map(_ > 0)

  override def delete(id: ReferenceId): ConnectionIO[Boolean] =
    sql"""
      delete from recipe_references
      where id = ${ReferenceId.value(id)}
    """.update.run.map(_ > 0)

  override def listByRecipe(
      recipeId: RecipeId
  ): ConnectionIO[List[RecipeReference]] =
    sql"""
      select id, recipe_id, kind, url, citation, display_name, created_at, updated_at
      from recipe_references
      where recipe_id = ${RecipeId.value(recipeId)}
      order by created_at, id
    """.query[ReferenceRow].to[List].map(_.map(reference))
}

private object DoobieScrapedDocumentRepository extends ScrapedDocumentRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(document: ScrapedDocument): ConnectionIO[Unit] =
    sql"""
      insert into scraped_documents (
        id, reference_id, source_url, resolved_url, title, content_text,
        content_hash, http_etag, http_last_modified, scraped_at, updated_at
      ) values (
        ${ScrapedDocumentId.value(document.id)},
        ${ReferenceId.value(document.referenceId)},
        ${document.sourceUrl},
        ${document.resolvedUrl},
        ${document.title},
        ${document.contentText},
        ${document.contentHash},
        ${document.httpEtag},
        ${document.httpLastModified},
        ${document.scrapedAt},
        ${document.updatedAt}
      )
    """.update.run.void

  override def find(
      id: ScrapedDocumentId
  ): ConnectionIO[Option[ScrapedDocument]] =
    select(fr"where id = ${ScrapedDocumentId.value(id)}").option

  override def findByReference(
      referenceId: ReferenceId
  ): ConnectionIO[Option[ScrapedDocument]] =
    select(fr"where reference_id = ${ReferenceId.value(referenceId)}").option

  override def update(document: ScrapedDocument): ConnectionIO[Boolean] =
    sql"""
      update scraped_documents
      set reference_id = ${ReferenceId.value(document.referenceId)},
          source_url = ${document.sourceUrl},
          resolved_url = ${document.resolvedUrl},
          title = ${document.title},
          content_text = ${document.contentText},
          content_hash = ${document.contentHash},
          http_etag = ${document.httpEtag},
          http_last_modified = ${document.httpLastModified},
          scraped_at = ${document.scrapedAt},
          updated_at = ${document.updatedAt}
      where id = ${ScrapedDocumentId.value(document.id)}
    """.update.run.map(_ > 0)

  override def delete(id: ScrapedDocumentId): ConnectionIO[Boolean] =
    sql"""
      delete from scraped_documents
      where id = ${ScrapedDocumentId.value(id)}
    """.update.run.map(_ > 0)

  private def select(where: Fragment): Query0[ScrapedDocument] =
    (fr"""
      select id, reference_id, source_url, resolved_url, title, content_text,
             content_hash, http_etag, http_last_modified, scraped_at, updated_at
      from scraped_documents
    """ ++ where).query[ScrapedDocumentRow].map(scrapedDocument)
}

private object DoobieScrapeJobRepository extends ScrapeJobRepository[ConnectionIO] {
  import RepositoryMapping.*

  override def create(job: ScrapeJob): ConnectionIO[Unit] =
    sql"""
      insert into scrape_jobs (
        id, reference_id, status, attempt_count, available_at, claimed_at,
        finished_at, last_error, created_at, updated_at
      ) values (
        ${ScrapeJobId.value(job.id)},
        ${ReferenceId.value(job.referenceId)},
        ${job.status.databaseValue},
        ${job.attemptCount},
        ${job.availableAt},
        ${job.claimedAt},
        ${job.finishedAt},
        ${job.lastError},
        ${job.createdAt},
        ${job.updatedAt}
      )
    """.update.run.void

  override def find(id: ScrapeJobId): ConnectionIO[Option[ScrapeJob]] =
    select(fr"where id = ${ScrapeJobId.value(id)}").option

  override def findLatestByReference(
      referenceId: ReferenceId
  ): ConnectionIO[Option[ScrapeJob]] =
    select(
      fr"""
        where reference_id = ${ReferenceId.value(referenceId)}
        order by created_at desc, id desc
        limit 1
      """
    ).option

  override def claimNext(availableAt: Instant): ConnectionIO[Option[ScrapeJob]] =
    sql"""
      with candidate as (
        select pending.id
        from scrape_jobs pending
        where pending.status = 'pending'
          and pending.available_at <= $availableAt
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
          claimed_at = $availableAt,
          finished_at = null,
          last_error = null,
          updated_at = $availableAt
      from candidate
      where job.id = candidate.id
      returning job.id, job.reference_id, job.status, job.attempt_count,
                job.available_at, job.claimed_at, job.finished_at,
                job.last_error, job.created_at, job.updated_at
    """.query[ScrapeJobRow].map(scrapeJob).option

  override def recoverStale(
      claimedBefore: Instant,
      recoveredAt: Instant,
      maximumAttempts: Int
  ): ConnectionIO[Int] =
    sql"""
      update scrape_jobs
      set status =
            case when attempt_count >= $maximumAttempts then 'failed'
                 else 'pending'
            end,
          available_at = $recoveredAt,
          claimed_at =
            case when attempt_count >= $maximumAttempts then claimed_at
                 else null
            end,
          finished_at =
            case when attempt_count >= $maximumAttempts then $recoveredAt
                 else null
            end,
          last_error = 'Recovered after the worker stopped before completing the job',
          updated_at = $recoveredAt
      where status = 'running'
        and claimed_at < $claimedBefore
    """.update.run

  override def update(job: ScrapeJob): ConnectionIO[Boolean] =
    sql"""
      update scrape_jobs
      set reference_id = ${ReferenceId.value(job.referenceId)},
          status = ${job.status.databaseValue},
          attempt_count = ${job.attemptCount},
          available_at = ${job.availableAt},
          claimed_at = ${job.claimedAt},
          finished_at = ${job.finishedAt},
          last_error = ${job.lastError},
          updated_at = ${job.updatedAt}
      where id = ${ScrapeJobId.value(job.id)}
    """.update.run.map(_ > 0)

  override def delete(id: ScrapeJobId): ConnectionIO[Boolean] =
    sql"delete from scrape_jobs where id = ${ScrapeJobId.value(id)}".update.run
      .map(_ > 0)

  override def listByReference(
      referenceId: ReferenceId
  ): ConnectionIO[List[ScrapeJob]] =
    select(
      fr"where reference_id = ${ReferenceId.value(referenceId)} order by created_at, id"
    ).to[List]

  private def select(where: Fragment): Query0[ScrapeJob] =
    (fr"""
      select id, reference_id, status, attempt_count, available_at, claimed_at,
             finished_at, last_error, created_at, updated_at
      from scrape_jobs
    """ ++ where).query[ScrapeJobRow].map(scrapeJob)
}

private object DoobieRecipeSearchDocumentRepository
    extends RecipeSearchDocumentRepository[ConnectionIO] {
  override def create(document: RecipeSearchDocument): ConnectionIO[Unit] =
    sql"""
      insert into recipe_search_documents (
        recipe_id, plain_text, search_vector, updated_at
      ) values (
        ${RecipeId.value(document.recipeId)},
        ${document.plainText},
        to_tsvector('english', ${document.plainText}),
        ${document.updatedAt}
      )
    """.update.run.void

  override def find(
      recipeId: RecipeId
  ): ConnectionIO[Option[RecipeSearchDocument]] =
    sql"""
      select recipe_id, plain_text, search_vector::text, updated_at
      from recipe_search_documents
      where recipe_id = ${RecipeId.value(recipeId)}
    """
      .query[(UUID, String, String, Instant)]
      .option
      .map(
        _.map { case (id, plainText, searchVector, updatedAt) =>
          RecipeSearchDocument(RecipeId(id), plainText, searchVector, updatedAt)
        }
      )

  override def update(document: RecipeSearchDocument): ConnectionIO[Boolean] =
    sql"""
      update recipe_search_documents
      set plain_text = ${document.plainText},
          search_vector = to_tsvector('english', ${document.plainText}),
          updated_at = ${document.updatedAt}
      where recipe_id = ${RecipeId.value(document.recipeId)}
    """.update.run.map(_ > 0)

  override def rebuildSearchDocument(
      recipeId: RecipeId,
      updatedAt: Instant
  ): ConnectionIO[Unit] =
    sql"""
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
        $updatedAt
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
      where recipe.id = ${RecipeId.value(recipeId)}
      on conflict (recipe_id) do update
      set plain_text = excluded.plain_text,
          search_vector = excluded.search_vector,
          updated_at = excluded.updated_at
    """.update.run.void

  override def search(query: String): ConnectionIO[List[RecipeSearchResult]] =
    sql"""
      with search_query as (
        select websearch_to_tsquery('english', $query) as value
      )
      select recipe.id, recipe.title, recipe.description, recipe.primary_photo_id,
             recipe.created_at, recipe.updated_at, recipe.last_made_at,
             (
               ts_rank_cd(document.search_vector, search_query.value, 32) * 10.0 +
               greatest(
                 similarity(recipe.title, $query),
                 similarity(document.plain_text, $query) * 0.35
               )
             ) as rank
      from recipe_search_documents document
      join recipes recipe on recipe.id = document.recipe_id
      cross join search_query
      where document.search_vector @@ search_query.value
         or similarity(recipe.title, $query) >= 0.18
      order by rank desc, recipe.updated_at desc, recipe.id
    """
      .query[(RepositoryMapping.RecipeRow, Double)]
      .to[List]
      .map(_.map { case (recipe, rank) =>
        RecipeSearchResult(RepositoryMapping.recipe(recipe), rank)
      })

  override def delete(recipeId: RecipeId): ConnectionIO[Boolean] =
    sql"""
      delete from recipe_search_documents
      where recipe_id = ${RecipeId.value(recipeId)}
    """.update.run.map(_ > 0)
}

private object DoobieRecipeKeywordRepository extends RecipeKeywordRepository[ConnectionIO] {
  override def listByRecipe(recipeId: RecipeId): ConnectionIO[List[RecipeKeyword]] =
    sql"""
      select id, recipe_id, keyword
      from recipe_keywords
      where recipe_id = ${RecipeId.value(recipeId)}
      order by lower(keyword), id
    """
      .query[(UUID, UUID, String)]
      .to[List]
      .map(_.map { case (id, storedRecipeId, keyword) =>
        RecipeKeyword(id, RecipeId(storedRecipeId), keyword)
      })

  override def replace(recipeId: RecipeId, keywords: List[String]): ConnectionIO[Unit] =
    sql"delete from recipe_keywords where recipe_id = ${RecipeId.value(recipeId)}".update.run.void *>
      keywords.traverse_ { keyword =>
        sql"""
          insert into recipe_keywords (id, recipe_id, keyword)
          values (${UUID.randomUUID()}, ${RecipeId.value(recipeId)}, $keyword)
        """.update.run.void
      }
}
