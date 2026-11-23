# ScholarChain network (decision D10): one VPC across two AZs, no NAT gateway.
#
#   public subnets       ALB only (the single public component, decision D12)
#   private app subnets  ECS Fargate tasks (API, worker, embedding ingestion), interface endpoints
#   private data subnets EMR transient clusters, Neptune Serverless
#
# Private subnets have no default route. AWS service access is through the S3 and DynamoDB gateway
# endpoints (free) and optional interface endpoints (one toggle each, see variables.tf for cost).
# Security groups are chained by role; the only CIDR-based ingress rule is the ALB listener.

locals {
  public_cidrs = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i)]
  app_cidrs    = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i + 2)]
  data_cidrs   = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i + 4)]

  interface_service_names = {
    ecr_api         = "com.amazonaws.${var.region}.ecr.api"
    ecr_dkr         = "com.amazonaws.${var.region}.ecr.dkr"
    logs            = "com.amazonaws.${var.region}.logs"
    secretsmanager  = "com.amazonaws.${var.region}.secretsmanager"
    bedrock_runtime = "com.amazonaws.${var.region}.bedrock-runtime"
    s3vectors       = "com.amazonaws.${var.region}.s3vectors"
  }
  enabled_interface_endpoints = {
    for k, name in local.interface_service_names : k => name if var.interface_endpoints[k]
  }
  endpoint_subnet_ids = slice(aws_subnet.app[*].id, 0, var.interface_endpoint_az_count)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.name_prefix}-vpc" }
}

# VPC flow logs to the logs bucket (s3://<logs>/logs/vpc-flow/), not CloudWatch Logs: S3 vended
# delivery costs about half of CloudWatch ingestion and needs no extra IAM role. REJECT-only by
# default keeps volume small while still recording blocked traffic; the logs bucket lifecycle
# expires the objects. The bucket policy statement lives in modules/data_lake.
resource "aws_flow_log" "vpc" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = var.flow_log_traffic_type
  log_destination_type     = "s3"
  log_destination          = "${var.flow_log_bucket_arn}/logs/vpc-flow/"
  max_aggregation_interval = 600

  destination_options {
    file_format                = "plain-text"
    per_hour_partition         = false
    hive_compatible_partitions = false
  }

  tags = { Name = "${var.name_prefix}-vpc-flow" }
}

# Remove every rule from the default security group so nothing can use it by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-default-unused" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-igw" }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name_prefix}-public-${var.availability_zones[count.index]}", "scholarchain:tier" = "public" }
}

resource "aws_subnet" "app" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.app_cidrs[count.index]
  availability_zone = var.availability_zones[count.index]
  tags              = { Name = "${var.name_prefix}-app-${var.availability_zones[count.index]}", "scholarchain:tier" = "app" }
}

resource "aws_subnet" "data" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.data_cidrs[count.index]
  availability_zone = var.availability_zones[count.index]
  tags = merge(
    { Name = "${var.name_prefix}-data-${var.availability_zones[count.index]}", "scholarchain:tier" = "data" },
    var.emr_managed_policy_tag
  )
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-public-rt" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# One private route table, no default route (no NAT). Gateway endpoints attach to it.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-private-rt" }
}

resource "aws_route_table_association" "app" {
  count          = 2
  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "data" {
  count          = 2
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.private.id
}

# Gateway endpoints: no hourly charge.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = { Name = "${var.name_prefix}-s3-gateway" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = { Name = "${var.name_prefix}-dynamodb-gateway" }
}

# Interface endpoints: USD 0.01/h per AZ each (about USD 7.30/month per endpoint in one AZ).
resource "aws_vpc_endpoint" "interface" {
  for_each            = local.enabled_interface_endpoints
  vpc_id              = aws_vpc.this.id
  service_name        = each.value
  vpc_endpoint_type   = "Interface"
  subnet_ids          = local.endpoint_subnet_ids
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
  tags                = { Name = "${var.name_prefix}-${replace(each.key, "_", "-")}-endpoint" }
}

# ------------------------------------------------------------------------------------------------
# Security groups, chained by role. Rules are separate resources so that EMR-managed rules on the
# EMR groups are never removed by Terraform.
# ------------------------------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  #checkov:skip=CKV2_AWS_5:Attached to aws_lb.api in modules/runtime when enable_runtime is true (decision D8); checkov cannot follow the cross-module, count-gated reference.
  name        = "${var.name_prefix}-alb"
  description = "Public ALB: listener from approved CIDRs, forwards only to the API tasks"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-alb" }
}

resource "aws_security_group" "api" {
  #checkov:skip=CKV2_AWS_5:Attached to aws_ecs_service.api in modules/runtime when enable_runtime is true (decision D8); checkov cannot follow the cross-module, count-gated reference.
  name        = "${var.name_prefix}-api"
  description = "REST API Fargate tasks: ingress only from the ALB"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-api" }
}

resource "aws_security_group" "worker" {
  #checkov:skip=CKV2_AWS_5:Attached to aws_ecs_service.worker (enable_runtime) and to the embedding-ingestion RunTask in the pipeline state machine; checkov cannot follow these cross-module references.
  name        = "${var.name_prefix}-worker"
  description = "Worker and embedding-ingestion Fargate tasks: no ingress"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-worker" }
}

resource "aws_security_group" "neptune" {
  #checkov:skip=CKV2_AWS_5:Attached to aws_neptune_cluster.this in modules/neptune when enable_neptune is true (decision D7); checkov cannot follow the cross-module, count-gated reference.
  name        = "${var.name_prefix}-neptune"
  description = "Neptune Serverless: 8182 only from API and worker tasks"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-neptune" }
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name_prefix}-endpoints"
  description = "Interface VPC endpoints: 443 only from ScholarChain workloads"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-endpoints" }
}

resource "aws_security_group" "emr_primary" {
  #checkov:skip=CKV2_AWS_5:Attached by the part B EMR cluster definition.
  name                   = "${var.name_prefix}-emr-primary"
  description            = "EMR primary node (EMR adds its managed rules)"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true
  tags                   = merge({ Name = "${var.name_prefix}-emr-primary" }, var.emr_managed_policy_tag)
}

resource "aws_security_group" "emr_core" {
  #checkov:skip=CKV2_AWS_5:Attached by the part B EMR cluster definition.
  name                   = "${var.name_prefix}-emr-core"
  description            = "EMR core and task nodes (EMR adds its managed rules)"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true
  tags                   = merge({ Name = "${var.name_prefix}-emr-core" }, var.emr_managed_policy_tag)
}

resource "aws_security_group" "emr_service_access" {
  #checkov:skip=CKV2_AWS_5:Attached by the part B EMR cluster definition.
  name                   = "${var.name_prefix}-emr-service-access"
  description            = "EMR service access group for private subnets"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true
  tags                   = merge({ Name = "${var.name_prefix}-emr-service-access" }, var.emr_managed_policy_tag)
}

# ALB
resource "aws_vpc_security_group_ingress_rule" "alb_listener" {
  #checkov:skip=CKV_AWS_260:Decision D12: the public ALB is the only public component, HTTP:80 only in dev (no certificate); API-key auth stays on. Narrow alb_ingress_cidrs in dev.tfvars to restrict it.
  for_each          = toset(var.alb_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "ALB listener (HTTP in dev, API-key auth enforced by the service)"
  ip_protocol       = "tcp"
  from_port         = var.alb_listener_port
  to_port           = var.alb_listener_port
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to API tasks"
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "From the ALB only"
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  referenced_security_group_id = aws_security_group.alb.id
}

# Workload egress: gateway endpoint prefix lists, interface endpoints, Neptune.
locals {
  workload_sgs = {
    api    = aws_security_group.api.id
    worker = aws_security_group.worker.id
  }
  endpoint_clients = {
    api         = aws_security_group.api.id
    worker      = aws_security_group.worker.id
    emr_primary = aws_security_group.emr_primary.id
    emr_core    = aws_security_group.emr_core.id
  }
}

resource "aws_vpc_security_group_egress_rule" "workload_to_s3" {
  for_each          = local.workload_sgs
  security_group_id = each.value
  description       = "HTTPS to S3 through the gateway endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
}

resource "aws_vpc_security_group_egress_rule" "workload_to_dynamodb" {
  for_each          = local.workload_sgs
  security_group_id = each.value
  description       = "HTTPS to DynamoDB through the gateway endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = aws_vpc_endpoint.dynamodb.prefix_list_id
}

resource "aws_vpc_security_group_egress_rule" "workload_to_endpoints" {
  for_each                     = local.workload_sgs
  security_group_id            = each.value
  description                  = "HTTPS to interface endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.endpoints.id
}

resource "aws_vpc_security_group_egress_rule" "workload_to_neptune" {
  for_each                     = local.workload_sgs
  security_group_id            = each.value
  description                  = "Neptune HTTPS/Gremlin/openCypher port"
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
  referenced_security_group_id = aws_security_group.neptune.id
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_workloads" {
  for_each                     = local.workload_sgs
  security_group_id            = aws_security_group.neptune.id
  description                  = "From ${each.key} tasks"
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_clients" {
  for_each                     = local.endpoint_clients
  security_group_id            = aws_security_group.endpoints.id
  description                  = "HTTPS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = each.value
}

# EMR in a private subnet: the service access group must accept 9443 from the primary group.
resource "aws_vpc_security_group_ingress_rule" "emr_service_access_from_primary" {
  security_group_id            = aws_security_group.emr_service_access.id
  description                  = "EMR service access from the primary node"
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  referenced_security_group_id = aws_security_group.emr_primary.id
}
