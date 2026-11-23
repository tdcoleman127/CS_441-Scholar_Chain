variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "log_retention_days" {
  type = number
}

variable "required_tags" {
  description = "Tags passed to RunJobFlow. Must include scholarchain:environment (Step Functions role RequestTag condition)."
  type        = map(string)

  validation {
    condition     = contains(keys(var.required_tags), "scholarchain:environment")
    error_message = "required_tags must include scholarchain:environment."
  }
}

# ---- SQS -----------------------------------------------------------------------------------------

variable "sqs_jobs_queue_name" {
  type = string
}

variable "sqs_jobs_dlq_name" {
  type = string
}

variable "jobs_max_receive_count" {
  description = "Deliveries before a job message moves to the DLQ."
  type        = number
  default     = 5

  validation {
    condition     = var.jobs_max_receive_count >= 1 && var.jobs_max_receive_count <= 10
    error_message = "Use 1..10."
  }
}

variable "jobs_visibility_timeout_seconds" {
  description = "At least the longest job deadline (ServiceSettings SCHOLARCHAIN_SVC_JOB_DEADLINE_MS max 900000 ms) plus margin."
  type        = number
  default     = 960
}

# ---- Step Functions and the transient EMR cluster ------------------------------------------------

variable "state_machine_name" {
  type = string
}

variable "pipeline_log_group_name" {
  type = string
}

variable "step_functions_role_arn" {
  type = string
}

variable "emr_service_role_name" {
  type = string
}

variable "emr_instance_profile_name" {
  type = string
}

variable "emr_release_label" {
  type = string
}

variable "emr_log_uri" {
  type = string
}

variable "emr_subnet_id" {
  description = "One data subnet (instance groups launch in a single subnet)."
  type        = string
}

variable "emr_security_group_ids" {
  description = "emr_primary, emr_core, emr_service_access from modules/network."
  type        = map(string)
}

variable "emr_instance_type" {
  type = string
}

variable "emr_core_instance_count" {
  type = number
}

variable "emr_core_max_instance_count" {
  type = number
}

variable "emr_idle_timeout_seconds" {
  type = number
}

variable "emr_max_steps" {
  type = number
}

variable "emr_step_timeout_seconds" {
  type = number
}

variable "emr_create_timeout_seconds" {
  type    = number
  default = 1800
}

# ---- Optional embedding-ingestion task -----------------------------------------------------------

variable "ecs_cluster_arn" {
  type = string
}

variable "embedding_ingestion_family" {
  type = string
}

variable "embedding_ingestion_enabled" {
  description = "Add the RunEmbeddingIngestion branch. Requires an ingestion command in the image (none exists yet)."
  type        = bool
}

variable "ingestion_subnet_ids" {
  type = list(string)
}

variable "ingestion_security_group_id" {
  type = string
}

variable "ingestion_timeout_seconds" {
  type    = number
  default = 3600
}
