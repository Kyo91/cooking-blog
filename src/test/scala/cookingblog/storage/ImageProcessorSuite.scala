package cookingblog.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

final class ImageProcessorSuite extends CatsEffectSuite {
  test("decodes and creates variants for JPEG, PNG, and WebP") {
    List(
      ("jpeg", "image/jpeg", "jpg", 32, 24),
      ("png", "image/png", "png", 32, 24),
      ("webp", "image/png", "png", 1, 1)
    ).traverse_ { case (writer, contentType, extension, width, height) =>
      encodedImage(writer).flatMap { bytes =>
        ImageProcessor()
          .process(Stream.emits(bytes).covary[IO])
          .use { processed =>
            IO {
              assertEquals(processed.contentType, contentType)
              assertEquals(processed.extension.value, extension)
              assertEquals(processed.width, width)
              assertEquals(processed.height, height)
              assertEquals(processed.files.keySet, PhotoVariant.values.toSet)
              processed.files.values.foreach(path => assert(ImageIO.read(path.toFile) != null))
            }
          }
      }
    }
  }

  test("rejects a stream immediately after the 10,000,000 byte boundary") {
    val body =
      Stream.constant[IO, Byte](0).take(ImageProcessor.MaxUploadBytes + 1)
    ImageProcessor()
      .process(body)
      .use(_ => IO.unit)
      .attempt
      .map(result =>
        assert(
          result.left.exists(
            _.isInstanceOf[ImageProcessingException.UploadTooLarge]
          )
        )
      )
  }

  test("accepts a valid image at exactly the 10,000,000 byte boundary") {
    encodedImage("png").flatMap { image =>
      val padding = ImageProcessor.MaxUploadBytes - image.length.toLong
      val body =
        Stream.emits(image).covary[IO] ++
          Stream.constant[IO, Byte](0).take(padding)
      ImageProcessor()
        .process(body)
        .use(processed =>
          IO(assertEquals(processed.uploadedByteSize, ImageProcessor.MaxUploadBytes))
        )
    }
  }

  test("rejects bytes that do not decode as an allowed image") {
    ImageProcessor()
      .process(Stream.emits("not an image".getBytes).covary[IO])
      .use(_ => IO.unit)
      .attempt
      .map(result =>
        assertEquals(
          result.left.toOption,
          Some(ImageProcessingException.UnsupportedImage)
        )
      )
  }

  private def encodedImage(writer: String): IO[Array[Byte]] =
    if (writer == "webp") {
      IO(
        java.util.Base64.getDecoder.decode(
          "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAgA0JaQAA3AA/vv9UAA="
        )
      )
    } else {
      IO.blocking {
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
          graphics.setColor(Color.ORANGE)
          graphics.fillRect(0, 0, image.getWidth, image.getHeight)
        } finally {
          graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        val written = ImageIO.write(image, writer, output)
        assert(written, s"No ImageIO writer was available for $writer")
        output.toByteArray
      }
    }
}
