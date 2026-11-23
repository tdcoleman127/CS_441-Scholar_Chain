# Remote-state bucket. Name: <project>-tfstate-<account id>-<region>. The account ID is read from
# aws_caller_identity at plan time and never hard-coded (decision D3).
#
# Deletion safeguards: versioning, prevent_destroy, force_destroy = false, and a bucket policy that
# denies DeleteBucket and non-TLS access. Removing the bucket requires a human to edit this file.
# Encryption: SSE-S3 (AES256) with an S3 Bucket Key. A customer managed KMS key is not used here so
# that the bootstrap has no dependency on a key that a later root manages, and state stays readable
# by the human admin profile without extra key grants (justified AWS-managed choice).

data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  state_name = "${var.project}-tfstate-${local.account_id}-${var.region}"
  required_tags = {
    "scholarchain:project"     = var.project
    "scholarchain:environment" = "shared"
    "scholarchain:component"   = "terraform-state"
    "scholarchain:managed-by"  = "terraform"
    "scholarchain:owner"       = "infra-engineer"
    "scholarchain:cost-center" = "cs441-course"
  }
}

resource "aws_s3_bucket" "state" {
  #checkov:skip=CKV_AWS_144:Cross-Region replication is out of budget for a course state bucket; versioning protects history.
  #checkov:skip=CKV_AWS_145:SSE-S3 is the justified choice for the bootstrap state bucket (see header).
  #checkov:skip=CKV2_AWS_62:No event notifications are needed on the state bucket.
  #checkov:skip=CKV_AWS_18:Server access logging would need a log bucket outside this root; CloudTrail records API access.
  bucket        = local.state_name
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-state"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_state_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

data "aws_iam_policy_document" "state" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
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

  statement {
    sid       = "DenyBucketDeletion"
    effect    = "Deny"
    actions   = ["s3:DeleteBucket"]
    resources = [aws_s3_bucket.state.arn]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket     = aws_s3_bucket.state.id
  policy     = data.aws_iam_policy_document.state.json
  depends_on = [aws_s3_bucket_public_access_block.state]
}
