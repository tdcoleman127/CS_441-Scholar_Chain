# ScholarChain data lake. Bucket and prefix names follow the code that reads them:
#
#   raw        SCHOLARCHAIN_RAW_BUCKET      raw/<source>/...                (dataset-manifest.yaml s3_prefixes.raw)
#   samples    SCHOLARCHAIN_SAMPLE_BUCKET   samples/<source>/manifest_version=1/ (run-bounded-pipeline.sh)
#   curated    SCHOLARCHAIN_CURATED_BUCKET  curated/, graph/, semantic-context/, reports/,
#                                           run-manifests/ (SCHOLARCHAIN_RUN_MANIFEST_PREFIX),
#                                           bounded-smoke/ (run-bounded-pipeline.sh OUTPUT_PREFIX)
#                                           (S3Prefix enum in modules/storage ObjectStore.scala;
#                                           AwsRuntimeStores reads the curated bucket only)
#   artifacts  SCHOLARCHAIN_HADOOP_JAR_URI  artifacts/<component>/<git sha>/... (jar URI must carry the SHA)
#   logs       EMR LogUri and S3 access logs logs/emr/, logs/s3-access/<bucket>/, logs/alb/ (part B)
#
# Encryption: SSE-KMS with the project key and an S3 Bucket Key on raw, samples, curated, and
# artifacts. The logs bucket uses SSE-S3 because S3 server access log delivery and ALB access logs
# do not support SSE-KMS destinations (justified AWS-managed choice).
#
# Deletion safeguards: versioning on data buckets, force_destroy = false unless the course cleanup
# mode is explicitly turned on (data_cleanup_mode), public access blocked, TLS-only bucket policies.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

locals {
  data_buckets = toset(["raw", "samples", "curated", "artifacts"])
  all_buckets  = setunion(local.data_buckets, toset(["logs"]))
}

resource "aws_s3_bucket" "this" {
  #checkov:skip=CKV_AWS_144:Cross-Region replication is outside the USD 25 course budget; versioning protects history.
  #checkov:skip=CKV2_AWS_62:No S3 event notifications are consumed in phase 12.
  #checkov:skip=CKV2_AWS_61:False positive on for_each: aws_s3_bucket_lifecycle_configuration.data (4 data buckets) and .logs (logs bucket) cover every bucket.
  #checkov:skip=CKV_AWS_145:Data buckets use SSE-KMS with the project CMK (aws_s3_bucket_server_side_encryption_configuration.data); the logs bucket must be SSE-S3 for S3 access-log and ALB log delivery.
  #checkov:skip=CKV_AWS_18:Data buckets log to the logs bucket (aws_s3_bucket_logging.data); the logs bucket does not log to itself (recursive delivery).
  #checkov:skip=CKV_AWS_21:Data buckets are versioned (aws_s3_bucket_versioning.data); the logs bucket holds expiring logs only (30-day lifecycle), so versioning adds cost without value.
  for_each      = local.all_buckets
  bucket        = var.bucket_names[each.key]
  force_destroy = var.data_cleanup_mode
  tags          = { "scholarchain:data-role" = each.key }
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = aws_s3_bucket.this
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "data" {
  for_each = local.data_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  for_each = local.data_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  #checkov:skip=CKV_AWS_145:S3 server access logs and ALB access logs require an SSE-S3 destination.
  bucket = aws_s3_bucket.this["logs"].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_logging" "data" {
  for_each      = local.data_buckets
  bucket        = aws_s3_bucket.this[each.key].id
  target_bucket = aws_s3_bucket.this["logs"].id
  target_prefix = "logs/s3-access/${each.key}/"
}

resource "aws_s3_bucket_lifecycle_configuration" "data" {
  for_each = local.data_buckets
  bucket   = aws_s3_bucket.this[each.key].id

  rule {
    id     = "noncurrent-and-multipart"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.data]
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.this["logs"].id

  rule {
    id     = "expire-logs"
    status = "Enabled"
    filter {}
    expiration {
      days = var.log_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "tls_only" {
  for_each = local.all_buckets

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # S3 server access log delivery into the logs bucket only, from this account's data buckets.
  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []
    content {
      sid       = "AllowS3ServerAccessLogDelivery"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this["logs"].arn}/logs/s3-access/*"]
      principals {
        type        = "Service"
        identifiers = ["logging.s3.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [var.account_id]
      }
      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = [for b in local.data_buckets : aws_s3_bucket.this[b].arn]
      }
    }
  }

  # ALB access logs (logs/alb/). us-east-1 predates the log-delivery service principal, so delivery
  # uses the Regional Elastic Load Balancing account (data source aws_elb_service_account in
  # envs/dev), limited to this account's AWSLogs path.
  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []
    content {
      sid       = "AllowAlbAccessLogDelivery"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this["logs"].arn}/logs/alb/AWSLogs/${var.account_id}/*"]
      principals {
        type        = "AWS"
        identifiers = [var.elb_service_account_arn]
      }
    }
  }

  # VPC flow logs (logs/vpc-flow/), delivered by the CloudWatch Logs delivery service.
  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []
    content {
      sid       = "AllowVpcFlowLogDeliveryWrite"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this["logs"].arn}/logs/vpc-flow/AWSLogs/${var.account_id}/*"]
      principals {
        type        = "Service"
        identifiers = ["delivery.logs.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [var.account_id]
      }
      condition {
        test     = "StringEquals"
        variable = "s3:x-amz-acl"
        values   = ["bucket-owner-full-control"]
      }
      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = ["arn:${var.partition}:logs:${var.region}:${var.account_id}:*"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []
    content {
      sid       = "AllowVpcFlowLogDeliveryAclCheck"
      effect    = "Allow"
      actions   = ["s3:GetBucketAcl", "s3:ListBucket"]
      resources = [aws_s3_bucket.this["logs"].arn]
      principals {
        type        = "Service"
        identifiers = ["delivery.logs.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [var.account_id]
      }
      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = ["arn:${var.partition}:logs:${var.region}:${var.account_id}:*"]
      }
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each   = aws_s3_bucket.this
  bucket     = each.value.id
  policy     = data.aws_iam_policy_document.tls_only[each.key].json
  depends_on = [aws_s3_bucket_public_access_block.this]
}
