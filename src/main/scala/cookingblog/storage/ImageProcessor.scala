package cookingblog.storage

import cats.effect.{IO, Ref, Resource}
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory}
import fs2.Stream
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}

import java.awt.{Color, Graphics2D, RenderingHints}
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import javax.imageio.stream.ImageInputStream
import javax.imageio.{ImageIO, ImageReader}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class ProcessedPhoto(
    contentType: String,
    extension: String,
    uploadedByteSize: Long,
    width: Int,
    height: Int,
    files: Map[PhotoVariant, Path]
)

enum ImageProcessingException(message: String) extends RuntimeException(message) {
  case UploadTooLarge(limit: Long)
      extends ImageProcessingException(s"Photo exceeds the $limit byte limit")
  case EmptyUpload extends ImageProcessingException("Photo is empty")
  case UnsupportedImage extends ImageProcessingException("Photo must decode as JPEG, PNG, or WebP")
  case ImageTooLarge extends ImageProcessingException("Decoded photo dimensions are too large")
}

final class ImageProcessor(
    maxUploadBytes: Long = ImageProcessor.MaxUploadBytes
) {
  import ImageProcessingException.*
  import ImageProcessor.ImageFormat

  def process(source: Stream[IO, Byte]): Resource[IO, ProcessedPhoto] =
    temporaryDirectory.evalMap { directory =>
      val input = directory.resolve("upload")
      streamToFile(source, input) *> decodeAndCreate(input, directory)
    }

  private def streamToFile(source: Stream[IO, Byte], target: Path): IO[Unit] =
    Ref.of[IO, Long](0L).flatMap { count =>
      source.chunks
        .evalMap { chunk =>
          count
            .updateAndGet(_ + chunk.size.toLong)
            .flatMap { total =>
              if (total > maxUploadBytes) {
                IO.raiseError[fs2.Chunk[Byte]](UploadTooLarge(maxUploadBytes))
              } else {
                IO.pure(chunk)
              }
            }
        }
        .unchunks
        .through(Fs2Files[IO].writeAll(Fs2Path.fromNioPath(target)))
        .compile
        .drain *> count.get.flatMap { total =>
        IO.raiseWhen(total == 0L)(EmptyUpload)
      }
    }

  private def decodeAndCreate(input: Path, directory: Path): IO[ProcessedPhoto] =
    imageInput(input)
      .use { inputStream =>
        imageReader(inputStream).use { reader =>
          for {
            _ <- IO.blocking(reader.setInput(inputStream, true, true))
            formatName <- IO.blocking(reader.getFormatName)
            format <- normalizedFormat(formatName)
            dimensions <- IO.blocking((reader.getWidth(0), reader.getHeight(0)))
            (sourceWidth, sourceHeight) = dimensions
            _ <- IO.raiseWhen(
              sourceWidth <= 0 ||
                sourceHeight <= 0 ||
                sourceWidth.toLong * sourceHeight.toLong > ImageProcessor.MaxPixels
            )(ImageTooLarge)
            decoded <- IO
              .blocking(Option(reader.read(0)))
              .flatMap(IO.fromOption(_)(UnsupportedImage))
            orientation <- readOrientation(input)
            oriented <- orient(decoded, orientation)
            display <- scaleDown(oriented, ImageProcessor.DisplayMaximum)
            thumbnail <- scaleDown(oriented, ImageProcessor.ThumbnailMaximum)
            originalPath <- write(
              oriented,
              directory,
              PhotoVariant.Original,
              format
            )
            displayPath <- write(
              display,
              directory,
              PhotoVariant.Display,
              format
            )
            thumbnailPath <- write(
              thumbnail,
              directory,
              PhotoVariant.Thumbnail,
              format
            )
            uploadedByteSize <- IO.blocking(Files.size(input))
          } yield ProcessedPhoto(
            contentType = format.contentType,
            extension = format.extension,
            uploadedByteSize = uploadedByteSize,
            width = oriented.getWidth,
            height = oriented.getHeight,
            files = Map(
              PhotoVariant.Original -> originalPath,
              PhotoVariant.Display -> displayPath,
              PhotoVariant.Thumbnail -> thumbnailPath
            )
          )
        }
      }
      .handleErrorWith {
        case exception: ImageProcessingException => IO.raiseError(exception)
        case NonFatal(_)                         => IO.raiseError(UnsupportedImage)
      }

  private def imageInput(input: Path): Resource[IO, ImageInputStream] =
    Resource.make(
      IO.blocking(Option(ImageIO.createImageInputStream(input.toFile)))
        .flatMap(IO.fromOption(_)(UnsupportedImage))
    )(stream => IO.blocking(stream.close()))

  private def imageReader(
      input: ImageInputStream
  ): Resource[IO, ImageReader] =
    Resource.make(
      IO.blocking(ImageIO.getImageReaders(input).asScala.nextOption())
        .flatMap(IO.fromOption(_)(UnsupportedImage))
    )(reader => IO.blocking(reader.dispose()))

  private def readOrientation(input: Path): IO[Int] =
    IO.blocking {
      val metadata = ImageMetadataReader.readMetadata(input.toFile)
      Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .filter(_.containsTag(ExifDirectoryBase.TAG_ORIENTATION))
        .map(_.getInt(ExifDirectoryBase.TAG_ORIENTATION))
        .filter(value => value >= 1 && value <= 8)
        .getOrElse(1)
    }.recover { case NonFatal(_) =>
      1
    }

  private def orient(
      source: BufferedImage,
      orientation: Int
  ): IO[BufferedImage] = {
    if (orientation == 1) {
      IO.pure(source)
    } else {
      val width = source.getWidth
      val height = source.getHeight
      val swapDimensions = orientation >= 5 && orientation <= 8
      val destination =
        compatibleImage(
          source,
          if (swapDimensions) height else width,
          if (swapDimensions) width else height
        )
      val transform =
        orientation match {
          case 2 =>
            new AffineTransform(-1.0, 0.0, 0.0, 1.0, width.toDouble, 0.0)
          case 3 =>
            new AffineTransform(
              -1.0,
              0.0,
              0.0,
              -1.0,
              width.toDouble,
              height.toDouble
            )
          case 4 =>
            new AffineTransform(
              1.0,
              0.0,
              0.0,
              -1.0,
              0.0,
              height.toDouble
            )
          case 5 => new AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
          case 6 =>
            new AffineTransform(0.0, 1.0, -1.0, 0.0, height.toDouble, 0.0)
          case 7 =>
            new AffineTransform(
              0.0,
              -1.0,
              -1.0,
              0.0,
              height.toDouble,
              width.toDouble
            )
          case 8 =>
            new AffineTransform(
              0.0,
              -1.0,
              1.0,
              0.0,
              0.0,
              width.toDouble
            )
          case _ => new AffineTransform()
        }
      graphics(destination).use { graphics =>
        IO.blocking {
          graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
          )
          graphics.drawImage(source, transform, null)
          destination
        }
      }
    }
  }

  private def scaleDown(
      source: BufferedImage,
      maximum: Int
  ): IO[BufferedImage] = {
    val longest = math.max(source.getWidth, source.getHeight)
    if (longest <= maximum) {
      IO.pure(source)
    } else {
      val ratio = maximum.toDouble / longest.toDouble
      val width = math.max(1, math.round(source.getWidth * ratio).toInt)
      val height = math.max(1, math.round(source.getHeight * ratio).toInt)
      val scaled = compatibleImage(source, width, height)
      graphics(scaled).use { graphics =>
        IO.blocking {
          graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC
          )
          graphics.setRenderingHint(
            RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY
          )
          graphics.drawImage(source, 0, 0, width, height, null)
          scaled
        }
      }
    }
  }

  private def graphics(image: BufferedImage): Resource[IO, Graphics2D] =
    Resource.make(IO.blocking(image.createGraphics()))(graphics => IO.blocking(graphics.dispose()))

  private def compatibleImage(
      source: BufferedImage,
      width: Int,
      height: Int
  ): BufferedImage = {
    val imageType =
      if (source.getColorModel.hasAlpha) BufferedImage.TYPE_INT_ARGB
      else BufferedImage.TYPE_INT_RGB
    BufferedImage(width, height, imageType)
  }

  private def write(
      source: BufferedImage,
      directory: Path,
      variant: PhotoVariant,
      format: ImageFormat
  ): IO[Path] = {
    val target = directory.resolve(s"${variant.filename}.${format.extension}")
    val writable =
      if (format == ImageFormat.Jpeg && source.getColorModel.hasAlpha) {
        val rgb = BufferedImage(
          source.getWidth,
          source.getHeight,
          BufferedImage.TYPE_INT_RGB
        )
        graphics(rgb).use { graphics =>
          IO.blocking {
            graphics.setColor(Color.WHITE)
            graphics.fillRect(0, 0, rgb.getWidth, rgb.getHeight)
            graphics.drawImage(source, 0, 0, null)
            rgb
          }
        }
      } else {
        IO.pure(source)
      }
    writable.flatMap { image =>
      IO.blocking(ImageIO.write(image, format.writerName, target.toFile)).flatMap {
        case true  => IO.pure(target)
        case false => IO.raiseError(UnsupportedImage)
      }
    }
  }

  private def normalizedFormat(raw: String): IO[ImageFormat] = {
    val format =
      raw.toLowerCase(java.util.Locale.ROOT) match {
        case "jpeg" | "jpg" => Some(ImageFormat.Jpeg)
        case "png"          => Some(ImageFormat.Png)
        case "webp"         => Some(ImageFormat.Png)
        case _              => None
      }
    IO.fromOption(format)(UnsupportedImage)
  }

  private def temporaryDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("cooking-blog-photo-")))(directory =>
      cleanupDirectory(directory)
    )

  private def cleanupDirectory(directory: Path): IO[Unit] =
    IO.blocking(Files.exists(directory))
      .ifM(
        Resource
          .fromAutoCloseable(IO.blocking(Files.walk(directory)))
          .use { stream =>
            IO.blocking {
              val paths =
                stream
                  .iterator()
                  .asScala
                  .toList
                  .sortBy(_.getNameCount)
                  .reverse
              paths.foreach(path => {
                val _ = Files.deleteIfExists(path)
              })
              ()
            }
          },
        IO.unit
      )
}

object ImageProcessor {
  val MaxUploadBytes: Long = 10_000_000L
  private val MaxPixels: Long = 50_000_000L
  private val DisplayMaximum = 1600
  private val ThumbnailMaximum = 480

  private[storage] enum ImageFormat(
      val contentType: String,
      val extension: String,
      val writerName: String
  ) {
    case Jpeg extends ImageFormat("image/jpeg", "jpg", "jpeg")
    case Png extends ImageFormat("image/png", "png", "png")
  }
}
