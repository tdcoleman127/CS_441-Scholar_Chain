# One customer managed key for ScholarChain data at rest (S3 data buckets, DynamoDB, Neptune,
# S3 Vectors, SQS, Secrets Manager, CloudWatch Logs). One key costs USD 1/month plus requests;
# a key per service would add cost without a separate trust boundary in a single-team dev account.
#
# Key policy: the account root statement delegates use to IAM policies (each role in modules/iam
# names this key ARN explicitly). CloudWatch Logs gets a service statement limited by encryption
# context to this project's log groups. Rotation is on. The deletion window is bounded (7-30 days).

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

variable "name_prefix" {
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

variable "log_group_prefix" {
  description = "CloudWatch Logs group prefix allowed to use the key, for example /scholarchain/dev."
  type        = string
}

variable "vector_bucket_name" {
  description = "S3 Vectors bucket (created in part B) whose background indexing may decrypt with this key."
  type        = string
}

variable "deletion_window_in_days" {
  type    = number
  default = 7

  validation {
    condition     = var.deletion_window_in_days >= 7 && var.deletion_window_in_days <= 30
    error_message = "deletion_window_in_days must be 7..30."
  }
}

data "aws_iam_policy_document" "key" {
  #checkov:skip=CKV_AWS_109:Root-account statement is the standard key-policy delegation to IAM; resource "*" in a key policy means this key.
  #checkov:skip=CKV_AWS_111:Same: key policies always use resource "*" (the key itself).
  #checkov:skip=CKV_AWS_356:Same: key policies always use resource "*" (the key itself).
  statement {
    sid       = "EnableIamPoliciesInThisAccount"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${var.partition}:iam::${var.account_id}:root"]
    }
  }

  statement {
    sid    = "AllowCloudWatchLogsForProjectLogGroups"
    effect = "Allow"
    actions = [
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*",
    ]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${var.partition}:logs:${var.region}:${var.account_id}:log-group:${var.log_group_prefix}/*"]
    }
  }
}

data "aws_iam_policy_document" "s3vectors" {
  # S3 Vectors background indexing needs kms:Decrypt (and only kms:Decrypt) for the indexing service
  # principal. Statement shape follows the key policy example in the Amazon S3 User Guide,
  # "Data protection and encryption in S3 Vectors":
  # https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-data-encryption.html
  # The example uses aws:SourceArn ArnLike "bucket/*". This statement narrows it to this vector bucket
  # and the resources under it (index ARNs are bucket/<name>/index/<index>). The earlier exact
  # bucket-ARN-only value made CreateIndex fail with 403 "Insufficient access to perform
  # asynchronous indexing" (phase 12 apply, repair-04).
  statement {
    sid       = "AllowS3VectorsIndexingForProjectVectorBucket"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["indexing.s3vectors.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        "arn:${var.partition}:s3vectors:${var.region}:${var.account_id}:bucket/${var.vector_bucket_name}",
        "arn:${var.partition}:s3vectors:${var.region}:${var.account_id}:bucket/${var.vector_bucket_name}/*",
      ]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ForAnyValue:StringEquals"
      variable = "kms:EncryptionContextKeys"
      values   = ["aws:s3vectors:arn", "aws:s3vectors:resource-id"]
    }
  }
}

data "aws_iam_policy_document" "combined" {
  source_policy_documents = [data.aws_iam_policy_document.key.json, data.aws_iam_policy_document.s3vectors.json]
}

resource "aws_kms_key" "data" {
  description             = "${var.name_prefix} data at rest"
  enable_key_rotation     = true
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.combined.json
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name_prefix}-data"
  target_key_id = aws_kms_key.data.key_id
}

output "key_arn" {
  value = aws_kms_key.data.arn
}

output "alias_name" {
  value = aws_kms_alias.data.name
}
