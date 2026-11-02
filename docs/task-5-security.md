# Task 5 – DevSecOps and Security Integration

Step-by-step record of how security checks were added to the Jenkins pipeline from Task 4. Static analysis, dependency scanning, secret/IaC scanning and container image scanning now run on every push. Any HIGH or CRITICAL finding fails the build before the image is pushed or deployed.

Requirements: integrate code-quality checks or static analysis; scan dependencies for known vulnerabilities; scan the container image; configure at least one security gate that blocks a critical or high-severity finding; remediate the finding and demonstrate a successful build; store credentials and secrets securely, never in source code.

- **Pipeline:** Jenkins job `transaction-validator` ([`Jenkinsfile`](../Jenkinsfile)), push-triggered (Task 4)
- **Builds used as evidence:** #8 – #12. The commit SHAs quoted below are the intermediate commits those builds ran on. They were later squashed into the single Task 5 commit, but Jenkins keeps them in each build's record and image tag.
- **Date:** 2026-11-02

---

## 1. Tools and decisions

| Area | Tool | Runs in stage | Gate |
| ---- | ---- | ------------- | ---- |
| Static analysis / code quality (SAST) | **SpotBugs 4.10.5 + FindSecBugs 1.14.0** (`spotbugs-maven-plugin`, [`pom.xml`](../pom.xml)) | Security Checks | `mvn spotbugs:check` fails on any finding of Medium confidence or higher |
| Dependency vulnerabilities (SCA) | **Trivy 0.76.1** `rootfs` scan of the built `target/transaction-validator.jar` | Security Checks | HIGH / CRITICAL CVE |
| Secrets + IaC misconfiguration | **Trivy** `fs --scanners secret,misconfig` over the repo (Dockerfile, `k8s/`, `infra/terraform/`, `jenkins/`) | Security Checks | HIGH / CRITICAL finding, or any secret |
| Container image | **Trivy** `image` scan of `jordyong/transaction-validator:<tag>` (Alpine OS packages + Java libraries) | Image Scan (new, between Container Build and Push) | HIGH / CRITICAL CVE |
| Reporting | **Warnings Next Generation** plugin (`recordIssues`), plus JSON + HTML reports archived per build | both | – |
| Secret history check | **gitleaks 8.28** over the full git history (one-off evidence, §7) | – | – |

Why these tools (the brief allows equivalents with justification):

- **SpotBugs + FindSecBugs instead of SonarQube/SonarCloud.** It runs inside the Maven build with no server, account or token, and it can fail the build directly. FindSecBugs adds about 140 security rules (injection, weak crypto, unsafe deserialisation, XXE, …) on top of the SpotBugs bug patterns. SonarCloud would add a hosted dashboard, but it needs a third-party account and another stored token. It can be added later as an extra stage without changing the gate design.
- **Trivy for dependencies instead of OWASP Dependency-Check.** Dependency-Check needs an NVD API key, and without one its first database download takes hours. Trivy uses the same vulnerability database (NVD + GitHub advisories + vendor feeds), downloads it in seconds, and is already required for the image scan, so one tool and one database covers dependencies, secrets, IaC and images. Trivy scans the **built jar**, not `pom.xml`, so the result is exactly what ships. Scanning `pom.xml` also made Trivy resolve the dependency tree against Maven Central, which rate-limited us with `429 Too Many Requests`.
- **Trivy installed in the Jenkins image** ([`jenkins/Dockerfile`](../jenkins/Dockerfile)), pinned to v0.76.1 and verified with its SHA-256, rather than run as a `docker run aquasec/trivy` sidecar. The jar and repo live in the Jenkins volume, so a sidecar container couldn't see them without extra mounts. The vulnerability DB is cached in `JENKINS_HOME`.
- **Gate on HIGH and CRITICAL.** The scanners still record every severity in the reports, but only HIGH/CRITICAL fail the build. MEDIUM/LOW findings are visible in Jenkins as a backlog (§6) without blocking releases.
- **Run all scanners, then fail once.** The Security Checks stage collects every failure and stops with one message such as `Security gate failed: SAST …; dependency scan …`. One run therefore shows every problem at once instead of one per push.
- **Accepted risks are explicit and expire.** Findings that are accepted by design go in [`.trivyignore.yaml`](../.trivyignore.yaml), each with a `statement` and an `expired_at` date. After that date the finding blocks the pipeline again and has to be reviewed.

---

## 2. Changes

| File | Change |
| ---- | ------ |
| [`Jenkinsfile`](../Jenkinsfile) | `trivyGate()` helper; real Security Checks stage; new Image Scan stage before Push; `recordIssues` + `archiveArtifacts` for all reports |
| [`jenkins/Dockerfile`](../jenkins/Dockerfile) | Installs Trivy 0.76.1 (checksum-verified) including `contrib/html.tpl` for HTML reports |
| [`jenkins/plugins.txt`](../jenkins/plugins.txt) | `warnings-ng` |
| [`pom.xml`](../pom.xml) | `spotbugs-maven-plugin` with FindSecBugs; later the Spring Boot 4.0.9 upgrade and version 1.1.0 (§5) |
| [`spotbugs-exclude.xml`](../spotbugs-exclude.xml) | Suppression file. It is empty: every finding was fixed rather than suppressed |
| [`.trivyignore.yaml`](../.trivyignore.yaml) | Two accepted IaC risks with justification and expiry (§4) |
| [`infra/terraform/variables.tf`](../infra/terraform/variables.tf) | `endpoint_public_access_cidrs` has no `0.0.0.0/0` default anymore and validation rejects it (§4) |
| [`Dockerfile`](../Dockerfile) | `apk upgrade` in the runtime stage, so Alpine fixes published after the base image was built are included |
| `src/main/java/.../ValidationProperties.java`, `ValidationResult.java` | Defensive immutable copies (§3) |
| `.gitignore`, `.dockerignore` | `reports/` (scan output), and the scanner config files are kept out of the image build context |

The gate helper in the Jenkinsfile:

```groovy
// Runs a Trivy scan, keeps the full JSON + HTML report, and returns non-zero if it holds a HIGH/CRITICAL finding.
def trivyGate(String name, String scan) {
    sh """
        trivy ${scan} --quiet --ignorefile .trivyignore.yaml --format json --output reports/trivy-${name}.json
        trivy convert --quiet --format template --template @/usr/local/share/trivy/contrib/html.tpl \
          --output reports/trivy-${name}.html reports/trivy-${name}.json
    """
    return sh(returnStatus: true,
              script: "trivy convert --quiet --ignorefile .trivyignore.yaml --format table --severity HIGH,CRITICAL --exit-code 1 reports/trivy-${name}.json")
}
```

Each scan runs once and writes the full report. `trivy convert --exit-code 1 --severity HIGH,CRITICAL` then turns that report into the gate decision and prints the blocking findings as a table in the console log.

Pipeline order (stages after Package are skipped when an earlier gate fails):

```
Checkout → Build → Test → Package → Security Checks → Container Build → Image Scan → Push → Deploy → Smoke Test
                                     ├ SpotBugs/FindSecBugs              └ Trivy image
                                     ├ Trivy deps (jar)
                                     └ Trivy secrets + IaC
```

---

## 3. Blocked pipeline: the gates on the existing code (build #8)

The gates were pushed **without touching the application first** (commit `6137cce`), to see what the Task 1–4 code really looked like. Build #8 was triggered by the push and **failed in Security Checks**. Container Build, Image Scan, Push and Deploy were skipped, so production stayed on `1.0.0-af723d7`.

```
ERROR: Security gate failed: SAST (SpotBugs + FindSecBugs); dependency scan (HIGH/CRITICAL CVEs in the application jar); secret / IaC misconfiguration scan
Finished: FAILURE
```

| Scanner | Findings (build #8) | Gate |
| ------- | ------------------- | ---- |
| SpotBugs + FindSecBugs | 4 (Medium) | fail |
| Trivy dependencies | 18 total: **6 CRITICAL, 5 HIGH**, 7 MEDIUM | fail |
| Trivy secrets + IaC | 10 total: **3 CRITICAL**, 4 MEDIUM, 3 LOW; 0 secrets | fail |

**SpotBugs** (all four have the same cause: records that store and return mutable collections):

```
[ERROR] Medium: ValidationProperties.supportedCurrencies() may expose internal representation by returning ValidationProperties.supportedCurrencies  EI_EXPOSE_REP
[ERROR] Medium: new ValidationProperties(BigDecimal, Set) may expose internal representation by storing an externally mutable object  EI_EXPOSE_REP2
[ERROR] Medium: ValidationResult.errors() may expose internal representation by returning ValidationResult.errors  EI_EXPOSE_REP
[ERROR] Medium: new ValidationResult(String, boolean, ValidationResult$Status, List) may expose internal representation ...  EI_EXPOSE_REP2
```

**Trivy dependencies.** These are real, current CVEs in the Spring Boot 3.5.16 dependency set:

```
Total: 11 (HIGH: 5, CRITICAL: 6)
│ org.apache.tomcat.embed:tomcat-embed-core │ CVE-2026-65182, -65637, -65905, -68525 │ CRITICAL │ 10.1.55 │ fixed in 10.1.58/10.1.59, 11.0.25 │
│ org.springframework:spring-webmvc         │ CVE-2026-47884, -47890                 │ CRITICAL │ 6.2.19  │ fixed in 7.0.9                    │
│ com.fasterxml.jackson.core:jackson-core   │ CVE-2026-89407, -89425                 │ HIGH     │ 2.21.4  │ fixed in 2.21.7                   │
│ com.fasterxml.jackson.core:jackson-databind│ CVE-2026-68497, -91776, -91777        │ HIGH     │ 2.21.4  │ fixed in 2.21.7                   │
```

**Trivy IaC.** Trivy evaluates `infra/terraform/` together with the terraform-aws-modules EKS module it calls:

```
AWS-0040 (CRITICAL): Public cluster access is enabled.
AWS-0041 (CRITICAL): Cluster allows access from a public CIDR: 0.0.0.0/0
AWS-0104 (CRITICAL): Security group rule allows unrestricted egress to any IP address.
```

The image scan never ran, because the build stopped before Container Build. A baseline scan of the previously deployed image `1.0.0-a6bfe4b` (run by hand before the gates existed) also found **CVE-2026-111497 (CRITICAL) in Alpine `brotli-libs` 1.2.0-r1** on top of the Java CVEs above. That image would have been blocked by the Image Scan gate as well.

---

## 4. Remediation step 1: code and IaC (build #9)

Commit `d5d0026`.

**SpotBugs: fixed in code, not suppressed.** Both records now take an immutable copy in their compact constructor, so neither callers nor Spring's binder can change their state afterwards:

```java
public record ValidationProperties(BigDecimal maxAmount, Set<String> supportedCurrencies) {
    public ValidationProperties {
        supportedCurrencies = supportedCurrencies == null ? Set.of() : Set.copyOf(supportedCurrencies);
    }
}

public record ValidationResult(String transactionId, boolean valid, Status status, List<ValidationError> errors) {
    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
    ...
}
```

All 27 unit tests still pass.

**AWS-0041: fixed.** The variable's `0.0.0.0/0` default was a real weakness: running `terraform apply` without the `-var` would have opened the Kubernetes API to the internet. The default is gone and validation now rejects it:

```hcl
variable "endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint (pass your own /32; no open default)"
  type        = list(string)

  validation {
    condition     = length(var.endpoint_public_access_cidrs) > 0 && !contains(var.endpoint_public_access_cidrs, "0.0.0.0/0")
    error_message = "Restrict the API endpoint to specific CIDRs (e.g. your IP /32); 0.0.0.0/0 is not allowed."
  }
}
```

**AWS-0040 and AWS-0104: accepted risks**, recorded in [`.trivyignore.yaml`](../.trivyignore.yaml) with expiry `2027-05-01`:

| ID | Why it is accepted |
| -- | ------------------ |
| AWS-0040 public API endpoint | kubectl and the Jenkins deployer run outside the VPC. Access is limited to one /32 (enforced by the validation above), and every call is IAM-authenticated. The production alternative is a private endpoint with a VPN or an in-VPC build agent. |
| AWS-0104 unrestricted node egress | Worker nodes in private subnets need outbound internet through the NAT gateway to pull images from Docker Hub and reach AWS APIs. They have no inbound internet path. The production alternative is VPC endpoints + ECR + an egress allow-list. |

**Result: build #9 is still blocked, now by the dependency gate only:**

```
[INFO] BugInstance size is 0
Total: 11 (HIGH: 5, CRITICAL: 6)
ERROR: Security gate failed: dependency scan (HIGH/CRITICAL CVEs in the application jar)
Finished: FAILURE
```

---

## 5. Remediation step 2: dependencies and base image, successful build (build #10)

Commit `922ea76`.

Spring Boot **3.5.16 is the last 3.5 release**, and the two spring-webmvc CVEs are only fixed in Spring Framework 7.0.9. A version override on 3.5 could fix Tomcat and Jackson but not Spring itself. The only real fix is the next major version, so the application moved to **Spring Boot 4.0.9** (Spring Framework 7.0.10, Tomcat 11.0.27, Jackson 3.1.7). The version went from 1.0.0 to **1.1.0** because this is a framework major upgrade.

| Change | Detail |
| ------ | ------ |
| Parent | `spring-boot-starter-parent` 3.5.16 → **4.0.9** |
| Web starter | `spring-boot-starter-web` → `spring-boot-starter-webmvc` (Boot 4 module split) |
| Test slice | added `spring-boot-starter-webmvc-test`; `@WebMvcTest` moved to `org.springframework.boot.webmvc.test.autoconfigure` (2 test classes) |
| Base image | `RUN apk upgrade --no-cache` in the runtime stage of `eclipse-temurin:21-jre-alpine` (Alpine 3.24.3), which pulls in the fixed `brotli-libs` on every build, even when the cached base image is older |

Local verification before the push: `mvn clean verify spotbugs:check` gave 27/27 tests passing and 0 SpotBugs findings. A local `trivy image --severity HIGH,CRITICAL --exit-code 1` of the new image exited with 0.

**Build #10: SUCCESS.** Every gate passed, the image was pushed, the rollout completed and the smoke test passed:

| Scanner | Build #8 (HIGH+CRIT) | Build #10 (HIGH+CRIT) |
| ------- | -------------------- | --------------------- |
| SpotBugs + FindSecBugs | 4 findings | **0** |
| Trivy dependencies (jar) | 11 | **0** |
| Trivy secrets + IaC | 3 | **0** (2 accepted, documented) |
| Trivy image | (not reached; baseline 12) | **0** |

```
[INFO] BugInstance size is 0
deployment "transaction-validator" successfully rolled out
8         Jenkins #10: jordyong/transaction-validator:1.1.0-922ea76
{"service":"transaction-validator","timestamp":"2026-11-02T15:37:43.893219457Z","status":"UP"}
Finished: SUCCESS
```

---

## 6. Core test case: a controlled critical finding blocks the release (builds #11, #12)

To show the gate on demand, a known-vulnerable library was added on purpose (commit `38f7d8a`):

```xml
<!-- SECURITY GATE DEMO: known-vulnerable library (Text4Shell, CVE-2022-42889, CRITICAL). Must be blocked. -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-text</artifactId>
    <version>1.9</version>
</dependency>
```

**Build #11: FAILURE, release blocked.** The tests passed, but the dependency gate stopped the pipeline. No image was built or pushed, and the cluster stayed on `1.1.0-922ea76`:

```
Total: 1 (HIGH: 0, CRITICAL: 1)
│ org.apache.commons:commons-text (transaction-validator.jar) │ CVE-2022-42889 │ CRITICAL │ fixed │ 1.9 │ 1.10.0 │ apache-commons-text: variable interpolation RCE │
ERROR: Security gate failed: dependency scan (HIGH/CRITICAL CVEs in the application jar)
Stage "Container Build" skipped due to earlier failure(s)
Stage "Image Scan" skipped due to earlier failure(s)
Stage "Push" skipped due to earlier failure(s)
Stage "Deploy" skipped due to earlier failure(s)
Finished: FAILURE
```

**Build #12: SUCCESS.** The demo commit was reverted (`fe878b8`), all gates passed again, and `1.1.0-fe878b8` rolled out:

```
$ kubectl -n finsecure get deploy transaction-validator -o jsonpath='{.spec.template.spec.containers[0].image}'
jordyong/transaction-validator:1.1.0-fe878b8
$ kubectl -n finsecure get pods
NAME                                     READY   STATUS    RESTARTS   AGE
transaction-validator-7464c5b88c-shzvp   1/1     Running   0          35s
transaction-validator-7464c5b88c-tbdrg   1/1     Running   0          23s
```

### Results across all builds

Totals per scanner as shown by Warnings NG, all severities:

| Build | Commit | Result | SpotBugs | Trivy deps | Trivy secrets+IaC | Trivy image | Blocked by |
| ----- | ------ | ------ | -------- | ---------- | ----------------- | ----------- | ---------- |
| #8  | `6137cce` gates added | FAILURE | 4 | 18 (11 H/C) | 10 (3 C) | – | SAST, deps, IaC |
| #9  | `d5d0026` code + IaC fixes | FAILURE | 0 | 18 (11 H/C) | 7 (0 H/C) | – | deps |
| #10 | `922ea76` Boot 4 + base patch | **SUCCESS** | 0 | 0 | 7 (0 H/C) | 0 | – |
| #11 | `38f7d8a` commons-text 1.9 | FAILURE | 0 | 1 (1 C) | 7 (0 H/C) | – | deps |
| #12 | `fe878b8` revert | **SUCCESS** | 0 | 0 | 7 (0 H/C) | 0 | – |

The 7 remaining non-blocking IaC findings (4 MEDIUM, 3 LOW) form the hardening backlog. They are visible in the Warnings NG "Trivy secrets + IaC" view of every build: AWS-0038 (cluster control-plane log types), AWS-0178 (VPC flow logs), DS-0026 (no Dockerfile `HEALTHCHECK`; Kubernetes probes cover this), KSV-0011 (CPU limit), KSV-0021 (high GID) and KSV-0125 (images from a trusted registry list).

---

## 7. Secure secret and credential management

No secret is stored in the repository, the container image, `casc.yaml` or the build logs.

| Secret | Stored in | How it reaches its consumer |
| ------ | --------- | --------------------------- |
| Docker Hub push token | `secrets/dockerhub_token` in the workspace, **outside the git repo** | Docker Compose secret → mounted at `/run/secrets/` in the Jenkins container → JCasC `${dockerhub_token}` → Jenkins credential `dockerhub` (encrypted at rest in `JENKINS_HOME`) → `withCredentials` only inside the Push stage |
| AWS access key for `finsecure-jenkins` | `secrets/aws_access_key_id`, `secrets/aws_secret_access_key` | Same path → Jenkins credential `aws-jenkins` → `withCredentials` only in Deploy / Smoke Test |
| Jenkins admin password | `secrets/jenkins_admin_password` | Docker secret → JCasC local security realm |
| Kubernetes Secrets (none used by the app yet) | etcd in EKS | envelope-encrypted with a customer-managed KMS key (Task 3 Terraform) |

Controls and evidence:

- **Masked in logs.** Jenkins replaces bound credentials in console output. From build #12:
  ```
  [Pipeline] withCredentials
  Masking supported pattern matches of $DH_TOKEN
  + echo ****
  + docker login -u jordyong --password-stdin
  ```
- **No secrets left behind on disk.** `DOCKER_CONFIG` and `KUBECONFIG` point into the build workspace, `docker logout` runs after Push, and the post-build step deletes both directories. Registry and cluster credentials never persist in `JENKINS_HOME`.
- **Least privilege.** `finsecure-jenkins` can only call `eks:DescribeCluster`, and its EKS access entry is `AmazonEKSEditPolicy` scoped to the `finsecure` namespace (Task 4). The Docker Hub token is a scoped access token, not the account password.
- **Secret scanning in the pipeline.** Trivy's secret scanner runs on every build as part of the Security Checks gate. It found 0 secrets in builds #8 – #12.
- **Secret scanning of the whole git history**, all 11 commits, with gitleaks:
  ```
  $ docker run --rm -v "$PWD":/repo:ro zricethezav/gitleaks:v8.28.0 git /repo --no-banner -v
  INF 11 commits scanned.
  INF scanned ~220484 bytes (220.48 KB) in 83.3ms
  INF no leaks found
  ```
- **Ignore rules as defence in depth.** `.gitignore` excludes `.env`, `*.pem`, `*.key`, `*.tfstate*` and `.terraform/`. The `secrets/` directory sits outside the repository altogether.

---

## 8. Reproducing locally

```bash
# SAST (from nus-devops-capstone/)
mvnd -B -ntp clean verify spotbugs:check

# Dependency scan of the built jar
docker run --rm -v "$PWD":/src:ro -v trivy-cache:/root/.cache aquasec/trivy:0.76.1 \
  rootfs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 /src/target/transaction-validator.jar

# Secrets + IaC (skip the local .terraform provider/module cache, which Jenkins doesn't have)
docker run --rm -v "$PWD":/src:ro -v trivy-cache:/root/.cache -w /src aquasec/trivy:0.76.1 \
  fs --scanners secret,misconfig --ignorefile .trivyignore.yaml --skip-files pom.xml \
  --skip-dirs target --skip-dirs infra/terraform/.terraform --severity HIGH,CRITICAL --exit-code 1 .

# Image
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache aquasec/trivy:0.76.1 \
  image --scanners vuln --severity HIGH,CRITICAL --exit-code 1 jordyong/transaction-validator:<tag>
```

After changing `jenkins/Dockerfile` or `plugins.txt`, rebuild Jenkins from `jenkins/`: `docker compose --env-file ../../secrets/jenkins.env up -d --build`.

---

## 9. Evidence checklist (Task 5 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| Security scan reports | Archived artifacts of every build: `reports/trivy-{dependencies,repo,image}.{json,html}`, `target/spotbugsXml.xml`; Warnings NG views per build (SpotBugs, Trivy dependencies, Trivy secrets + IaC, Trivy image), screenshot of build #8 and #10 |
| Blocked pipeline | Build #8 (§3: SAST + deps + IaC), build #9 (§4: deps), build #11 (§6: controlled CRITICAL, Push/Deploy skipped); Stage View screenshot showing Security Checks red and later stages skipped |
| Remediation evidence | Code fix (§4), Terraform fix + documented accepted risks (§4), Spring Boot 4 upgrade + base image patch (§5); the before/after table in §6; build #10 and #12 SUCCESS with all scanner counts at 0 HIGH/CRITICAL |
| Successful build after remediation | Builds #10 and #12: Stage View all green, `kubectl get pods` on `1.1.0-fe878b8` |
| Secure secret and credential management | §7: Docker secrets → JCasC → Jenkins credentials, `****` masking in the console, gitleaks "no leaks found" over the full history, Trivy secret scan 0 findings, scoped IAM + KMS-encrypted Kubernetes Secrets; screenshot of Jenkins → Credentials (values hidden) |
