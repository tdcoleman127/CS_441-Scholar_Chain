Use the spark-engineer agent to implement the ScholarChain Spark collaboration-analysis pipeline.

Required jobs:

1. Canonical researcher and authorship builder
- Read accepted researcher and work entity links plus Hadoop author-paper outputs.
- Construct canonical Researcher, Work, AUTHORED, Topic, Institution, and Venue tables.
- Preserve unresolved author mentions without forcing a merge.
- Validate that every canonical authorship edge points to existing nodes.

2. Temporal weighted coauthorship graph builder
- Map Hadoop pair candidates onto canonical researcher IDs.
- Drop self-pairs created by identity resolution.
- Canonicalize pair order so each logical undirected pair has one identity.
- Aggregate duplicate pairs.
- Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and supporting work IDs or a stable pointer to them.
- Support configurable year windows.

3. Researcher collaboration breadth and team-size statistics
For each researcher and time window compute:
- publicationCount P_r
- distinctCoauthorCount C_r
- totalCoauthorOccurrences T_r
- averageCoauthorsPerPaper A_r
- maximumCoauthorsOnOnePaper M_r
- weightedDegree W_r
- the work IDs responsible for M_r
Keep every component visible. Do not replace these measures with one opaque collaboration score.

4. Connected components
- Compute connected components of the researcher coauthorship graph.
- Store component ID and component size.
- Report the fraction of researchers in the largest component and the number of singleton researchers when applicable.

5. Researcher connectivity and path support
- Provide a scalable method for shortest paths between selected researcher pairs.
- Materialize or cache bounded path evidence for benchmark queries.
- Where feasible, estimate component-level average path length or effective diameter using sampling or another documented scalable method.
- Never compute all-pairs shortest paths by collecting the graph on the driver.

6. Collaboration communities
- Compute a documented community method appropriate to the selected Spark stack, such as label propagation or another justified algorithm.
- Store community membership, community size, and cross-community edges.

7. Bridge analysis
- Compute at least one documented bridge measure such as exact or approximate betweenness centrality, cross-community degree, or participation coefficient.
- Record algorithm parameters and approximation strategy when exact computation is impractical.
- Report the number of distinct communities directly connected by each selected researcher when derivable.

8. K-core or equivalent dense-subgraph measure
- Compute coreness or a justified alternative that captures whether a researcher is embedded in a dense collaboration structure.
- Test against small hand-checkable graphs.

9. Temporal collaboration analysis
- Recompute selected metrics over configured time windows or yearly snapshots.
- Measure changes in distinct coauthors, team sizes, component membership, community membership, and bridge behavior.
- Do not compare windows with incompatible inclusion rules without stating the limitation.

10. Semantic-context preparation
- Produce researcher-profile summaries, community summaries, collaboration-path summaries, and selected paper or topic summaries for later embedding.
- Summaries must be generated from computed data and source records, not invented metrics.

11. Export
- Write partitioned Parquet node, edge, metric, community, component, path-evidence, and semantic-summary tables.
- Produce load manifests for Neptune, DynamoDB, S3 Vectors, and ordinary S3.

Operational requirements:
- Explain partitioning and shuffle boundaries.
- Avoid collecting the full graph on the driver.
- Configure checkpointing where iterative algorithms require it.
- Emit counts and invariant failures.
- Make every run reproducible from a run manifest.

Run sbt check, a local or small-cluster Spark integration test, and a bounded EMR Spark run.
Report stage DAGs, partition counts, shuffle metrics, graph counts, component counts, community counts, metric distributions, path-test results, temporal windows, rejected integrity violations, and output paths.