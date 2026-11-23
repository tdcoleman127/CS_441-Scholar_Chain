# ScholarChain dev environment, grouped by concern. Part A: foundation (cost), network, identity
# and encryption, data lake. Part B: DynamoDB, S3 Vectors, Neptune (enable_neptune), runtime
# compute (enable_runtime for the ALB and services), workflow (Step Functions with the transient
# EMR cluster, SQS), and observability. Every name comes from locals.tf.

# ---- Foundation: cost and safety -----------------------------------------------------------------

module "budget" {
  source = "../../modules/budget"

  name_prefix                   = local.name_prefix
  limit_usd                     = var.budget_limit_usd
  alert_thresholds_usd          = [10, 15, 20]
  budget_start_date             = var.budget_start_date
  notification_emails           = var.budget_notification_emails
  cost_allocation_tag_keys      = local.cost_allocation_tag_keys
  activate_cost_allocation_tags = var.activate_cost_allocation_tags
}

# ---- Network -------------------------------------------------------------------------------------

module "network" {
  source = "../../modules/network"

  name_prefix                 = local.name_prefix
  region                      = local.region
  vpc_cidr                    = var.vpc_cidr
  availability_zones          = var.availability_zones
  alb_ingress_cidrs           = var.alb_ingress_cidrs
  service_port                = var.service_port
  interface_endpoints         = var.interface_endpoints
  interface_endpoint_az_count = var.interface_endpoint_az_count
  flow_log_bucket_arn         = module.data_lake.bucket_arns["logs"]
  flow_log_traffic_type       = var.flow_log_traffic_type

  # The logs-bucket policy must grant flow-log delivery first; otherwise the delivery service
  # rewrites the bucket policy itself (drift).
  depends_on = [module.data_lake]
}

# ---- Identity and encryption ---------------------------------------------------------------------

module "kms" {
  source = "../../modules/kms"

  name_prefix             = local.name_prefix
  account_id              = local.account_id
  region                  = local.region
  partition               = local.partition
  log_group_prefix        = local.log_group_prefix
  vector_bucket_name      = local.vector_bucket_name
  deletion_window_in_days = local.kms_deletion_window_days
}

# Secret container only. Terraform never holds the value: the human stores the API-key digests
# with the AWS CLI under the admin profile, outside Claude. ECS references the secret by ARN.
resource "aws_secretsmanager_secret" "api_key_digests" {
  #checkov:skip=CKV2_AWS_57:The value is a list of SHA-256 key digests rotated by the human; no Lambda rotation in dev.
  name                    = local.api_key_secret_name
  description             = "SHA-256 digests of ScholarChain API keys (SCHOLARCHAIN_API_KEYS). Value set by the human, never by Terraform."
  kms_key_id              = module.kms.key_arn
  recovery_window_in_days = local.secret_recovery_window_days
}

module "iam" {
  source = "../../modules/iam"

  name_prefix = local.name_prefix
  environment = local.environment
  account_id  = local.account_id
  region      = local.region
  partition   = local.partition
  kms_key_arn = module.kms.key_arn
  bucket_arns = module.data_lake.bucket_arns

  names = {
    dynamodb_table_prefix = local.dynamodb_table_prefix
    dynamodb_tables       = local.dynamodb_tables
    vector_bucket_name    = local.vector_bucket_name
    vector_index_names    = local.vector_index_names
    ecr_repository_name   = local.ecr_repository_name
    ecs_cluster_name      = local.ecs_cluster_name
    ecs_service_names     = local.ecs_service_names
    ecs_task_families     = local.ecs_task_families
    log_groups            = local.log_groups
    sqs_jobs_queue_name   = local.sqs_jobs_queue_name
    sqs_jobs_dlq_name     = local.sqs_jobs_dlq_name
    state_machine_name    = local.state_machine_name
    run_manifest_prefix   = local.run_manifest_prefix
  }

  api_key_secret_arn      = aws_secretsmanager_secret.api_key_digests.arn
  generation_model_id     = var.generation_model_id
  embedding_model_id      = var.embedding_model_id
  deployer_principal_arns = var.deployer_principal_arns

  pipeline_runner_principal_arns = var.pipeline_runner_principal_arns
}

# ---- Data lake -----------------------------------------------------------------------------------

module "data_lake" {
  source = "../../modules/data_lake"

  bucket_names                      = local.bucket_names
  account_id                        = local.account_id
  region                            = local.region
  partition                         = local.partition
  elb_service_account_arn           = data.aws_elb_service_account.current.arn
  kms_key_arn                       = module.kms.key_arn
  data_cleanup_mode                 = var.data_cleanup_mode
  noncurrent_version_retention_days = var.noncurrent_version_retention_days
  log_retention_days                = var.log_retention_days
}

data "aws_elb_service_account" "current" {}

# ---- DynamoDB ------------------------------------------------------------------------------------

module "dynamodb" {
  source = "../../modules/dynamodb"

  table_prefix      = local.dynamodb_table_prefix
  tables            = local.dynamodb_tables
  kms_key_arn       = module.kms.key_arn
  data_cleanup_mode = var.data_cleanup_mode
}

# ---- S3 Vectors ----------------------------------------------------------------------------------

module "vectors" {
  source = "../../modules/vectors"

  vector_bucket_name = local.vector_bucket_name
  index_names        = local.vector_index_names
  dimension          = var.embedding_dimension
  kms_key_arn        = module.kms.key_arn
  data_cleanup_mode  = var.data_cleanup_mode
}

# ---- Neptune Serverless (enable_neptune) ---------------------------------------------------------

module "neptune" {
  source = "../../modules/neptune"
  count  = var.enable_neptune ? 1 : 0

  cluster_identifier     = local.neptune_cluster_identifier
  engine_version         = var.neptune_engine_version
  parameter_group_family = var.neptune_parameter_group_family
  min_capacity           = var.neptune_min_capacity
  max_capacity           = var.neptune_max_capacity
  subnet_ids             = module.network.data_subnet_ids
  security_group_id      = module.network.security_group_ids["neptune"]
  kms_key_arn            = module.kms.key_arn
  loader_role_arn        = module.iam.role_arns["neptune_loader"]
  reader_role_names = {
    api_task       = module.iam.role_names["api_task"]
    worker_task    = module.iam.role_names["worker_task"]
    runtime_reader = module.iam.role_names["runtime_reader"]
  }
  deletion_protection   = var.neptune_deletion_protection
  skip_final_snapshot   = var.neptune_skip_final_snapshot
  backup_retention_days = var.neptune_backup_retention_days
  log_retention_days    = var.cloudwatch_log_retention_days
  account_id            = local.account_id
  region                = local.region
  partition             = local.partition
}

# ---- Runtime compute -----------------------------------------------------------------------------

locals {
  # Every key below is read by core ConfigLoader.EnvBindings or services Main.scala. Values are
  # names and IDs only; SCHOLARCHAIN_API_KEYS comes from Secrets Manager by ARN (modules/runtime).
  container_environment = merge(
    {
      SCHOLARCHAIN_RUNTIME_MODE          = "aws"
      SCHOLARCHAIN_RELEASE_RUN_ID        = var.release_run_id
      SCHOLARCHAIN_PROFILE               = local.environment
      SCHOLARCHAIN_AWS_REGION            = local.region
      SCHOLARCHAIN_AWS_ACCOUNT_ID        = local.account_id
      SCHOLARCHAIN_RAW_BUCKET            = module.data_lake.bucket_names["raw"]
      SCHOLARCHAIN_CURATED_BUCKET        = module.data_lake.bucket_names["curated"]
      SCHOLARCHAIN_VECTOR_BUCKET         = local.vector_bucket_name
      SCHOLARCHAIN_VECTOR_INDEX          = local.vector_index_prefix
      SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX = local.dynamodb_table_prefix
      SCHOLARCHAIN_RUN_MANIFEST_PREFIX   = local.run_manifest_prefix
      SCHOLARCHAIN_EMBEDDING_MODEL_ID    = var.embedding_model_id
      SCHOLARCHAIN_GENERATION_MODEL_ID   = var.generation_model_id
      # No Neptune adapter reads this yet (services uses UnavailableGraphStore). When Neptune is
      # off, a non-placeholder marker keeps core placeholderProblems empty.
      SCHOLARCHAIN_NEPTUNE_ENDPOINT = var.enable_neptune ? module.neptune[0].reader_endpoint : "neptune-not-provisioned"
    },
    var.runtime_algorithm_env
  )
}

module "runtime" {
  source = "../../modules/runtime"

  name_prefix         = local.name_prefix
  region              = local.region
  account_id          = local.account_id
  kms_key_arn         = module.kms.key_arn
  data_cleanup_mode   = var.data_cleanup_mode
  ecr_repository_name = local.ecr_repository_name
  ecs_cluster_name    = local.ecs_cluster_name
  ecs_service_names   = local.ecs_service_names
  ecs_task_families   = local.ecs_task_families
  log_groups = {
    api                 = local.log_groups["api"]
    worker              = local.log_groups["worker"]
    embedding_ingestion = local.log_groups["embedding_ingestion"]
  }
  log_retention_days    = var.cloudwatch_log_retention_days
  role_arns             = module.iam.role_arns
  image_tag             = var.image_tag
  container_environment = local.container_environment
  api_key_secret_arn    = aws_secretsmanager_secret.api_key_digests.arn
  service_port          = var.service_port
  task_sizes = {
    api                 = { cpu = 512, memory = 1024 }
    worker              = { cpu = 512, memory = 1024 }
    embedding_ingestion = { cpu = 512, memory = 1024 }
  }
  embedding_ingestion_command = var.embedding_ingestion_command
  enable_runtime              = var.enable_runtime
  alb_deletion_protection     = var.alb_deletion_protection
  api_desired_count           = var.api_desired_count
  worker_desired_count        = var.worker_desired_count
  health_check_path           = var.health_check_path
  vpc_id                      = module.network.vpc_id
  public_subnet_ids           = module.network.public_subnet_ids
  app_subnet_ids              = module.network.app_subnet_ids
  security_group_ids          = module.network.security_group_ids
  logs_bucket_name            = module.data_lake.bucket_names["logs"]

  # The ALB checks write access to the logs bucket when it enables access logs.
  depends_on = [module.data_lake]
}

# ---- Workflow: Step Functions pipeline, transient EMR, SQS ---------------------------------------

module "workflow" {
  source = "../../modules/workflow"

  name_prefix                 = local.name_prefix
  environment                 = local.environment
  kms_key_arn                 = module.kms.key_arn
  log_retention_days          = var.cloudwatch_log_retention_days
  required_tags               = local.required_tags
  sqs_jobs_queue_name         = local.sqs_jobs_queue_name
  sqs_jobs_dlq_name           = local.sqs_jobs_dlq_name
  state_machine_name          = local.state_machine_name
  pipeline_log_group_name     = local.log_groups["pipeline"]
  step_functions_role_arn     = module.iam.role_arns["step_functions"]
  emr_service_role_name       = module.iam.emr_service_role_name
  emr_instance_profile_name   = module.iam.emr_instance_profile_name
  emr_release_label           = var.emr_release_label
  emr_log_uri                 = "s3://${module.data_lake.bucket_names["logs"]}/logs/emr/"
  emr_subnet_id               = module.network.data_subnet_ids[0]
  emr_security_group_ids      = module.network.security_group_ids
  emr_instance_type           = var.emr_instance_type
  emr_core_instance_count     = var.emr_core_instance_count
  emr_core_max_instance_count = var.emr_core_max_instance_count
  emr_idle_timeout_seconds    = var.emr_idle_timeout_seconds
  emr_max_steps               = var.emr_max_steps
  emr_step_timeout_seconds    = var.emr_step_timeout_seconds
  ecs_cluster_arn             = module.runtime.ecs_cluster_arn
  embedding_ingestion_family  = local.ecs_task_families["embedding_ingestion"]
  embedding_ingestion_enabled = length(var.embedding_ingestion_command) > 0 && var.image_tag != ""
  ingestion_subnet_ids        = module.network.app_subnet_ids
  ingestion_security_group_id = module.network.security_group_ids["worker"]

  # The roles and the instance profile must exist before the state machine can pass them.
  depends_on = [module.iam]
}

# ---- Observability -------------------------------------------------------------------------------

module "observability" {
  source = "../../modules/observability"

  name_prefix                = local.name_prefix
  region                     = local.region
  custom_metric_namespace    = local.custom_metric_namespace
  state_machine_arn          = module.workflow.state_machine_arn
  jobs_queue_name            = module.workflow.jobs_queue_name
  jobs_dlq_name              = module.workflow.jobs_dlq_name
  dynamodb_table_names       = [for t in local.dynamodb_tables : "${local.dynamodb_table_prefix}-${t}"]
  ecs_cluster_name           = local.ecs_cluster_name
  ecs_service_names          = local.ecs_service_names
  enable_runtime             = var.enable_runtime
  alb_arn_suffix             = coalesce(module.runtime.alb_arn_suffix, "none")
  target_group_arn_suffix    = coalesce(module.runtime.target_group_arn_suffix, "none")
  enable_neptune             = var.enable_neptune
  neptune_cluster_identifier = local.neptune_cluster_identifier
  neptune_max_capacity       = var.neptune_max_capacity
}
