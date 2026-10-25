# Task 3 – Kubernetes and Cloud Deployment

Step-by-step record of how the transaction validation API was deployed to **Amazon EKS**: infrastructure provisioned with Terraform, the app deployed with Kubernetes manifests, then external access and self-healing verified.

Requirements: a Deployment with ≥2 replicas, a Service, a ConfigMap for configurable values, readiness and liveness probes, deployment to managed Kubernetes, external accessibility, and automatic recreation of a deleted pod.

- **Cloud:** AWS, region `ap-southeast-1` (Singapore)
- **Cluster:** EKS `finsecure-eks`, Kubernetes 1.37
- **Image:** `jordyong/transaction-validator:1.0.0-6161ce2` (public, Task 2)
- **Date:** 2026-10-25

---

## 1. Architecture

```
                 Internet
                    │  HTTP :80
          ┌─────────▼──────────┐
          │ AWS Network LB     │  (created by Service type LoadBalancer)
          └─────────┬──────────┘
 VPC 10.0.0.0/16    │                         ap-southeast-1a / 1b
 ┌──────────────────┼──────────────────────────────────────────────┐
 │ public subnets   │  10.0.48.0/24, 10.0.49.0/24   NAT gateway ──► Internet (image pulls)
 │──────────────────┼──────────────────────────────────────────────│
 │ private subnets  ▼  10.0.0.0/20, 10.0.16.0/20                    │
 │   ┌ node m7i-flex.large ┐    ┌ node m7i-flex.large ┐             │
 │   │ transaction-       │     │ transaction-       │              │
 │   │ validator pod :8080│     │ validator pod :8080│              │
 │   └────────────────────┘     └────────────────────┘              │
 └─────────────────────────────────────────────────────────────────┘
        ▲ kubectl (API endpoint restricted to the operator's IP /32)
   EKS control plane (AWS-managed) · Secrets encrypted with KMS · audit logs → CloudWatch
```

| Decision | Why |
| -------- | --- |
| **EKS** | Recommended in the brief. The AWS CLI was already configured, and the account is in AWS. |
| **Terraform** (`infra/terraform/`) with the official `terraform-aws-modules/vpc` (6.x) and `eks` (21.x) modules | Infrastructure as code: reviewable, repeatable, and `terraform destroy` removes everything. That matters because the cluster is **destroyed between sessions** to control cost. |
| Nodes in **private subnets**, one shared NAT gateway | Worker nodes have no public IPs. A single NAT (instead of one per AZ) saves about US$1.40/day for this non-production environment. |
| Public subnets tagged `kubernetes.io/role/elb=1` | Tells the AWS cloud controller where to place internet-facing load balancers. |
| API endpoint public but limited to **my IP /32** | `kubectl` works from the laptop while the Kubernetes API isn't open to the internet. |
| 2× `m7i-flex.large` managed node group (min 2, max 3), one per AZ | 2 vCPU / 8 GiB each, and Free-Tier eligible (see §5.2; `t3.medium` was rejected by the account's Free plan). Two nodes in two AZs, so the replicas never share a node or zone, with headroom for Prometheus/Grafana in Task 6. |
| KMS envelope encryption of Kubernetes Secrets | On by default in the EKS module. Secrets (Task 5) are encrypted at rest with a customer-managed key. |
| Control-plane audit/API/authenticator logs to CloudWatch (7-day retention) | An audit trail for troubleshooting (Task 6), with short retention to limit cost. |
| `enable_cluster_creator_admin_permissions` | The IAM identity that runs Terraform gets cluster-admin through an EKS access entry. No `aws-auth` ConfigMap editing needed. |

### Cost

About US$0.40/h while running: EKS control plane US$0.10, 2× m7i-flex.large about US$0.20, NAT gateway about US$0.06, NLB about US$0.025. The account is on the AWS Free plan, so this comes out of Free plan credits. Workflow: `terraform apply` at the start of a session and `terraform destroy` at the end (see §8).

---

## 2. IAM: least-privilege deployer policy

The `wsl` IAM user used by the AWS CLI had no EKS permissions:

```
An error occurred (AccessDeniedException) when calling the ListClusters operation:
User: arn:aws:iam::<ACCOUNT_ID>:user/wsl is not authorized to perform: eks:ListClusters
```

Instead of attaching `AdministratorAccess`, a scoped customer-managed policy was written: [`infra/iam/terraform-deployer-policy.json`](../infra/iam/terraform-deployer-policy.json).

| Statement | Scope |
| --------- | ----- |
| `ec2:*`, `eks:*`, `autoscaling:*`, `elasticloadbalancing:Describe*` | Only in `ap-southeast-1` (`aws:RequestedRegion` condition) |
| IAM role management + `iam:PassRole` | Only roles named `finsecure-*` |
| IAM policy management | Only policies named `finsecure-*` (plus read access to AWS-managed policies) |
| OIDC provider (IRSA) | Only `oidc.eks.ap-southeast-1.amazonaws.com/*` |
| `iam:CreateServiceLinkedRole` | Only for the EKS, EKS node group, AutoScaling and ELB services |
| `iam:GetRole` on service-linked roles | Only `role/aws-service-role/*` and `role/AWSServiceRoleFor*` (added after the first apply failed, see §5.1) |
| KMS key/alias management | Only in `ap-southeast-1` |
| CloudWatch Logs | Only log groups `/aws/eks/finsecure-*` |
| SSM `GetParameter` | Only `/aws/service/eks/*` (EKS AMI lookup) |

The `finsecure-*` scoping works because the EKS module prefixes every IAM, KMS and log resource with the cluster name (`finsecure-eks`) or node-group name (`finsecure-nodes`).

The account ID is replaced by the placeholder `<ACCOUNT_ID>` in the repo. Fill it in when creating the policy:

```bash
sed "s/<ACCOUNT_ID>/$(aws sts get-caller-identity --query Account --output text)/g" \
  infra/iam/terraform-deployer-policy.json > /tmp/finsecure-deployer-policy.json
```

Applied by an administrator in the AWS console:

1. IAM → Policies → Create policy → JSON → paste the rendered file → name `finsecure-terraform-deployer`
2. IAM → Users → `wsl` → Add permissions → Attach policies directly → `finsecure-terraform-deployer`

Verified:

```bash
$ aws eks describe-cluster-versions --region ap-southeast-1 --query 'clusterVersions[?versionStatus==`STANDARD_SUPPORT`].clusterVersion' --output text
1.37	1.36	1.35	1.34
```

1.37 is the EKS default and newest standard-support version, so it was pinned in `infra/terraform/terraform.tfvars`.

---

## 3. Tooling

kubectl must be within one minor version of the cluster. The installed `/usr/bin/kubectl` was v1.32, so v1.37.1 was installed for the user only (no sudo) and its checksum verified:

```bash
curl -sLO https://dl.k8s.io/release/v1.37.1/bin/linux/amd64/kubectl
curl -sLO https://dl.k8s.io/release/v1.37.1/bin/linux/amd64/kubectl.sha256
echo "$(cat kubectl.sha256)  kubectl" | sha256sum --check      # kubectl: OK
install -m 0755 kubectl ~/.local/bin/kubectl
```

> `~/.local/bin` comes **after** `/usr/bin` in this machine's `PATH`, so plain `kubectl` still resolves to v1.32. Either call `~/.local/bin/kubectl`, or put `export PATH="$HOME/.local/bin:$PATH"` at the end of `~/.zshrc`.

Terraform v1.16.0 and AWS CLI v2.36 were already installed.

---

## 4. Kubernetes manifests (`k8s/`)

Applied together with `kubectl apply -k k8s/` ([`kustomization.yaml`](../k8s/kustomization.yaml)).

| File | Resource | Key settings |
| ---- | -------- | ------------ |
| [`namespace.yaml`](../k8s/namespace.yaml) | Namespace `finsecure` | Isolates the app from system workloads |
| [`configmap.yaml`](../k8s/configmap.yaml) | ConfigMap `transaction-validator-config` | `VALIDATION_MAX_AMOUNT`, `VALIDATION_SUPPORTED_CURRENCIES`, `SERVER_SHUTDOWN=graceful`, `JAVA_OPTS` |
| [`deployment.yaml`](../k8s/deployment.yaml) | Deployment `transaction-validator` | 2 replicas, probes, rolling update, hardened security context, resources |
| [`service.yaml`](../k8s/service.yaml) | Service `transaction-validator` | `type: LoadBalancer` → internet-facing **NLB**, port 80 → 8080 |
| [`pdb.yaml`](../k8s/pdb.yaml) | PodDisruptionBudget | `minAvailable: 1`, so node drains/upgrades never take both replicas down |

### ConfigMap → application config

The ConfigMap is loaded with `envFrom`. Spring Boot's relaxed binding maps `VALIDATION_MAX_AMOUNT` → `validation.max-amount` and so on (see `application.yml`), so **validation limits change without rebuilding the image**.

### Probes

The probes use the Spring Boot Actuator health groups enabled in Task 1 (`management.endpoint.health.probes.enabled=true`):

| Probe | Endpoint | Settings | Purpose |
| ----- | -------- | -------- | ------- |
| `startupProbe` | `/actuator/health/liveness` | every 5s, 12 failures (≤60s) | Gives the JVM time to start before liveness checks begin, so slow starts don't cause restart loops |
| `livenessProbe` | `/actuator/health/liveness` | every 10s, timeout 2s, 3 failures | Restarts a container that has hung |
| `readinessProbe` | `/actuator/health/readiness` | every 5s, timeout 2s, 3 failures | Takes a pod out of the Service endpoints until it can serve traffic |

The custom `/health` endpoint stays as the public, human-friendly check. The probes use the actuator groups because Spring updates their state during startup and graceful shutdown.

### Deployment hardening and availability

- `RollingUpdate` with `maxUnavailable: 0`, `maxSurge: 1`: a new pod must be Ready before an old one is removed, so rollouts have zero downtime (Task 4).
- `revisionHistoryLimit: 10`: keeps ReplicaSets for `kubectl rollout undo` (Task 4 rollback).
- `topologySpreadConstraints` on `kubernetes.io/hostname`: replicas spread across both nodes.
- Resources: requests `250m` CPU / `384Mi` memory, limit `512Mi`. `-XX:MaxRAMPercentage=75` sizes the heap to fit inside that limit.
- Security context: `runAsNonRoot` (uid 10001, matching the image), `readOnlyRootFilesystem` with an `emptyDir` on `/tmp` for Tomcat, `allowPrivilegeEscalation: false`, all Linux capabilities dropped, `RuntimeDefault` seccomp.

### Local dry run with kind

Before spending money on EKS, the manifests were tested on a throwaway local cluster:

```bash
kind create cluster --name finsecure-test --wait 120s
kubectl apply -k k8s/
kubectl -n finsecure rollout status deploy/transaction-validator    # successfully rolled out
kubectl -n finsecure port-forward svc/transaction-validator 18080:80 &
curl localhost:18080/health      # {"status":"UP",...} 200
kind delete cluster --name finsecure-test
```

Both pods became Ready with `readOnlyRootFilesystem: true`, and the ConfigMap limit was applied (20000 SGD → 422 `AMOUNT_LIMIT_EXCEEDED` against 10000.00). The Service stayed `<pending>`, as expected without a cloud load balancer.

---

## 5. Provision EKS with Terraform

```bash
cd infra/terraform
terraform init
MYIP=$(curl -s https://checkip.amazonaws.com)
terraform plan -out=eks.tfplan -var="endpoint_public_access_cidrs=[\"$MYIP/32\"]"
```

```
Plan: 55 to add, 0 to change, 0 to destroy.
```

The resources: VPC, 2 public + 2 private subnets, IGW, NAT gateway + EIP, route tables, EKS cluster, KMS key + alias, cluster IAM role + encryption policy, OIDC provider, security groups and rules, managed node group (launch template + IAM role), the add-ons `vpc-cni`, `kube-proxy` and `coredns`, an access entry, and a CloudWatch log group.

```bash
terraform apply eks.tfplan
```


The first apply took about 12 minutes (EKS control plane: 10m48s) and **failed at the node group**. Two problems had to be fixed before the cluster had nodes.

### 5.1 Problem 1: `iam:GetRole` on the node group service-linked role

```
Error: creating EKS Node Group (finsecure-eks:finsecure-nodes-...): InvalidRequestException:
Failed to validate if SLR: AWSServiceRoleForAmazonEKSNodegroup already exists due to missing permissions for 'iam:GetRole'
```

EKS checks whether the service-linked role `AWSServiceRoleForAmazonEKSNodegroup` exists before creating it. The policy only allowed `iam:GetRole` on `role/finsecure-*`.

The first fix, allowing `GetRole` on `role/aws-service-role/*`, **wasn't enough**:

```bash
$ aws iam get-role --role-name AWSServiceRoleForAmazonEKS          # exists → works
arn:aws:iam::<ACCOUNT_ID>:role/aws-service-role/eks.amazonaws.com/AWSServiceRoleForAmazonEKS
$ aws iam get-role --role-name AWSServiceRoleForAmazonEKSNodegroup # doesn't exist yet → still denied
An error occurred (AccessDenied) ... on resource: role AWSServiceRoleForAmazonEKSNodegroup
```

Lesson: **for a role that doesn't exist yet, IAM checks `GetRole` against an ARN with no path** (`role/AWSServiceRoleForAmazonEKSNodegroup`), so a `role/aws-service-role/*` pattern can't match. The final statement allows both forms:

```json
{
  "Sid": "ReadServiceLinkedRoles",
  "Effect": "Allow",
  "Action": ["iam:GetRole"],
  "Resource": [
    "arn:aws:iam::<ACCOUNT_ID>:role/aws-service-role/*",
    "arn:aws:iam::<ACCOUNT_ID>:role/AWSServiceRoleFor*"
  ]
}
```

Verified: `aws iam get-role --role-name AWSServiceRoleForAmazonEKSNodegroup` now returns `NoSuchEntity` instead of `AccessDenied`. EKS then creates the role itself, using the existing `iam:CreateServiceLinkedRole` permission.

### 5.2 Problem 2: the AWS Free plan rejects `t3.medium`

The next apply created the node group, but it stayed in `CREATING` for over 10 minutes and **no EC2 instances appeared**. The Auto Scaling group's activity log gave the reason:

```bash
$ aws autoscaling describe-scaling-activities --auto-scaling-group-name eks-finsecure-nodes-... --max-items 1
"StatusCode": "Failed",
"StatusMessage": "Could not launch On-Demand Instances. InvalidParameterCombination - The specified instance type
                  is not eligible for Free Tier. ..."
```

The account is on the **AWS Free plan**, which only allows Free-Tier-eligible instance types:

```bash
$ aws ec2 describe-instance-types --region ap-southeast-1 --filters Name=free-tier-eligible,Values=true \
    --query 'InstanceTypes[].[InstanceType,VCpuInfo.DefaultVCpus,MemoryInfo.SizeInMiB]' --output text
t8i.micro 2 1024    t4g.small 2 2048    c7i-flex.large 2 4096   t4g.micro 2 1024
t3.micro  2 1024    t3.small  2 2048    m7i-flex.large 2 8192   t8i.small 2 2048
```

`m7i-flex.large` (2 vCPU, 8 GiB, x86_64, up to 29 pods per node) was chosen. The `*.micro`/`*.small` types are too small for a JVM plus system pods, and allow very few pods per node because of ENI/IP limits.

Recovery steps:

1. Stop the stuck apply **gracefully** (`pkill -INT -x terraform`). Terraform saves state and releases the lock, and the half-created node group is marked *tainted*.
2. Set `node_instance_types` default to `["m7i-flex.large"]` in `variables.tf`.
3. `terraform apply`. The EKS module uses `create_before_destroy` for node groups, so the new group was created **before** the tainted one was deleted:

```
module.eks.module.eks_managed_node_group["finsecure-nodes"].aws_eks_node_group.this[0]: Creation complete after 1m18s
module.eks.aws_eks_addon.this["coredns"]: Creation complete after 24s
module.eks.aws_eks_addon.this["kube-proxy"]: Creation complete after 24s
module.eks.module.eks_managed_node_group["finsecure-nodes"].aws_eks_node_group.this[0]: Destruction complete after 4m17s

Apply complete! Resources: 3 added, 0 changed, 1 destroyed.
```

> Tip: if a node group sits in `CREATING` for more than about 5 minutes, check `describe-scaling-activities` on its Auto Scaling group straight away. EKS itself reports no health issue until the creation finally times out.

---

## 6. Connect kubectl and deploy

```bash
aws eks update-kubeconfig --region ap-southeast-1 --name finsecure-eks
kubectl version
```

```
Client Version: v1.37.1
Server Version: v1.37.0-eks-b9510e3
```

```bash
$ kubectl get nodes -L node.kubernetes.io/instance-type,topology.kubernetes.io/zone
NAME                                            STATUS   VERSION               INSTANCE-TYPE    ZONE
ip-10-0-18-86.ap-southeast-1.compute.internal   Ready    v1.37.1-eks-39aca56   m7i-flex.large   ap-southeast-1b
ip-10-0-3-158.ap-southeast-1.compute.internal   Ready    v1.37.1-eks-39aca56   m7i-flex.large   ap-southeast-1a
```

Deploy everything:

```bash
$ kubectl apply -k k8s/
namespace/finsecure created
configmap/transaction-validator-config created
service/transaction-validator created
deployment.apps/transaction-validator created
poddisruptionbudget.policy/transaction-validator created

$ kubectl -n finsecure rollout status deploy/transaction-validator
deployment "transaction-validator" successfully rolled out
```

### Running pods (≥2 replicas, Ready, on different nodes)

```bash
$ kubectl -n finsecure get deploy,pods,svc,pdb -o wide
NAME                                    READY   UP-TO-DATE   AVAILABLE   IMAGES
deployment.apps/transaction-validator   2/2     2            2           jordyong/transaction-validator:1.0.0-6161ce2

NAME                                         READY   STATUS    RESTARTS   IP            NODE
pod/transaction-validator-7cf9895cc6-2k7sp   1/1     Running   0          10.0.5.69     ip-10-0-3-158.ap-southeast-1.compute.internal
pod/transaction-validator-7cf9895cc6-vzngm   1/1     Running   0          10.0.21.242   ip-10-0-18-86.ap-southeast-1.compute.internal

NAME                            TYPE           CLUSTER-IP     EXTERNAL-IP                                                                          PORT(S)
service/transaction-validator   LoadBalancer   172.20.8.226   a4e0dcc20aa154ad9959c62284cf88c1-d9b13b55edddd8cb.elb.ap-southeast-1.amazonaws.com   80:32135/TCP

NAME                                               MIN AVAILABLE   ALLOWED DISRUPTIONS
poddisruptionbudget.policy/transaction-validator   1               1
```

### Probe, ConfigMap and rollout configuration as applied

```bash
$ kubectl -n finsecure describe deploy transaction-validator
Replicas:               2 desired | 2 updated | 2 total | 2 available | 0 unavailable
StrategyType:           RollingUpdate
RollingUpdateStrategy:  0 max unavailable, 1 max surge
    Image:      jordyong/transaction-validator:1.0.0-6161ce2
    Limits:     memory: 512Mi
    Requests:   cpu: 250m, memory: 384Mi
    Liveness:   http-get http://:http/actuator/health/liveness delay=0s timeout=2s period=10s successThreshold=1 failureThreshold=3
    Readiness:  http-get http://:http/actuator/health/readiness delay=0s timeout=2s period=5s successThreshold=1 failureThreshold=3
    Startup:    http-get http://:http/actuator/health/liveness delay=0s timeout=1s period=5s successThreshold=1 failureThreshold=12
    Environment Variables from:
      transaction-validator-config  ConfigMap  Optional: false
```

---

## 7. Verify

### 7.1 External access through the NLB

The Service got an internet-facing NLB within seconds (`EnsuredLoadBalancer` event), and AWS reported it `active`. A new NLB's DNS name takes a few minutes to become resolvable.

> **Gotcha: WSL DNS negative caching.** Polling the hostname before its DNS record existed made WSL's resolver (`172.21.160.1`, which forwards to Windows) cache an NXDOMAIN, so `curl` failed with exit code 6 (*could not resolve host*) even after the record existed. Public DNS confirmed the record:
> ```bash
> $ curl -s "https://dns.google/resolve?name=<nlb-hostname>&type=A"    # → 54.255.198.71, 52.221.64.161 (one per AZ)
> ```
> Workarounds: wait for the negative cache to expire, run `ipconfig /flushdns` in Windows, or pin the IP with `curl --resolve <host>:80:<ip>`, which still sends the real hostname. The runs below used `--resolve $LB:80:54.255.198.71`; it's left out of the commands for readability.

```bash
LB=a4e0dcc20aa154ad9959c62284cf88c1-d9b13b55edddd8cb.elb.ap-southeast-1.amazonaws.com

$ curl -s -w '\nHTTP %{http_code}\n' http://$LB/health
{"timestamp":"2026-10-25T16:41:53.842167851Z","service":"transaction-validator","status":"UP"}
HTTP 200

$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-3001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.50,"currency":"SGD"}' \
  http://$LB/validate
{"transactionId":"TXN-3001","valid":true,"status":"APPROVED","errors":[]}
HTTP 200

$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-3002","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":12000,"currency":"JPY"}' \
  http://$LB/validate
{"transactionId":"TXN-3002","valid":false,"status":"REJECTED","errors":[
  {"code":"AMOUNT_LIMIT_EXCEEDED","field":"amount","message":"amount exceeds the limit of 10000.00"},
  {"code":"UNSUPPORTED_CURRENCY","field":"currency","message":"currency JPY is not supported"}]}
HTTP 422
```

Both NLB IPs (`54.255.198.71`, `52.221.64.161`) served traffic.

### 7.2 ConfigMap drives application configuration

The same 12000 SGD transaction was sent before and after raising `VALIDATION_MAX_AMOUNT`, with no image rebuild:

```bash
$ kubectl -n finsecure get cm transaction-validator-config -o jsonpath='{.data.VALIDATION_MAX_AMOUNT}'
10000.00
$ curl ... -d '{"transactionId":"TXN-3003",...,"amount":12000,"currency":"SGD"}' http://$LB/validate
{"transactionId":"TXN-3003","valid":false,"status":"REJECTED","errors":[{"code":"AMOUNT_LIMIT_EXCEEDED",...}]} HTTP 422

$ kubectl -n finsecure patch configmap transaction-validator-config --type merge -p '{"data":{"VALIDATION_MAX_AMOUNT":"20000.00"}}'
$ kubectl -n finsecure rollout restart deploy/transaction-validator     # env vars are read at startup
$ kubectl -n finsecure rollout status deploy/transaction-validator
deployment "transaction-validator" successfully rolled out
$ kubectl -n finsecure exec deploy/transaction-validator -- printenv VALIDATION_MAX_AMOUNT
20000.00
$ curl ... same body ...
{"transactionId":"TXN-3003","valid":true,"status":"APPROVED","errors":[]} HTTP 200

$ kubectl apply -k k8s/ && kubectl -n finsecure rollout restart deploy/transaction-validator   # revert to Git
$ curl ... same body ...
{"transactionId":"TXN-3003","valid":false,"status":"REJECTED",...} HTTP 422
```

> `envFrom` values are read only when a container starts, so a ConfigMap change needs a `rollout restart`, which is zero-downtime thanks to `maxUnavailable: 0`. Git stays the source of truth: the patch was for the demo, and `kubectl apply -k` restored the committed value.

### 7.3 Automatic recovery: delete a pod

Health checks were sent through the NLB every ~0.2s for 45s while one pod was deleted:

```bash
$ kubectl -n finsecure get pods -o wide
NAME                                     READY   STATUS    AGE   NODE
transaction-validator-54d9fb8984-kt75v   1/1     Running   20s   ip-10-0-3-158...
transaction-validator-54d9fb8984-t744z   1/1     Running   25s   ip-10-0-18-86...

$ kubectl -n finsecure delete pod transaction-validator-54d9fb8984-kt75v      # 00:42:49
pod "transaction-validator-54d9fb8984-kt75v" deleted from finsecure namespace
```

`kubectl get pods -w --output-watch-events` during the deletion:

```
EVENT     POD                                     PHASE    READY
MODIFIED  transaction-validator-54d9fb8984-kt75v  Running  true     ← terminating
ADDED     transaction-validator-54d9fb8984-qtbpg  Pending  <none>   ← ReplicaSet creates a replacement immediately
MODIFIED  transaction-validator-54d9fb8984-qtbpg  Pending  false
MODIFIED  transaction-validator-54d9fb8984-kt75v  Failed   false    ← JVM exits 143 on SIGTERM (normal)
MODIFIED  transaction-validator-54d9fb8984-qtbpg  Running  false    ← started, waiting for readiness
DELETED   transaction-validator-54d9fb8984-kt75v  Failed   false
MODIFIED  transaction-validator-54d9fb8984-qtbpg  Running  true     ← Ready, added to the NLB targets
```

```bash
$ kubectl -n finsecure get pods -o wide                                      # 00:42:58 – 2/2 Ready again (~9s)
NAME                                     READY   STATUS    RESTARTS   NODE
transaction-validator-54d9fb8984-qtbpg   1/1     Running   0          ip-10-0-3-158...   ← new pod
transaction-validator-54d9fb8984-t744z   1/1     Running   0          ip-10-0-18-86...

$ kubectl -n finsecure get events --field-selector involvedObject.kind=ReplicaSet
SuccessfulCreate   Created pod: transaction-validator-54d9fb8984-qtbpg
```

Requests during the deletion:

```
$ sort load.txt | uniq -c
    189 200
```

**189/189 requests succeeded.** The surviving replica kept serving while the readiness probe kept the new pod out of the NLB until it was ready.

---

## 8. Cost control: tear down and recreate

The cluster is destroyed at the end of each working session and recreated from code at the start of the next:

```bash
# teardown (~10–15 min). Delete the Service FIRST so the cloud controller removes the NLB;
# otherwise the NLB and its ENIs block VPC deletion.
kubectl delete -k k8s/
cd infra/terraform && terraform destroy -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"

# recreate (~15 min)
terraform apply -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
aws eks update-kubeconfig --region ap-southeast-1 --name finsecure-eks
kubectl apply -k ../../k8s/
```

The NLB hostname changes on every recreate. Get it with `kubectl -n finsecure get svc transaction-validator`.

Terraform state (`*.tfstate`) is kept locally in `infra/terraform/` and is git-ignored. It holds resource IDs and must not be lost while the cluster exists, or the resources would have to be imported or deleted by hand. A remote S3 backend would remove that risk, but would need extra S3/DynamoDB permissions.

---

## 9. Evidence checklist (Task 3 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| Deployment and Service configurations | [`k8s/deployment.yaml`](../k8s/deployment.yaml), [`k8s/service.yaml`](../k8s/service.yaml); `kubectl get deploy,svc` (§6) |
| ConfigMap | [`k8s/configmap.yaml`](../k8s/configmap.yaml); change-and-verify demo (§7.2) |
| Readiness and liveness probe configuration | `deployment.yaml` + `kubectl describe deploy` output (§6) |
| Evidence of running pods | `kubectl get pods -o wide`: 2/2 Running, on different nodes in different AZs (§6) |
| External application access | `curl http://<nlb>/health` → 200 and `/validate` results (§7.1); screenshot of the AWS console EC2 → Load Balancers page |
| Evidence of automatic recovery | Pod delete + watch events + 189/189 successful requests (§7.3) |
| Managed Kubernetes | EKS console screenshot of `finsecure-eks` (Active, 1.37) and its node group; Terraform `Apply complete!` (§5.2) |
