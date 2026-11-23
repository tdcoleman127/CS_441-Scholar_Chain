output "state_bucket_name" {
  description = "Name of the remote-state bucket. Put it in envs/<env>/backend.hcl (uncommitted)."
  value       = aws_s3_bucket.state.bucket
}

output "state_bucket_arn" {
  description = "ARN of the remote-state bucket."
  value       = aws_s3_bucket.state.arn
}
