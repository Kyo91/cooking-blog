package cookingblog.domain

import munit.FunSuite

import java.util.UUID

final class DomainSuite extends FunSuite {
  test("domain IDs parse valid UUIDs and reject invalid values") {
    val uuid = UUID.randomUUID()

    assertEquals(RecipeId.parse(uuid.toString), Right(RecipeId(uuid)))
    assert(RecipeId.parse("not-a-uuid").isLeft)
  }

  test("database enums reject unknown values") {
    assertEquals(ReferenceKind.fromDatabase("url"), Right(ReferenceKind.Url))
    assertEquals(
      ScrapeJobStatus.fromDatabase("running"),
      Right(ScrapeJobStatus.Running)
    )
    assert(ReferenceKind.fromDatabase("magazine").isLeft)
    assert(ScrapeJobStatus.fromDatabase("cancelled").isLeft)
  }
}
