package cookingblog.storage

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cookingblog.config.{S3CredentialsMode, S3PhotoConfig}
import fs2.{Chunk, Stream}
import fs2.interop.flow.fromPublisher
import org.reactivestreams.{Subscriber, Subscription}
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*

import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** S3-compatible implementation using opaque storage-key directories and immutable variants. */
final class S3PhotoStore private (
    client: S3AsyncClient,
    config: S3PhotoConfig,
    operations: Semaphore[IO]
) extends PhotoStore {
  override def put(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension,
      source: Path
  ): IO[Unit] =
    withOperation {
      val request =
        PutObjectRequest
          .builder()
          .bucket(config.bucket)
          .key(objectKey(storageKey, variant, extension))
          .contentType(extension.contentType)
          .cacheControl("public, max-age=31536000, immutable")
          .build()
      IO.fromCompletableFuture(
        IO(client.putObject(request, AsyncRequestBody.fromFile(source)))
      ).void
    }

  override def read(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension
  ): Stream[IO, Byte] =
    Stream.resource(operations.permit).flatMap { _ =>
      val request =
        GetObjectRequest
          .builder()
          .bucket(config.bucket)
          .key(objectKey(storageKey, variant, extension))
          .build()
      // Own the future before waiting for headers, including cancellation before subscription.
      Stream
        .eval(IO(new AtomicBoolean(false)))
        .flatMap { subscribed =>
          val response = Resource.make(
            IO(client.getObject(request, AsyncResponseTransformer.toPublisher[GetObjectResponse]()))
          ) { future =>
            IO {
              val cancelled = future.cancel(true)
              if (!cancelled && !future.isCompletedExceptionally && !subscribed.get()) {
                future.join().subscribe(S3PhotoStore.cancellingSubscriber)
              }
            }
          }
          Stream.resource(response).flatMap { future =>
            Stream.eval(IO.fromCompletableFuture(IO.pure(future))).flatMap { publisher =>
              fromPublisher[IO, ByteBuffer](chunkSize = 1) { subscriber =>
                IO {
                  subscribed.set(true)
                  publisher.subscribe(S3PhotoStore.adaptSubscriber(subscriber))
                }
              }.flatMap(buffer => Stream.chunk(Chunk.byteBuffer(buffer)))
            }
          }
        }
        .timeout(config.requestTimeout)
    }

  override def delete(storageKey: StorageKey): IO[Unit] =
    listObjects(prefixFor(storageKey)).flatMap { objects =>
      objects
        .map(_.key())
        .grouped(1000)
        .toList
        .traverse_(keys =>
          withOperation {
            val request =
              DeleteObjectsRequest
                .builder()
                .bucket(config.bucket)
                .delete(
                  Delete
                    .builder()
                    .objects(keys.map(key => ObjectIdentifier.builder().key(key).build()).asJava)
                    .quiet(true)
                    .build()
                )
                .build()
            IO.fromCompletableFuture(IO(client.deleteObjects(request))).flatMap { response =>
              IO.raiseWhen(!response.errors().isEmpty)(
                new IllegalStateException(
                  s"S3 bulk deletion failed for ${response.errors().size()} object(s)"
                )
              )
            }
          }
        )
    }

  override def listStorageKeys: IO[Set[StorageKey]] =
    listObjects(rootPrefix).map(objects => storageKeys(objects).keySet)

  override def listStorageKeysOlderThan(cutoff: Instant): IO[Set[StorageKey]] =
    listObjects(rootPrefix).map { objects =>
      storageKeys(objects).collect {
        case (storageKey, latestModified) if latestModified.isBefore(cutoff) => storageKey
      }.toSet
    }

  override def checkWritable: IO[Boolean] =
    withOperation {
      // A unique key under the configured photo prefix verifies the same IAM scope as uploads.
      // Always attempt cleanup, including when a failed/cancelled PUT may have reached S3.
      IO(StorageKey.random).flatMap { storageKey =>
        val key = objectKey(storageKey, PhotoVariant.Original, PhotoExtension.Png)
        val remove = IO
          .fromCompletableFuture(
            IO(
              client.deleteObject(
                DeleteObjectRequest.builder().bucket(config.bucket).key(key).build()
              )
            )
          )
          .void
        val write = IO
          .fromCompletableFuture(
            IO(
              client.putObject(
                PutObjectRequest.builder().bucket(config.bucket).key(key).build(),
                AsyncRequestBody.fromBytes(Array.emptyByteArray)
              )
            )
          )
          .void
        write.guarantee(remove)
      }
    }.attempt.map(_.isRight)

  private def listObjects(prefix: String): IO[List[S3Object]] = {
    def next(continuationToken: Option[String], collected: List[S3Object]): IO[List[S3Object]] =
      withOperation {
        val builder =
          ListObjectsV2Request.builder().bucket(config.bucket).prefix(prefix)
        continuationToken.foreach(builder.continuationToken)
        IO.fromCompletableFuture(IO(client.listObjectsV2(builder.build())))
      }.flatMap { response =>
        val objects = response.contents().asScala.toList
        if (response.isTruncated) {
          next(Option(response.nextContinuationToken), collected ++ objects)
        } else {
          IO.pure(collected ++ objects)
        }
      }

    next(None, Nil)
  }

  private def storageKeys(objects: List[S3Object]): Map[StorageKey, Instant] =
    objects.foldLeft(Map.empty[StorageKey, Instant]) { (keys, objectSummary) =>
      storageKeyFromObjectKey(objectSummary.key()).fold(keys) { storageKey =>
        val modified = objectSummary.lastModified()
        keys.updatedWith(storageKey) {
          case Some(previous) => Some(if (previous.isAfter(modified)) previous else modified)
          case None           => Some(modified)
        }
      }
    }

  private def storageKeyFromObjectKey(key: String): Option[StorageKey] = {
    val withoutPrefix = key.stripPrefix(rootPrefix)
    withoutPrefix.split("/", 2).headOption.flatMap(StorageKey.parse(_).toOption)
  }

  private def objectKey(
      storageKey: StorageKey,
      variant: PhotoVariant,
      extension: PhotoExtension
  ): String = s"${prefixFor(storageKey)}${variant.filename}.${extension.value}"

  private def prefixFor(storageKey: StorageKey): String =
    s"$rootPrefix${StorageKey.value(storageKey)}/"

  private val rootPrefix = Option(config.prefix).filter(_.nonEmpty).fold("")(_ + "/")

  private def withOperation[A](operation: IO[A]): IO[A] = operations.permit.use(_ => operation)
}

object S3PhotoStore {

  /** The caller retains ownership of the SDK client. */
  private[storage] def fromClient(config: S3PhotoConfig, client: S3AsyncClient): IO[S3PhotoStore] =
    Semaphore[IO](config.maximumConcurrency.toLong).map(new S3PhotoStore(client, config, _))

  // FS2's Flow bridge owns demand and subscription cancellation; only adapt the protocol types.
  private def adaptSubscriber(subscriber: Flow.Subscriber[ByteBuffer]): Subscriber[ByteBuffer] =
    new Subscriber[ByteBuffer] {
      override def onSubscribe(subscription: Subscription): Unit =
        subscriber.onSubscribe(new Flow.Subscription {
          override def request(elements: Long): Unit = subscription.request(elements)
          override def cancel(): Unit = subscription.cancel()
        })
      override def onNext(buffer: ByteBuffer): Unit = subscriber.onNext(buffer)
      override def onError(error: Throwable): Unit = subscriber.onError(error)
      override def onComplete(): Unit = subscriber.onComplete()
    }

  private def cancellingSubscriber: Subscriber[ByteBuffer] =
    new Subscriber[ByteBuffer] {
      override def onSubscribe(subscription: Subscription): Unit = subscription.cancel()
      override def onNext(buffer: ByteBuffer): Unit = ()
      override def onError(error: Throwable): Unit = ()
      override def onComplete(): Unit = ()
    }

  def create(config: S3PhotoConfig): Resource[IO, S3PhotoStore] =
    for {
      operations <- Resource.eval(Semaphore[IO](config.maximumConcurrency.toLong))
      client <- Resource.make(buildClient(config))(closeClient)
    } yield new S3PhotoStore(client, config, operations)

  private def buildClient(config: S3PhotoConfig): IO[S3AsyncClient] =
    IO.blocking {
      val builder =
        S3AsyncClient
          .builder()
          .region(Region.of(config.region))
          .httpClientBuilder(
            NettyNioAsyncHttpClient
              .builder()
              .connectionTimeout(java.time.Duration.ofMillis(config.connectionTimeout.toMillis))
          )
          .overrideConfiguration(builder => {
            val _ =
              builder.apiCallTimeout(
                java.time.Duration.ofMillis(config.requestTimeout.toMillis)
              )
          })
          .forcePathStyle(config.pathStyleAccess)
      config.endpoint.foreach(builder.endpointOverride)
      config.credentialsMode match {
        case S3CredentialsMode.Default =>
          builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
        case S3CredentialsMode.Static =>
          builder.credentialsProvider(
            StaticCredentialsProvider.create(
              AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey.value)
            )
          )
      }
      builder.build()
    }

  private def closeClient(client: S3AsyncClient): IO[Unit] = IO.blocking(client.close())
}
