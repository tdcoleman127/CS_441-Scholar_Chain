variable "region" {
  description = "AWS Region (decision D2)."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = var.region == "us-east-1"
    error_message = "Only us-east-1 is approved (decision D2)."
  }
}

variable "environment" {
  description = "Environment name. Only dev is approved (docs/agents.md section 7)."
  type        = string
  default     = "dev"

  validation {
    condition     = var.environment == "dev"
    error_message = "Only the dev environment is approved."
  }
}

# ---- Cost and safety ----------------------------------------------------------------------------

variable "budget_notification_emails" {
  description = "Budget alert recipients. No default (decision D1); set only in the uncommitted dev.tfvars."
  type        = list(string)
  sensitive   = true
}

variable "budget_limit_usd" {
  description = "Project budget in USD (decision D1)."
  type        = number
  default     = 25
}

variable "budget_start_date" {
  description = "Budget period start (YYYY-MM-DD_HH:MM, UTC)."
  type        = string
  default     = "2026-11-01_00:00"
}

variable "activate_cost_allocation_tags" {
  description = "Activate the scholarchain:* cost-allocation tags. Turn on only after Billing has seen the tags."
  type        = bool
  default     = false
}

variable "data_cleanup_mode" {
  description = "Course cleanup mode: lets terraform destroy remove non-empty data buckets and shortens KMS and secret recovery windows to the minimum. false except during an approved cleanup."
  type        = bool
  default     = false
}

variable "kms_deletion_window_days" {
  description = "KMS key deletion window (7..30)."
  type        = number
  default     = 30
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep noncurrent object versions in data buckets."
  type        = number
  default     = 30
}

variable "log_retention_days" {
  description = "Days to keep objects in the logs bucket."
  type        = number
  default     = 30
}

# ---- Network ------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC CIDR."
  type        = string
  default     = "10.42.0.0/16"
}

variable "availability_zones" {
  description = "Two AZs in us-east-1."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "alb_ingress_cidrs" {
  description = "CIDRs allowed to reach the public ALB (part B creates the ALB only when enable_runtime is true)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "interface_endpoints" {
  description = "Interface endpoint toggles (USD 0.01/h per AZ each). Turn on only the ones a running component needs."
  type = object({
    ecr_api         = bool
    ecr_dkr         = bool
    logs            = bool
    secretsmanager  = bool
    bedrock_runtime = bool
    s3vectors       = bool
  })
  default = {
    ecr_api         = false
    ecr_dkr         = false
    logs            = false
    secretsmanager  = false
    bedrock_runtime = false
    s3vectors       = false
  }
}

variable "interface_endpoint_az_count" {
  description = "AZs hosting interface endpoint ENIs (1 or 2)."
  type        = number
  default     = 1
}

# ---- Identity -----------------------------------------------------------------------------------

variable "deployer_principal_arns" {
  description = "IAM principal ARNs allowed to assume the deployment role. Empty: no deployment role."
  type        = list(string)
  default     = []
}

variable "pipeline_runner_principal_arns" {
  description = "IAM principal ARNs allowed to assume scholarchain-<env>-pipeline-runner (scripts/run-bounded-pipeline.sh). Empty: no pipeline-runner role."
  type        = list(string)
  default     = []
}

# ---- Models (decision D13) ----------------------------------------------------------------------

variable "generation_model_id" {
  description = "Bedrock generation model (SCHOLARCHAIN_GENERATION_MODEL_ID)."
  type        = string
  default     = "amazon.nova-micro-v1:0"
}

variable "embedding_model_id" {
  description = "Bedrock embedding model (SCHOLARCHAIN_EMBEDDING_MODEL_ID)."
  type        = string
  default     = "amazon.nova-2-multimodal-embeddings-v1:0"
}

variable "embedding_dimension" {
  description = "Embedding dimension for every S3 Vectors index (EmbeddingModels.ProductionDimension = 1024)."
  type        = number
  default     = 1024

  validation {
    condition     = var.embedding_dimension == 1024
    error_message = "Every S3 Vectors index uses dimension 1024 (decision D13, modules/rag ProductionDimension)."
  }
}

# ---- Toggles (decisions D7, D8) -----------------------------------------------------------------

variable "enable_runtime" {
  description = "Create the ECS services and the ALB (decision D8). Default false. Needs image_tag, release_run_id, runtime_algorithm_env, and the ECR, logs, and Secrets Manager interface endpoints (no NAT)."
  type        = bool
  default     = false

  validation {
    condition = !var.enable_runtime || (
      var.interface_endpoints.ecr_api && var.interface_endpoints.ecr_dkr &&
      var.interface_endpoints.logs && var.interface_endpoints.secretsmanager
    )
    error_message = "enable_runtime needs interface_endpoints ecr_api, ecr_dkr, logs, and secretsmanager (private subnets have no NAT, decision D10)."
  }

  validation {
    condition     = !var.enable_runtime || can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "enable_runtime needs image_tag set to the full Git SHA of a pushed image."
  }

  validation {
    condition     = !var.enable_runtime || can(regex("^run:v1:[0-9a-f]{64}$", var.release_run_id))
    error_message = "enable_runtime needs release_run_id (SCHOLARCHAIN_RELEASE_RUN_ID, run:v1:<64 hex>)."
  }

  validation {
    condition = !var.enable_runtime || alltrue([
      for k in [
        "SCHOLARCHAIN_ER_POLICY_VERSION", "SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD", "SCHOLARCHAIN_ER_REVIEW_THRESHOLD",
        "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR", "SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR", "SCHOLARCHAIN_GRAPH_POLICY_VERSION",
        "SCHOLARCHAIN_BRIDGE_ALGORITHM", "SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS", "SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT",
      ] : contains(keys(var.runtime_algorithm_env), k)
    ])
    error_message = "enable_runtime needs every algorithm key in runtime_algorithm_env (core ConfigLoader has no defaults for them)."
  }
}

variable "enable_neptune" {
  description = "Create Neptune Serverless (decision D7). Default false."
  type        = bool
  default     = false
}

variable "service_port" {
  description = "REST API container port (ServiceSettings default port 8080)."
  type        = number
  default     = 8080
}

# ---- EMR transient cluster (decisions D5, D6) ---------------------------------------------------

variable "emr_release_label" {
  description = "EMR release. emr-7.14.0 matches project/Versions.scala (hadoop 3.4.2, spark 3.5.8) and the AL2023 system buckets in modules/iam."
  type        = string
  default     = "emr-7.14.0"

  validation {
    condition     = can(regex("^emr-7\\.[0-9]+\\.[0-9]+$", var.emr_release_label))
    error_message = "Only EMR 7.x releases match the IAM system-bucket pattern (AL2023)."
  }
}

variable "emr_instance_type" {
  description = "Primary and core instance type (On-Demand only, decision D6)."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_instance_count" {
  description = "On-Demand core nodes per run."
  type        = number
  default     = 2

  validation {
    condition     = var.emr_core_instance_count >= 1 && var.emr_core_instance_count <= 3
    error_message = "emr_core_instance_count must be 1..3 in dev. Raising it needs human approval."
  }
}

variable "emr_core_max_instance_count" {
  description = "Managed-scaling ceiling for core nodes. Equal to emr_core_instance_count: fixed size, no managed scaling."
  type        = number
  default     = 2

  validation {
    condition     = var.emr_core_max_instance_count >= 1 && var.emr_core_max_instance_count <= 3
    error_message = "emr_core_max_instance_count must be 1..3 in dev. Raising it needs human approval."
  }
}

variable "emr_idle_timeout_seconds" {
  description = "Auto-termination idle timeout (backstop if the state machine cannot terminate)."
  type        = number
  default     = 1800

  validation {
    condition     = var.emr_idle_timeout_seconds >= 60 && var.emr_idle_timeout_seconds <= 3600
    error_message = "Use 60..3600 seconds."
  }
}

variable "emr_max_steps" {
  description = "Most EMR steps one pipeline execution may add."
  type        = number
  default     = 6

  validation {
    condition     = var.emr_max_steps >= 1 && var.emr_max_steps <= 10
    error_message = "Use 1..10."
  }
}

variable "emr_step_timeout_seconds" {
  description = "Per-step timeout; matches run-bounded-pipeline.sh MAX_STEP_TIMEOUT_SECONDS."
  type        = number
  default     = 3600

  validation {
    condition     = var.emr_step_timeout_seconds >= 60 && var.emr_step_timeout_seconds <= 3600
    error_message = "Use 60..3600 seconds."
  }
}

# ---- Neptune (decision D7; course cleanup mode is explicit here) -------------------------------

variable "neptune_engine_version" {
  type    = string
  default = "1.4.8.1"
}

variable "neptune_parameter_group_family" {
  type    = string
  default = "neptune1.4"
}

variable "neptune_min_capacity" {
  description = "Lowest allowed Neptune Serverless minimum (1.0 NCU)."
  type        = number
  default     = 1.0
}

variable "neptune_max_capacity" {
  description = "Decision D7: 2.5 NCU."
  type        = number
  default     = 2.5
}

variable "neptune_deletion_protection" {
  description = "false in dev only (course cleanup mode, decision D7). A persistent environment sets true."
  type        = bool
  default     = false
}

variable "neptune_skip_final_snapshot" {
  description = "true in dev only (course cleanup mode, decision D7). A persistent environment sets false."
  type        = bool
  default     = true
}

variable "neptune_backup_retention_days" {
  type    = number
  default = 1
}

# ---- Runtime compute (decisions D8, D11, D12) ---------------------------------------------------

variable "image_tag" {
  description = "Full Git SHA of the services image in ECR. Empty: no task definitions are registered."
  type        = string
  default     = ""

  validation {
    condition     = var.image_tag == "" || can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be empty or a full 40-character Git SHA."
  }
}

variable "release_run_id" {
  description = "SCHOLARCHAIN_RELEASE_RUN_ID for the API and worker (run:v1:<64 hex>)."
  type        = string
  default     = ""
}

variable "runtime_algorithm_env" {
  description = "SCHOLARCHAIN_ER_*, SCHOLARCHAIN_GRAPH_*, and SCHOLARCHAIN_BRIDGE_* values for the release (no defaults in core). Non-secret."
  type        = map(string)
  default     = {}

  validation {
    condition = alltrue([
      for k in keys(var.runtime_algorithm_env) :
      can(regex("^SCHOLARCHAIN_(ER|GRAPH|BRIDGE)_[A-Z_]+$", k))
    ])
    error_message = "runtime_algorithm_env holds only SCHOLARCHAIN_ER_*, SCHOLARCHAIN_GRAPH_*, and SCHOLARCHAIN_BRIDGE_* keys."
  }
}

variable "api_desired_count" {
  type    = number
  default = 1

  validation {
    condition     = contains([0, 1], var.api_desired_count)
    error_message = "Desired count is 0 or 1 in dev (decision D8)."
  }
}

variable "worker_desired_count" {
  description = "0 until a worker consumer exists in modules/services."
  type        = number
  default     = 0

  validation {
    condition     = contains([0, 1], var.worker_desired_count)
    error_message = "Desired count is 0 or 1 in dev (decision D8)."
  }
}

variable "alb_deletion_protection" {
  description = "ALB deletion protection. false in dev so that enable_runtime=false removes the ALB without data_cleanup_mode (the ALB holds no data)."
  type        = bool
  default     = false
}

variable "health_check_path" {
  description = "ALB health check (human decision R1-F2). /healthz is liveness and needs no dependency. /readyz fails in AWS mode until the Neptune adapter exists (UnavailableGraphStore), so it stays an EXPECTED-FAIL smoke check."
  type        = string
  default     = "/healthz"

  validation {
    condition     = contains(["/readyz", "/healthz"], var.health_check_path)
    error_message = "Use /readyz or /healthz."
  }
}

variable "embedding_ingestion_command" {
  description = "Command for the embedding-ingestion task. Empty: no ingestion entrypoint exists in code yet, so the pipeline has no ingestion branch."
  type        = list(string)
  default     = []
}

variable "flow_log_traffic_type" {
  type    = string
  default = "REJECT"
}

variable "cloudwatch_log_retention_days" {
  description = "Retention for every /scholarchain/dev/* log group and the Neptune audit group."
  type        = number
  default     = 30

  validation {
    condition     = contains([7, 14, 30, 60, 90], var.cloudwatch_log_retention_days)
    error_message = "Use 7, 14, 30, 60, or 90 days."
  }
}
