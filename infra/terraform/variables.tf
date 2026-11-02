variable "region" {
  description = "AWS region for all resources"
  type        = string
  default     = "ap-southeast-1"
}

variable "cluster_name" {
  description = "EKS cluster name; also the prefix the IAM deployer policy is scoped to (finsecure-*)"
  type        = string
  default     = "finsecure-eks"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the cluster VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "node_instance_types" {
  description = "EC2 instance types for the managed node group"
  type        = list(string)
  default     = ["m7i-flex.large"]
}

variable "node_desired_size" {
  description = "Desired number of worker nodes"
  type        = number
  default     = 2
}

variable "endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint (pass your own /32; no open default)"
  type        = list(string)

  validation {
    condition     = length(var.endpoint_public_access_cidrs) > 0 && !contains(var.endpoint_public_access_cidrs, "0.0.0.0/0")
    error_message = "Restrict the API endpoint to specific CIDRs (e.g. your IP /32); 0.0.0.0/0 is not allowed."
  }
}

variable "jenkins_iam_user" {
  description = "IAM user Jenkins deploys as (created in the console, see infra/iam/jenkins-deployer-policy.json)"
  type        = string
  default     = "finsecure-jenkins"
}

variable "app_namespace" {
  description = "Kubernetes namespace Jenkins may deploy to"
  type        = string
  default     = "finsecure"
}
