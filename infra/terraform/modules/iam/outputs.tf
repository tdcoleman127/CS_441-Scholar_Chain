output "role_arns" {
  description = "Role ARNs by purpose. deployment is null until deployer_principal_arns is set."
  value = {
    emr_service         = aws_iam_role.emr_service.arn
    emr_instance        = aws_iam_role.emr_instance.arn
    ecs_execution       = aws_iam_role.ecs_execution.arn
    api_task            = aws_iam_role.api_task.arn
    worker_task         = aws_iam_role.worker_task.arn
    runtime_reader      = aws_iam_role.runtime_reader.arn
    embedding_ingestion = aws_iam_role.embedding_ingestion.arn
    neptune_loader      = aws_iam_role.neptune_loader.arn
    step_functions      = aws_iam_role.step_functions.arn
    deployment          = try(aws_iam_role.deployment[0].arn, null)
    pipeline_runner     = try(aws_iam_role.pipeline_runner[0].arn, null)
  }
}

output "pipeline_runner_role_arn" {
  description = "Role for scripts/run-bounded-pipeline.sh. null until pipeline_runner_principal_arns is set."
  value       = try(aws_iam_role.pipeline_runner[0].arn, null)
}

output "role_names" {
  description = "Role names, for part B policy attachments (Neptune data plane)."
  value = {
    api_task            = aws_iam_role.api_task.name
    worker_task         = aws_iam_role.worker_task.name
    runtime_reader      = aws_iam_role.runtime_reader.name
    embedding_ingestion = aws_iam_role.embedding_ingestion.name
  }
}

output "emr_instance_profile_arn" {
  value = aws_iam_instance_profile.emr_instance.arn
}

output "emr_instance_profile_name" {
  description = "RunJobFlow JobFlowRole (part B state machine)."
  value       = aws_iam_instance_profile.emr_instance.name
}

output "emr_service_role_name" {
  description = "RunJobFlow ServiceRole (part B state machine)."
  value       = aws_iam_role.emr_service.name
}
