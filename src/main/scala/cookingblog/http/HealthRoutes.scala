package cookingblog.http

import cookingblog.database.Database
import cats.effect.IO
import cats.syntax.all.*
import cookingblog.service.PhotoService
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Authenticated dependency health checks. */
final class HealthRoutes(pool: Database, photoService: PhotoService) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" / "live" =>
      Ok("{\"status\":\"ok\"}").map(_.withContentType(`Content-Type`(MediaType.application.json)))
    case GET -> Root / "health" / "ready" =>
      (
        pool.ping.attempt,
        photoService.checkStoreWritable
      ).tupled.flatMap {
        case (Right(()), true) =>
          Ok("{\"status\":\"ready\"}").map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
        case _ =>
          ServiceUnavailable("{\"status\":\"not_ready\"}").map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
      }
  }
}
