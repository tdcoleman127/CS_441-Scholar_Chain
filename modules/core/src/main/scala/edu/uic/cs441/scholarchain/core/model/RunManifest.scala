package edu.uic.cs441.scholarchain.core.model

import upickle.default.ReadWriter

/** One immutable input of a pipeline run: a dataset snapshot and its checksum. */
final case class RunInput(sourceDataset: SourceType, snapshotId: String, checksum: String)
    derives ReadWriter

/**
 * The immutable manifest stored with every pipeline run. Its `runId` is derived from the other
 * fields (see ids.StableIds.runId), so it is deterministic and contains no timestamp. A rerun of
 * identical inputs, code, and configuration increments `attempt` to obtain a distinct run ID.
 *
 * The manifest is the root of provenance: it carries no `provenance` field itself, and every
 * record's `provenance.pipelineRunId` points at one of these. Wall-clock times, if recorded, belong
 * in a separate run log, never in the manifest or the ID.
 */
final case class RunManifest(
    schemaVersion: Int,
    runId: String,
    pipelineName: String,
    codeVersion: String,
    configFingerprint: String,
    inputs: List[RunInput],
    attempt: Int
) derives ReadWriter
