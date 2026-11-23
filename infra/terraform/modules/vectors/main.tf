# ScholarChain S3 Vectors. Names follow modules/storage vectors/VectorStore.scala:
#   bucket   SCHOLARCHAIN_VECTOR_BUCKET
#   index    <SCHOLARCHAIN_VECTOR_INDEX>-<wire>, wires from the VectorIndex enum
#   metadata VectorMetadata.NonFilterable = {textRef}; every other key is filterable
#   metric   cosine (InMemoryStores.scala, the reference VectorStore, ranks by cosine distance;
#            S3VectorsStore returns the service distance unchanged)
#   dims     1024 float32 (decision D13; rag EmbeddingModels.ProductionDimension)
#
# Every index attribute is ForceNew in hashicorp/aws 6.73.0 (registry doc aws_s3vectors_index):
# changing dimension, metric, data type, or metadata keys replaces the index and deletes its vectors.
#
# Encryption: SSE-KMS with the full CMK ARN on the bucket; indexes inherit it. The KMS key policy
# (modules/kms) lets indexing.s3vectors.amazonaws.com decrypt for this bucket only.
#
# Access policy: none. Every caller is a same-account IAM role whose identity policy (modules/iam)
# names the exact index ARNs, so a resource policy would add no restriction the code path needs.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
  }
}

variable "vector_bucket_name" {
  type = string
}

variable "index_names" {
  description = "Physical index names, <prefix>-<wire>."
  type        = list(string)
}

variable "dimension" {
  type = number
}

variable "distance_metric" {
  type    = string
  default = "cosine"

  validation {
    condition     = contains(["cosine", "euclidean"], var.distance_metric)
    error_message = "distance_metric must be cosine or euclidean."
  }
}

variable "non_filterable_metadata_keys" {
  description = "VectorMetadata.NonFilterable."
  type        = list(string)
  default     = ["textRef"]
}

variable "kms_key_arn" {
  type = string
}

variable "data_cleanup_mode" {
  description = "Course cleanup mode: force_destroy deletes indexes and vectors with the bucket."
  type        = bool
}

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = var.vector_bucket_name
  force_destroy      = var.data_cleanup_mode

  encryption_configuration {
    sse_type    = "aws:kms"
    kms_key_arn = var.kms_key_arn
  }

  tags = { "scholarchain:data-role" = "vectors" }
}

resource "aws_s3vectors_index" "this" {
  for_each = toset(var.index_names)

  index_name         = each.value
  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  data_type          = "float32"
  dimension          = var.dimension
  distance_metric    = var.distance_metric

  metadata_configuration {
    non_filterable_metadata_keys = var.non_filterable_metadata_keys
  }

  tags = { "scholarchain:data-role" = "vector-index" }
}

output "vector_bucket_arn" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_arns" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}
