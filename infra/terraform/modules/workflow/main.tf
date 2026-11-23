# ScholarChain workflow and messaging.
#
# SQS: the jobs queue (API enqueues with sqs:SendMessage, the worker consumes) and its DLQ, both
# SSE-KMS with the project CMK, bounded maxReceiveCount.
#
# Step Functions: scholarchain-<env>-pipeline. One execution is one bounded pipeline run on one
# transient EMR cluster (decisions D5, D6):
#
#   ValidateInput   refuses an empty step list or more than emr_max_steps steps
#   CreateCluster   elasticmapreduce:createCluster.sync (RunJobFlow). On failure: FindStartingCluster
#                   (aws-sdk:emr:listClusters, created after the execution start, non-terminal) and
#                   TerminateStartingCluster (terminate the match on <prefix>-pipeline-<execution name>).
#                   Hadoop + Spark, emr_release_label,
#                   On-Demand primary + small On-Demand core group, no task group, no Spot,
#                   KeepJobFlowAliveWhenNoSteps (steps are added next), auto-termination idle
#                   timeout as the backstop, LogUri s3://<logs>/logs/emr/, data subnet, the three
#                   EMR security groups, the emr-instance profile, Tags incl. scholarchain:environment
#   RunSteps        Map, MaxConcurrency 1: addStep.sync per input step, ActionOnFailure CONTINUE,
#                   per-step timeout; the first failure stops the Map
#   TerminateCluster  terminateCluster.sync, always: on success and on any failure (Catch States.ALL)
#   RunEmbeddingIngestion (optional, only when embedding_ingestion_enabled and the input asks)
#
# Execution input contract (scripts/run-bounded-pipeline.sh builds it; part C):
#   {
#     "runId": "run:v1:<64 hex>",
#     "steps": [ { "Name": "<step name>",
#                  "HadoopJarStep": { "Jar": "s3://.../<git sha>/....jar" | "command-runner.jar",
#                                     "MainClass": "<class>" (optional), "Args": [ ... ] } } ],
#     "runEmbeddingIngestion": false            (optional)
#   }
#
# No state-machine-level TimeoutSeconds on purpose: an execution timeout skips the Catch, so the
# cluster would only stop at the idle backstop. Every Task has its own timeout instead, and a
# timeout is caught and terminates the cluster.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

# ---- SQS -----------------------------------------------------------------------------------------

resource "aws_sqs_queue" "jobs_dlq" {
  name                              = var.sqs_jobs_dlq_name
  message_retention_seconds         = 1209600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 3600
}

resource "aws_sqs_queue" "jobs" {
  name                              = var.sqs_jobs_queue_name
  visibility_timeout_seconds        = var.jobs_visibility_timeout_seconds
  message_retention_seconds         = 345600
  receive_wait_time_seconds         = 20
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 3600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dlq.arn
    maxReceiveCount     = var.jobs_max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "jobs_dlq" {
  queue_url = aws_sqs_queue.jobs_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.jobs.arn]
  })
}

# ---- Step Functions log group --------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "pipeline" {
  #checkov:skip=CKV_AWS_338:Dev log retention is bounded by log_retention_days (default 30) to fit the USD 25 budget.
  name              = var.pipeline_log_group_name
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# ---- State machine -------------------------------------------------------------------------------

locals {
  emr_tags = [for k in sort(keys(var.required_tags)) : { Key = k, Value = var.required_tags[k] }]

  # Managed scaling only when the ceiling exceeds the fixed core size (default: equal, fixed size).
  managed_scaling = jsondecode(var.emr_core_max_instance_count > var.emr_core_instance_count ? jsonencode({
    ManagedScalingPolicy = {
      ComputeLimits = {
        UnitType                     = "Instances"
        MinimumCapacityUnits         = 1 + var.emr_core_instance_count
        MaximumCapacityUnits         = 1 + var.emr_core_max_instance_count
        MaximumOnDemandCapacityUnits = 1 + var.emr_core_max_instance_count
        MaximumCoreCapacityUnits     = var.emr_core_max_instance_count
      }
    }
  }) : "{}")

  create_cluster_parameters = merge({
    "Name.$"             = "States.Format('${var.name_prefix}-pipeline-{}', $$.Execution.Name)"
    ReleaseLabel         = var.emr_release_label
    Applications         = [{ Name = "Hadoop" }, { Name = "Spark" }]
    ServiceRole          = var.emr_service_role_name
    JobFlowRole          = var.emr_instance_profile_name
    LogUri               = var.emr_log_uri
    VisibleToAllUsers    = true
    StepConcurrencyLevel = 1
    AutoTerminationPolicy = {
      IdleTimeout = var.emr_idle_timeout_seconds
    }
    Instances = {
      Ec2SubnetId                   = var.emr_subnet_id
      EmrManagedMasterSecurityGroup = var.emr_security_group_ids["emr_primary"]
      EmrManagedSlaveSecurityGroup  = var.emr_security_group_ids["emr_core"]
      ServiceAccessSecurityGroup    = var.emr_security_group_ids["emr_service_access"]
      KeepJobFlowAliveWhenNoSteps   = true
      TerminationProtected          = false
      InstanceGroups = [
        {
          Name          = "primary"
          InstanceRole  = "MASTER"
          Market        = "ON_DEMAND"
          InstanceType  = var.emr_instance_type
          InstanceCount = 1
        },
        {
          Name          = "core"
          InstanceRole  = "CORE"
          Market        = "ON_DEMAND"
          InstanceType  = var.emr_instance_type
          InstanceCount = var.emr_core_instance_count
        },
      ]
    }
    Tags = local.emr_tags
  }, local.managed_scaling)

  terminate = {
    Type           = "Task"
    Resource       = "arn:aws:states:::elasticmapreduce:terminateCluster.sync"
    Parameters     = { "ClusterId.$" = "$.cluster.ClusterId" }
    ResultPath     = null
    TimeoutSeconds = 1800
    Retry = [{
      ErrorEquals     = ["States.ALL"]
      IntervalSeconds = 30
      MaxAttempts     = 3
      BackoffRate     = 2
    }]
  }

  # JSON round trip: the two conditional branches are objects with different attributes.
  ingestion_states = jsondecode(var.embedding_ingestion_enabled ? jsonencode(local.ingestion_states_enabled) : "{}")

  ingestion_states_enabled = {
    IngestionRequested = {
      Type = "Choice"
      Choices = [{
        And = [
          { Variable = "$.runEmbeddingIngestion", IsPresent = true },
          { Variable = "$.runEmbeddingIngestion", BooleanEquals = true },
        ]
        Next = "RunEmbeddingIngestion"
      }]
      Default = "PipelineSucceeded"
    }
    RunEmbeddingIngestion = {
      Type           = "Task"
      Resource       = "arn:aws:states:::ecs:runTask.sync"
      TimeoutSeconds = var.ingestion_timeout_seconds
      Parameters = {
        Cluster        = var.ecs_cluster_arn
        TaskDefinition = var.embedding_ingestion_family
        LaunchType     = "FARGATE"
        NetworkConfiguration = {
          AwsvpcConfiguration = {
            Subnets        = var.ingestion_subnet_ids
            SecurityGroups = [var.ingestion_security_group_id]
            AssignPublicIp = "DISABLED"
          }
        }
        Overrides = {
          ContainerOverrides = [{
            Name        = "embedding-ingestion"
            Environment = [{ Name = "SCHOLARCHAIN_RELEASE_RUN_ID", "Value.$" = "$.runId" }]
          }]
        }
      }
      ResultPath = null
      Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.error", Next = "IngestionFailed" }]
      Next       = "PipelineSucceeded"
    }
    IngestionFailed = {
      Type  = "Fail"
      Error = "EmbeddingIngestionFailed"
      Cause = "The EMR cluster was terminated; the embedding-ingestion task failed or timed out."
    }
  }

  definition = {
    Comment = "Bounded ScholarChain pipeline run on a transient EMR cluster (terminated on every path)."
    StartAt = "ValidateInput"
    States = merge({
      ValidateInput = {
        Type = "Choice"
        Choices = [
          { Variable = "$.steps[0]", IsPresent = false, Next = "InvalidInput" },
          { Variable = "$.steps[${var.emr_max_steps}]", IsPresent = true, Next = "InvalidInput" },
        ]
        Default = "CreateCluster"
      }
      InvalidInput = {
        Type  = "Fail"
        Error = "InvalidPipelineInput"
        Cause = "steps must hold 1..${var.emr_max_steps} entries; no cluster was created."
      }
      CreateCluster = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:createCluster.sync"
        TimeoutSeconds = var.emr_create_timeout_seconds
        Parameters     = local.create_cluster_parameters
        ResultSelector = { "ClusterId.$" = "$.ClusterId" }
        ResultPath     = "$.cluster"
        Catch          = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.error", Next = "FindStartingCluster" }]
        Next           = "RunSteps"
      }
      # Review finding R1-F6: createCluster.sync can fail or time out after RunJobFlow returned, while
      # the cluster is still starting, and the Catch has no ClusterId. Look the cluster up by its
      # deterministic name (created after this execution started, in a non-terminal state) and
      # terminate every match. TerminateJobFlows keeps the environment ResourceTag condition, so only
      # this environment's tagged clusters can be terminated.
      FindStartingCluster = {
        Type     = "Task"
        Resource = "arn:aws:states:::aws-sdk:emr:listClusters"
        Parameters = {
          "CreatedAfter.$" = "$$.Execution.StartTime"
          ClusterStates    = ["STARTING", "BOOTSTRAPPING", "RUNNING", "WAITING"]
        }
        ResultSelector = { "Clusters.$" = "$.Clusters" }
        ResultPath     = "$.orphanLookup"
        TimeoutSeconds = 120
        Retry = [{
          ErrorEquals     = ["States.ALL"]
          IntervalSeconds = 10
          MaxAttempts     = 3
          BackoffRate     = 2
        }]
        Catch = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.cleanupError", Next = "ClusterCleanupFailed" }]
        Next  = "TerminateStartingCluster"
      }
      TerminateStartingCluster = {
        Type           = "Map"
        ItemsPath      = "$.orphanLookup.Clusters"
        MaxConcurrency = 1
        ItemSelector = {
          "Id.$"           = "$$.Map.Item.Value.Id"
          "Name.$"         = "$$.Map.Item.Value.Name"
          "ExpectedName.$" = "States.Format('${var.name_prefix}-pipeline-{}', $$.Execution.Name)"
        }
        ItemProcessor = {
          ProcessorConfig = { Mode = "INLINE" }
          StartAt         = "IsThisRunsCluster"
          States = {
            IsThisRunsCluster = {
              Type    = "Choice"
              Choices = [{ Variable = "$.Name", StringEqualsPath = "$.ExpectedName", Next = "TerminateStarting" }]
              Default = "NotThisRunsCluster"
            }
            NotThisRunsCluster = {
              Type = "Succeed"
            }
            TerminateStarting = {
              Type           = "Task"
              Resource       = "arn:aws:states:::elasticmapreduce:terminateCluster"
              Parameters     = { "ClusterId.$" = "$.Id" }
              ResultPath     = null
              TimeoutSeconds = 120
              Retry = [{
                ErrorEquals     = ["States.ALL"]
                IntervalSeconds = 30
                MaxAttempts     = 3
                BackoffRate     = 2
              }]
              End = true
            }
          }
        }
        ResultPath = null
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.cleanupError", Next = "ClusterCleanupFailed" }]
        Next       = "ClusterFailed"
      }
      ClusterCleanupFailed = {
        Type  = "Fail"
        Error = "ClusterCreationFailed"
        Cause = "RunJobFlow failed or timed out, and the lookup or termination of a still-starting cluster failed after retries. The idle timeout is the backstop. A human must check for a cluster named <prefix>-pipeline-<execution name>."
      }
      ClusterFailed = {
        Type  = "Fail"
        Error = "ClusterCreationFailed"
        Cause = "RunJobFlow failed or timed out. Any still-starting cluster of this execution was found by name and termination was requested; the idle timeout is the backstop."
      }
      RunSteps = {
        Type           = "Map"
        ItemsPath      = "$.steps"
        MaxConcurrency = 1
        ItemSelector = {
          "ClusterId.$" = "$.cluster.ClusterId"
          "Step.$"      = "$$.Map.Item.Value"
        }
        ItemProcessor = {
          ProcessorConfig = { Mode = "INLINE" }
          StartAt         = "AddStep"
          States = {
            AddStep = {
              Type           = "Task"
              Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
              TimeoutSeconds = var.emr_step_timeout_seconds
              Parameters = {
                "ClusterId.$" = "$.ClusterId"
                Step = {
                  "Name.$"          = "$.Step.Name"
                  ActionOnFailure   = "CONTINUE"
                  "HadoopJarStep.$" = "$.Step.HadoopJarStep"
                }
              }
              ResultSelector = { "StepId.$" = "$.Step.Id", "State.$" = "$.Step.Status.State" }
              End            = true
            }
          }
        }
        ResultPath = "$.stepResults"
        Catch      = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.error", Next = "TerminateClusterAfterFailure" }]
        Next       = "TerminateCluster"
      }
      TerminateCluster = merge(local.terminate, {
        Catch = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.terminateError", Next = "TerminateFailed" }]
        Next  = var.embedding_ingestion_enabled ? "IngestionRequested" : "PipelineSucceeded"
      })
      TerminateClusterAfterFailure = merge(local.terminate, {
        Catch = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.terminateError", Next = "TerminateFailed" }]
        Next  = "PipelineFailed"
      })
      TerminateFailed = {
        Type  = "Fail"
        Error = "ClusterTerminationFailed"
        Cause = "TerminateJobFlows failed after retries; the idle timeout is the backstop. A human must check the cluster."
      }
      PipelineFailed = {
        Type  = "Fail"
        Error = "PipelineStepFailed"
        Cause = "An EMR step failed or timed out; the cluster was terminated."
      }
      PipelineSucceeded = {
        Type = "Succeed"
      }
    }, local.ingestion_states)
  }
}

resource "aws_sfn_state_machine" "pipeline" {
  #checkov:skip=CKV_AWS_284:X-Ray tracing needs extra IAM and adds cost; execution history and ALL-level logs cover the bounded dev pipeline.
  name     = var.state_machine_name
  role_arn = var.step_functions_role_arn
  type     = "STANDARD"

  definition = jsonencode(local.definition)

  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.pipeline.arn}:*"
    include_execution_data = true
    level                  = "ALL"
  }

  # Execution data is encrypted with the AWS-owned Step Functions key. A CMK here would need a new
  # kms grant on the Step Functions role; the input holds only S3 URIs, class names, and a run ID.
}

output "state_machine_arn" {
  value = aws_sfn_state_machine.pipeline.arn
}

output "jobs_queue_url" {
  value = aws_sqs_queue.jobs.url
}

output "jobs_queue_arn" {
  value = aws_sqs_queue.jobs.arn
}

output "jobs_dlq_name" {
  value = aws_sqs_queue.jobs_dlq.name
}

output "jobs_queue_name" {
  value = aws_sqs_queue.jobs.name
}

output "pipeline_log_group_arn" {
  value = aws_cloudwatch_log_group.pipeline.arn
}
