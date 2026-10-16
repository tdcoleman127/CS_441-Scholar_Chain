// Meta-meta build: compile the version ledger (project/Versions.scala) here as well, so that
// project/plugins.sbt pins plugin versions from the same single ledger that build.sbt uses.
// Only that one file is added; other project/*.scala files may need plugin classes that do not
// exist at this level.
Compile / unmanagedSources += baseDirectory.value.getParentFile / "Versions.scala"
