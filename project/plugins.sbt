// sbt plugins. Versions come only from the ledger (project/Versions.scala), which the meta-meta
// build compiles (project/project/ledger.sbt).

// Formatting: scalafmt, scalafmtCheckAll, scalafmtSbtCheck. Used by the fmt and check aliases.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % Versions.sbtScalafmt)

// Packaging: self-contained job jars for EMR (hadoop, sparkAnalytics) and the services jar that
// the container image runs. Provided dependencies are excluded from every assembly.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % Versions.sbtAssembly)
