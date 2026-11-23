# ScholarChain IAM. Runtime roles and the deployment role are separate; no role can do both.
# Every resource ARN is either a resource from part A or a deterministic name from
# envs/dev/locals.tf that part B must create with the same name. "*" appears only for actions
# that support no resource-level permission, and each such statement says so.
#
# Neptune data-plane permissions (neptune-db:*) need the cluster resource ID, which only exists
# after part B creates the cluster. Part B attaches them to the roles named in outputs.tf with the
# exact cluster-resource-id ARN; nothing here grants Neptune access.

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
  arn = "arn:${var.partition}"
  n   = var.names

  curated   = var.bucket_arns["curated"]
  raw       = var.bucket_arns["raw"]
  samples   = var.bucket_arns["samples"]
  artifacts = var.bucket_arns["artifacts"]
  logs      = var.bucket_arns["logs"]

  table_arns = {
    for t in local.n.dynamodb_tables :
    t => "${local.arn}:dynamodb:${var.region}:${var.account_id}:table/${local.n.dynamodb_table_prefix}-${t}"
  }
  runtime_write_tables = [local.table_arns["query-session"], local.table_arns["evidence-bundle"]]
  runtime_read_tables  = [for t in ["run-ledger", "artifact-registry", "entity-link-cache"] : local.table_arns[t]]
  ingestion_tables     = [local.table_arns["run-ledger"], local.table_arns["artifact-registry"]]

  vector_bucket_arn = "${local.arn}:s3vectors:${var.region}:${var.account_id}:bucket/${local.n.vector_bucket_name}"
  vector_index_arns = [for i in local.n.vector_index_names : "${local.vector_bucket_arn}/index/${i}"]

  ecr_repository_arn = "${local.arn}:ecr:${var.region}:${var.account_id}:repository/${local.n.ecr_repository_name}"
  ecs_cluster_arn    = "${local.arn}:ecs:${var.region}:${var.account_id}:cluster/${local.n.ecs_cluster_name}"
  ecs_service_arns   = [for k, s in local.n.ecs_service_names : "${local.arn}:ecs:${var.region}:${var.account_id}:service/${local.n.ecs_cluster_name}/${s}"]
  ecs_task_arns      = "${local.arn}:ecs:${var.region}:${var.account_id}:task/${local.n.ecs_cluster_name}/*"
  ingestion_taskdef  = "${local.arn}:ecs:${var.region}:${var.account_id}:task-definition/${local.n.ecs_task_families["embedding_ingestion"]}:*"

  log_stream_arns = {
    for k, g in local.n.log_groups : k => "${local.arn}:logs:${var.region}:${var.account_id}:log-group:${g}:log-stream:*"
  }

  sqs_jobs_arn      = "${local.arn}:sqs:${var.region}:${var.account_id}:${local.n.sqs_jobs_queue_name}"
  state_machine_arn = "${local.arn}:states:${var.region}:${var.account_id}:stateMachine:${local.n.state_machine_name}"

  generation_model_arn = "${local.arn}:bedrock:${var.region}::foundation-model/${var.generation_model_id}"
  embedding_model_arn  = "${local.arn}:bedrock:${var.region}::foundation-model/${var.embedding_model_id}"

  # EMR 7.x (Amazon Linux 2023) system buckets, from the EMR Management Guide page
  # "Sample policies for private subnets that access Amazon S3".
  emr_system_read = [
    "${local.arn}:s3:::al2023-repos-${var.region}-de612dc2/*",
    "${local.arn}:s3:::repo.${var.region}.emr.amazonaws.com/*",
  ]
  emr_system_write = [
    var.region == "us-east-1" ? "${local.arn}:s3:::aws157-logs-prod/*" : "${local.arn}:s3:::aws157-logs-prod-${var.region}/*",
    "${local.arn}:s3:::prod.${var.region}.appinfo.src/*",
  ]

  emr_output_prefixes = ["bounded-smoke", "curated", "graph", local.n.run_manifest_prefix]
  emr_write_prefix    = "bounded-smoke"

  via = {
    s3        = "s3.${var.region}.amazonaws.com"
    dynamodb  = "dynamodb.${var.region}.amazonaws.com"
    sqs       = "sqs.${var.region}.amazonaws.com"
    s3vectors = "s3vectors.${var.region}.amazonaws.com"
    secrets   = "secretsmanager.${var.region}.amazonaws.com"
  }
}

# ------------------------------------------------------------------------------------------------
# Trust policies
# ------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "trust_ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.arn}:ecs:${var.region}:${var.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "trust_emr" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.arn}:elasticmapreduce:${var.region}:${var.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "trust_ec2" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "trust_neptune" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "trust_states" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = [local.state_machine_arn]
    }
  }
}

data "aws_iam_policy_document" "trust_deployer" {
  count = length(var.deployer_principal_arns) > 0 ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = var.deployer_principal_arns
    }
  }
}

# ------------------------------------------------------------------------------------------------
# 1. EMR service role (transient clusters, decision D5)
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "emr_service" {
  name               = "${var.name_prefix}-emr-service"
  assume_role_policy = data.aws_iam_policy_document.trust_emr.json
}

# AWS managed policy for EMR v2. It acts only on EC2 resources tagged
# for-use-with-amazon-emr-managed-policies=true (the network module tags the data subnets and the
# EMR security groups).
resource "aws_iam_role_policy_attachment" "emr_service_v2" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "${local.arn}:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

data "aws_iam_policy_document" "emr_service_passrole" {
  statement {
    sid       = "PassInstanceRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_instance.arn]
    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com*"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_passrole" {
  name   = "pass-instance-role"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_passrole.json
}

# ------------------------------------------------------------------------------------------------
# 2. EMR EC2 instance profile
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "emr_instance" {
  name               = "${var.name_prefix}-emr-instance"
  assume_role_policy = data.aws_iam_policy_document.trust_ec2.json
}

resource "aws_iam_instance_profile" "emr_instance" {
  name = "${var.name_prefix}-emr-instance"
  role = aws_iam_role.emr_instance.name
}

data "aws_iam_policy_document" "emr_instance" {
  statement {
    sid       = "ListInputs"
    actions   = ["s3:ListBucket"]
    resources = [local.samples, local.raw, local.artifacts]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["samples/*", "raw/*", "artifacts/*"]
    }
  }
  statement {
    sid     = "ReadInputs"
    actions = ["s3:GetObject"]
    resources = [
      "${local.samples}/samples/*",
      "${local.raw}/raw/*",
      "${local.artifacts}/artifacts/*",
    ]
  }
  statement {
    sid       = "ListOutputs"
    actions   = ["s3:ListBucket", "s3:ListBucketMultipartUploads"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for p in local.emr_output_prefixes : "${p}/*"]
    }
  }
  statement {
    sid       = "ReadOutputs"
    actions   = ["s3:GetObject"]
    resources = [for p in local.emr_output_prefixes : "${local.curated}/${p}/*"]
  }
  statement {
    sid = "WriteBoundedOutputs"
    # Review finding R1-F7: every current stage of scripts/run-bounded-pipeline.sh (hadoop, spark
    # part-a, part-b) writes only under bounded-smoke/, and no hadoop or spark main writes
    # run-manifests/. Writes are limited to that prefix; a full-corpus stage needs a new grant.
    # DeleteObject: the Hadoop output committer removes its _temporary directory. Versioning keeps
    # every deleted version, so no committed output is lost.
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["${local.curated}/${local.emr_write_prefix}/*"]
  }
  statement {
    sid       = "WriteClusterLogs"
    actions   = ["s3:PutObject"]
    resources = ["${local.logs}/logs/emr/*"]
  }
  statement {
    sid       = "EmrSystemBucketsRead"
    actions   = ["s3:GetObject"]
    resources = local.emr_system_read
  }
  statement {
    sid       = "EmrSystemBucketsWrite"
    actions   = ["s3:PutObject"]
    resources = local.emr_system_write
  }
  statement {
    sid       = "DataKeyViaS3"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3]
    }
  }
}

resource "aws_iam_role_policy" "emr_instance" {
  name   = "pipeline-data-access"
  role   = aws_iam_role.emr_instance.id
  policy = data.aws_iam_policy_document.emr_instance.json
}

# ------------------------------------------------------------------------------------------------
# 3. ECS task execution role (image pull, log delivery, secret injection)
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "ecs_execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.trust_ecs_tasks.json
}

data "aws_iam_policy_document" "ecs_execution" {
  statement {
    sid = "EcrAuthToken"
    #checkov:skip=CKV_AWS_356:ecr:GetAuthorizationToken supports no resource-level permission.
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid       = "PullServiceImage"
    actions   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    resources = [local.ecr_repository_arn]
  }
  statement {
    sid       = "WriteTaskLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [local.log_stream_arns["api"], local.log_stream_arns["worker"], local.log_stream_arns["embedding_ingestion"]]
  }
  statement {
    sid       = "InjectApiKeyDigests"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.api_key_secret_arn]
  }
  statement {
    sid       = "DecryptSecretViaSecretsManager"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.secrets]
    }
  }
}

resource "aws_iam_role_policy" "ecs_execution" {
  name   = "task-execution"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_execution.json
}

# ------------------------------------------------------------------------------------------------
# Shared runtime statements (API and worker): release exports, vector reads, Bedrock both models.
# ------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "runtime_common" {
  statement {
    sid       = "ListReleaseExports"
    actions   = ["s3:ListBucket"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*", "semantic-context/*"]
    }
  }
  statement {
    sid       = "ReadReleaseExports"
    actions   = ["s3:GetObject"]
    resources = ["${local.curated}/graph/*", "${local.curated}/semantic-context/*"]
  }
  statement {
    sid       = "SessionAndEvidenceTables"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query"]
    resources = local.runtime_write_tables
  }
  statement {
    sid       = "ReadOnlyTables"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = local.runtime_read_tables
  }
  statement {
    sid       = "QueryVectorIndexes"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = local.vector_index_arns
  }
  statement {
    sid       = "InvokeApprovedModels"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.generation_model_arn, local.embedding_model_arn]
  }
  statement {
    sid       = "DataKeyViaServices"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3, local.via.dynamodb, local.via.sqs, local.via.s3vectors]
    }
  }
}

# ------------------------------------------------------------------------------------------------
# 4. ECS runtime task role: REST API (modules/services, rag in-process; decision D11)
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "api_task" {
  name               = "${var.name_prefix}-api-task"
  assume_role_policy = data.aws_iam_policy_document.trust_ecs_tasks.json
}

data "aws_iam_policy_document" "api_task" {
  source_policy_documents = [data.aws_iam_policy_document.runtime_common.json]
  statement {
    sid       = "EnqueueJobs"
    actions   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = [local.sqs_jobs_arn]
  }
}

resource "aws_iam_role_policy" "api_task" {
  name   = "api-runtime"
  role   = aws_iam_role.api_task.id
  policy = data.aws_iam_policy_document.api_task.json
}

# ------------------------------------------------------------------------------------------------
# 5. ECS worker task role: asynchronous jobs from the jobs queue
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "worker_task" {
  name               = "${var.name_prefix}-worker-task"
  assume_role_policy = data.aws_iam_policy_document.trust_ecs_tasks.json
}

data "aws_iam_policy_document" "worker_task" {
  source_policy_documents = [data.aws_iam_policy_document.runtime_common.json]
  statement {
    sid       = "ConsumeJobs"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"]
    resources = [local.sqs_jobs_arn]
  }
}

resource "aws_iam_role_policy" "worker_task" {
  name   = "worker-runtime"
  role   = aws_iam_role.worker_task.id
  policy = data.aws_iam_policy_document.worker_task.json
}

# ------------------------------------------------------------------------------------------------
# 6. Runtime reader: strictly read-only data access (no writes, no Bedrock). Task role for
#    read-only tasks (smoke checks, a split-out retrieval service) and the read-only Neptune
#    principal that part B grants neptune-db:ReadDataViaQuery.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "runtime_reader" {
  name               = "${var.name_prefix}-runtime-reader"
  assume_role_policy = data.aws_iam_policy_document.trust_ecs_tasks.json
}

data "aws_iam_policy_document" "runtime_reader" {
  statement {
    sid       = "ListReleaseExports"
    actions   = ["s3:ListBucket"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*", "semantic-context/*", "reports/*"]
    }
  }
  statement {
    sid       = "ReadReleaseExports"
    actions   = ["s3:GetObject"]
    resources = ["${local.curated}/graph/*", "${local.curated}/semantic-context/*", "${local.curated}/reports/*"]
  }
  statement {
    sid       = "ReadTables"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = values(local.table_arns)
  }
  statement {
    sid       = "QueryVectorIndexes"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = local.vector_index_arns
  }
  statement {
    sid       = "DecryptViaServices"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3, local.via.dynamodb, local.via.s3vectors]
    }
  }
}

resource "aws_iam_role_policy" "runtime_reader" {
  name   = "read-only"
  role   = aws_iam_role.runtime_reader.id
  policy = data.aws_iam_policy_document.runtime_reader.json
}

# ------------------------------------------------------------------------------------------------
# 7. Embedding ingestion (ECS task): semantic context to S3, vectors to S3 Vectors, embedding
#    model only (decision D13).
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "embedding_ingestion" {
  name               = "${var.name_prefix}-embedding-ingestion"
  assume_role_policy = data.aws_iam_policy_document.trust_ecs_tasks.json
}

data "aws_iam_policy_document" "embedding_ingestion" {
  statement {
    sid       = "ListSources"
    actions   = ["s3:ListBucket"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*", "semantic-context/*"]
    }
  }
  statement {
    sid       = "ReadGraphExports"
    actions   = ["s3:GetObject"]
    resources = ["${local.curated}/graph/*"]
  }
  statement {
    sid       = "WriteSemanticContext"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${local.curated}/semantic-context/*"]
  }
  statement {
    sid       = "WriteVectors"
    actions   = ["s3vectors:PutVectors", "s3vectors:GetVectors"]
    resources = local.vector_index_arns
  }
  statement {
    sid       = "RunLedgerAndRegistry"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query"]
    resources = local.ingestion_tables
  }
  statement {
    sid       = "InvokeEmbeddingModelOnly"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.embedding_model_arn]
  }
  statement {
    sid       = "DataKeyViaServices"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3, local.via.dynamodb, local.via.s3vectors]
    }
  }
}

resource "aws_iam_role_policy" "embedding_ingestion" {
  name   = "embedding-ingestion"
  role   = aws_iam_role.embedding_ingestion.id
  policy = data.aws_iam_policy_document.embedding_ingestion.json
}

# ------------------------------------------------------------------------------------------------
# 8. Neptune bulk-loader role (associated with the cluster in part B; assumed by Neptune)
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "neptune_loader" {
  name               = "${var.name_prefix}-neptune-loader"
  assume_role_policy = data.aws_iam_policy_document.trust_neptune.json
}

data "aws_iam_policy_document" "neptune_loader" {
  statement {
    sid       = "ListGraphExports"
    actions   = ["s3:ListBucket"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*"]
    }
  }
  statement {
    sid       = "ReadGraphExports"
    actions   = ["s3:GetObject"]
    resources = ["${local.curated}/graph/*"]
  }
  statement {
    sid       = "DecryptViaS3"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3]
    }
  }
}

resource "aws_iam_role_policy" "neptune_loader" {
  name   = "bulk-load-read"
  role   = aws_iam_role.neptune_loader.id
  policy = data.aws_iam_policy_document.neptune_loader.json
}

# ------------------------------------------------------------------------------------------------
# 9. Step Functions: transient EMR cluster per bounded run, embedding-ingestion task
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "step_functions" {
  name               = "${var.name_prefix}-step-functions"
  assume_role_policy = data.aws_iam_policy_document.trust_states.json
}

data "aws_iam_policy_document" "step_functions" {
  statement {
    sid = "CreateTaggedClusterOnly"
    #checkov:skip=CKV_AWS_356:elasticmapreduce:RunJobFlow supports no resource ARN; limited by the request tag.
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "elasticmapreduce:RequestTag/scholarchain:environment"
      values   = [var.environment]
    }
  }
  statement {
    sid = "ManageOwnClusters"
    actions = [
      "elasticmapreduce:DescribeCluster",
      "elasticmapreduce:TerminateJobFlows",
      "elasticmapreduce:AddJobFlowSteps",
      "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps",
    ]
    resources = ["${local.arn}:elasticmapreduce:${var.region}:${var.account_id}:cluster/*"]
    condition {
      test     = "StringEquals"
      variable = "elasticmapreduce:ResourceTag/scholarchain:environment"
      values   = [var.environment]
    }
  }
  statement {
    sid = "FindClusterAfterCreateFailure"
    #checkov:skip=CKV_AWS_356:elasticmapreduce:ListClusters supports no resource-level permission. Read-only; used only by the CreateCluster failure path to find a still-starting cluster (R1-F6), which ManageOwnClusters then terminates under the environment tag condition.
    actions   = ["elasticmapreduce:ListClusters"]
    resources = ["*"]
  }
  statement {
    sid       = "PassEmrRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_service.arn, aws_iam_role.emr_instance.arn]
    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["elasticmapreduce.amazonaws.com", "ec2.amazonaws.com*"]
    }
  }
  statement {
    sid       = "RunIngestionTask"
    actions   = ["ecs:RunTask"]
    resources = [local.ingestion_taskdef]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.ecs_cluster_arn]
    }
  }
  statement {
    sid       = "TrackIngestionTask"
    actions   = ["ecs:StopTask", "ecs:DescribeTasks"]
    resources = [local.ecs_task_arns]
  }
  statement {
    sid       = "PassIngestionRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.embedding_ingestion.arn, aws_iam_role.ecs_execution.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
  statement {
    sid     = "SyncIntegrationRules"
    actions = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = [
      for r in [
        "StepFunctionsGetEventForEMRRunJobFlowRule",
        "StepFunctionsGetEventForEMRTerminateJobFlowsRule",
        "StepFunctionsGetEventForEMRAddJobFlowStepsRule",
        "StepFunctionsGetEventsForECSTaskRule",
      ] : "${local.arn}:events:${var.region}:${var.account_id}:rule/${r}"
    ]
  }
  statement {
    sid = "StateMachineLogDelivery"
    #checkov:skip=CKV_AWS_111:CloudWatch Logs vended log delivery actions support no resource-level permission.
    #checkov:skip=CKV_AWS_356:Same as above.
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "step_functions" {
  name   = "pipeline-orchestration"
  role   = aws_iam_role.step_functions.id
  policy = data.aws_iam_policy_document.step_functions.json
}

# ------------------------------------------------------------------------------------------------
# 10. Deployment execution role (scripts/deploy.sh): push the Git-SHA image, roll the two ECS
#     services, verify the revision. No data access, no Terraform state, no IAM writes.
#     Created only when deployer_principal_arns is non-empty.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "deployment" {
  count                = length(var.deployer_principal_arns) > 0 ? 1 : 0
  name                 = "${var.name_prefix}-deployment"
  assume_role_policy   = data.aws_iam_policy_document.trust_deployer[0].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deployment" {
  statement {
    sid = "EcrAuthToken"
    #checkov:skip=CKV_AWS_356:ecr:GetAuthorizationToken supports no resource-level permission.
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "PushServiceImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
    ]
    resources = [local.ecr_repository_arn]
  }
  statement {
    sid = "TaskDefinitions"
    #checkov:skip=CKV_AWS_356:ecs:RegisterTaskDefinition, DescribeTaskDefinition, and ListTaskDefinitions support no resource-level permission.
    # ListTaskDefinitions: scripts/rollback.sh finds the previous revision by family (R1-F4).
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition", "ecs:ListTaskDefinitions"]
    resources = ["*"]
  }
  statement {
    sid       = "RollServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = local.ecs_service_arns
  }
  statement {
    sid       = "VerifyRevision"
    actions   = ["ecs:ListTasks"]
    resources = ["*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.ecs_cluster_arn]
    }
  }
  statement {
    sid       = "DescribeTasks"
    actions   = ["ecs:DescribeTasks"]
    resources = [local.ecs_task_arns]
  }
  statement {
    sid = "TargetHealth"
    #checkov:skip=CKV_AWS_356:elasticloadbalancing Describe actions support no resource-level permission.
    actions   = ["elasticloadbalancing:DescribeTargetHealth", "elasticloadbalancing:DescribeTargetGroups"]
    resources = ["*"]
  }
  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.ecs_execution.arn, aws_iam_role.api_task.arn, aws_iam_role.worker_task.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
  statement {
    sid       = "ListArtifacts"
    actions   = ["s3:ListBucket"]
    resources = [local.artifacts]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["artifacts/*"]
    }
  }
  statement {
    sid       = "UploadArtifacts"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${local.artifacts}/artifacts/*"]
  }
  statement {
    sid       = "ArtifactKeyViaS3"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3]
    }
  }
}

resource "aws_iam_role_policy" "deployment" {
  count  = length(var.deployer_principal_arns) > 0 ? 1 : 0
  name   = "deploy-release"
  role   = aws_iam_role.deployment[0].id
  policy = data.aws_iam_policy_document.deployment.json
}

# ------------------------------------------------------------------------------------------------
# 11. Pipeline runner role (scripts/run-bounded-pipeline.sh; human decision R1-F5). Exactly the
#     calls that script makes: one StartExecution on the pipeline state machine, then reads.
#     No data writes, no PassRole, no IAM. sts:GetCallerIdentity needs no permission.
#     Created only when pipeline_runner_principal_arns is non-empty.
# ------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "trust_pipeline_runner" {
  count = length(var.pipeline_runner_principal_arns) > 0 ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = var.pipeline_runner_principal_arns
    }
  }
}

resource "aws_iam_role" "pipeline_runner" {
  count                = length(var.pipeline_runner_principal_arns) > 0 ? 1 : 0
  name                 = "${var.name_prefix}-pipeline-runner"
  assume_role_policy   = data.aws_iam_policy_document.trust_pipeline_runner[0].json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "pipeline_runner" {
  statement {
    # stepfunctions start-execution (the script's single mutating call).
    sid       = "StartPipelineExecution"
    actions   = ["states:StartExecution"]
    resources = [local.state_machine_arn]
  }
  statement {
    # describe-state-machine, list-tags-for-resource, list-executions (preflight).
    sid       = "InspectStateMachine"
    actions   = ["states:DescribeStateMachine", "states:ListTagsForResource", "states:ListExecutions"]
    resources = [local.state_machine_arn]
  }
  statement {
    # describe-execution, get-execution-history (polling and failure evidence).
    sid       = "InspectExecutions"
    actions   = ["states:DescribeExecution", "states:GetExecutionHistory"]
    resources = ["${local.arn}:states:${var.region}:${var.account_id}:execution:${local.n.state_machine_name}:*"]
  }
  statement {
    # emr list-clusters --active (one-cluster-at-a-time preflight).
    sid = "ListClusters"
    #checkov:skip=CKV_AWS_356:elasticmapreduce:ListClusters supports no resource-level permission; read-only.
    actions   = ["elasticmapreduce:ListClusters"]
    resources = ["*"]
  }
  statement {
    # emr describe-cluster, list-steps (failure evidence), only on this environment's clusters.
    sid       = "InspectOwnClusters"
    actions   = ["elasticmapreduce:DescribeCluster", "elasticmapreduce:ListSteps"]
    resources = ["${local.arn}:elasticmapreduce:${var.region}:${var.account_id}:cluster/*"]
    condition {
      test     = "StringEquals"
      variable = "elasticmapreduce:ResourceTag/scholarchain:environment"
      values   = [var.environment]
    }
  }
  statement {
    # s3api list-objects-v2 on the curated bounded-smoke/ prefix (output immutability check, Spark
    # input presence checks).
    sid       = "ListBoundedOutputs"
    actions   = ["s3:ListBucket"]
    resources = [local.curated]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["bounded-smoke/*"]
    }
  }
  statement {
    # s3api head-object (jar, sample inputs, Spark config, ER manifest, path pairs) and s3 cp of
    # step stdout logs (counters).
    sid     = "ReadPreflightObjects"
    actions = ["s3:GetObject"]
    resources = [
      "${local.samples}/samples/*",
      "${local.curated}/bounded-smoke/*",
      "${local.artifacts}/artifacts/*",
      "${local.logs}/logs/emr/*",
    ]
  }
  statement {
    sid       = "DecryptViaS3"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.via.s3]
    }
  }
}

resource "aws_iam_role_policy" "pipeline_runner" {
  count  = length(var.pipeline_runner_principal_arns) > 0 ? 1 : 0
  name   = "run-bounded-pipeline"
  role   = aws_iam_role.pipeline_runner[0].id
  policy = data.aws_iam_policy_document.pipeline_runner.json
}
