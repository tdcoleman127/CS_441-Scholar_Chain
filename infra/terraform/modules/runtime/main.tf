# ScholarChain runtime compute (decisions D8, D11, D12).
#
#   ECR   one repository, immutable Git-SHA tags, scan on push, CMK encryption, lifecycle policy.
#   ECS   one cluster; task definitions for the api, worker, and embedding-ingestion families, all
#         from the one services image (docker/services/Dockerfile). Registered only once image_tag
#         holds a Git SHA. deploy.sh registers later revisions, so the services ignore task_definition
#         drift.
#   ALB   the only public component: HTTP listener in dev (D12), forwarding to the API tasks in the
#         private app subnets (target type ip). API-key authentication stays on in the service.
#   Services  api and worker, created only when enable_runtime is true, desired count 0 or 1.
#
# No internal service discovery: the API composes rag in-process and the worker reaches nothing but
# AWS APIs and the SQS queue, so no service calls another by name (modules/services Main.scala).

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
  create_task_definitions = var.image_tag != ""
  image                   = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  base_environment = [for k in sort(keys(var.container_environment)) : { name = k, value = var.container_environment[k] }]
  secrets          = [{ name = "SCHOLARCHAIN_API_KEYS", valueFrom = var.api_key_secret_arn }]

  # /tmp on a task-scoped ephemeral volume so the root file system stays read-only.
  tmp_mount = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]

  containers = {
    api = {
      name                   = "api"
      image                  = local.image
      essential              = true
      readonlyRootFilesystem = true
      environment            = concat(local.base_environment, [{ name = "SCHOLARCHAIN_SVC_PORT", value = tostring(var.service_port) }])
      secrets                = local.secrets
      portMappings           = [{ containerPort = var.service_port, protocol = "tcp" }]
      mountPoints            = local.tmp_mount
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs["api"].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "api"
        }
      }
    }
    worker = {
      name                   = "worker"
      image                  = local.image
      essential              = true
      readonlyRootFilesystem = true
      environment            = local.base_environment
      secrets                = local.secrets
      mountPoints            = local.tmp_mount
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs["worker"].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "worker"
        }
      }
    }
    embedding_ingestion = merge(
      {
        name                   = "embedding-ingestion"
        image                  = local.image
        essential              = true
        readonlyRootFilesystem = true
        environment            = local.base_environment
        mountPoints            = local.tmp_mount
        logConfiguration = {
          logDriver = "awslogs"
          options = {
            "awslogs-group"         = aws_cloudwatch_log_group.ecs["embedding_ingestion"].name
            "awslogs-region"        = var.region
            "awslogs-stream-prefix" = "embedding-ingestion"
          }
        }
      },
      length(var.embedding_ingestion_command) > 0 ? { command = var.embedding_ingestion_command } : {}
    )
  }

  task_roles = {
    api                 = var.role_arns["api_task"]
    worker              = var.role_arns["worker_task"]
    embedding_ingestion = var.role_arns["embedding_ingestion"]
  }
}

# ---- ECR -----------------------------------------------------------------------------------------

resource "aws_ecr_repository" "this" {
  name                 = var.ecr_repository_name
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.data_cleanup_mode

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the newest ${var.ecr_keep_images} Git-SHA images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_keep_images
        }
        action = { type = "expire" }
      },
    ]
  })
}

# ---- Logs ----------------------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "ecs" {
  #checkov:skip=CKV_AWS_338:Dev log retention is bounded by log_retention_days (default 30) to fit the USD 25 budget.
  for_each          = var.log_groups
  name              = each.value
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# ---- ECS -----------------------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.ecs_cluster_name

  setting {
    #checkov:skip=CKV_AWS_65:Container Insights bills custom metrics per task; dev keeps the free AWS/ECS service metrics (CPU, memory) used by the alarms.
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_ecs_task_definition" "this" {
  for_each = local.create_task_definitions ? var.ecs_task_families : {}

  family                   = each.value
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_sizes[each.key].cpu)
  memory                   = tostring(var.task_sizes[each.key].memory)
  execution_role_arn       = var.role_arns["ecs_execution"]
  task_role_arn            = local.task_roles[each.key]
  container_definitions    = jsonencode([local.containers[each.key]])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume {
    name = "tmp"
  }
}

# ---- Public ALB (enable_runtime only) ------------------------------------------------------------

resource "aws_lb" "api" {
  #checkov:skip=CKV2_AWS_28:WAF costs at least USD 5/month plus requests, outside the USD 25 budget; API-key auth and rate limiting are enforced by the service.
  #checkov:skip=CKV_AWS_150:Dev ALB holds no data and is created and removed with the enable_runtime cost toggle (D8); alb_deletion_protection (default false in dev) would block that removal.
  #checkov:skip=CKV2_AWS_20:Decision D12: HTTP only in dev, no certificate, so there is no HTTPS listener to redirect to.
  count = var.enable_runtime ? 1 : 0

  name                       = "${var.name_prefix}-api"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [var.security_group_ids["alb"]]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.alb_deletion_protection
  idle_timeout               = 60

  access_logs {
    bucket  = var.logs_bucket_name
    prefix  = "logs/alb"
    enabled = true
  }
}

resource "aws_lb_target_group" "api" {
  #checkov:skip=CKV_AWS_378:Decision D12: no certificate in dev; ALB-to-task traffic stays inside the VPC (private app subnets, SG chained ALB to API only).
  count = var.enable_runtime ? 1 : 0

  name                 = "${var.name_prefix}-api"
  port                 = var.service_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 15

  health_check {
    path                = var.health_check_path
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  #checkov:skip=CKV_AWS_2:Decision D12: HTTP only in dev, no certificate available.
  #checkov:skip=CKV_AWS_103:Decision D12: no TLS listener in dev.
  #checkov:skip=CKV2_AWS_20:Decision D12: no HTTPS listener to redirect to in dev.
  count = var.enable_runtime ? 1 : 0

  load_balancer_arn = aws_lb.api[0].arn
  port              = var.alb_listener_port
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api[0].arn
  }
}

# ---- Services (enable_runtime only) --------------------------------------------------------------

resource "aws_ecs_service" "api" {
  count = var.enable_runtime ? 1 : 0

  name                               = var.ecs_service_names.api
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.this["api"].arn
  desired_count                      = var.api_desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 90
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_group_ids["api"]]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api[0].arn
    container_name   = "api"
    container_port   = var.service_port
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http]
}

resource "aws_ecs_service" "worker" {
  count = var.enable_runtime ? 1 : 0

  name                               = var.ecs_service_names.worker
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.this["worker"].arn
  desired_count                      = var.worker_desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_group_ids["worker"]]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}

# ---- Outputs -------------------------------------------------------------------------------------

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "ecr_repository_arn" {
  value = aws_ecr_repository.this.arn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "ecs_service_names" {
  value = {
    api    = try(aws_ecs_service.api[0].name, null)
    worker = try(aws_ecs_service.worker[0].name, null)
  }
}

output "task_definition_arns" {
  value = { for k, t in aws_ecs_task_definition.this : k => t.arn }
}

output "alb_dns_name" {
  value = try(aws_lb.api[0].dns_name, null)
}

output "alb_arn_suffix" {
  value = try(aws_lb.api[0].arn_suffix, null)
}

output "target_group_arn_suffix" {
  value = try(aws_lb_target_group.api[0].arn_suffix, null)
}

output "log_group_arns" {
  value = { for k, g in aws_cloudwatch_log_group.ecs : k => g.arn }
}
