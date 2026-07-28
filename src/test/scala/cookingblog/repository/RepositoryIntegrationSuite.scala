package cookingblog.repository

import cats.effect.*
import cats.syntax.all.*
import ciris.Secret
import cookingblog.config.DatabaseConfig
import cookingblog.database.Database
import cookingblog.domain.*
import cookingblog.service.PersistenceService
import cookingblog.storage.StorageKey
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite

import java.time.Instant
import java.util.UUID

final class RepositoryIntegrationSuite extends CatsEffectSuite {
  private val databaseConfig =
    DatabaseConfig(
      sys.env.getOrElse(
        "DATABASE_URL",
        "jdbc:postgresql://localhost:5432/cooking_blog"
      ),
      sys.env.getOrElse("DATABASE_USER", "cooking_blog"),
      Secret(sys.env.getOrElse("DATABASE_PASSWORD", "cooking_blog_dev")),
      poolSize = 2
    )

  private val baseTime = Instant.parse("2026-07-25T12:00:00Z")

  test("migration creates every Phase 2 table") {
    database.use { transactor =>
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

      sql"""
        select table_name
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
      """.query[String].to[Set].transact(transactor).map { actual =>
        assertEquals(actual, expected)
      }
    }
  }

  test("photo repository rejects invalid storage keys from database rows") {
    database.use { transactor =>
      val recipeId = RecipeId.random
      val mealId = MealId.random
      val photoId = PhotoId.random
      val timestamp = Instant.now()
      val invalidStorageKey = s"invalid-${PhotoId.value(photoId)}"
      val program =
        for {
          _ <- DoobieRepositories.recipes.create(
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
          _ <- DoobieRepositories.meals.create(
            Meal(mealId, recipeId, "", timestamp, timestamp, timestamp)
          )
          _ <- sql"""
            insert into photos (
              id, meal_id, storage_key, original_filename, content_type, byte_size,
              width, height, comment, created_at, updated_at
            ) values (
              ${PhotoId.value(photoId)}, ${MealId.value(mealId)}, $invalidStorageKey,
              'photo.png', 'image/png', 1, null, null, null, $timestamp, $timestamp
            )
          """.update.run
          result <- DoobieRepositories.photos.find(photoId).attempt
        } yield result
      program.transact(transactor).map { result =>
        assert(result.left.exists(_.getMessage.startsWith("Invalid photos.storage_key:")))
      }
    }
  }

  test("DAO interpreters support CRUD for every Phase 2 table") {
    database.use { transactor =>
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

      val program =
        for {
          _ <- DoobieRepositories.recipes.create(recipe)
          readRecipe <- DoobieRepositories.recipes.find(recipeId)
          recipeUpdated <- DoobieRepositories.recipes.update(
            recipe.copy(description = "updated", updatedAt = updatedAt)
          )
          listedRecipes <- DoobieRepositories.recipes.list
          _ <- DoobieRepositories.meals.create(meal)
          readMeal <- DoobieRepositories.meals.find(mealId)
          mealUpdated <- DoobieRepositories.meals.update(
            meal.copy(notes = "updated", updatedAt = updatedAt)
          )
          listedMeals <- DoobieRepositories.meals.listByRecipe(recipeId)
          _ <- DoobieRepositories.photos.create(photo)
          readPhoto <- DoobieRepositories.photos.find(photoId)
          photoUpdated <- DoobieRepositories.photos.update(
            photo.copy(comment = Some("updated"), updatedAt = updatedAt)
          )
          listedPhotos <- DoobieRepositories.photos.listByMeal(mealId)
          _ <- DoobieRepositories.references.create(reference)
          readReference <- DoobieRepositories.references.find(referenceId)
          referenceUpdated <- DoobieRepositories.references.update(
            reference.copy(displayName = Some("Updated"), updatedAt = updatedAt)
          )
          listedReferences <-
            DoobieRepositories.references.listByRecipe(recipeId)
          _ <- DoobieRepositories.scrapedDocuments.create(document)
          readDocument <-
            DoobieRepositories.scrapedDocuments.find(documentId)
          readDocumentByReference <-
            DoobieRepositories.scrapedDocuments.findByReference(referenceId)
          documentUpdated <- DoobieRepositories.scrapedDocuments.update(
            document.copy(contentText = "Updated.", updatedAt = updatedAt)
          )
          _ <- DoobieRepositories.scrapeJobs.create(job)
          readJob <- DoobieRepositories.scrapeJobs.find(jobId)
          jobUpdated <- DoobieRepositories.scrapeJobs.update(
            job.copy(
              status = ScrapeJobStatus.Running,
              attemptCount = 1,
              claimedAt = Some(updatedAt),
              updatedAt = updatedAt
            )
          )
          listedJobs <- DoobieRepositories.scrapeJobs.listByReference(referenceId)
          _ <- DoobieRepositories.searchDocuments.create(search)
          readSearch <- DoobieRepositories.searchDocuments.find(recipeId)
          searchUpdated <- DoobieRepositories.searchDocuments.update(
            search.copy(plainText = "updated dinner", updatedAt = updatedAt)
          )
          deletedSearch <-
            DoobieRepositories.searchDocuments.delete(recipeId)
          deletedJob <- DoobieRepositories.scrapeJobs.delete(jobId)
          deletedDocument <-
            DoobieRepositories.scrapedDocuments.delete(documentId)
          deletedReference <-
            DoobieRepositories.references.delete(referenceId)
          deletedPhoto <- DoobieRepositories.photos.delete(photoId)
          deletedMeal <- DoobieRepositories.meals.delete(mealId)
          deletedRecipe <- DoobieRepositories.recipes.delete(recipeId)
          searchAfterDelete <-
            DoobieRepositories.searchDocuments.find(recipeId)
          jobAfterDelete <- DoobieRepositories.scrapeJobs.find(jobId)
          documentAfterDelete <-
            DoobieRepositories.scrapedDocuments.find(documentId)
          referenceAfterDelete <-
            DoobieRepositories.references.find(referenceId)
          photoAfterDelete <- DoobieRepositories.photos.find(photoId)
          mealAfterDelete <- DoobieRepositories.meals.find(mealId)
          recipeAfterDelete <- DoobieRepositories.recipes.find(recipeId)
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

      program.transact(transactor)
    }
  }

  test("service transactions maintain invariants and roll back atomically") {
    database.use { transactor =>
      val service = PersistenceService(transactor)
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
            DoobieRepositories.recipes.find(recipeId).transact(transactor)
          _ <- service.createMeal(secondMeal)
          afterSecond <-
            DoobieRepositories.recipes.find(recipeId).transact(transactor)
          _ <- service.deleteMeal(secondMealId, baseTime.plusSeconds(7200))
          afterDelete <-
            DoobieRepositories.recipes.find(recipeId).transact(transactor)
          rollbackResult <-
            service.createReferenceAndJob(reference, invalidJob).attempt
          rolledBackReference <-
            DoobieRepositories.references.find(referenceId).transact(transactor)
        } yield {
          assertEquals(afterFirst.flatMap(_.lastMadeAt), Some(firstCookedAt))
          assertEquals(afterSecond.flatMap(_.lastMadeAt), Some(secondCookedAt))
          assertEquals(afterDelete.flatMap(_.lastMadeAt), Some(firstCookedAt))
          assert(rollbackResult.isLeft)
          assertEquals(rolledBackReference, None)
        }

      exercise.guarantee(
        DoobieRepositories.recipes.delete(recipeId).transact(transactor).void
      )
    }
  }

  test("database constraints enforce core domain invariants") {
    database.use { transactor =>
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
          _ <- DoobieRepositories.recipes.create(first).transact(transactor)
          _ <- DoobieRepositories.meals.create(meal).transact(transactor)
          duplicateResult <-
            DoobieRepositories.recipes.create(duplicate).transact(transactor).attempt
          invalidPhotoResult <-
            DoobieRepositories.photos
              .create(invalidPhoto)
              .transact(transactor)
              .attempt
          invalidReferenceResult <-
            sql"""
              insert into recipe_references (
                id, recipe_id, kind, url, citation, created_at, updated_at
              ) values (
                ${ReferenceId.value(invalidReferenceId)},
                ${RecipeId.value(firstId)},
                'book',
                'https://example.com/invalid',
                null,
                $baseTime,
                $baseTime
              )
            """.update.run.transact(transactor).attempt
        } yield {
          assert(duplicateResult.isLeft)
          assert(invalidPhotoResult.isLeft)
          assert(invalidReferenceResult.isLeft)
        }

      exercise.guarantee(
        List(firstId, secondId)
          .traverse_(DoobieRepositories.recipes.delete(_))
          .transact(transactor)
      )
    }
  }

  private val database: Resource[IO, Transactor[IO]] =
    Resource.eval(Database.migrate(databaseConfig)) *>
      Database.transactor(databaseConfig)
}
