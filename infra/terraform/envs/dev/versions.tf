# envs/dev root. Owner: infra-engineer (author). Applied only by the human architect.
#
# Provider pin: hashicorp/aws = 6.73.0. Verified in the public Terraform Registry on 2026-11-19
# through the terraform MCP server: get_latest_provider_version(hashicorp/aws) = 6.73.0, and the
# 6.73.0 docs list aws_s3vectors_vector_bucket, aws_s3vectors_index, and
# aws_s3vectors_vector_bucket_policy (S3 Vectors resources used by part B).
#
# Backend (decision D4): S3 with native S3 locking (use_lockfile). The bucket name contains the
# account ID, so it is not written here; pass it with -backend-config=backend.hcl (uncommitted,
# copied from backend.hcl.example). Until the human applies infra/terraform/bootstrap, use the
# documented local-backend fallback in ../../README.md.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.73.0"
    }
  }

  backend "s3" {
    key          = "scholarchain/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
