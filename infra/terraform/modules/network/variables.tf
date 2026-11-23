variable "name_prefix" {
  description = "Resource name prefix, for example scholarchain-dev."
  type        = string
}

variable "region" {
  description = "AWS Region."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block (/16 to /20)."
  type        = string
}

variable "availability_zones" {
  description = "Exactly two Availability Zones."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "The dev network spans exactly two Availability Zones."
  }
}

variable "alb_ingress_cidrs" {
  description = "CIDR blocks allowed to reach the public ALB listener (the only broad-CIDR rule)."
  type        = list(string)
}

variable "alb_listener_port" {
  description = "ALB listener port (HTTP 80 in dev, decision D12)."
  type        = number
  default     = 80
}

variable "service_port" {
  description = "Container port of the REST API (ServiceSettings SCHOLARCHAIN_SVC_PORT)."
  type        = number
}

variable "interface_endpoints" {
  description = <<-EOT
    Interface VPC endpoints, one toggle each, all default false. Each costs USD 0.01 per hour per
    Availability Zone (us-east-1 PrivateLink price) plus USD 0.01 per GB processed. With
    interface_endpoint_az_count = 1 an enabled endpoint costs about USD 0.01/h (USD 7.30/month).
    Keys: ecr_api, ecr_dkr, logs, secretsmanager, bedrock_runtime, s3vectors.
  EOT
  type = object({
    ecr_api         = bool
    ecr_dkr         = bool
    logs            = bool
    secretsmanager  = bool
    bedrock_runtime = bool
    s3vectors       = bool
  })
}

variable "interface_endpoint_az_count" {
  description = "Number of AZs (1 or 2) that host interface endpoint ENIs. 1 halves the hourly cost."
  type        = number
  default     = 1

  validation {
    condition     = contains([1, 2], var.interface_endpoint_az_count)
    error_message = "interface_endpoint_az_count must be 1 or 2."
  }
}

variable "flow_log_bucket_arn" {
  description = "Logs bucket ARN; flow logs go to logs/vpc-flow/."
  type        = string
}

variable "flow_log_traffic_type" {
  description = "ACCEPT, REJECT, or ALL. REJECT keeps dev volume and cost low."
  type        = string
  default     = "REJECT"

  validation {
    condition     = contains(["ACCEPT", "REJECT", "ALL"], var.flow_log_traffic_type)
    error_message = "flow_log_traffic_type must be ACCEPT, REJECT, or ALL."
  }
}

variable "emr_managed_policy_tag" {
  description = "Tag required by AmazonEMRServicePolicy_v2 on subnets and security groups EMR uses."
  type        = map(string)
  default     = { "for-use-with-amazon-emr-managed-policies" = "true" }
}
