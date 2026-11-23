variable "bucket_names" {
  description = "Physical bucket names keyed by role: raw, samples, curated, artifacts, logs."
  type        = map(string)

  validation {
    condition = alltrue([
      for k in ["raw", "samples", "curated", "artifacts", "logs"] : contains(keys(var.bucket_names), k)
    ])
    error_message = "bucket_names must contain raw, samples, curated, artifacts, and logs."
  }
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

variable "elb_service_account_arn" {
  description = "Regional Elastic Load Balancing account (aws_elb_service_account) that writes ALB access logs."
  type        = string
}

variable "kms_key_arn" {
  description = "Project KMS key for SSE-KMS on data buckets."
  type        = string
}

variable "data_cleanup_mode" {
  description = "Course cleanup mode. true lets terraform destroy remove non-empty buckets (force_destroy). Keep false except during an approved cleanup."
  type        = bool
  default     = false
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep noncurrent object versions in data buckets."
  type        = number
  default     = 30

  validation {
    condition     = var.noncurrent_version_retention_days >= 7 && var.noncurrent_version_retention_days <= 365
    error_message = "Use 7..365 days."
  }
}

variable "log_retention_days" {
  description = "Days to keep objects in the logs bucket."
  type        = number
  default     = 30

  validation {
    condition     = var.log_retention_days >= 7 && var.log_retention_days <= 365
    error_message = "Use 7..365 days."
  }
}
