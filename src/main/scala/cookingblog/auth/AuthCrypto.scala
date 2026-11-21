package cookingblog.auth

import java.security.MessageDigest

private[auth] object AuthCrypto {
  def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  def constantTimeEquals(left: Array[Byte], right: Array[Byte]): Boolean =
    MessageDigest.isEqual(left, right)
}
