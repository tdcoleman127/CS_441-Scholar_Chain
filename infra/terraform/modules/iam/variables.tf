variable "name_prefix" {
  description = "Resource name prefix, for example scholarchain-dev. Also the IAM path segment."
  type        = string
}

variable "environment" {
  type = string
}

variable "account_id" {
  type = string
}

variable "region" {
  type = string
}

variable "partition" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "bucket_arns" {
  description = "Bucket ARNs keyed by role: raw, samples, curated, artifacts, logs."
  type        = map(string)
}

variable "names" {
  description = "Deterministic names of resources that part B creates. IAM policies are built from these, so part B must use the same values (envs/dev/locals.tf)."
  type = object({
    dynamodb_table_prefix = string
    dynamodb_tables       = list(string)
    vector_bucket_name    = string
    vector_index_names    = list(string)
    ecr_repository_name   = string
    ecs_cluster_name      = string
    ecs_service_names     = map(string)
    ecs_task_families     = map(string)
    log_groups            = map(string)
    sqs_jobs_queue_name   = string
    sqs_jobs_dlq_name     = string
    state_machine_name    = string
    run_manifest_prefix   = string
  })
}

variable "api_key_secret_arn" {
  description = "Secrets Manager ARN holding the API-key digests (SCHOLARCHAIN_API_KEYS). Value is never in Terraform."
  type        = string
}

variable "generation_model_id" {
  type = string
}

variable "embedding_model_id" {
  type = string
}

variable "deployer_principal_arns" {
  description = "IAM principal ARNs allowed to assume the deployment role. Empty means the deployment role is not created."
  type        = list(string)
  default     = []
}

variable "pipeline_runner_principal_arns" {
  description = "IAM principal ARNs allowed to assume the pipeline-runner role (scripts/run-bounded-pipeline.sh). Empty means the role is not created."
  type        = list(string)
  default     = []
}
