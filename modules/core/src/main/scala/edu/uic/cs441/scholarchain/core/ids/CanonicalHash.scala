package edu.uic.cs441.scholarchain.core.ids

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * The canonical encoding and hash behind every hashed stable ID.
 *
 * Encoding (frozen, schema version 1):
 *   1. Each field is a string. Integers are rendered in base-10 ASCII with no sign for non-negative
 *      values and no leading zeros. An absent optional field is the empty string.
 *   2. Each field is Unicode-normalized to NFC and encoded as UTF-8.
 *   3. Each field is written as `<byte length in base-10 ASCII>` `:` `<bytes>`.
 *   4. Fields are concatenated with no separator. The first field is always a domain tag such as
 *      `scholarchain:coauthor-edge:v1`, so IDs of different kinds can never collide.
 *   5. A list is written as one field holding its element count, followed by its elements.
 *
 * Length prefixing makes the encoding injective: `("ab", "c")` encodes to `2:ab1:c` and
 * `("a", "bc")` to `1:a2:bc`. The digest is SHA-256, rendered as 64 lowercase hex characters.
 * Nothing in the encoding depends on time, locale, or the JVM default charset.
 */
object CanonicalHash:

  def encode(fields: Seq[String]): Array[Byte] =
    val out = ByteArrayOutputStream()
    fields.foreach { field =>
      val bytes = Normalizer.normalize(field, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8)
      out.write(bytes.length.toString.getBytes(StandardCharsets.US_ASCII))
      out.write(':'.toInt)
      out.write(bytes)
    }
    out.toByteArray

  /** Length-prefixed list: the element count, then the elements. */
  def list(items: Seq[String]): Seq[String] = items.size.toString +: items

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def hashFields(fields: Seq[String]): String = sha256Hex(encode(fields))

  /**
   * Content hash of a text: `sha256:<hex>` over the NFC-normalized UTF-8 bytes of the text itself
   * (not length-prefixed). Used for chunk text hashes and evidence content hashes.
   */
  def contentHash(text: String): String =
    "sha256:" + sha256Hex(
      Normalizer.normalize(text, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8)
    )
