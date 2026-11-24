# runtime_mode (Phase 14): validation and wiring to SCHOLARCHAIN_RUNTIME_MODE.
# Plan-only against a mocked AWS provider: no credentials, no AWS call, no backend, no state.
# Run from infra/terraform/envs/dev: terraform init -backend=false, then terraform test.

mock_provider "aws" {
  # The provider still validates policy arguments of mocked resources, so every mocked policy
  # document is a minimal valid policy.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json          = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"none:None\",\"Resource\":\"*\"}]}"
      minified_json = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"none:None\",\"Resource\":\"*\"}]}"
    }
  }
  override_data {
    target = data.aws_caller_identity.current
    values = {
      account_id = "000000000000"
      arn        = "arn:aws:iam::000000000000:user/mock"
      user_id    = "MOCKUSERID"
    }
  }
  override_data {
    target = data.aws_partition.current
    values = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }
  override_data {
    target = data.aws_elb_service_account.current
    values = {
      arn = "arn:aws:iam::000000000000:root"
    }
  }
}

variables {
  budget_notification_emails = ["budget-alerts@example.invalid"]
}

run "default_is_aws" {
  command = plan

  assert {
    condition     = var.runtime_mode == "aws"
    error_message = "runtime_mode must default to aws."
  }
  assert {
    condition     = local.container_environment["SCHOLARCHAIN_RUNTIME_MODE"] == "aws"
    error_message = "The default must pass SCHOLARCHAIN_RUNTIME_MODE=aws (no behavior change)."
  }
}

run "fixture_is_wired" {
  command = plan

  variables {
    runtime_mode = "fixture"
  }

  assert {
    condition     = local.container_environment["SCHOLARCHAIN_RUNTIME_MODE"] == "fixture"
    error_message = "runtime_mode = fixture must pass SCHOLARCHAIN_RUNTIME_MODE=fixture."
  }
}

run "rejects_wrong_case" {
  command = plan

  variables {
    runtime_mode = "Fixture"
  }

  expect_failures = [var.runtime_mode]
}

run "rejects_empty" {
  command = plan

  variables {
    runtime_mode = ""
  }

  expect_failures = [var.runtime_mode]
}

run "rejects_other_value" {
  command = plan

  variables {
    runtime_mode = "local"
  }

  expect_failures = [var.runtime_mode]
}
