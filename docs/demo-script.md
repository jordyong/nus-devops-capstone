# Final demonstration – script (13–15 min)

End-to-end walkthrough of FinSecure Pay for the Task 7 demonstration. A pipeline run takes about 5 minutes and the alert about 2 minutes, so both are **started early and shown when they finish**. Earlier builds (failed test, blocked security gate, rollback) are shown from Jenkins history instead of being re-run live.

## Preparation (start about 40 min before)

```bash
# 1. Jenkins + webhook relay (from nus-devops-capstone/jenkins/)
docker compose --env-file ../../secrets/jenkins.env up -d

# 2. Infrastructure (~15 min, never interrupt)
terraform -chdir=/home/jordy/nus_devops/capstone/nus-devops-capstone/infra/terraform apply -auto-approve \
  -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
aws eks update-kubeconfig --region ap-southeast-1 --name finsecure-eks

# 3. App + monitoring (from nus-devops-capstone/)
kubectl apply -k k8s/
KUBECTL=~/.local/bin/kubectl HELM=~/.local/bin/helm ./monitoring/install.sh

# 4. One pipeline run so the cluster runs the current immutable tag (Jenkins → Build with Parameters → Build)

# 5. Terminals / tabs to have open
kubectl -n monitoring port-forward svc/kps-grafana 3000:80          # Grafana dashboard + Explore
kubectl -n monitoring port-forward svc/kps-prometheus 9090:9090     # Prometheus Alerts page
NLB=$(kubectl -n finsecure get svc transaction-validator -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
```

Tabs: GitHub repo · Jenkins job page (Stage View) · Grafana dashboard · Prometheus `/alerts` · Gmail · `docs/images/architecture.svg`.
Prepare the live change but don't push it yet: add `AUD` to `VALIDATION_SUPPORTED_CURRENCIES` in `k8s/configmap.yaml`.

## Running order

| Time | Segment | What to show / say |
| ---- | ------- | ------------------ |
| 0:00 | **Context and architecture** | Business problem (fast, safe, repeatable deploys). Walk the diagram: delivery, request and feedback paths. |
| 1:00 | **Kick off the live change** | Show that `AUD` is rejected today, then commit and push the ConfigMap change; the webhook starts a build (it runs in the background). |
| 2:00 | **Application** | `/health`; one approved and one rejected `/validate` (all errors returned, 422); 28 unit tests in the Jenkins test report. |
| 3:30 | **Kubernetes** | `kubectl -n finsecure get deploy,pods -o wide` (2 replicas, 2 nodes); probes on :8081; delete a pod → recreated. |
| 5:00 | **Pipeline (live build)** | Stage View of the running build: Build → Test → Package → Security Checks → Container Build → Image Scan → Push → Deploy → Smoke Test. Warnings NG tabs (SpotBugs, Trivy). |
| 6:30 | **Failure paths from history** | Build #3: failing test, Deploy skipped. Build #11: Text4Shell CRITICAL, release blocked. Builds #8 → #10: real CVEs remediated by the Spring Boot 4 upgrade. |
| 8:00 | **Live build finished** | `kubectl rollout history` (change-cause per Jenkins build), then `AUD` is now **approved**: config shipped through the pipeline. |
| 8:30 | **Start the alert** | Launch the rejection burst (command below), then explain the alert rule and its threshold while it builds. |
| 9:00 | **Security and secrets** | `.trivyignore.yaml` (justified, expiring risks); secrets outside git → Docker secrets → JCasC (`****` in logs); scoped IAM; KMS-encrypted Secrets; no account data in logs. |
| 10:30 | **Monitoring and alert** | Grafana: rejection rate red, *Rejections by reason*; Prometheus `/alerts` shows `HighTransactionRejectionRate` firing; Gmail `[FIRING:1]` email. |
| 12:00 | **Logs and troubleshooting** | Grafana Explore → Loki `{namespace="finsecure", app="transaction-validator"} \|= "REJECTED" \| json`; use it to diagnose: one client, all pods, no recent deploy. |
| 12:45 | **Rollback** | Jenkins `ROLLBACK_TAG=<previous tag>` (or `kubectl rollout undo`), `rollout history`; FAILED/FIXED emails from builds #17/#18. |
| 13:30 | **AI use case** | Triage assistant: same inputs just used by hand (alert, PromQL, Loki, rollout history) → cited summary in the alert email; read-only, human decides. |
| 14:15 | **Lessons and next steps** | Real CVEs vs planted findings; deploy manifests with the image; remote state, ECR + Pod Identity, canary releases. |

## Commands used live

```bash
# Application through the NLB (use --resolve if WSL caches NXDOMAIN; see CLAUDE.md)
curl -s http://$NLB/health
curl -s -X POST http://$NLB/validate -H 'Content-Type: application/json' \
  -d '{"transactionId":"DEMO-OK","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.50,"currency":"SGD"}'
curl -s -X POST http://$NLB/validate -H 'Content-Type: application/json' \
  -d '{"transactionId":"DEMO-AUD","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":100,"currency":"AUD"}'

# Self-healing
POD=$(kubectl -n finsecure get pod -l app.kubernetes.io/name=transaction-validator -o name | head -1)
kubectl -n finsecure delete "$POD" --wait=false && kubectl -n finsecure get pods -w

# Rollout history
kubectl -n finsecure rollout history deploy/transaction-validator

# Alert trigger: ~5 invalid tx/s for 4 minutes from inside the cluster
kubectl -n finsecure run reject-burst --restart=Never --image=curlimages/curl:8.11.1 -- sh -c '
  end=$(( $(date +%s) + 240 )); n=0
  while [ $(date +%s) -lt $end ]; do n=$((n+1))
    curl -s -o /dev/null -X POST http://transaction-validator/validate -H "Content-Type: application/json" \
      -d "{\"transactionId\":\"DEMO-$n\",\"sourceAccount\":\"ACC12345678\",\"destinationAccount\":\"ACC12345678\",\"amount\":99999,\"currency\":\"JPY\"}"
    sleep 0.2
  done'
kubectl -n finsecure delete pod reject-burst      # afterwards
```

## After the demo

```bash
kubectl -n finsecure delete pod reject-burst --ignore-not-found
helm -n monitoring uninstall alloy loki kps
kubectl delete -k k8s/                                   # removes the NLB before terraform destroy
terraform -chdir=/home/jordy/nus_devops/capstone/nus-devops-capstone/infra/terraform destroy -auto-approve \
  -var="endpoint_public_access_cidrs=[\"$(curl -s https://checkip.amazonaws.com)/32\"]"
(cd jenkins && docker compose --env-file ../../secrets/jenkins.env stop)
```

## Fallbacks

- **Pipeline slow or webhook missed:** trigger the build by hand (Build with Parameters), and show build #15 (manifest + image rollout) from history.
- **Alert email late:** Alertmanager waits 30 s (`group_wait`) after firing. Show the Alertmanager UI and the `[FIRING:1]` email from the Task 6 run.
- **NLB DNS not resolving locally:** `curl --resolve "$NLB:80:<ip from dns.google>"`, or `kubectl -n finsecure port-forward svc/transaction-validator 8080:80`.
