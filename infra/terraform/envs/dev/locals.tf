# Naming contract for the dev environment. Every name here is derived from code that reads it;
# part A IAM policies are built from these values, so part B must create resources with exactly
# these names (see the handoff list in the phase 12 part A report).

locals {
  project     = "scholarchain"
  environment = var.environment
  name_prefix = "${local.project}-${local.environment}"

  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = var.region

  required_tags = {
    "scholarchain:project"     = local.project
    "scholarchain:environment" = local.environment # run-bounded-pipeline.sh ENV_TAG_KEY
    "scholarchain:managed-by"  = "terraform"
    "scholarchain:owner"       = "infra-engineer"
    "scholarchain:cost-center" = "cs441-course"
  }
  cost_allocation_tag_keys = ["scholarchain:project", "scholarchain:environment", "scholarchain:cost-center"]

  # S3 buckets (core ConfigLoader: SCHOLARCHAIN_RAW_BUCKET, SCHOLARCHAIN_CURATED_BUCKET;
  # run-bounded-pipeline.sh: SCHOLARCHAIN_SAMPLE_BUCKET, SCHOLARCHAIN_HADOOP_JAR_URI).
  bucket_names = {
    raw       = "${local.name_prefix}-raw-${local.account_id}"
    samples   = "${local.name_prefix}-samples-${local.account_id}"
    curated   = "${local.name_prefix}-curated-${local.account_id}"
    artifacts = "${local.name_prefix}-artifacts-${local.account_id}"
    logs      = "${local.name_prefix}-logs-${local.account_id}"
  }

  # storage.run-manifest-prefix (SCHOLARCHAIN_RUN_MANIFEST_PREFIX), inside the curated bucket.
  run_manifest_prefix = "run-manifests"

  # DynamoDB: physical name <prefix>-<logical> (modules/storage kv/KeyValueStore.scala Table enum).
  dynamodb_table_prefix = local.name_prefix # SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX
  dynamodb_tables       = ["run-ledger", "entity-link-cache", "query-session", "evidence-bundle", "artifact-registry"]

  # S3 Vectors: physical index name <vector-index>-<wire> (modules/storage vectors/VectorStore.scala).
  vector_bucket_name  = "${local.name_prefix}-vectors" # SCHOLARCHAIN_VECTOR_BUCKET
  vector_index_prefix = local.name_prefix              # SCHOLARCHAIN_VECTOR_INDEX
  vector_index_wires = [
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ]
  vector_index_names = [for w in local.vector_index_wires : "${local.vector_index_prefix}-${w}"]

  # Runtime compute (decision D11: one API service, one worker service, one image).
  ecr_repository_name = "${local.name_prefix}-services"
  ecs_cluster_name    = local.name_prefix
  ecs_service_names = {
    api    = "${local.name_prefix}-api"
    worker = "${local.name_prefix}-worker"
  }
  ecs_task_families = {
    api                 = "${local.name_prefix}-api"
    worker              = "${local.name_prefix}-worker"
    embedding_ingestion = "${local.name_prefix}-embedding-ingestion"
  }

  log_group_prefix = "/${local.project}/${local.environment}"
  log_groups = {
    api                 = "${local.log_group_prefix}/ecs/api"
    worker              = "${local.log_group_prefix}/ecs/worker"
    embedding_ingestion = "${local.log_group_prefix}/ecs/embedding-ingestion"
    pipeline            = "${local.log_group_prefix}/states/pipeline"
  }

  sqs_jobs_queue_name = "${local.name_prefix}-jobs"
  sqs_jobs_dlq_name   = "${local.name_prefix}-jobs-dlq"
  state_machine_name  = "${local.name_prefix}-pipeline"

  neptune_cluster_identifier = "${local.name_prefix}-graph"

  # Reserved CloudWatch namespace for S3 Vectors client and LLM usage metrics. No code emits
  # CloudWatch metrics yet (services exposes Prometheus counters at GET /metrics only).
  custom_metric_namespace = "ScholarChain/${local.environment}"

  # Secrets Manager: API-key digests for SCHOLARCHAIN_API_KEYS (modules/services security/Auth.scala).
  api_key_secret_name = "${local.project}/${local.environment}/api-key-digests"

  kms_deletion_window_days    = var.data_cleanup_mode ? 7 : var.kms_deletion_window_days
  secret_recovery_window_days = var.data_cleanup_mode ? 7 : 30
}
