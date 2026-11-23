# Cost guardrails (decisions D1, D3).
#
# Budget: USD 25 cumulative for the project, with ACTUAL-spend alerts at USD 10, 15, and 20 and a
# FORECASTED alert at USD 25. Credits and refunds are excluded from the tracked cost, so the
# USD 100 promotional credits do not hide spend from the alerts. AWS Budgets has no "cumulative"
# period, so an ANNUALLY budget starting on budget_start_date covers the course term.
# A budget alerts; it does not stop spend.
#
# Cost-allocation tags: activation through Terraform works only after AWS Billing has seen the tag
# keys on a resource (up to 24 hours after the first tagged resource), and only from the payer
# account. It is therefore behind activate_cost_allocation_tags (default false).

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

variable "limit_usd" {
  type    = number
  default = 25

  validation {
    condition     = var.limit_usd > 0 && var.limit_usd <= 25
    error_message = "The approved project budget is at most USD 25 (decision D1)."
  }
}

variable "alert_thresholds_usd" {
  type    = list(number)
  default = [10, 15, 20]
}

variable "budget_start_date" {
  description = "Budget period start, format YYYY-MM-DD_HH:MM (UTC)."
  type        = string
  default     = "2026-11-01_00:00"
}

variable "notification_emails" {
  description = "Budget alert recipients. No default; supplied in an uncommitted tfvars file."
  type        = list(string)
  sensitive   = true

  validation {
    condition     = length(var.notification_emails) > 0
    error_message = "At least one budget notification address is required."
  }
}

variable "cost_allocation_tag_keys" {
  type = list(string)
}

variable "activate_cost_allocation_tags" {
  type    = bool
  default = false
}

resource "aws_budgets_budget" "project" {
  name              = "${var.name_prefix}-project-budget"
  budget_type       = "COST"
  limit_amount      = format("%.2f", var.limit_usd)
  limit_unit        = "USD"
  time_unit         = "ANNUALLY"
  time_period_start = var.budget_start_date

  cost_types {
    include_credit             = false
    include_refund             = false
    include_discount           = true
    include_tax                = true
    include_support            = true
    include_other_subscription = true
    include_recurring          = true
    include_upfront            = true
    include_subscription       = true
    use_blended                = false
    use_amortized              = false
  }

  dynamic "notification" {
    for_each = var.alert_thresholds_usd
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.notification_emails
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = var.limit_usd
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.notification_emails
  }
}

resource "aws_ce_cost_allocation_tag" "this" {
  for_each = var.activate_cost_allocation_tags ? toset(var.cost_allocation_tag_keys) : toset([])
  tag_key  = each.value
  status   = "Active"
}

output "budget_name" {
  value = aws_budgets_budget.project.name
}
