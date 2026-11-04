package edu.uic.cs441.scholarchain.rag.semantic

import edu.uic.cs441.scholarchain.core.model.YearWindow
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Deterministic text rules shared by the rendering templates and the semantic-context format.
 *
 * Source text (titles, abstracts, names) is untrusted data (CLAUDE.md law 8). [[quote]] puts it on
 * one line inside double quotes: it NFC-normalizes, turns every control, format (bidi), and
 * whitespace character (including U+2028 and U+2029) into a single space, bounds the length in code
 * points, and escapes backslash and double quote. Quoted text therefore cannot start a new line,
 * close its own quotes, or imitate a template header or a context delimiter.
 */
object TextRules:

  /** Ellipsis appended when [[quote]] truncates. */
  val Ellipsis: String = "..."

  /** Collapses whitespace and control characters, then bounds the length (code points). */
  def clean(raw: String, maxChars: Int): String =
    require(maxChars > Ellipsis.length, "maxChars too small")
    val nfc     = Normalizer.normalize(Option(raw).getOrElse(""), Normalizer.Form.NFC)
    val sb      = new StringBuilder
    var pending = false
    nfc.codePoints().forEach { cp =>
      val blank = Character.isWhitespace(cp) || Character.isISOControl(cp) ||
        Character.getType(cp) == Character.FORMAT || Character.isSpaceChar(cp)
      if blank then pending = sb.nonEmpty
      else
        if pending then sb.append(' ')
        pending = false
        sb.appendAll(Character.toChars(cp))
    }
    val s = sb.toString
    if s.codePointCount(0, s.length) <= maxChars then s
    else s.substring(0, s.offsetByCodePoints(0, maxChars - Ellipsis.length)) + Ellipsis

  /** [[clean]], then escaped and wrapped in double quotes. */
  def quote(raw: String, maxChars: Int): String =
    "\"" + clean(raw, maxChars).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /**
   * Fixed number format: whole values as integers, other values with exactly six decimals
   * (HALF_EVEN), never scientific notation, never locale dependent. Non-finite values are refused
   * by the templates before they get here.
   */
  def num(d: Double): String =
    require(!d.isNaN && !d.isInfinite, "non-finite number")
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString
    else java.math.BigDecimal(d).setScale(6, RoundingMode.HALF_EVEN).toPlainString

  def window(w: Option[YearWindow]): String =
    w.fold("all-years")(x => s"${x.startYear}-${x.endYear}")
