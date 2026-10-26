package edu.uic.cs441.scholarchain.core.validation

/**
 * Typed contract violations. `path` names the offending field from the record root, for example
 * `claims[0].confidence`; the empty path means the whole record.
 */
enum ValidationError:
  case MissingProvenance(path: String, detail: String)
  case ConfidenceOutOfRange(path: String, value: Double)
  case UnsupportedSchemaVersion(path: String, found: Int, supported: List[Int])
  case MalformedId(path: String, expectedKind: String, value: String)
  case InvalidPairOrdering(path: String, low: String, high: String)
  case SelfPair(path: String, researcherId: String)
  case InvalidYearWindow(path: String, startYear: Int, endYear: Int)
  case IdMismatch(path: String, found: String, expected: String)
  case InvalidField(path: String, reason: String)

  def path: String

  def message: String = this match
    case MissingProvenance(p, d)           => s"$p: missing provenance ($d)"
    case ConfidenceOutOfRange(p, v)        => s"$p: confidence $v is outside [0, 1]"
    case UnsupportedSchemaVersion(p, f, s) =>
      s"$p: schema version $f is not one of ${s.mkString(", ")}"
    case MalformedId(p, k, v)         => s"$p: '$v' is not a well-formed $k ID"
    case InvalidPairOrdering(p, l, h) =>
      s"$p: pair ('$l', '$h') is not in canonical order (low < high)"
    case SelfPair(p, r)             => s"$p: self-pair on '$r'"
    case InvalidYearWindow(p, s, e) => s"$p: start year $s exceeds end year $e"
    case IdMismatch(p, f, e)        => s"$p: ID '$f' does not match its formula (expected '$e')"
    case InvalidField(p, r)         => s"$p: $r"
