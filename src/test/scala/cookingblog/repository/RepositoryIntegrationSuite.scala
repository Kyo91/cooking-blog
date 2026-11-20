package cookingblog.repository

import cats.effect.*
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.service.PersistenceService
import cookingblog.storage.StorageKey
import cookingblog.testing.PostgresIntegrationSuite

import cookingblog.database.Database.instant
import skunk.codec.all.{text, uuid}
import skunk.implicits.*

import java.time.Instant
import java.util.UUID

final class RepositoryIntegrationSuite extends PostgresIntegrationSuite {

  private val baseTime = Instant.parse("2026-07-25T12:00:00Z")

  test("migration creates every Phase 2 table") {
    database.use { pool =>
      val expected = Set(
        "recipes",
        "meals",
        "photos",
        "recipe_references",
        "scraped_documents",
        "scrape_jobs",
        "recipe_search_documents",
        "recipe_keywords"
      )

      pool.session
        .use(_.execute(sql"""
        select table_name::text
        from information_schema.tables
        where table_schema = 'public'
          and table_name in (
            'recipes',
            'meals',
            'photos',
            'recipe_references',
            'scraped_documents',
            'scrape_jobs',
            'recipe_search_documents',
            'recipe_keywords'
          )
      """.query(text)))
        .map(_.toSet)
        .map { actual =>
          assertEquals(actual, expected)
        }
    }
  }

  test("photo repository rejects invalid storage keys from database rows") {
    database.use { pool =>
      val recipeId = RecipeId.random
      val mealId = MealId.random
      val photoId = PhotoId.random
      val timestamp = Instant.now()
      val invalidStorageKey = s"invalid-${PhotoId.value(photoId)}"
      pool.session
        .use { session =>
          session.transaction.use { _ =>
            val repos = new SkunkRepositories(session)
            for {
              _ <- repos.recipes.create(
                Recipe(
                  recipeId,
                  s"Invalid photo key ${PhotoId.value(photoId)}",
                  "",
                  None,
                  timestamp,
                  timestamp,
                  None
                )
              )
              _ <- repos.meals.create(
                Meal(mealId, recipeId, "", timestamp, timestamp, timestamp)
              )
              _ <- session.execute(sql"""
            insert into photos (
              id, meal_id, storage_key, original_filename, content_type, byte_size,
              width, height, comment, created_at, updated_at
            ) values (
              $uuid, $uuid, $text,
              'photo.png', 'image/png', 1, null, null, null, $instant, $instant
            )
          """.command)(
                (
                  PhotoId.value(photoId),
                  MealId.value(mealId),
                  invalidStorageKey,
                  timestamp,
                  timestamp
                )
              )
              result <- repos.photos.find(photoId).attempt
            } yield result
          }
        }
        .map { result =>
          assert(result.left.exists(_.getMessage.startsWith("Invalid photos.storage_key:")))
        }
    }
  }

  test("DAO interpreters support CRUD for every Phase 2 table") {
    database.use { pool =>
      val recipeId = RecipeId.random
      val mealId = MealId.random
      val photoId = PhotoId.random
      val referenceId = ReferenceId.random
      val documentId = ScrapedDocumentId.random
      val jobId = ScrapeJobId.random
      val titleSuffix = UUID.randomUUID().toString
      val recipe =
        Recipe(
          recipeId,
          s"DAO recipe $titleSuffix",
          "initial",
          None,
          baseTime,
          baseTime,
          None
        )
      val meal =
        Meal(mealId, recipeId, "initial", baseTime, baseTime, baseTime)
      val photo =
        Photo(
          photoId,
          mealId,
          StorageKey.random,
          "dinner.webp",
          "image/webp",
          128,
          Some(16),
          Some(12),
          None,
          baseTime,
          baseTime
        )
      val reference =
        RecipeReference(
          referenceId,
          recipeId,
          ReferenceKind.Url,
          Some(s"https://example.com/$titleSuffix"),
          None,
          Some("Example"),
          baseTime,
          baseTime
        )
      val document =
        ScrapedDocument(
          documentId,
          referenceId,
          s"https://example.com/$titleSuffix",
          None,
          Some("Dinner"),
          "Cook until done.",
          "abc123",
          None,
          None,
          baseTime,
          baseTime
        )
      val job =
        ScrapeJob(
          jobId,
          referenceId,
          ScrapeJobStatus.Pending,
          0,
          baseTime,
          None,
          None,
          None,
          baseTime,
          baseTime
        )
      val search =
        RecipeSearchDocument(recipeId, "initial dinner", "", baseTime)
      val updatedAt = baseTime.plusSeconds(60)

      pool.transaction { repos =>
        for {
          _ <- repos.recipes.create(recipe)
          readRecipe <- repos.recipes.find(recipeId)
          updatedRecipe <- repos.recipes.updateDetails(
            recipe.id,
            recipe.title,
            "updated",
            updatedAt
          )
          recipeUpdated = updatedRecipe.nonEmpty
          listedRecipes <- repos.recipes.list
          _ <- repos.meals.create(meal)
          readMeal <- repos.meals.find(mealId)
          mealUpdated <- repos.meals.update(
            meal.copy(notes = "updated", updatedAt = updatedAt)
          )
          listedMeals <- repos.meals.listByRecipe(recipeId)
          _ <- repos.photos.create(photo)
          readPhoto <- repos.photos.find(photoId)
          photoUpdated <- repos.photos.update(
            photo.copy(comment = Some("updated"), updatedAt = updatedAt)
          )
          listedPhotos <- repos.photos.listByMeal(mealId)
          _ <- repos.references.create(reference)
          readReference <- repos.references.find(referenceId)
          referenceUpdated <- repos.references.update(
            reference.copy(displayName = Some("Updated"), updatedAt = updatedAt)
          )
          listedReferences <-
            repos.references.listByRecipe(recipeId)
          _ <- repos.scrapedDocuments.create(document)
          readDocument <-
            repos.scrapedDocuments.find(documentId)
          readDocumentByReference <-
            repos.scrapedDocuments.findByReference(referenceId)
          documentUpdated <- repos.scrapedDocuments.update(
            document.copy(contentText = "Updated.", updatedAt = updatedAt)
          )
          _ <- repos.scrapeJobs.create(job)
          readJob <- repos.scrapeJobs.find(jobId)
          jobUpdated <- repos.scrapeJobs.update(
            job.copy(
              status = ScrapeJobStatus.Running,
              attemptCount = 1,
              claimedAt = Some(updatedAt),
              updatedAt = updatedAt
            )
          )
          listedJobs <- repos.scrapeJobs.listByReference(referenceId)
          _ <- repos.searchDocuments.create(search)
          readSearch <- repos.searchDocuments.find(recipeId)
          searchUpdated <- repos.searchDocuments.update(
            search.copy(plainText = "updated dinner", updatedAt = updatedAt)
          )
          deletedSearch <-
            repos.searchDocuments.delete(recipeId)
          deletedJob <- repos.scrapeJobs.delete(jobId)
          deletedDocument <-
            repos.scrapedDocuments.delete(documentId)
          deletedReference <-
            repos.references.delete(referenceId)
          deletedPhoto <- repos.photos.delete(photoId)
          deletedMeal <- repos.meals.delete(mealId)
          deletedRecipe <- repos.recipes.delete(recipeId)
          searchAfterDelete <-
            repos.searchDocuments.find(recipeId)
          jobAfterDelete <- repos.scrapeJobs.find(jobId)
          documentAfterDelete <-
            repos.scrapedDocuments.find(documentId)
          referenceAfterDelete <-
            repos.references.find(referenceId)
          photoAfterDelete <- repos.photos.find(photoId)
          mealAfterDelete <- repos.meals.find(mealId)
          recipeAfterDelete <- repos.recipes.find(recipeId)
        } yield {
          assertEquals(readRecipe, Some(recipe))
          assert(recipeUpdated)
          assert(listedRecipes.exists(_.id == recipeId))
          assertEquals(readMeal, Some(meal))
          assert(mealUpdated)
          assertEquals(listedMeals.map(_.id), List(mealId))
          assertEquals(readPhoto, Some(photo))
          assert(photoUpdated)
          assertEquals(listedPhotos.map(_.id), List(photoId))
          assertEquals(readReference, Some(reference))
          assert(referenceUpdated)
          assertEquals(listedReferences.map(_.id), List(referenceId))
          assertEquals(readDocument, Some(document))
          assertEquals(readDocumentByReference, Some(document))
          assert(documentUpdated)
          assertEquals(readJob, Some(job))
          assert(jobUpdated)
          assertEquals(listedJobs.map(_.id), List(jobId))
          assert(readSearch.exists(_.plainText == search.plainText))
          assert(
            readSearch.exists(_.searchVector.contains("'dinner'"))
          )
          assert(
            List(
              searchUpdated,
              deletedSearch,
              deletedJob,
              deletedDocument,
              deletedReference,
              deletedPhoto,
              deletedMeal,
              deletedRecipe
            ).forall(identity)
          )
          assertEquals(
            List(
              searchAfterDelete,
              jobAfterDelete,
              documentAfterDelete,
              referenceAfterDelete,
              photoAfterDelete,
              mealAfterDelete,
              recipeAfterDelete
            ),
            List.fill(7)(None)
          )
        }

      }
    }
  }

  test("service transactions maintain invariants and roll back atomically") {
    database.use { pool =>
      val service = PersistenceService(pool)
      val recipeId = RecipeId.random
      val firstMealId = MealId.random
      val secondMealId = MealId.random
      val referenceId = ReferenceId.random
      val titleSuffix = UUID.randomUUID().toString
      val recipe =
        Recipe(
          recipeId,
          s"Service recipe $titleSuffix",
          "",
          None,
          baseTime,
          baseTime,
          None
        )
      val search = RecipeSearchDocument(recipeId, recipe.title, "", baseTime)
      val firstCookedAt = baseTime.minusSeconds(3600)
      val secondCookedAt = baseTime.plusSeconds(3600)
      val firstMeal =
        Meal(
          firstMealId,
          recipeId,
          "first",
          firstCookedAt,
          baseTime,
          baseTime
        )
      val secondMeal =
        Meal(
          secondMealId,
          recipeId,
          "second",
          secondCookedAt,
          baseTime,
          baseTime
        )
      val reference =
        RecipeReference(
          referenceId,
          recipeId,
          ReferenceKind.Url,
          Some(s"https://example.com/rollback/$titleSuffix"),
          None,
          None,
          baseTime,
          baseTime
        )
      val invalidJob =
        ScrapeJob(
          ScrapeJobId.random,
          ReferenceId.random,
          ScrapeJobStatus.Pending,
          0,
          baseTime,
          None,
          None,
          None,
          baseTime,
          baseTime
        )

      val exercise =
        for {
          _ <- service.createRecipe(recipe, search)
          _ <- service.createMeal(firstMeal)
          afterFirst <-
            pool.use(_.recipes.find(recipeId))
          _ <- service.createMeal(secondMeal)
          afterSecond <-
            pool.use(_.recipes.find(recipeId))
          _ <- service.deleteMeal(secondMealId, baseTime.plusSeconds(7200))
          afterDelete <-
            pool.use(_.recipes.find(recipeId))
          rollbackResult <-
            service.createReferenceAndJob(reference, invalidJob).attempt
          rolledBackReference <-
            pool.use(_.references.find(referenceId))
        } yield {
          assertEquals(afterFirst.flatMap(_.lastMadeAt), Some(firstCookedAt))
          assertEquals(afterSecond.flatMap(_.lastMadeAt), Some(secondCookedAt))
          assertEquals(afterDelete.flatMap(_.lastMadeAt), Some(firstCookedAt))
          assert(rollbackResult.isLeft)
          assertEquals(rolledBackReference, None)
        }

      exercise.guarantee(
        pool.use(_.recipes.delete(recipeId)).void
      )
    }
  }

  test("database constraints enforce core domain invariants") {
    database.use { pool =>
      val firstId = RecipeId.random
      val secondId = RecipeId.random
      val mealId = MealId.random
      val suffix = UUID.randomUUID().toString
      val first =
        Recipe(
          firstId,
          s"Unique $suffix",
          "",
          None,
          baseTime,
          baseTime,
          None
        )
      val duplicate = first.copy(
        id = secondId,
        title = s"  UNIQUE $suffix  "
      )
      val meal = Meal(mealId, firstId, "", baseTime, baseTime, baseTime)
      val invalidPhoto =
        Photo(
          PhotoId.random,
          mealId,
          StorageKey.random,
          "oversized.jpg",
          "image/jpeg",
          10000001,
          None,
          None,
          None,
          baseTime,
          baseTime
        )
      val invalidReferenceId = ReferenceId.random

      val exercise =
        for {
          _ <- pool.use(_.recipes.create(first))
          _ <- pool.use(_.meals.create(meal))
          duplicateResult <-
            pool.use(_.recipes.create(duplicate)).attempt
          invalidPhotoResult <-
            pool.use(_.photos.create(invalidPhoto)).attempt
          invalidReferenceResult <-
            pool.session
              .use(
                _.execute(sql"""
              insert into recipe_references (
                id, recipe_id, kind, url, citation, created_at, updated_at
              ) values (
                $uuid,
                $uuid,
                'book',
                'https://example.com/invalid',
                null,
                $instant,
                $instant
              )
            """.command)(
                  (
                    ReferenceId.value(invalidReferenceId),
                    RecipeId.value(firstId),
                    baseTime,
                    baseTime
                  )
                )
              )
              .attempt
        } yield {
          assert(duplicateResult.isLeft)
          assert(invalidPhotoResult.isLeft)
          assert(invalidReferenceResult.isLeft)
        }

      exercise.guarantee(
        pool.transaction(repos => List(firstId, secondId).traverse_(repos.recipes.delete))
      )
    }
  }

}
