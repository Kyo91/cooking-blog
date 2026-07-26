package cookingblog.http

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Authenticated, classpath-backed browser assets. */
final class StaticRoutes(classLoader: ClassLoader) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "static" / "htmx-2.0.4.min.js" =>
      asset("public/htmx.min.js", MediaType.application.javascript)
    case GET -> Root / "static" / "app-v1.js" =>
      asset("public/app-v1.js", MediaType.application.javascript)
  }

  private def asset(resource: String, mediaType: MediaType): IO[Response[IO]] =
    IO.blocking(Option(classLoader.getResourceAsStream(resource))).flatMap {
      case None         => NotFound()
      case Some(stream) =>
        IO.blocking {
          try { stream.readAllBytes() }
          finally { stream.close() }
        }.flatMap(bytes => Ok(bytes, `Content-Type`(mediaType)))
    }
}
