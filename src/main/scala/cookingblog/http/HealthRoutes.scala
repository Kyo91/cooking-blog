package cookingblog.http

import cats.effect.IO
import cats.syntax.all.*
import cookingblog.service.PhotoService
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Authenticated dependency health checks. */
final class HealthRoutes(transactor: Transactor[IO], photoService: PhotoService) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" / "live" =>
      Ok("{\"status\":\"ok\"}").map(_.withContentType(`Content-Type`(MediaType.application.json)))
    case GET -> Root / "health" / "ready" =>
      (
        sql"select 1".query[Int].unique.transact(transactor).attempt,
        photoService.checkStoreWritable
      ).tupled.flatMap {
        case (Right(1), true) =>
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
