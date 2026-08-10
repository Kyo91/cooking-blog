package cookingblog

import cookingblog.storage.{
  PhotoMigrationDiagnostic,
  PhotoMigrationDirection,
  PhotoMigrationSummary
}
import io.circe.parser.parse
import munit.FunSuite

final class PhotoMigrationCommandSuite extends FunSuite {
  test("defaults migration direction to local-to-S3") {
    val parsed = PhotoMigrationCommand.parseArguments(List("--dry-run"))
    assertEquals(
      parsed.map(_.direction),
      Right(PhotoMigrationDirection.LocalToS3)
    )
  }

  test("parses explicit S3-to-local direction in both supported forms") {
    assertEquals(
      PhotoMigrationCommand.parseArguments(List("--direction", "s3-to-local")).map(_.direction),
      Right(PhotoMigrationDirection.S3ToLocal)
    )
    assertEquals(
      PhotoMigrationCommand
        .parseArguments(List("--direction=local-to-s3"))
        .map(_.direction),
      Right(PhotoMigrationDirection.LocalToS3)
    )
  }

  test("rejects an ambiguous direction value") {
    val parsed = PhotoMigrationCommand.parseArguments(List("--direction", "reverse"))
    assert(parsed.isLeft)
  }

  test("parses explicit repair authorization") {
    val parsed = PhotoMigrationCommand.parseArguments(List("--repair", "--resume"))
    assertEquals(parsed.map(options => (options.repair, options.resume)), Right((true, true)))
  }

  test("renders actionable diagnostics in JSON and human output without exception content") {
    val summary = PhotoMigrationSummary(
      failed = 1,
      diagnostics = List(
        PhotoMigrationDiagnostic(
          "0123456789abcdef0123456789abcdef",
          "display",
          "write-destination",
          "permission-denied"
        )
      )
    )
    val json = PhotoMigrationCommand.renderOutput(summary, json = true)
    val human = PhotoMigrationCommand.renderOutput(summary, json = false)
    val parsed = parse(json).toOption.get
    val diagnostic = parsed.hcursor.downField("diagnostics").downArray

    assertEquals(diagnostic.get[String]("storageKey"), Right("0123456789abcdef0123456789abcdef"))
    assertEquals(diagnostic.get[String]("variant"), Right("display"))
    assertEquals(diagnostic.get[String]("operation"), Right("write-destination"))
    assertEquals(diagnostic.get[String]("category"), Right("permission-denied"))
    assert(human.contains("storage-key=0123456789abcdef0123456789abcdef"))
    assert(human.contains("variant=display operation=write-destination"))
    assert(human.contains("category=permission-denied"))
    assert(!json.contains("exception"))
    assert(!human.contains("exception"))
  }
}
