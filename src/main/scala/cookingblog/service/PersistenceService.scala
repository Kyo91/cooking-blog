package cookingblog.service

import cookingblog.database.Database
import cats.effect.IO
import cats.syntax.all.*
import cookingblog.domain.*

import java.time.Instant

/** Lower-level transactional use cases used by persistence and scraper integration tests.
  *
  * Production HTTP writes generally enter through [[RecipeApiService]].
  */
final class PersistenceService(
    pool: Database
) {
  def createRecipe(
      recipe: Recipe,
      searchDocument: RecipeSearchDocument
  ): IO[Unit] =
    pool.transaction(repos =>
      repos.recipes.create(recipe) *> repos.searchDocuments.create(searchDocument)
    )

  def createMeal(meal: Meal): IO[Boolean] =
    pool.transaction(repos =>
      repos.meals.create(meal) *>
        repos.recipes.refreshLastMadeAt(meal.recipeId, meal.updatedAt)
    )

  /** Updates a meal and refreshes every affected recipe when a meal is ever moved. */
  def updateMeal(meal: Meal): IO[Boolean] = {
    pool.transaction { repos =>
      import repos.{recipes, meals}
      meals.find(meal.id).flatMap {
        case None           => false.pure[IO]
        case Some(previous) =>
          meals.update(meal).flatMap {
            case false => false.pure[IO]
            case true  =>
              val affectedRecipeIds =
                List(previous.recipeId, meal.recipeId).distinct
              affectedRecipeIds
                .traverse(recipes.refreshLastMadeAt(_, meal.updatedAt))
                .map(_.forall(identity))
          }
      }
    }
  }

  /** Deletes a meal only after locating its recipe so the cached last-made value can be recomputed.
    */
  def deleteMeal(id: MealId, updatedAt: Instant): IO[Boolean] = {
    pool.transaction { repos =>
      import repos.{recipes, meals}
      meals.find(id).flatMap {
        case None       => false.pure[IO]
        case Some(meal) =>
          meals.delete(id).flatMap {
            case false => false.pure[IO]
            case true  => recipes.refreshLastMadeAt(meal.recipeId, updatedAt)
          }
      }
    }
  }

  def createReferenceAndJob(
      reference: RecipeReference,
      job: ScrapeJob
  ): IO[Unit] =
    pool.transaction(repos => repos.references.create(reference) *> repos.scrapeJobs.create(job))

  /** Upserts imported text and its search document together, preventing stale search content. */
  def saveScrapedDocumentAndSearch(
      document: ScrapedDocument,
      searchDocument: RecipeSearchDocument
  ): IO[Unit] = {
    pool.transaction { repos =>
      import repos.{scrapedDocuments, searchDocuments}
      scrapedDocuments.findByReference(document.referenceId).flatMap {
        case None           => scrapedDocuments.create(document)
        case Some(existing) =>
          scrapedDocuments.update(document.copy(id = existing.id)).void
      } *> searchDocuments.find(searchDocument.recipeId).flatMap {
        case None    => searchDocuments.create(searchDocument)
        case Some(_) => searchDocuments.update(searchDocument).void
      }
    }
  }
}

object PersistenceService {
  def apply(pool: Database): PersistenceService =
    new PersistenceService(
      pool
    )
}
