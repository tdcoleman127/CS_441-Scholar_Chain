# Non-secret outputs. The human exports these as the SCHOLARCHAIN_* environment variables the code
# and scripts read. No secret value is ever an output.

output "account_id" {
  description = "Account the plan ran against (from aws_caller_identity)."
  value       = local.account_id
}

output "scholarchain_env" {
  description = "Values for the SCHOLARCHAIN_* configuration keys known after part A."
  value = {
    SCHOLARCHAIN_AWS_REGION            = local.region
    SCHOLARCHAIN_RAW_BUCKET            = module.data_lake.bucket_names["raw"]
    SCHOLARCHAIN_SAMPLE_BUCKET         = module.data_lake.bucket_names["samples"]
    SCHOLARCHAIN_CURATED_BUCKET        = module.data_lake.bucket_names["curated"]
    SCHOLARCHAIN_VECTOR_BUCKET         = local.vector_bucket_name
    SCHOLARCHAIN_VECTOR_INDEX          = local.vector_index_prefix
    SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX = local.dynamodb_table_prefix
    SCHOLARCHAIN_RUN_MANIFEST_PREFIX   = local.run_manifest_prefix
    SCHOLARCHAIN_EMBEDDING_MODEL_ID    = var.embedding_model_id
    SCHOLARCHAIN_GENERATION_MODEL_ID   = var.generation_model_id
    SCHOLARCHAIN_NEPTUNE_ENDPOINT      = var.enable_neptune ? module.neptune[0].reader_endpoint : null
  }
}

# ---- Part B: names and ARNs the operational scripts read (part C) -------------------------------

output "pipeline" {
  description = "scripts/run-bounded-pipeline.sh: start an execution of this state machine with the documented input."
  value = {
    state_machine_arn  = module.workflow.state_machine_arn
    state_machine_name = local.state_machine_name
    log_group          = local.log_groups["pipeline"]
    emr_release_label  = var.emr_release_label
    emr_log_uri        = "s3://${module.data_lake.bucket_names["logs"]}/logs/emr/"
    emr_max_steps      = var.emr_max_steps
    emr_step_timeout_s = var.emr_step_timeout_seconds
    env_tag_key        = "scholarchain:environment"
    env_tag_value      = local.environment
  }
}

output "runtime" {
  description = "scripts/deploy.sh, rollback.sh, smoke-test.sh."
  value = {
    ecr_repository_url  = module.runtime.ecr_repository_url
    ecr_repository_name = local.ecr_repository_name
    ecs_cluster_name    = module.runtime.ecs_cluster_name
    ecs_service_names   = local.ecs_service_names
    ecs_services_exist  = var.enable_runtime
    ecs_task_families   = local.ecs_task_families
    task_definitions    = module.runtime.task_definition_arns # deploy.sh base revisions (R1-F3)
    alb_dns_name        = module.runtime.alb_dns_name
    alb_base_url        = module.runtime.alb_dns_name == null ? null : "http://${module.runtime.alb_dns_name}"
    health_check_path   = var.health_check_path
    image_tag           = var.image_tag
  }
}

output "messaging" {
  value = {
    jobs_queue_url = module.workflow.jobs_queue_url
    jobs_queue_arn = module.workflow.jobs_queue_arn
    jobs_dlq_name  = module.workflow.jobs_dlq_name
  }
}

output "data_stores" {
  value = {
    dynamodb_tables     = module.dynamodb.table_names
    vector_bucket_arn   = module.vectors.vector_bucket_arn
    vector_index_arns   = module.vectors.index_arns
    neptune_cluster_arn = var.enable_neptune ? module.neptune[0].cluster_arn : null
    neptune_resource_id = var.enable_neptune ? module.neptune[0].cluster_resource_id : null
  }
}

output "observability" {
  value = {
    dashboard_name          = module.observability.dashboard_name
    custom_metric_namespace = module.observability.custom_metric_namespace
  }
}

output "bucket_names" {
  value = module.data_lake.bucket_names
}

output "artifacts_bucket" {
  description = "Upload jars to s3://<this>/artifacts/hadoop/<git sha>/ (SCHOLARCHAIN_HADOOP_JAR_URI)."
  value       = module.data_lake.bucket_names["artifacts"]
}

output "kms_key_arn" {
  value = module.kms.key_arn
}

output "api_key_secret_arn" {
  description = "ARN only. The value is set by the human outside Terraform."
  value       = aws_secretsmanager_secret.api_key_digests.arn
}

output "role_arns" {
  value = module.iam.role_arns
}

output "pipeline_runner_role_arn" {
  description = "Assume this role to run scripts/run-bounded-pipeline.sh (R1-F5). null until pipeline_runner_principal_arns is set."
  value       = module.iam.pipeline_runner_role_arn
}

output "network" {
  value = {
    vpc_id             = module.network.vpc_id
    public_subnet_ids  = module.network.public_subnet_ids
    app_subnet_ids     = module.network.app_subnet_ids
    data_subnet_ids    = module.network.data_subnet_ids
    security_group_ids = module.network.security_group_ids
  }
}
