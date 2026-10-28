package cookingblog.storage

import cats.effect.{Deferred, IO}
import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import cookingblog.config.{S3CredentialsMode, S3PhotoConfig}
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Method as HttpMethod, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.reactivestreams.{Subscriber, Subscription}
import software.amazon.awssdk.core.async.{AsyncResponseTransformer, ResponsePublisher, SdkPublisher}
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final class S3PhotoStoreSuite extends CatsEffectSuite {
  private val key = StorageKey.parse("0123456789abcdef0123456789abcdef").toOption.get
  private val configuration = S3PhotoConfig(
    bucket = "photos",
    prefix = "cooking-blog/photos",
    region = "us-east-1",
    endpoint = None,
    pathStyleAccess = true,
    credentialsMode = S3CredentialsMode.Static,
    accessKeyId = "test",
    secretAccessKey = Secret("test"),
    maximumConcurrency = 1,
    connectionTimeout = 5.seconds,
    requestTimeout = 30.seconds
  )

  test("reads emit before the body completes, bound demand, and cancel on early termination") {
    val body = new ControlledBody
    val sdk = new StubClient(body)
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      bytes <- store.read(key, PhotoVariant.Original, PhotoExtension.Png).take(2).compile.toList
      writable <- store.checkWritable.timeout(3.seconds)
    } yield {
      assertEquals(bytes, List[Byte](1, 2))
      assert(body.cancelled.get())
      assertEquals(body.requested.get(), 1L)
      assert(writable, "early termination must release the operation permit")
    }
  }

  test("cancelling a waiting body cancels the SDK subscription and releases its permit") {
    val body = new ControlledBody(emit = false)
    val sdk = new StubClient(body)
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      started <- Deferred[IO, Unit]
      fiber <- store
        .read(key, PhotoVariant.Original, PhotoExtension.Png)
        .onFinalize(started.complete(()).void)
        .compile
        .drain
        .start
      _ <- IO.cede.iterateUntil(_ => body.requested.get() > 0).timeout(3.seconds)
      _ <- fiber.cancel
      _ <- started.get
      writable <- store.checkWritable.timeout(3.seconds)
    } yield {
      assert(body.cancelled.get())
      assert(writable)
    }
  }

  test("cancelling while waiting for headers cancels the SDK future and releases its permit") {
    val sdk = new StubClient(new ControlledBody)
    sdk.delayHeaders = true
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      fiber <- store.read(key, PhotoVariant.Original, PhotoExtension.Png).compile.drain.start
      _ <- IO.cede.iterateUntil(_ => sdk.getStarted.get()).timeout(3.seconds)
      _ <- fiber.cancel
      writable <- store.checkWritable.timeout(3.seconds)
    } yield {
      assert(sdk.headers.isCancelled)
      assert(writable)
    }
  }

  test("the request timeout bounds streamed bodies and releases the subscription and permit") {
    val body = new ControlledBody(emit = false)
    val sdk = new StubClient(body)
    for {
      store <- S3PhotoStore.fromClient(configuration.copy(requestTimeout = 200.millis), sdk.client)
      result <- store.read(key, PhotoVariant.Original, PhotoExtension.Png).compile.drain.attempt
      writable <- store.checkWritable.timeout(3.seconds)
    } yield {
      assert(result.swap.toOption.exists(_.isInstanceOf[java.util.concurrent.TimeoutException]))
      assert(body.cancelled.get())
      assert(writable)
    }
  }

  test("body errors propagate and release the operation permit") {
    val body = new ControlledBody(failure = Some(new IllegalStateException("body failed")))
    val sdk = new StubClient(body)
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      result <- store.read(key, PhotoVariant.Original, PhotoExtension.Png).compile.drain.attempt
      writable <- store.checkWritable.timeout(3.seconds)
    } yield {
      assertEquals(result.swap.toOption.map(_.getMessage), Some("body failed"))
      assert(writable)
    }
  }

  test("complete bodies preserve all bytes") {
    val sdk = new StubClient(new ControlledBody(complete = true))
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      bytes <- store.read(key, PhotoVariant.Original, PhotoExtension.Png).compile.toList
    } yield assertEquals(bytes, List[Byte](1, 2, 3))
  }

  test("real SDK reads a partial HTTP body without waiting for completion and releases resources") {
    val app = HttpApp[IO] { request =>
      request.method match {
        case HttpMethod.GET =>
          IO.pure(
            Response[IO](Status.Ok).withBodyStream(
              Stream.emits(Array.fill[Byte](8192)(7)).covary[IO] ++ Stream.never[IO]
            )
          )
        case HttpMethod.PUT    => IO.pure(Response[IO](Status.Ok))
        case HttpMethod.DELETE => IO.pure(Response[IO](Status.NoContent))
        case _                 => IO.pure(Response[IO](Status.MethodNotAllowed))
      }
    }
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withShutdownTimeout(100.millis)
      .withHttpApp(app)
      .build
      .use { server =>
        val config = configuration
          .copy(endpoint = Some(URI.create(s"http://127.0.0.1:${server.address.getPort}")))
        S3PhotoStore.create(config).use { store =>
          for {
            first <- store
              .read(key, PhotoVariant.Original, PhotoExtension.Png)
              .take(1)
              .compile
              .toList
              .timeout(5.seconds)
            second <- store
              .read(key, PhotoVariant.Original, PhotoExtension.Png)
              .take(1)
              .compile
              .toList
              .timeout(5.seconds)
            writable <- store.checkWritable.timeout(5.seconds)
          } yield {
            assertEquals(first, List[Byte](7))
            assertEquals(second, first)
            assert(writable)
          }
        }
      }
  }

  test("bulk delete surfaces per-object errors and can be retried idempotently") {
    val sdk = new StubClient(new ControlledBody)
    sdk.partialDelete = true
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      failed <- store.delete(key).attempt
      _ <- IO { sdk.partialDelete = false }
      _ <- store.delete(key)
    } yield {
      assertEquals(
        failed.swap.toOption.map(_.getMessage),
        Some("S3 bulk deletion failed for 1 object(s)")
      )
      assertEquals(sdk.deletedBatches.size, 2)
      assertEquals(sdk.deletedBatches.head, sdk.deletedBatches.last)
      assert(!failed.swap.toOption.get.getMessage.contains("private-key"))
    }
  }

  test("readiness writes unique photo keys and deletes them without using HeadBucket") {
    val sdk = new StubClient(new ControlledBody)
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      first <- store.checkWritable
      second <- store.checkWritable
    } yield {
      assert(first && second)
      assertEquals(sdk.putKeys.size, 2)
      assertEquals(sdk.putKeys.toSet.size, 2)
      assertEquals(sdk.putKeys.toList, sdk.deletedKeys.toList)
      sdk.putKeys.foreach { value =>
        assert(value.startsWith("cooking-blog/photos/"))
        assert(value.endsWith("/original.png"))
        assert(StorageKey.parse(value.split("/")(2)).isRight)
      }
    }
  }

  test("readiness reports denied writes and still attempts cleanup") {
    val sdk = new StubClient(new ControlledBody)
    sdk.denyPut = true
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      writable <- store.checkWritable
    } yield {
      assert(!writable)
      assertEquals(sdk.deletedKeys.toList, sdk.putKeys.toList)
    }
  }

  test("readiness reports denied deletes rather than falsely claiming writability") {
    val sdk = new StubClient(new ControlledBody)
    sdk.denyDelete = true
    for {
      store <- S3PhotoStore.fromClient(configuration, sdk.client)
      writable <- store.checkWritable
    } yield assert(!writable)
  }

  private final class ControlledBody(
      emit: Boolean = true,
      complete: Boolean = false,
      failure: Option[Throwable] = None
  ) extends SdkPublisher[ByteBuffer] {
    val cancelled = new AtomicBoolean(false)
    val requested = new AtomicLong(0)
    private val emitted = new AtomicBoolean(false)

    override def subscribe(subscriber: Subscriber[? >: ByteBuffer]): Unit = {
      subscriber.onSubscribe(new Subscription {
        override def request(elements: Long): Unit = {
          val _ = requested.addAndGet(elements)
          if (emitted.compareAndSet(false, true)) {
            failure match {
              case Some(error)  => subscriber.onError(error)
              case None if emit =>
                subscriber.onNext(ByteBuffer.wrap(Array[Byte](1, 2, 3)))
                if (complete) { subscriber.onComplete() }
              case None => ()
            }
          }
        }
        override def cancel(): Unit = cancelled.set(true)
      })
    }
  }

  private final class StubClient(body: ControlledBody) extends InvocationHandler {
    var partialDelete = false
    var denyPut = false
    var denyDelete = false
    var delayHeaders = false
    val headers = new CompletableFuture[ResponsePublisher[GetObjectResponse]]()
    val getStarted = new AtomicBoolean(false)
    val putKeys = ListBuffer.empty[String]
    val deletedKeys = ListBuffer.empty[String]
    val deletedBatches = ListBuffer.empty[List[String]]

    val client: S3AsyncClient = Proxy
      .newProxyInstance(
        classOf[S3AsyncClient].getClassLoader,
        Array(classOf[S3AsyncClient]),
        this
      )
      .asInstanceOf[S3AsyncClient]

    override def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
      method.getName match {
        case "getObject" =>
          getStarted.set(true)
          if (delayHeaders) {
            headers
          } else {
            val transformer = arguments(1).asInstanceOf[
              AsyncResponseTransformer[GetObjectResponse, ResponsePublisher[GetObjectResponse]]
            ]
            val future = transformer.prepare()
            transformer.onResponse(GetObjectResponse.builder().build())
            transformer.onStream(body)
            future
          }
        case "putObject" =>
          putKeys += arguments(0).asInstanceOf[PutObjectRequest].key()
          if (denyPut) {
            CompletableFuture.failedFuture[PutObjectResponse](
              new IllegalStateException("put denied")
            )
          } else {
            CompletableFuture.completedFuture(PutObjectResponse.builder().build())
          }
        case "deleteObject" =>
          deletedKeys += arguments(0).asInstanceOf[DeleteObjectRequest].key()
          if (denyDelete) {
            CompletableFuture.failedFuture[DeleteObjectResponse](
              new IllegalStateException("delete denied")
            )
          } else {
            CompletableFuture.completedFuture(DeleteObjectResponse.builder().build())
          }
        case "listObjectsV2" =>
          CompletableFuture.completedFuture(
            ListObjectsV2Response
              .builder()
              .isTruncated(false)
              .contents(S3Object.builder().key("private-key").build())
              .build()
          )
        case "deleteObjects" =>
          deletedBatches += arguments(0)
            .asInstanceOf[DeleteObjectsRequest]
            .delete()
            .objects()
            .asScala
            .map(_.key())
            .toList
          val builder = DeleteObjectsResponse.builder()
          if (partialDelete) {
            val _ = builder.errors(
              S3Error
                .builder()
                .key("private-key")
                .code("AccessDenied")
                .message("sensitive provider message")
                .build()
            )
          }
          CompletableFuture.completedFuture(builder.build())
        case other => throw new UnsupportedOperationException(other)
      }
    }
  }
}
