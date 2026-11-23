# ScholarChain observability: one dashboard and bounded alarms.
#
# Metric sources actually available:
#   EMR             AWS/ElasticMapReduce per JobFlowId. Transient clusters have no fixed ID, so the
#                   dashboard uses SEARCH; alarms cannot use SEARCH, so EMR failure is alarmed through
#                   the state machine (ExecutionsFailed, ExecutionsTimedOut) and bounded by the
#                   cluster's auto-termination idle timeout.
#   Step Functions  AWS/States per StateMachineArn.
#   SQS             AWS/SQS: DLQ depth alarm (any message), jobs queue age.
#   DynamoDB        AWS/DynamoDB: read and write throttle events, all five tables, one alarm.
#   ECS             AWS/ECS CPUUtilization, MemoryUtilization (service metrics, no Container Insights).
#   ALB             AWS/ApplicationELB TargetResponseTime p95, HTTPCode_Target_5XX, HTTPCode_ELB_5XX.
#   Neptune         AWS/Neptune ServerlessDatabaseCapacity at the 2.5 NCU cap.
#   Custom          none. modules/services exposes process-local Prometheus counters at GET /metrics
#                   (ops/Observability.scala) and emits no CloudWatch metric; no S3 Vectors client
#                   or LLM usage metric exists in code (no Bedrock client yet, P12-F1). The namespace
#                   below is reserved for them; alarms are added when the code emits the metrics.
#
# No SNS topic: alarm notification needs a subscriber address, and the only address variable
# (budget_notification_emails) is sensitive and cannot drive for_each. Alarms show in the console
# and the dashboard; wiring an SNS topic is a human decision (listed in the report).

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

variable "region" {
  type = string
}

variable "custom_metric_namespace" {
  type = string
}

variable "state_machine_arn" {
  type = string
}

variable "jobs_queue_name" {
  type = string
}

variable "jobs_dlq_name" {
  type = string
}

variable "dynamodb_table_names" {
  type = list(string)
}

variable "ecs_cluster_name" {
  type = string
}

variable "ecs_service_names" {
  type = map(string)
}

variable "enable_runtime" {
  type = bool
}

variable "alb_arn_suffix" {
  type = string
}

variable "target_group_arn_suffix" {
  type = string
}

variable "enable_neptune" {
  type = bool
}

variable "neptune_cluster_identifier" {
  type = string
}

variable "neptune_max_capacity" {
  type = number
}

variable "alb_p95_latency_seconds" {
  type    = number
  default = 2
}

locals {
  ddb_ids = { for i, t in var.dynamodb_table_names : t => i }
}

# ---- Always-on alarms (no hourly cost; USD 0.10 per standard alarm metric per month) -------------

resource "aws_cloudwatch_metric_alarm" "jobs_dlq_depth" {
  alarm_name          = "${var.name_prefix}-jobs-dlq-not-empty"
  alarm_description   = "A job message exhausted its receives and moved to the DLQ."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.jobs_dlq_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "pipeline_failed" {
  alarm_name          = "${var.name_prefix}-pipeline-failed"
  alarm_description   = "A bounded pipeline execution failed (EMR step, cluster creation, or termination)."
  namespace           = "AWS/States"
  metric_name         = "ExecutionsFailed"
  dimensions          = { StateMachineArn = var.state_machine_arn }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "pipeline_timed_out" {
  alarm_name          = "${var.name_prefix}-pipeline-timed-out"
  alarm_description   = "A pipeline execution timed out; check that its EMR cluster terminated."
  namespace           = "AWS/States"
  metric_name         = "ExecutionsTimedOut"
  dimensions          = { StateMachineArn = var.state_machine_arn }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_throttles" {
  alarm_name          = "${var.name_prefix}-dynamodb-throttles"
  alarm_description   = "Read or write throttle events on any ScholarChain table."
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "total"
    expression  = "SUM(METRICS())"
    label       = "Throttle events"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = local.ddb_ids
    content {
      id = "r${metric_query.value}"
      metric {
        namespace   = "AWS/DynamoDB"
        metric_name = "ReadThrottleEvents"
        dimensions  = { TableName = metric_query.key }
        period      = 300
        stat        = "Sum"
      }
    }
  }

  dynamic "metric_query" {
    for_each = local.ddb_ids
    content {
      id = "w${metric_query.value}"
      metric {
        namespace   = "AWS/DynamoDB"
        metric_name = "WriteThrottleEvents"
        dimensions  = { TableName = metric_query.key }
        period      = 300
        stat        = "Sum"
      }
    }
  }
}

# ---- Runtime alarms (enable_runtime) -------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "alb_latency_p95" {
  count               = var.enable_runtime ? 1 : 0
  alarm_name          = "${var.name_prefix}-api-latency-p95"
  alarm_description   = "API p95 latency above ${var.alb_p95_latency_seconds}s at the ALB."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.alb_p95_latency_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "alb_target_5xx" {
  count               = var.enable_runtime ? 1 : 0
  alarm_name          = "${var.name_prefix}-api-target-5xx"
  alarm_description   = "API tasks returned 5xx responses."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "alb_elb_5xx" {
  count               = var.enable_runtime ? 1 : 0
  alarm_name          = "${var.name_prefix}-alb-5xx"
  alarm_description   = "The ALB itself returned 5xx (no healthy target, timeouts)."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  for_each            = var.enable_runtime ? var.ecs_service_names : {}
  alarm_name          = "${var.name_prefix}-${each.key}-cpu-high"
  alarm_description   = "ECS ${each.key} service CPU above 85% for 15 minutes."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = { ClusterName = var.ecs_cluster_name, ServiceName = each.value }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

# ---- Neptune alarm (enable_neptune) --------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "neptune_at_cap" {
  count               = var.enable_neptune ? 1 : 0
  alarm_name          = "${var.name_prefix}-neptune-at-capacity-cap"
  alarm_description   = "Neptune Serverless has run at its ${var.neptune_max_capacity} NCU cap for 15 minutes."
  namespace           = "AWS/Neptune"
  metric_name         = "ServerlessDatabaseCapacity"
  dimensions          = { DBClusterIdentifier = var.neptune_cluster_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = var.neptune_max_capacity
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
}

# ---- Dashboard (free: up to 3 dashboards per account) --------------------------------------------

locals {
  widgets = concat(
    [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title   = "EMR transient clusters (SEARCH)"
          region  = var.region
          stat    = "Average"
          period  = 300
          metrics = [[{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"AppsRunning\"', 'Average', 300)", id = "emr1" }], [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"IsIdle\"', 'Average', 300)", id = "emr2" }]]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title   = "Pipeline executions"
          region  = var.region
          stat    = "Sum"
          period  = 300
          metrics = [for m in ["ExecutionsStarted", "ExecutionsSucceeded", "ExecutionsFailed", "ExecutionsTimedOut"] : ["AWS/States", m, "StateMachineArn", var.state_machine_arn]]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title  = "Jobs queue and DLQ"
          region = var.region
          stat   = "Maximum"
          period = 300
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.jobs_queue_name],
            ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", var.jobs_queue_name],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.jobs_dlq_name],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "DynamoDB consumed capacity and throttles"
          region = var.region
          stat   = "Sum"
          period = 300
          # One row per (table, metric). Not flatten(): it is recursive and would collapse each row
          # into loose strings, which PutDashboard rejects ("metrics has to be an array of array").
          metrics = [for p in setproduct(var.dynamodb_table_names, ["ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits"]) : ["AWS/DynamoDB", p[1], "TableName", p[0]]]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title  = "ECS services CPU and memory"
          region = var.region
          stat   = "Average"
          period = 300
          # One row per (service, metric); see the DynamoDB widget for why this is not flatten().
          metrics = [for p in setproduct(values(var.ecs_service_names), ["CPUUtilization", "MemoryUtilization"]) : ["AWS/ECS", p[1], "ClusterName", var.ecs_cluster_name, "ServiceName", p[0]]]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title   = "Neptune Serverless capacity (NCU)"
          region  = var.region
          stat    = "Average"
          period  = 300
          metrics = [["AWS/Neptune", "ServerlessDatabaseCapacity", "DBClusterIdentifier", var.neptune_cluster_identifier]]
        }
      },
      {
        type = "text", x = 0, y = 24, width = 24, height = 3
        properties = {
          markdown = "Custom namespace **${var.custom_metric_namespace}** is reserved for S3 Vectors client and LLM usage metrics. The code emits none yet (services exposes Prometheus counters at GET /metrics only)."
        }
      },
    ],
    var.enable_runtime ? [
      {
        type = "metric", x = 0, y = 18, width = 24, height = 6
        properties = {
          title  = "API at the ALB: p95 latency and 5xx"
          region = var.region
          period = 300
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p95" }],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "Sum" }],
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum" }],
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum" }],
          ]
        }
      },
    ] : []
  )
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = "${var.name_prefix}-overview"
  dashboard_body = jsonencode({ widgets = local.widgets })
}

output "dashboard_name" {
  value = aws_cloudwatch_dashboard.this.dashboard_name
}

output "custom_metric_namespace" {
  value = var.custom_metric_namespace
}
