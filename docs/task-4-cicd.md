# Task 4 – CI/CD Pipeline Automation

Step-by-step record of how the transaction validation API got a Jenkins pipeline. A push to GitHub triggers the pipeline. It builds and tests the code, builds and pushes the container image, deploys it to EKS, and can roll back to a known-good version.

Requirements: connect Jenkins to the Git repository; a Jenkinsfile with Checkout → Build → Test → Package → Security Checks → Container Build → Push → Deploy; trigger on source change; show that a failed test fails the pipeline and prevents deployment; automate image build/push and Kubernetes deployment; show a rollout of a new version; show rollback to a known-good version.

- **CI server:** Jenkins LTS (JDK 21) in Docker on the developer machine, configured entirely as code (JCasC)
- **Repo:** `https://github.com/jordyong/nus-devops-capstone` (branch `main`)
- **Registry:** Docker Hub `jordyong/transaction-validator`
- **Target:** EKS `finsecure-eks`, namespace `finsecure` (Task 3)
- **Date:** 2026-11-02

---

## 1. Architecture

```
 developer ── git push ──► GitHub ── push webhook ──► smee.io channel
                                                          │ (SSE, outbound only)
 ┌──────────────── laptop (Docker) ───────────────────────▼──────────────┐
 │  smee-client ── POST /github-webhook/ ──► Jenkins (localhost:8080)    │
 │                                            │  git clone (public HTTPS) │
 │                                            │  ./mvnw build/test/package│
 │   /var/run/docker.sock ◄── docker build ───┤                           │
 └────────────────────────────────────────────┼───────────────────────────┘
                     docker push (token)      │ aws eks update-kubeconfig + kubectl
                            ▼                 ▼  (IAM user finsecure-jenkins)
                 Docker Hub jordyong/   EKS finsecure-eks ─ ns finsecure
                 transaction-validator  Deployment transaction-validator (2 replicas)
```

| Decision | Why |
| -------- | --- |
| **Jenkins in Docker, configured by JCasC + Job DSL** (`jenkins/`) | The whole CI server is code: plugins (`plugins.txt`), admin user, credentials and the pipeline job (`casc.yaml`). `docker compose up` gives an identical Jenkins every time. The setup wizard is disabled. |
| **smee.io relay** for the GitHub webhook | Jenkins only listens on `127.0.0.1` and has no public endpoint. The smee client keeps an outbound connection to smee.io and replays GitHub's webhook to Jenkins. That gives real push-triggered builds with no inbound port opened. |
| **Host Docker socket** mounted into Jenkins, with the jenkins user added to the socket's group (`DOCKER_GID`) | Image builds reuse the host Docker engine, and Jenkins doesn't run as root. In production this would be an ephemeral build agent or Kaniko/BuildKit pod instead (the socket is root-equivalent). |
| **Secrets as Docker secrets → JCasC `${...}`** | `secrets/` lives in the workspace, outside the repo. Docker mounts the files at `/run/secrets/`, and JCasC turns them into Jenkins credentials (`dockerhub`, `aws-jenkins`). Nothing secret is in git, the image or `casc.yaml`. |
| **Dedicated IAM user `finsecure-jenkins`** | Least privilege, see §2. The pipeline never uses the `wsl` admin-ish deployer identity. |
| **Image tag `<pom version>-<git short sha>`** (plus `latest`) | Every commit gives an immutable, traceable tag. Deployments always use the immutable tag, so a rollback can name an exact image. |
| **Deploy with `kubectl set image`** + `change-cause` annotation | Each Jenkins deploy becomes one Deployment revision labelled `Jenkins #N: <image>`, which makes `kubectl rollout history` readable (`revisionHistoryLimit: 10`, Task 3). |
| **Auto-undo on failed rollout** | If `rollout status` doesn't succeed within 180 s, the Deploy stage runs `kubectl rollout undo` and fails the build. |
| **`ROLLBACK_TAG` parameter** | A rollback build skips Build…Push and redeploys an existing, already tested image tag. Rolling back takes about 20 s and rebuilds nothing. |
| **In-cluster smoke test** | After Deploy, a throwaway `curlimages/curl` pod calls `http://transaction-validator/health` through the Service. It doesn't depend on NLB DNS propagation. |
| Security Checks stage is a placeholder | The stage exists in the right place. SonarQube, dependency scanning and Trivy gates are added in Task 5. |

---

## 2. IAM and cluster access for Jenkins

Jenkins authenticates to AWS as its own IAM user, **`finsecure-jenkins`**. The `wsl` CLI user cannot create IAM users, so the user was created in the console with one inline policy, [`infra/iam/jenkins-deployer-policy.json`](../infra/iam/jenkins-deployer-policy.json):

```json
{ "Effect": "Allow", "Action": "eks:DescribeCluster",
  "Resource": "arn:aws:eks:ap-southeast-1:<ACCOUNT_ID>:cluster/finsecure-eks" }
```

`eks:DescribeCluster` is all `aws eks update-kubeconfig` needs. What the user may do *inside* Kubernetes comes from an **EKS access entry** in Terraform (`infra/terraform/main.tf`). `AmazonEKSEditPolicy` is scoped to the `finsecure` namespace only:

```hcl
access_entries = {
  jenkins = {
    principal_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:user/${var.jenkins_iam_user}"
    policy_associations = {
      edit = {
        policy_arn   = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"
        access_scope = { type = "namespace", namespaces = [var.app_namespace] }
      }
    }
  }
}
```

Verified with the Jenkins keys after `terraform apply`:

```
$ aws eks list-access-entries --cluster-name finsecure-eks --region ap-southeast-1
    "arn:aws:iam::<ACCOUNT_ID>:user/finsecure-jenkins",  ...

$ kubectl auth can-i ...            # as finsecure-jenkins
patch deploy (finsecure): yes
create pods (finsecure):  yes       # smoke-test pod
attach pods (finsecure):  yes
get deploy (default):     no
delete namespaces:        no
```

Docker Hub access uses a **personal access token** with read/write scope (not the account password), stored as the `dockerhub` credential. The pipeline logs in with `--password-stdin` into a per-build `DOCKER_CONFIG` inside the workspace, logs out in `post`, and deletes that directory at the end of every build.

---

## 3. Jenkins setup (`jenkins/`)

| File | Purpose |
| ---- | ------- |
| `Dockerfile` | `jenkins/jenkins:lts-jdk21` + Docker CLI (+ buildx), AWS CLI v2, kubectl v1.37.1 (sha256-verified). Plugins from `plugins.txt` are installed at build time. |
| `plugins.txt` | configuration-as-code, job-dsl, workflow-aggregator, git, github, credentials-binding, junit, timestamper, ws-cleanup, pipeline stage/graph views |
| `casc.yaml` | Admin user, authorization, Jenkins URL, credentials `dockerhub` + `aws-jenkins`, and the `transaction-validator` pipeline job (SCM = this repo, `githubPush()` trigger, `scriptPath('Jenkinsfile')`) |
| `docker-compose.yml` | `jenkins` (bound to `127.0.0.1:8080`, Docker socket, secrets) + `smee` relay |

Workspace-only secret files (`../../secrets/`, never committed, no trailing newlines): `jenkins_admin_password`, `dockerhub_token`, `aws_access_key_id`, `aws_secret_access_key`, and `jenkins.env` (`SMEE_URL`, `DOCKER_GID`).

```bash
cd jenkins
docker compose --env-file ../../secrets/jenkins.env up -d --build   # first time / after changes
docker compose --env-file ../../secrets/jenkins.env up -d           # later sessions
```

GitHub webhook: repo **Settings → Webhooks** → Payload URL = the smee channel URL, content type `application/json`, event **push**.

### 3.1 Problem 1: webhook rejected with 405

After the first push, the smee client logged

```
POST http://jenkins:8080/github-webhook/ - 405
```

and Jenkins logged `java.lang.Exception: Method POST required` from `RequirePostWithGHHookPayload`. A hand-made `curl -X POST` to `/github-webhook/` returned **200**, so Jenkins itself was fine. To isolate the relay, a test ping was posted to the smee channel. It reproduced the 405 with `smee-client` **5.0.0** (the version `npx smee-client` resolved), and **200** with `smee-client@2` in a throwaway container on the same network. Fix: pin `smee-client@2.0.4` in `docker-compose.yml` (commit `536b54a`). After that:

```
POST http://jenkins:8080/github-webhook/ - 200
INFO  o.j.p.g.w.s.DefaultPushGHEventSubscriber$1#run: Poked transaction-validator
INFO  c.c.jenkins.GitHubPushTrigger$1#run: SCM changes detected in transaction-validator. Triggering #1
```

### 3.2 Problem 2: checkout failed, private repository

Build #1 (started by the push above) failed before the Jenkinsfile was even read:

```
Started by GitHub push by jordyong
stderr: remote: Invalid username or token. Password authentication is not supported for Git operations.
fatal: Authentication failed for 'https://github.com/jordyong/nus-devops-capstone.git/'
Finished: FAILURE
```

The repository was private and the job had no Git credential. The two options were a fine-grained read-only PAT as a Jenkins credential, or making the repo public. The repo was **made public**, since it has to be shared for assessment anyway and contains no secrets. `git ls-remote` without credentials then succeeded, and build #2 was started manually, because the webhook for that commit had already been consumed by build #1.

---

## 4. The pipeline ([`Jenkinsfile`](../Jenkinsfile))

| Stage | What it does | Skipped when `ROLLBACK_TAG` set |
| ----- | ------------ | :-: |
| Checkout | `checkout scm`, computes `GIT_SHORT`, `APP_VERSION` (from `pom.xml`) and `IMAGE_TAG`, and sets the build name | |
| Build | `./mvnw -B -ntp clean compile` | ✔ |
| Test | `./mvnw -B -ntp test`; `junit` publishes `target/surefire-reports/*.xml` (always, so failures are shown) | ✔ |
| Package | `./mvnw package -DskipTests`; archives + fingerprints `transaction-validator.jar` | ✔ |
| Security Checks | Placeholder (Task 5: SonarQube, dependency check, Trivy gate) | ✔ |
| Container Build | `docker build --build-arg VERSION --build-arg REVISION -t <image>:<tag> -t <image>:latest .` | ✔ |
| Push | `docker login` (token, stdin) → push both tags → `docker logout` | ✔ |
| Deploy | `aws eks update-kubeconfig` (into the workspace) → `kubectl set image` → annotate `change-cause` → `rollout status --timeout=180s`, otherwise `rollout undo` and fail | |
| Smoke Test | Throwaway curl pod → `http://transaction-validator/health` (5 retries) | |

Pipeline options: `disableConcurrentBuilds()` (two deploys never race), `timeout(30 min)`, `timestamps()`, and keep the last 20 builds. Because a stage failure marks the build failed, every later stage is **skipped**. That is how a failing test prevents Container Build, Push and Deploy.

---

## 5. Successful pipeline run (build #2)

`terraform apply` (cluster with the Jenkins access entry) → `kubectl apply -k k8s/` (initial Deployment, image `1.0.0-6161ce2` from Task 2) → build #2 at commit `536b54a`.

```
#2 1.0.0-536b54a  SUCCESS  152s
  Checkout         SUCCESS         1.4s
  Build            SUCCESS        13.5s
  Test             SUCCESS        11.2s
  Package          SUCCESS         6.4s
  Security Checks  SUCCESS         0.1s
  Container Build  SUCCESS        41.7s
  Push             SUCCESS        30.9s
  Deploy           SUCCESS        30.6s
  Smoke Test       SUCCESS         7.5s
```

Key console lines:

```
Checking out Revision 536b54a07da0336d322299cb6439d12a6e1e3151 (refs/remotes/origin/main)
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
+ docker build --build-arg VERSION=1.0.0 --build-arg REVISION=536b54a -t jordyong/transaction-validator:1.0.0-536b54a -t jordyong/transaction-validator:latest .
1.0.0-536b54a: digest: sha256:5e4b5c1383ae4de7ae08d3593b88ab26742ef193fe6e814f2a209d22729e2f37 size: 2616
deployment.apps/transaction-validator image updated
deployment "transaction-validator" successfully rolled out
REVISION  CHANGE-CAUSE
1         <none>
2         Jenkins #2: jordyong/transaction-validator:1.0.0-536b54a
transaction-validator-59748d4746-4zx6f   1/1   Running   0   27s   10.0.6.212   ip-10-0-13-28...
transaction-validator-59748d4746-frm8z   1/1   Running   0   12s   10.0.22.67   ip-10-0-21-245...
{"status":"UP","service":"transaction-validator","timestamp":"2026-11-02T14:36:59.294659559Z"}
Deployed jordyong/transaction-validator:1.0.0-536b54a to finsecure-eks/finsecure
Finished: SUCCESS
```

(The smoke test also prints `warning: couldn't attach to pod/smoke-2, falling back to streaming logs`. The curl container finished before `kubectl run -i` could attach, so kubectl reads its logs instead. The result is the same.)

---

## 6. A failed test fails the pipeline and prevents deployment (build #3)

A deliberately wrong assertion was pushed (commit `2bca2d2`) in `TransactionValidatorTest.rejectsAmountAboveLimit`, expecting `APPROVED` for an amount above the limit:

```diff
-        assertThat(result.status()).isEqualTo(ValidationResult.Status.REJECTED);
+        assertThat(result.status()).isEqualTo(ValidationResult.Status.APPROVED);
```

The push triggered build #3 through the webhook:

```
Started by GitHub push by jordyong
[ERROR] com.finsecure.validator.service.TransactionValidatorTest.rejectsAmountAboveLimit -- Time elapsed: 0.007 s <<< FAILURE!
expected: APPROVED
 but was: REJECTED
[ERROR] Tests run: 27, Failures: 1, Errors: 0, Skipped: 0
Stage "Package" skipped due to earlier failure(s)
Stage "Security Checks" skipped due to earlier failure(s)
Stage "Container Build" skipped due to earlier failure(s)
Stage "Push" skipped due to earlier failure(s)
Stage "Deploy" skipped due to earlier failure(s)
Stage "Smoke Test" skipped due to earlier failure(s)
Pipeline failed – no new version was deployed (or the failed rollout was undone).
Finished: FAILURE
```

Jenkins test report for #3: `failCount 1, passCount 26`. No image `1.0.0-2bca2d2` was built or pushed, and the cluster kept running the previous version:

```
$ kubectl -n finsecure get deploy -o wide
NAME                    READY   UP-TO-DATE   AVAILABLE   ...   IMAGES
transaction-validator   2/2     2            2           ...   jordyong/transaction-validator:1.0.0-536b54a
```

---

## 7. Rollout of a new application version (build #4)

The broken test was reverted (`71658ee`, "Revert demo failing test") and pushed. The webhook triggered build #4, which built, pushed and rolled out a **new image**:

```
Started by GitHub push by jordyong
#4 1.0.0-71658ee  SUCCESS  77s
1.0.0-71658ee: digest: sha256:dffec9ece960a45ba698175002d3a62aa51581bee3a1fcb58e83b634955b8594 size: 2616

$ kubectl -n finsecure rollout history deploy/transaction-validator
REVISION  CHANGE-CAUSE
1         <none>
2         Jenkins #2: jordyong/transaction-validator:1.0.0-536b54a
3         Jenkins #4: jordyong/transaction-validator:1.0.0-71658ee
```

The rolling update (`maxUnavailable: 0`, readiness probe, Task 3) replaces pods one at a time, so the service stays available during the rollout.

---

## 8. Rollback to a known-good version

### 8.1 Pipeline rollback, `ROLLBACK_TAG` (build #5)

**Build with Parameters** → `ROLLBACK_TAG=1.0.0-536b54a` (the version verified in build #2). Through the API:

```bash
curl -u admin:<pw> -H "Jenkins-Crumb: <crumb>" -X POST \
  "http://localhost:8080/job/transaction-validator/buildWithParameters?ROLLBACK_TAG=1.0.0-536b54a"
```

```
#5 rollback → 1.0.0-536b54a  SUCCESS  23s
  Checkout         SUCCESS         1.3s
  Build            NOT_EXECUTED
  Test             NOT_EXECUTED
  Package          NOT_EXECUTED
  Security Checks  NOT_EXECUTED
  Container Build  NOT_EXECUTED
  Push             NOT_EXECUTED
  Deploy           SUCCESS        15.2s
  Smoke Test       SUCCESS         3.1s

Stage "Build" skipped due to when conditional        (… same for Test … Push)
deployment.apps/transaction-validator image updated
deployment "transaction-validator" successfully rolled out
Deployed jordyong/transaction-validator:1.0.0-536b54a to finsecure-eks/finsecure

$ kubectl -n finsecure rollout history deploy/transaction-validator
REVISION  CHANGE-CAUSE
1         <none>
3         Jenkins #4: jordyong/transaction-validator:1.0.0-71658ee
4         Jenkins #5: jordyong/transaction-validator:1.0.0-536b54a
$ kubectl -n finsecure get deploy -o wide
transaction-validator   2/2   2   2   ...   jordyong/transaction-validator:1.0.0-536b54a
```

(Revision 2 became revision 4: Kubernetes renumbers a pod template when it is reused.)

### 8.2 Kubernetes-native rollback, `kubectl rollout undo`

The same can be done from the cluster without Jenkins, for example if Jenkins itself is down. Undo returns to the previous revision, here the latest version `1.0.0-71658ee`:

```
$ kubectl -n finsecure rollout undo deploy/transaction-validator
deployment.apps/transaction-validator rolled back
deployment "transaction-validator" successfully rolled out
$ kubectl -n finsecure rollout history deploy/transaction-validator
REVISION  CHANGE-CAUSE
1         <none>
4         Jenkins #5: jordyong/transaction-validator:1.0.0-536b54a
5         Jenkins #4: jordyong/transaction-validator:1.0.0-71658ee
$ kubectl -n finsecure get deploy -o wide
transaction-validator   2/2   2   2   ...   jordyong/transaction-validator:1.0.0-71658ee
```

`kubectl rollout undo --to-revision=N` targets a specific revision. The Deploy stage also runs `rollout undo` automatically when a new version never becomes healthy (§4).

### 8.3 External check after the rollbacks

```
$ curl --resolve <nlb>:80:18.140.153.79 http://<nlb>/health
{"service":"transaction-validator","timestamp":"2026-11-02T14:41:46.538015765Z","status":"UP"}
$ curl --resolve <nlb>:80:18.140.153.79 -H 'Content-Type: application/json' \
    -d '{"transactionId":"TXN-4001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.50,"currency":"SGD"}' \
    http://<nlb>/validate
{"transactionId":"TXN-4001","valid":true,"status":"APPROVED","errors":[]}
HTTP 200
```

---

## 9. Session workflow

```bash
# start
(cd jenkins && docker compose --env-file ../../secrets/jenkins.env up -d)
terraform -chdir=infra/terraform apply -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
aws eks update-kubeconfig --region ap-southeast-1 --name finsecure-eks
kubectl apply -k k8s/
# … git push → pipeline …

# end
kubectl delete -k k8s/          # removes the NLB first
terraform -chdir=infra/terraform destroy -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
(cd jenkins && docker compose --env-file ../../secrets/jenkins.env stop)
```

`kubectl apply -k k8s/` resets the image to the one in `k8s/deployment.yaml`. After recreating the cluster, run the pipeline (or a `ROLLBACK_TAG` build) to deploy the current version. Jenkins reaches the EKS API from the same public IP as the operator, so the `/32` endpoint restriction also covers Jenkins.

---

## 10. Evidence checklist (Task 4 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| Jenkinsfile | [`Jenkinsfile`](../Jenkinsfile) (§4); Jenkins setup as code in [`jenkins/`](../jenkins/) (§3) |
| Jenkins connected to Git, triggered by source change | Builds #3 and #4 show `Started by GitHub push by jordyong`; smee log `POST …/github-webhook/ - 200`; GitHub webhook "Recent Deliveries" screenshot |
| Successful pipeline execution | Build #2 (§5): Stage View screenshot with all stages green, console log, test report 27/27 |
| Failed-test pipeline execution | Build #3 (§6): Stage View with Test red and later stages skipped, test report (1 failure), `kubectl get deploy` still on `1.0.0-536b54a` |
| Automated image build + push | Build #2/#4 `docker push` digests; Docker Hub tags page screenshot (`1.0.0-536b54a`, `1.0.0-71658ee`, `latest`) |
| Application rollout evidence | Build #4 (§7), `kubectl rollout history` with `change-cause` per Jenkins build |
| Rollback evidence | Build #5 `rollback → 1.0.0-536b54a` (§8.1) + `kubectl rollout undo` (§8.2) with history before/after |
