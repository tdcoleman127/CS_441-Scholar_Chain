variable "region" {
  description = "AWS Region for the state bucket. Decision D2: us-east-1 only."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = var.region == "us-east-1"
    error_message = "Only us-east-1 is approved (phase 12 decision D2)."
  }
}

variable "project" {
  description = "Project name used in resource names and tags."
  type        = string
  default     = "scholarchain"
}

variable "noncurrent_state_retention_days" {
  description = "Days to keep noncurrent (superseded) state object versions before expiry."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_state_retention_days >= 30 && var.noncurrent_state_retention_days <= 365
    error_message = "Keep noncurrent state versions between 30 and 365 days."
  }
}
