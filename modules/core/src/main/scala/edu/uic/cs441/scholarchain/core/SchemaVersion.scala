package edu.uic.cs441.scholarchain.core

/**
 * Wire schema versions of the ScholarChain shared contract.
 *
 * Every record in `core.model` except the embedded value types (Provenance, YearWindow, ExternalId,
 * Property, MetricValue, QueryStep, RunInput) carries a `schemaVersion` field. A reader rejects any
 * record whose version is not in [[Supported]] before decoding its shape.
 *
 * Changing a field, its nullability, its JSON name, an enum wire value, or any stable ID formula is
 * a schema-version change: bump [[Current]], keep the old version readable or document the
 * migration, and update the golden fixtures under `modules/core/src/test/resources/wire/`.
 */
object SchemaVersion:

  /** The version every producer writes. */
  val Current: Int = 1

  /** The versions every consumer accepts. */
  val Supported: Set[Int] = Set(1)

  def isSupported(version: Int): Boolean = Supported.contains(version)

  /** Version of the configuration file contract (`scholarchain.config-schema-version`). */
  val ConfigCurrent: Int = 1

  val ConfigSupported: Set[Int] = Set(1)

  /** Versions of the ScholarChain-internal researcher identity (`researcher:sc<N>:<hex>`). */
  val ResearcherIdentityCurrent: Int = 1

  val ResearcherIdentitySupported: Set[Int] = Set(1)
