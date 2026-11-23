# ScholarChain Neptune Serverless (decision D7). envs/dev instantiates this module only when
# enable_neptune is true; Neptune Serverless bills at least min_capacity NCU every hour it exists
# (it does not scale to zero), so the cluster exists only while the human loads or queries.
#
# Capacity: min 1.0 NCU (the lowest allowed value) and max 2.5 NCU (decision D7; also the lowest
# allowed maximum). Source: Neptune User Guide, "Neptune Serverless minimum capacity" (min 1.0)
# and the API reference ServerlessV2ScalingConfiguration (max >= 2.5).
#
# Engine 1.4.8.1, parameter group family neptune1.4: verified read-only with
# `aws neptune describe-db-engine-versions` (scholarchain-readonly, 2026-11-19).
#
# Access: IAM database authentication on; port 8182 only from the API and worker security groups
# (modules/network). Data-plane read grants (neptune-db:ReadDataViaQuery only; see the policy) on
# this cluster's resource ID go to the api_task, worker_task, and runtime_reader roles. No role gets
# loader-invocation or write permissions here (open question for storage-engineer: which principal
# starts the bulk loader). The neptune_loader role is associated so the loader can read graph/.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

variable "cluster_identifier" {
  type = string
}

variable "engine_version" {
  type = string
}

variable "parameter_group_family" {
  type = string
}

variable "min_capacity" {
  type = number

  validation {
    condition     = var.min_capacity >= 1.0
    error_message = "Neptune Serverless min_capacity is at least 1.0 NCU."
  }
}

variable "max_capacity" {
  type = number

  validation {
    condition     = var.max_capacity == 2.5
    error_message = "max_capacity is fixed at 2.5 NCU (decision D7). Raising it needs human approval."
  }
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "loader_role_arn" {
  type = string
}

variable "reader_role_names" {
  description = "Roles that receive neptune-db read grants on this cluster (api_task, worker_task, runtime_reader)."
  type        = map(string)
}

variable "deletion_protection" {
  type = bool
}

variable "skip_final_snapshot" {
  type = bool
}

variable "backup_retention_days" {
  type = number

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 7
    error_message = "Use 1..7 days in dev."
  }
}

variable "log_retention_days" {
  type = number
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

resource "aws_neptune_subnet_group" "this" {
  name       = "${var.cluster_identifier}-subnets"
  subnet_ids = var.subnet_ids
}

resource "aws_neptune_cluster_parameter_group" "this" {
  name   = "${var.cluster_identifier}-params"
  family = var.parameter_group_family

  parameter {
    name  = "neptune_enable_audit_log"
    value = "1"
  }
}

# Neptune creates this group on first audit-log export with no retention; creating it first bounds
# retention. It cannot use the project CMK, whose CloudWatch Logs grant is limited to /scholarchain/*.
resource "aws_cloudwatch_log_group" "audit" {
  #checkov:skip=CKV_AWS_158:Neptune owns the /aws/neptune/ group name; the CMK key policy covers /scholarchain/* only. AWS-managed encryption at rest applies.
  #checkov:skip=CKV_AWS_338:Dev audit logs are kept for log_retention_days to bound cost.
  name              = "/aws/neptune/${var.cluster_identifier}/audit"
  retention_in_days = var.log_retention_days
}

resource "aws_neptune_cluster" "this" {
  #checkov:skip=CKV_AWS_361:Decision D7: the dev cluster exists only while loading or querying and is rebuilt from the curated graph/ prefix; 1-day automated backups are enough. A persistent environment raises backup_retention_days.
  #checkov:skip=CKV2_AWS_58:Decision D7: deletion protection off in dev only (course cleanup mode, explicit in dev.tfvars); a persistent environment sets neptune_deletion_protection = true.
  cluster_identifier                   = var.cluster_identifier
  engine                               = "neptune"
  engine_version                       = var.engine_version
  neptune_subnet_group_name            = aws_neptune_subnet_group.this.name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this.name
  vpc_security_group_ids               = [var.security_group_id]
  storage_encrypted                    = true
  kms_key_arn                          = var.kms_key_arn
  iam_database_authentication_enabled  = true
  iam_roles                            = [var.loader_role_arn]
  backup_retention_period              = var.backup_retention_days
  preferred_backup_window              = "07:00-08:00"
  copy_tags_to_snapshot                = true
  deletion_protection                  = var.deletion_protection
  skip_final_snapshot                  = var.skip_final_snapshot
  final_snapshot_identifier            = var.skip_final_snapshot ? null : "${var.cluster_identifier}-final"
  enable_cloudwatch_logs_exports       = ["audit"]
  apply_immediately                    = true

  serverless_v2_scaling_configuration {
    min_capacity = var.min_capacity
    max_capacity = var.max_capacity
  }

  depends_on = [aws_cloudwatch_log_group.audit]
}

resource "aws_neptune_cluster_instance" "this" {
  identifier                   = "${var.cluster_identifier}-1"
  cluster_identifier           = aws_neptune_cluster.this.id
  engine                       = "neptune"
  instance_class               = "db.serverless"
  neptune_subnet_group_name    = aws_neptune_subnet_group.this.name
  auto_minor_version_upgrade   = true
  apply_immediately            = true
  promotion_tier               = 0
  preferred_maintenance_window = "sun:08:00-sun:09:00"
}

# Read-only data-plane grant on this exact cluster (resource ID), per role.
data "aws_iam_policy_document" "read" {
  statement {
    sid = "NeptuneReadOnlyQuery"
    # Reviewer assumption R1 (neptune-db:connect). AWS Neptune User Guide, engine release 1.2.0.0
    # (https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases-1.2.0.0.html): granular
    # data-plane actions replace "the deprecated connect action", and policies based on it "must be
    # adjusted". connect was the all-data-access action before 1.2.0.0, so it is broader than read
    # and is not granted. The read-only query example in "Creating IAM data-access policies"
    # (https://docs.aws.amazon.com/neptune/latest/userguide/iam-data-access-examples.html) grants
    # neptune-db:ReadDataViaQuery alone; that is the only action granted here. Neptune Serverless
    # needs engine 1.2.0.1 or later, so the granular model applies.
    actions   = ["neptune-db:ReadDataViaQuery"]
    resources = ["arn:${var.partition}:neptune-db:${var.region}:${var.account_id}:${aws_neptune_cluster.this.cluster_resource_id}/*"]
  }
}

resource "aws_iam_role_policy" "read" {
  for_each = var.reader_role_names
  name     = "neptune-read"
  role     = each.value
  policy   = data.aws_iam_policy_document.read.json
}

output "cluster_arn" {
  value = aws_neptune_cluster.this.arn
}

output "cluster_resource_id" {
  value = aws_neptune_cluster.this.cluster_resource_id
}

output "endpoint" {
  value = aws_neptune_cluster.this.endpoint
}

output "reader_endpoint" {
  value = aws_neptune_cluster.this.reader_endpoint
}

output "port" {
  value = aws_neptune_cluster.this.port
}
