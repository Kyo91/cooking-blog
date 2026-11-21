package cookingblog.storage

opaque type StorageKey = String
object StorageKey {
  private val Pattern = raw"[a-f0-9]{32}".r

  def random: StorageKey = java.util.UUID.randomUUID().toString.replace("-", "")
  def parse(value: String): Either[String, StorageKey] =
    Either.cond(
      Pattern.matches(value),
      value,
      s"Invalid photo storage key '$value': expected 32 lower-case hexadecimal characters"
    )
  def value(key: StorageKey): String = key
}
