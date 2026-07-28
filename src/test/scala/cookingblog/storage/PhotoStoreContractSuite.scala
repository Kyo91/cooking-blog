package cookingblog.storage

import cats.effect.{IO, Resource}
import ciris.Secret
import cookingblog.config.{S3CredentialsMode, S3PhotoConfig}
import munit.CatsEffectSuite
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import java.net.URI
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*

final class PhotoStoreContractSuite extends CatsEffectSuite {
  test("StorageKey rejects values outside the persisted key format") {
    val invalid = StorageKey.parse("not-a-storage-key")
    assertEquals(
      invalid.left.toOption,
      Some(
        "Invalid photo storage key 'not-a-storage-key': expected 32 lower-case hexadecimal characters"
      )
    )
  }

  test("local PhotoStore satisfies immutable variant contract") {
    temporaryDirectory.use(directory =>
      LocalPhotoStore.create(directory).flatMap(store => verifyContract(store, directory))
    )
  }

  test("local PhotoStore ignores unrelated directory entries") {
    temporaryDirectory.use { directory =>
      for {
        store <- LocalPhotoStore.create(directory)
        _ <- IO.blocking(Files.createDirectory(directory.resolve("unrelated")))
        keys <- store.listStorageKeys
      } yield assertEquals(keys, Set.empty[StorageKey])
    }
  }

  test("S3-compatible PhotoStore satisfies immutable variant contract") {
    s3Configuration match {
      case None =>
        IO.println(
          "Skipping S3-compatible PhotoStore contract; set S3_TEST_ENDPOINT to enable it"
        )
      case Some(configuration) =>
        temporaryDirectory.use(directory =>
          S3PhotoStore.create(configuration).use { store =>
            putUnrelatedObject(configuration) *> verifyContract(store, directory)
          }
        )
    }
  }

  private def verifyContract(store: PhotoStore, directory: Path): IO[Unit] = {
    val storageKey = StorageKey.parse("0123456789abcdef0123456789abcdef").toOption.get
    for {
      first <- writeFile(directory, "first.png", Array[Byte](1, 2, 3))
      second <- writeFile(directory, "second.png", Array[Byte](4, 5, 6))
      _ <- store.put(storageKey, PhotoVariant.Original, PhotoExtension.Png, first)
      _ <- store.put(storageKey, PhotoVariant.Display, PhotoExtension.Png, second)
      original <- store
        .read(storageKey, PhotoVariant.Original, PhotoExtension.Png)
        .compile
        .to(Array)
      display <- store.read(storageKey, PhotoVariant.Display, PhotoExtension.Png).compile.to(Array)
      storageKeys <- store.listStorageKeys
      agedStorageKeys <- store.listStorageKeysOlderThan(Instant.now().plusSeconds(5))
      writable <- store.checkWritable
      _ <- store.put(storageKey, PhotoVariant.Original, PhotoExtension.Png, second)
      overwritten <- store
        .read(storageKey, PhotoVariant.Original, PhotoExtension.Png)
        .compile
        .to(Array)
      _ <- store.delete(storageKey)
      afterDelete <- store.listStorageKeys
    } yield {
      assertEquals(original.toList, List[Byte](1, 2, 3))
      assertEquals(display.toList, List[Byte](4, 5, 6))
      assertEquals(storageKeys, Set(storageKey))
      assertEquals(agedStorageKeys, Set(storageKey))
      assert(writable)
      assertEquals(overwritten.toList, List[Byte](4, 5, 6))
      assertEquals(afterDelete, Set.empty[StorageKey])
    }
  }

  private def temporaryDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("photo-store-contract-")))(directory =>
      IO.blocking {
        val walk = Files.walk(directory)
        try {
          walk
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
        } finally {
          walk.close()
        }
      }.handleError(_ => ())
    )

  private def writeFile(directory: Path, name: String, bytes: Array[Byte]): IO[Path] =
    IO.blocking {
      val target = directory.resolve(name)
      Files.write(target, bytes)
      target
    }

  private def putUnrelatedObject(configuration: S3PhotoConfig): IO[Unit] =
    Resource
      .make(
        IO.blocking {
          val builder =
            S3AsyncClient
              .builder()
              .region(Region.of(configuration.region))
              .forcePathStyle(configuration.pathStyleAccess)
              .credentialsProvider(
                StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(
                    configuration.accessKeyId,
                    configuration.secretAccessKey.value
                  )
                )
              )
          configuration.endpoint.foreach(builder.endpointOverride)
          builder.build()
        }
      )(client => IO.blocking(client.close()))
      .use { client =>
        val rootPrefix = Option(configuration.prefix).filter(_.nonEmpty).fold("")(_ + "/")
        val request =
          PutObjectRequest
            .builder()
            .bucket(configuration.bucket)
            .key(s"${rootPrefix}unrelated/object.txt")
            .build()
        IO.fromCompletableFuture(
          IO(client.putObject(request, AsyncRequestBody.fromBytes(Array(1))))
        ).void
      }

  private def s3Configuration: Option[S3PhotoConfig] =
    sys.env.get("S3_TEST_ENDPOINT").map { endpoint =>
      S3PhotoConfig(
        bucket = sys.env.getOrElse("S3_TEST_BUCKET", "cooking-blog-photos"),
        prefix = s"cooking-blog/photos/contract-${java.util.UUID.randomUUID()}",
        region = sys.env.getOrElse("S3_TEST_REGION", "us-east-1"),
        endpoint = Some(URI.create(endpoint)),
        pathStyleAccess = true,
        credentialsMode = S3CredentialsMode.Static,
        accessKeyId = sys.env.getOrElse("S3_TEST_ACCESS_KEY_ID", "cooking-blog"),
        secretAccessKey =
          Secret(sys.env.getOrElse("S3_TEST_SECRET_ACCESS_KEY", "cooking-blog-dev-secret")),
        maximumConcurrency = 2,
        connectionTimeout = 5.seconds,
        requestTimeout = 30.seconds
      )
    }
}
