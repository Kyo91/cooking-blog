package cookingblog.service

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.domain.*
import cookingblog.repository.*
import doobie.*
import doobie.implicits.*

import java.time.Instant

/** Lower-level transactional use cases used by persistence and scraper integration tests.
  *
  * Production HTTP writes generally enter through [[RecipeApiService]].
  */
final class PersistenceService(
    transactor: Transactor[IO],
    recipes: RecipeRepository[ConnectionIO],
    meals: MealRepository[ConnectionIO],
    references: RecipeReferenceRepository[ConnectionIO],
    scrapedDocuments: ScrapedDocumentRepository[ConnectionIO],
    scrapeJobs: ScrapeJobRepository[ConnectionIO],
    searchDocuments: RecipeSearchDocumentRepository[ConnectionIO]
) {
  def createRecipe(
      recipe: Recipe,
      searchDocument: RecipeSearchDocument
  ): IO[Unit] =
    (recipes.create(recipe) *> searchDocuments.create(searchDocument))
      .transact(transactor)

  def createMeal(meal: Meal): IO[Boolean] =
    (meals.create(meal) *>
      recipes.refreshLastMadeAt(meal.recipeId, meal.updatedAt))
      .transact(transactor)

  /** Updates a meal and refreshes every affected recipe when a meal is ever moved. */
  def updateMeal(meal: Meal): IO[Boolean] = {
    val program =
      meals.find(meal.id).flatMap {
        case None           => false.pure[ConnectionIO]
        case Some(previous) =>
          meals.update(meal).flatMap {
            case false => false.pure[ConnectionIO]
            case true  =>
              val affectedRecipeIds =
                List(previous.recipeId, meal.recipeId).distinct
              affectedRecipeIds
                .traverse(recipes.refreshLastMadeAt(_, meal.updatedAt))
                .map(_.forall(identity))
          }
      }

    program.transact(transactor)
  }

  /** Deletes a meal only after locating its recipe so the cached last-made value can be recomputed.
    */
  def deleteMeal(id: MealId, updatedAt: Instant): IO[Boolean] = {
    val program =
      meals.find(id).flatMap {
        case None       => false.pure[ConnectionIO]
        case Some(meal) =>
          meals.delete(id).flatMap {
            case false => false.pure[ConnectionIO]
            case true  => recipes.refreshLastMadeAt(meal.recipeId, updatedAt)
          }
      }

    program.transact(transactor)
  }

  def createReferenceAndJob(
      reference: RecipeReference,
      job: ScrapeJob
  ): IO[Unit] =
    (references.create(reference) *> scrapeJobs.create(job)).transact(transactor)

  /** Upserts imported text and its search document together, preventing stale search content. */
  def saveScrapedDocumentAndSearch(
      document: ScrapedDocument,
      searchDocument: RecipeSearchDocument
  ): IO[Unit] = {
    val program =
      scrapedDocuments.findByReference(document.referenceId).flatMap {
        case None           => scrapedDocuments.create(document)
        case Some(existing) =>
          scrapedDocuments.update(document.copy(id = existing.id)).void
      } *> searchDocuments.find(searchDocument.recipeId).flatMap {
        case None    => searchDocuments.create(searchDocument)
        case Some(_) => searchDocuments.update(searchDocument).void
      }

    program.transact(transactor)
  }
}

object PersistenceService {
  def apply(transactor: Transactor[IO]): PersistenceService =
    new PersistenceService(
      transactor,
      DoobieRepositories.recipes,
      DoobieRepositories.meals,
      DoobieRepositories.references,
      DoobieRepositories.scrapedDocuments,
      DoobieRepositories.scrapeJobs,
      DoobieRepositories.searchDocuments
    )
}
