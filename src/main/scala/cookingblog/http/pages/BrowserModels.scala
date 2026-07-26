package cookingblog.http.pages

import cookingblog.domain.{Meal, Photo, Recipe, RecipeReference, ScrapeJob, ScrapedDocument}

final case class BrowserRecipe(
    recipe: Recipe,
    meals: List[Meal],
    photos: List[Photo],
    keywords: List[String],
    references: List[BrowserReference]
)
final case class BrowserReference(
    reference: RecipeReference,
    job: Option[ScrapeJob],
    document: Option[ScrapedDocument]
)
