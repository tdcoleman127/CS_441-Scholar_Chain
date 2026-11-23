variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "data_cleanup_mode" {
  description = "Course cleanup mode: ECR force_delete."
  type        = bool
}

variable "ecr_repository_name" {
  type = string
}

variable "ecr_keep_images" {
  description = "Tagged images kept in ECR (older Git-SHA images expire)."
  type        = number
  default     = 20
}

variable "ecs_cluster_name" {
  type = string
}

variable "ecs_service_names" {
  type = object({ api = string, worker = string })
}

variable "ecs_task_families" {
  description = "Keys api, worker, embedding_ingestion."
  type        = map(string)
}

variable "log_groups" {
  description = "ECS log groups, keys api, worker, embedding_ingestion."
  type        = map(string)
}

variable "log_retention_days" {
  type = number
}

variable "role_arns" {
  description = "Part A role ARNs: ecs_execution, api_task, worker_task, embedding_ingestion."
  type        = map(string)
}

variable "image_tag" {
  description = "Full 40-character Git SHA of the services image. Empty: no task definitions are registered."
  type        = string
}

variable "container_environment" {
  description = "Non-secret SCHOLARCHAIN_* environment shared by every container."
  type        = map(string)
}

variable "api_key_secret_arn" {
  description = "Secrets Manager ARN injected as SCHOLARCHAIN_API_KEYS (by ARN, never by value)."
  type        = string
}

variable "service_port" {
  type = number
}

variable "task_sizes" {
  description = "Fargate CPU units and memory MiB per family."
  type        = map(object({ cpu = number, memory = number }))
}

variable "embedding_ingestion_command" {
  description = "Container command for the embedding-ingestion task. Empty until an ingestion entrypoint exists in code."
  type        = list(string)
}

variable "enable_runtime" {
  description = "Create the ALB and the two ECS services (decision D8)."
  type        = bool
}

variable "api_desired_count" {
  type = number
}

variable "worker_desired_count" {
  type = number
}

variable "alb_deletion_protection" {
  description = "ALB deletion protection. Independent of data_cleanup_mode: the ALB holds no data, and enable_runtime=false must be able to remove it."
  type        = bool
}

variable "health_check_path" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "app_subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  description = "From modules/network: alb, api, worker."
  type        = map(string)
}

variable "alb_listener_port" {
  type    = number
  default = 80
}

variable "logs_bucket_name" {
  type = string
}
