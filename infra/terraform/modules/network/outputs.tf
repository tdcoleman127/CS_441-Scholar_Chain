output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  value = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  value = aws_subnet.data[*].id
}

output "s3_gateway_endpoint_id" {
  value = aws_vpc_endpoint.s3.id
}

output "dynamodb_gateway_endpoint_id" {
  value = aws_vpc_endpoint.dynamodb.id
}

output "interface_endpoint_ids" {
  value = { for k, e in aws_vpc_endpoint.interface : k => e.id }
}

output "security_group_ids" {
  value = {
    alb                = aws_security_group.alb.id
    api                = aws_security_group.api.id
    worker             = aws_security_group.worker.id
    neptune            = aws_security_group.neptune.id
    endpoints          = aws_security_group.endpoints.id
    emr_primary        = aws_security_group.emr_primary.id
    emr_core           = aws_security_group.emr_core.id
    emr_service_access = aws_security_group.emr_service_access.id
  }
}
