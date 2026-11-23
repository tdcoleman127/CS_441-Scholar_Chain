# ScholarChain DynamoDB tables. Names and keys follow modules/storage:
#   kv/KeyValueStore.scala      Table enum: run-ledger, entity-link-cache, query-session,
#                               evidence-bundle, artifact-registry; physical name <prefix>-<logical>
#   kv/DynamoDbKeyValueStore    string key attributes "pk" (partition) and "sk" (sort); numeric
#                               "ttl" (epoch seconds) set only on QuerySession items. No GSIs or LSIs:
#                               the store reads by key and by partition only (queryPartition).
#
# Capacity: on-demand (PAY_PER_REQUEST). Dev traffic is bursty and near zero most of the time
# (pipeline runs and short query sessions), so provisioned capacity would bill idle RCU/WCU every
# hour; on-demand bills only requests and has no capacity maximum to tune. Cost is bounded by the
# bounded pipeline and the ECS desired-count cap (0 or 1 per service).
#
# Encryption: SSE with the project CMK. PITR: on for every table that holds pipeline or evidence
# state; off for query-session, whose items are short-lived (TTL) and rebuilt by a new query.
# Deletion protection: on unless the explicit course cleanup mode is on (data_cleanup_mode).

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

variable "table_prefix" {
  description = "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX."
  type        = string
}

variable "tables" {
  description = "Logical table names (KeyValueStore.scala Table enum)."
  type        = list(string)
}

variable "kms_key_arn" {
  type = string
}

variable "data_cleanup_mode" {
  description = "Course cleanup mode: turns deletion protection off. Apply once with true before an approved destroy."
  type        = bool
}

variable "ttl_tables" {
  description = "Tables whose items carry the numeric ttl attribute (DynamoDbKeyValueStore writes ttl only for QuerySession)."
  type        = list(string)
  default     = ["query-session"]
}

variable "pitr_disabled_tables" {
  description = "Tables without point-in-time recovery (ephemeral, TTL-expired data)."
  type        = list(string)
  default     = ["query-session"]
}

resource "aws_dynamodb_table" "this" {
  #checkov:skip=CKV_AWS_28:PITR is on for every table except query-session (ephemeral, TTL-expired session state); see pitr_disabled_tables.
  for_each = toset(var.tables)

  name         = "${var.table_prefix}-${each.value}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  point_in_time_recovery {
    enabled = !contains(var.pitr_disabled_tables, each.value)
  }

  dynamic "ttl" {
    for_each = contains(var.ttl_tables, each.value) ? [1] : []
    content {
      attribute_name = "ttl"
      enabled        = true
    }
  }

  deletion_protection_enabled = !var.data_cleanup_mode

  tags = { "scholarchain:data-role" = "kv-${each.value}" }
}

output "table_names" {
  value = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "table_arns" {
  value = { for k, t in aws_dynamodb_table.this : k => t.arn }
}
