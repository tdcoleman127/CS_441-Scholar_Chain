provider "aws" {
  region = var.region

  # Required tags (foundation). The scholarchain:environment key is the one
  # scripts/run-bounded-pipeline.sh checks on the EMR cluster (ENV_TAG_KEY).
  default_tags {
    tags = local.required_tags
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}
