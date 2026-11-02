// ScholarChain sbt multi-project build. Owner: build-engineer.
//
// Policy encoded here:
// - Scala 3 everywhere (Versions.scala3, the 3.3 LTS line).
// - Every version comes from the single ledger, project/Versions.scala. No literals below.
// - Module dependency boundaries follow the CLAUDE.md table; dependsOn is the only wiring, acyclic.
// - Hadoop and Spark are Provided (EMR supplies them); test libraries are Test; the logging
//   implementation is Runtime.
// - One JSON library (uPickle) and one test framework (MUnit) for the whole codebase.
// - The build reads no environment variables, holds no secrets, endpoints, account IDs, or
//   deployment steps. Container images are built only for local use (dockerLocal).
//
// COMPATIBILITY (Scala 3 versus EMR Spark): Amazon EMR 7.x ships Spark 3.5.x built for Scala 2.12
// (EMR 7.14.0: Scala 2.12.18). Scala 3 code needs the Scala 2.13 standard library. sparkAnalytics
// compiles and tests locally against Spark's _2.13 artifacts (CrossVersion.for3Use2_13), but its
// assembly cannot be assumed to run on EMR's Scala 2.12 Spark. This is escalated to the human
// in the phase-02 report; do not paper over it here.
//
// Aliases:
//   sbt fmt          format every .scala and .sbt file
//   sbt check        definition of done: format check, compile, Test/compile, all unit tests,
//                    the AWS-free contract tests (tests/contracts), then markTestRun
//   sbt packageJobs  EMR job jars (hadoop, sparkAnalytics); run after sbt check
//   sbt dockerLocal  local services image; needs a running Docker engine; run after sbt check

// ---- Build-wide settings ----------------------------------------------------------------------

ThisBuild / organization := "edu.uic.cs441.scholarchain"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := Versions.scala3
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-release",
  Versions.javaRelease
)
ThisBuild / javacOptions ++= Seq("--release", Versions.javaRelease)

// ---- Dependencies (versions only from Versions; scope is decided here) --------------------------

// The single JSON library and the configuration library allowed in core.
val upickle        = "com.lihaoyi" %% "upickle" % Versions.upickle
val typesafeConfig = "com.typesafe" % "config"  % Versions.typesafeConfig

// The single test framework. Always Test scope.
val munit = "org.scalameta" %% "munit" % Versions.munit % Test

// Hadoop: pure Java client APIs (shaded), supplied by EMR at runtime.
val hadoopPlatform = Seq(
  "org.apache.hadoop" % "hadoop-client-api"     % Versions.hadoop,
  "org.apache.hadoop" % "hadoop-client-runtime" % Versions.hadoop
)

// Spark: there are no Scala 3 artifacts, so use the Scala 2.13 build. Spark code should use the
// DataFrame/Dataset Column API and Java-friendly entry points rather than Scala-collection-typed or
// implicit-Encoder-derived APIs, to keep Scala binary assumptions at the boundary small.
val sparkPlatform = Seq(
  ("org.apache.spark" %% "spark-sql" % Versions.spark).cross(CrossVersion.for3Use2_13)
)

// AWS SDK for Java v2 (pure Java, one shared ledger value, Compile scope: the services image needs
// these at runtime and EMR does not supply them to that image). Each service artifact inherits two
// HTTP client implementations at runtime scope from the SDK's `services` parent POM (2.57.8:
// apache5-client with httpclient5, and netty-nio-client with Netty; older SDK lines inherited
// apache-client instead of apache5-client). All three are excluded and the single synchronous
// url-connection-client (JDK HttpURLConnection, no further transitive dependencies) is declared
// instead, so exactly one implementation is on the classpath and the SDK selects it without
// configuration. Async clients (for example Bedrock streaming) would need netty-nio-client; add
// it only on request.
def awsSdkModule(artifact: String): ModuleID =
  ("software.amazon.awssdk" % artifact % Versions.awsSdk)
    .exclude("software.amazon.awssdk", "apache5-client")
    .exclude("software.amazon.awssdk", "apache-client")
    .exclude("software.amazon.awssdk", "netty-nio-client")
val awsHttpClient = awsSdkModule("url-connection-client")

// storage (WO-00): object storage, key-value tables with conditional writes, and vector storage.
// Neptune needs no client here: the fast-track bulk-load path writes CSV files and manifests to S3
// (the S3 client) and openCypher templates are plain strings. A Neptune data-plane client
// (neptunedata) is added only when storage-engineer requests live query execution.
val awsStorageClients = Seq(
  awsSdkModule("s3"),
  awsSdkModule("dynamodb"),
  awsSdkModule("s3vectors"),
  awsHttpClient
)

// rag (WO-00): the model client for embeddings and synthesis (Bedrock Runtime, synchronous
// InvokeModel/Converse). Declared only in rag. services reaches it only through dependsOn(rag).
val awsModelClient = awsSdkModule("bedrockruntime")

// Logging: API at compile scope, exactly one implementation at Runtime scope (services only).
// Job jars carry no logging implementation; EMR supplies its own.
val slf4jApi       = "org.slf4j"      % "slf4j-api"       % Versions.slf4j
val logbackRuntime = "ch.qos.logback" % "logback-classic" % Versions.logback % Runtime

// JDK 17 module opens that Spark 3.5 (and Hadoop on JDK 17) need in forked local test JVMs.
// Mirrors Spark's own launcher defaults (org.apache.spark.launcher.JavaModuleOptions).
val jdk17ModuleOpens = Seq(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "-Djdk.reflect.useDirectMethodHandle=false"
)

// ---- Shared project settings ------------------------------------------------------------------

lazy val commonSettings = Seq(
  libraryDependencies += munit
)

// Forked test JVM for modules that start Hadoop or Spark locally.
lazy val platformTestSettings = Seq(
  Test / fork              := true,
  Test / parallelExecution := false,
  Test / javaOptions ++= jdk17ModuleOpens
)

// Self-contained jar. Provided dependencies are excluded; the Scala 3 and Scala 2.13 standard
// libraries are included because no runtime supplies them.
def assemblySettings(jarBaseName: String) = Seq(
  assembly / assemblyJarName       := s"$jarBaseName-${version.value}.jar",
  assembly / assemblyMergeStrategy := {
    case PathList("module-info.class")                            => MergeStrategy.discard
    case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
    case other                                                    =>
      val inherited = (assembly / assemblyMergeStrategy).value
      inherited(other)
  }
)

// ---- Modules (dependsOn mirrors the CLAUDE.md boundary table) -----------------------------------

// core: Scala standard library, the JSON library, the configuration library. No other module.
lazy val core = (project in file("modules/core"))
  .settings(
    commonSettings,
    name := "scholarchain-core",
    libraryDependencies ++= Seq(upickle, typesafeConfig)
  )

// hadoop: core plus Hadoop APIs (Provided). No Spark.
lazy val hadoop = (project in file("modules/hadoop"))
  .dependsOn(core)
  .settings(
    commonSettings,
    platformTestSettings,
    assemblySettings("scholarchain-hadoop-jobs"),
    name := "scholarchain-hadoop",
    libraryDependencies ++= hadoopPlatform.map(_ % Provided)
  )

// entity-resolution: core only.
lazy val entityResolution = (project in file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "scholarchain-entity-resolution"
  )

// spark: core and entity-resolution plus Spark APIs (Provided). No hadoop job classes.
// Hadoop client APIs are Provided and pinned to EMR's Hadoop, which replaces Spark's default.
lazy val sparkAnalytics = (project in file("modules/spark"))
  .dependsOn(core, entityResolution)
  .settings(
    commonSettings,
    platformTestSettings,
    assemblySettings("scholarchain-spark-jobs"),
    name := "scholarchain-spark",
    libraryDependencies ++= (sparkPlatform ++ hadoopPlatform).map(_ % Provided)
  )

// storage: core plus AWS SDK store clients (S3, DynamoDB, S3 Vectors) and one SDK HTTP client.
lazy val storage = (project in file("modules/storage"))
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "scholarchain-storage",
    libraryDependencies ++= awsStorageClients
  )

// rag: core and storage ports plus the model client (Bedrock Runtime). No HTTP server library;
// the SDK HTTP client it inherits from storage is a client, not a server.
lazy val rag = (project in file("modules/rag"))
  .dependsOn(core, storage)
  .settings(
    commonSettings,
    name := "scholarchain-rag",
    libraryDependencies += awsModelClient
  )

// services: core, storage, rag plus an HTTP server library (added on service-engineer's
// request). The only module with a logging implementation, at Runtime scope.
lazy val services = (project in file("modules/services"))
  .dependsOn(core, storage, rag)
  .settings(
    commonSettings,
    assemblySettings("scholarchain-services"),
    name := "scholarchain-services",
    libraryDependencies ++= Seq(slf4jApi, logbackRuntime),
    dockerLocalBuild := {
      val log        = streams.value.log
      val jar        = assembly.value
      val dockerfile = (ThisBuild / baseDirectory).value / "docker" / "services" / "Dockerfile"
      val context    = target.value / "docker-context"
      IO.delete(context)
      IO.createDirectory(context)
      IO.copyFile(jar, context / "services.jar")
      IO.copyFile(dockerfile, context / "Dockerfile")
      val image   = s"scholarchain-services:${version.value}"
      val command = Seq(
        "docker",
        "build",
        "--build-arg",
        s"JAVA_RUNTIME_IMAGE=${Versions.javaRuntimeImage}",
        "--tag",
        image,
        context.getAbsolutePath
      )
      log.info(s"Building local image $image")
      val exit = scala.sys.process
        .Process(command)
        .!(scala.sys.process.ProcessLogger(line => log.info(line), line => log.info(line)))
      if (exit != 0) sys.error(s"docker build exited with code $exit")
      image
    }
  )

// ---- Cross-module test trees (owned by test-engineer) --------------------------------------------

// Contract tests that need no AWS. Part of `sbt check` through the root aggregate. Tests that
// need AWS must not live here; they get a separate, non-aggregated project when requested.
// Hadoop and Spark are added at Test scope because Provided scope does not propagate through
// dependsOn.
lazy val contractTests = (project in file("tests/contracts"))
  .dependsOn(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)
  .settings(
    commonSettings,
    platformTestSettings,
    name           := "scholarchain-contract-tests",
    publish / skip := true,
    libraryDependencies ++= (sparkPlatform ++ hadoopPlatform).map(_ % Test)
  )

// ---- Root -------------------------------------------------------------------------------------

lazy val markTestRun = taskKey[File](
  "Writes .claude/.last-test-run after every aggregated test task (unit and contract) passes. " +
    "Reach it only through `sbt check`."
)
lazy val dockerLocalBuild = taskKey[String](
  "Builds the local services image from docker/services/Dockerfile and the services assembly."
)

lazy val root = (project in file("."))
  .aggregate(
    core,
    hadoop,
    entityResolution,
    sparkAnalytics,
    storage,
    rag,
    services,
    contractTests
  )
  .settings(
    name           := "scholarchain",
    publish / skip := true,
    // The marker is written only after the test task of every aggregated project succeeds. A
    // failing test fails the dependency, so the body never runs and the marker stays stale.
    markTestRun := {
      val _ = test
        .all(ScopeFilter(inAggregates(ThisProject, includeRoot = false), inConfigurations(Test)))
        .value
      val marker = (ThisBuild / baseDirectory).value / ".claude" / ".last-test-run"
      IO.write(marker, s"sbt check: all tests passed at ${java.time.Instant.now()}\n")
      streams.value.log.info(s"markTestRun: updated $marker")
      marker
    }
  )

addCommandAlias("fmt", "; scalafmtSbt ; scalafmtAll")
addCommandAlias(
  "check",
  "; scalafmtSbtCheck ; scalafmtCheckAll ; compile ; Test/compile ; markTestRun"
)
addCommandAlias("packageJobs", "; hadoop/assembly ; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "services/dockerLocalBuild")

// ---- Ledger consistency (fails the build at load time on drift) -----------------------------------

// Three files are read by external tools before any build code runs, so they repeat a ledger
// value. This check keeps them equal to project/Versions.scala.
Global / onLoad := {
  val previous = (Global / onLoad).value
  previous.andThen { state =>
    val base     = state.configuration.baseDirectory
    val problems = Seq.newBuilder[String]

    val runningSbt = state.configuration.provider.id.version
    if (runningSbt != Versions.sbt)
      problems +=
        s"project/build.properties runs sbt $runningSbt but the ledger pins ${Versions.sbt}"

    val fmtConf    = base / ".scalafmt.conf"
    val fmtVersion = """(?m)^\s*version\s*=\s*"?([^"\s]+)"?\s*$""".r
    val fmtPinned  =
      if (fmtConf.exists) fmtVersion.findFirstMatchIn(IO.read(fmtConf)).map(_.group(1)) else None
    if (!fmtPinned.contains(Versions.scalafmt))
      problems +=
        s".scalafmt.conf version is ${fmtPinned.getOrElse("missing")} but the ledger pins ${Versions.scalafmt}"

    val dockerfile  = base / "docker" / "services" / "Dockerfile"
    val expectedArg = s"ARG JAVA_RUNTIME_IMAGE=${Versions.javaRuntimeImage}"
    if (!dockerfile.exists || !IO.readLines(dockerfile).map(_.trim).contains(expectedArg))
      problems += s"docker/services/Dockerfile must declare exactly: $expectedArg"

    val found = problems.result()
    if (found.nonEmpty)
      sys.error(found.mkString("Version ledger drift (project/Versions.scala):\n  ", "\n  ", ""))
    state
  }
}
