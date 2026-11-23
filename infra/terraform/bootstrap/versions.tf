# Bootstrap root: the remote-state bucket for every other ScholarChain root.
# Owner: infra-engineer (author). Applied by the human architect, by hand, once (decision D4).
# This root keeps its own state locally (terraform.tfstate in this directory, ignored by Git
# through *.tfstate). Agents never read that file (CLAUDE.md law 4).
#
# Provider pin: hashicorp/aws 6.73.0, the latest version in the public Terraform Registry on
# 2026-11-19 (terraform MCP get_latest_provider_version). The same exact pin is used by every root.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.73.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.required_tags
  }
}
