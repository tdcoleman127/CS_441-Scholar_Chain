// ScholarChain version ledger.
//
// This object is the single source of every pinned version in the build: the sbt launcher version,
// the Scala compiler, the formatter, every sbt plugin, every library, and the container base image.
// No other build file may contain a version literal, except three files that external tools read
// before any Scala code runs: project/build.properties (sbt launcher), .scalafmt.conf (scalafmt),
// and docker/services/Dockerfile (the default base image for builds outside sbt). The build fails at
// load time if any of those three drifts from this ledger (see ledgerConsistencyCheck in build.sbt).
//
// This file is compiled twice: by the meta-meta build (project/project/ledger.sbt adds it), so that
// project/plugins.sbt can read plugin versions, and by the meta-build, so that build.sbt can read
// everything else. Keep it plain Scala 2.12 (the sbt 1.x meta-build language) with no imports.
//
// Rules: exact versions only; no ranges, no "latest", no SNAPSHOT, no dynamic revisions.
// Structure, new entries, and scopes: build-engineer. Version values: dependency-updater, once a
// ratified amendment records this path in docs/agents.md section 4.
//
// Evidence for the EMR-aligned values: Amazon EMR Release Guide, release 7.14.0 (latest 7.x,
// fetched 2026-10-15): hadoop-client 3.4.2-amzn-2, spark-client 3.5.8-amzn-0, Scala 2.12.18,
// default Java Amazon Corretto 17.
object Versions {

  // ---- Toolchain ----------------------------------------------------------------------------
  // sbt 1.x keeps the plugin ecosystem (sbt-scalafmt, sbt-assembly) on its stable line.
  // Must equal sbt.version in project/build.properties (checked at load).
  val sbt = "1.12.15"
  // Scala 3 LTS line. Its standard library is the Scala 2.13 library, which is what Spark's
  // _2.13 artifacts expect. Every Scala 3 library below is published with Scala 3.3.x TASTy, so
  // the 3.3 compiler can read it. Do not move to a non-LTS minor without rechecking every _3 POM.
  val scala3 = "3.3.8"
  // JVM bytecode target and the Java major version on EMR 7.x (Corretto 17) and in the image.
  val javaRelease = "17"
  // Must equal "version" in .scalafmt.conf (checked at load).
  val scalafmt = "3.11.5"

  // ---- sbt plugins (read by project/plugins.sbt) -------------------------------------------------
  val sbtScalafmt = "2.6.2"
  val sbtAssembly = "2.5.0"

  // ---- Runtime-supplied platform (Provided scope; EMR 7.14.0 ships these) ---------------------------
  // Pure Java APIs: no Scala binary assumptions. Matched to EMR hadoop-client 3.4.2-amzn-2.
  val hadoop = "3.4.2"
  // Matched to EMR spark-client 3.5.8-amzn-0. Compiled against the _2.13 artifacts with
  // CrossVersion.for3Use2_13. EMR's Spark is built for Scala 2.12: see COMPATIBILITY in build.sbt.
  val spark = "3.5.8"

  // ---- Libraries ----------------------------------------------------------------------------
  // The single JSON library for the whole codebase (built with Scala 3.3.7).
  val upickle = "4.4.3"
  // The configuration library allowed in core (pure Java, no Scala binary dependency).
  val typesafeConfig = "1.4.9"
  // Logging API (compile) and its one implementation (Runtime scope, services image only).
  // logback 1.5.38 is built and tested against slf4j-api 2.0.17.
  val slf4j   = "2.0.17"
  val logback = "1.5.38"
  // The single test framework for the whole codebase (built with Scala 3.3.8).
  val munit = "1.3.6"

  // ---- Container image ------------------------------------------------------------------------
  // Amazon Corretto 17 on Amazon Linux 2023, headless JRE, matching EMR 7.x's default Java.
  // Pinned by tag and by multi-architecture index digest (Docker Hub, observed 2026-10-15),
  // because Corretto tags are re-pushed. Must equal the ARG default in docker/services/Dockerfile.
  val javaRuntimeImage =
    "amazoncorretto:17.0.20-al2023-headless@sha256:f0360fa5e8b6bd5cf85443c1425705de9b55928068a8045f5093ed0b6f9661d9"
}
