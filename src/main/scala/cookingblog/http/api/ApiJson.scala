package cookingblog.http.api

import cookingblog.domain.*
import cookingblog.service.*
import io.circe.*
import io.circe.generic.semiauto.*

import java.time.Instant
import scala.util.Try

object ApiJson {
  given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Instant] =
    Decoder.decodeString.emap(value =>
      Try(Instant.parse(value)).toEither.left
        .map(_ => "must be an ISO-8601 timestamp")
    )

  given Encoder[RecipeId] =
    Encoder.encodeString.contramap(id => RecipeId.value(id).toString)
  given Encoder[MealId] =
    Encoder.encodeString.contramap(id => MealId.value(id).toString)
  given Encoder[ReferenceId] =
    Encoder.encodeString.contramap(id => ReferenceId.value(id).toString)
  given Encoder[PhotoId] =
    Encoder.encodeString.contramap(id => PhotoId.value(id).toString)
  given Encoder[ScrapeJobId] =
    Encoder.encodeString.contramap(id => ScrapeJobId.value(id).toString)

  given Encoder[ReferenceKind] =
    Encoder.encodeString.contramap(_.databaseValue)
  given Encoder[ScrapeJobStatus] =
    Encoder.encodeString.contramap(_.databaseValue)

  given Encoder[Recipe] = deriveEncoder
  given Encoder[Meal] = deriveEncoder
  given Encoder[RecipeReference] = {
    val base: Encoder.AsObject[RecipeReference] = deriveEncoder
    Encoder.AsObject.instance(reference =>
      base
        .encodeObject(reference)
        .add(
          "importStatus",
          reference.kind match {
            case ReferenceKind.Url  => Json.fromString("pending")
            case ReferenceKind.Book => Json.Null
          }
        )
    )
  }
  given Encoder[ScrapeJob] = deriveEncoder
  given Encoder[RecipePage] = deriveEncoder

  given Decoder[CreateRecipeInput] = deriveDecoder
  given Decoder[UpdateRecipeInput] = deriveDecoder
  given Decoder[CreateMealInput] = deriveDecoder
  given Decoder[UpdateMealInput] = deriveDecoder
  given Decoder[CreateReferenceInput] = deriveDecoder
  given Decoder[UpdateReferenceInput] = deriveDecoder
}
