package cookingblog.auth

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
@link("crypto")
private object OpenSsl {
  def RAND_bytes(buffer: Ptr[Byte], size: CInt): CInt = extern
  def SHA256(input: Ptr[Byte], size: CSize, output: Ptr[Byte]): Ptr[Byte] = extern
  def CRYPTO_memcmp(left: Ptr[Byte], right: Ptr[Byte], size: CSize): CInt = extern
}

private[cookingblog] object AuthCrypto {
  private val digestSize = 32

  def fillRandom(bytes: Array[Byte]): Unit = {
    if (bytes.nonEmpty && OpenSsl.RAND_bytes(bytes.at(0), bytes.length) != 1) {
      throw IllegalStateException("OpenSSL random byte generation failed")
    }
  }

  def sha256(bytes: Array[Byte]): Array[Byte] = {
    val output = new Array[Byte](digestSize)
    val input = if (bytes.isEmpty) stackalloc[Byte]() else bytes.at(0)
    if (OpenSsl.SHA256(input, bytes.length.toUSize, output.at(0)) == null) {
      throw IllegalStateException("OpenSSL SHA-256 failed")
    }
    output
  }

  def constantTimeEquals(left: Array[Byte], right: Array[Byte]): Boolean = {
    // Compare fixed-size digests with OpenSSL rather than short-circuiting on byte content.
    val leftDigest = sha256(left)
    val rightDigest = sha256(right)
    OpenSsl.CRYPTO_memcmp(leftDigest.at(0), rightDigest.at(0), digestSize.toUSize) == 0
  }
}
