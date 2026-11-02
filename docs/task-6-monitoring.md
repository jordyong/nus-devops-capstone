# Task 6 – Monitoring, Alerting and Logging

Step-by-step record of how the transaction validation API and its EKS cluster were made observable: Prometheus metrics (application, business and infrastructure), a Grafana dashboard, alert rules with email notification through Alertmanager, Jenkins build emails, and centralized logs in Loki.

Requirements: collect relevant application and infrastructure metrics; a dashboard with at least two meaningful live metrics; at least one alert threshold; deliberately trigger the alert and capture evidence; Jenkins email notification or an approved equivalent; at least one real notification; document how application logs can be accessed for troubleshooting.

- **Cluster:** EKS `finsecure-eks`, app namespace `finsecure`, monitoring namespace `monitoring`
- **App version:** 1.2.0 (Jenkins builds #15 – #18)
- **Date:** 2026-11-03

---

## 1. Architecture

```
                    ┌──────────────────────────── EKS finsecure-eks ────────────────────────────┐
                    │  ns finsecure                         ns monitoring                        │
 NLB :80 ──────────►│  transaction-validator ×2                                                 │
 (public API only)  │   :8080  /validate, /health                                                │
                    │   :8081  /actuator/prometheus ◄── scrape 15s ── Prometheus ── rules ──┐    │
                    │          /actuator/health/*       (ServiceMonitor)  ▲    ▲            │    │
                    │   stdout: ECS JSON logs                  node-exporter  kube-state-   ▼    │
                    │        │                                 (node CPU/mem) metrics   Alertmanager ── SMTP ──► Gmail
                    │        ▼                                                                   │
                    │   Alloy ── push ──► Loki ◄── LogQL ── Grafana ── PromQL ──► Prometheus     │
                    └──────────────────────────────────────▲─────────────────────────────────────┘
                                                            │ kubectl port-forward (no public endpoint)
 Jenkins (laptop) ── email-ext ── SMTP ──► Gmail        developer
```

| Decision | Why |
| -------- | --- |
| **kube-prometheus-stack** Helm chart (Prometheus Operator, Prometheus, Alertmanager, Grafana, node-exporter, kube-state-metrics) | The brief's recommended tools in one chart. Infrastructure metrics (nodes, pods, deployments) work out of the box, and the operator CRDs (`ServiceMonitor`, `PrometheusRule`) let the app's scrape config and alerts live in the repo as YAML. |
| **Loki + Grafana Alloy** for logs | Centralized logs in the same Grafana as the metrics, so a dashboard panel, an alert and the matching log lines are one click apart. Lighter than EFK (no Elasticsearch JVM on two small nodes) and needs no extra AWS IAM, which the restricted deployer user can't grant. Alloy replaces the deprecated Promtail. |
| **Actuator on a separate management port (8081)** | Metrics and probe endpoints are reachable only inside the cluster (ClusterIP `transaction-validator-metrics`). The public NLB forwards only to 8080, so `/actuator/prometheus` returns 404 from the internet. |
| **Business metrics in the app** (Micrometer counters) | HTTP status codes alone can't say *why* transactions fail. `finsecure_validation_errors_total{code}` shows which rule rejects them, which matters for a payments API (bad client release vs config change vs card testing). |
| **Structured JSON logs (ECS)** enabled from the ConfigMap | One JSON object per line, so Loki/Grafana can filter on `log.level`, `logger` and `message`. Local runs keep plain text. Account numbers and amounts are deliberately **not** logged. |
| **Gmail SMTP** for Alertmanager and Jenkins | A real mailbox receives real notifications. Credentials (an app password, not the account password) stay in the workspace `secrets/` folder and are injected as a Kubernetes Secret and a Jenkins credential. |
| **Ephemeral storage** (emptyDir) for Prometheus, Loki and Grafana | The cluster has no EBS CSI driver and is destroyed at the end of every session. Everything that matters (dashboards, rules, datasources, Alertmanager routes) is provisioned from the repo by `monitoring/install.sh`, so nothing is lost by rebuilding. Production would use EBS-backed PVCs or managed services (Amazon Managed Prometheus/Grafana, S3 for Loki). |
| **Grafana/Prometheus via `kubectl port-forward`**, no LoadBalancer | Nothing in the monitoring stack is exposed to the internet. Access follows the same IAM/EKS authentication as kubectl. |

---

## 2. Application changes (v1.2.0)

| File | Change |
| ---- | ------ |
| [`pom.xml`](../pom.xml) | `io.micrometer:micrometer-registry-prometheus`; version 1.2.0 |
| [`application.yml`](../src/main/resources/application.yml) | `management.server.port: 8081`; expose `health,info,prometheus`; `application` tag on every metric; latency histogram buckets for `http.server.requests` (for p95/p99) |
| [`TransactionValidator.java`](../src/main/java/com/finsecure/validator/service/TransactionValidator.java) | Counters `finsecure.transactions{outcome=approved|rejected}` and `finsecure.validation.errors{code=...}`, all registered at startup (so every series exists at 0 and `rate()` is accurate); one INFO log line per decision with the transaction id and rule codes |
| [`TransactionValidatorTest.java`](../src/test/java/com/finsecure/validator/service/TransactionValidatorTest.java) | New test `countsOutcomesAndRejectionReasons` with a `SimpleMeterRegistry` (28 tests total) |
| [`Dockerfile`](../Dockerfile) | `EXPOSE 8080 8081` |
| [`k8s/deployment.yaml`](../k8s/deployment.yaml) | `management` container port; probes moved to `management`; manifest image `1.2.0` |
| [`k8s/service-metrics.yaml`](../k8s/service-metrics.yaml) | ClusterIP Service for port 8081 (scrape target) |
| [`k8s/configmap.yaml`](../k8s/configmap.yaml) | `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` |

The Task 5 SAST gate caught the first version of the metrics code: storing the injected `MeterRegistry` in a field raised SpotBugs `EI_EXPOSE_REP2`. The fix was better code anyway. Counters are now created once in the constructor (one per error code), so the registry isn't kept at all.

Local check before pushing:

```
$ curl -s -o /dev/null -w '%{http_code}' localhost:18080/actuator/prometheus     # public port
404
$ curl -s localhost:18081/actuator/prometheus | grep ^finsecure_                  # management port
finsecure_transactions_total{application="transaction-validator",outcome="approved"} 0.0
finsecure_transactions_total{application="transaction-validator",outcome="rejected"} 1.0
finsecure_validation_errors_total{application="transaction-validator",code="AMOUNT_LIMIT_EXCEEDED"} 1.0
finsecure_validation_errors_total{application="transaction-validator",code="SAME_ACCOUNT"} 1.0
finsecure_validation_errors_total{application="transaction-validator",code="UNSUPPORTED_CURRENCY"} 1.0
...
{"@timestamp":"2026-11-02T16:46:51.774Z","log":{"level":"INFO","logger":"com.finsecure.validator.service.TransactionValidator"},...,
 "message":"Transaction T1 REJECTED codes=[AMOUNT_LIMIT_EXCEEDED, UNSUPPORTED_CURRENCY, SAME_ACCOUNT]","ecs":{"version":"8.11"}}
```

### Pipeline change: deploy all manifests together with the image

Until Task 5, Deploy ran `kubectl set image`, which changes only the image. Version 1.2.0 needs new manifests as well (probes on 8081, a new Service, a ConfigMap key). With the old Deploy, the new image would have failed its probes. Deploy now renders a throwaway kustomize overlay over `k8s/` that pins the image to the build's immutable tag and drops the cluster-scoped Namespace (Jenkins' EKS access entry only covers objects *inside* `finsecure`), then applies it as **one** rollout:

```
configmap/transaction-validator-config configured
service/transaction-validator unchanged
service/transaction-validator-metrics created
deployment.apps/transaction-validator configured
poddisruptionbudget.policy/transaction-validator configured
deployment "transaction-validator" successfully rolled out
12        Jenkins #15: jordyong/transaction-validator:1.2.0-f247748
```

The pipeline now also pushes the plain version tag (`1.2.0`), which `k8s/deployment.yaml` references, so `kubectl apply -k k8s/` on a freshly created cluster starts a compatible image before Jenkins pins the immutable tag.

---

## 3. Monitoring stack ([`monitoring/`](../monitoring/))

| File | Purpose |
| ---- | ------- |
| [`install.sh`](../monitoring/install.sh) | Idempotent installer: secrets from `../../secrets/`, then three Helm releases, then the app resources and dashboard |
| [`kube-prometheus-stack-values.yaml`](../monitoring/kube-prometheus-stack-values.yaml) | Chart 92.17.0. Disables EKS-managed control-plane targets (scheduler, controller-manager, etcd, kube-proxy) whose default alerts would fire forever; selects ServiceMonitors/rules from all namespaces; Grafana admin from a Secret; Loki datasource |
| [`loki-values.yaml`](../monitoring/loki-values.yaml) | Chart 7.5.0, single binary, filesystem storage, 48 h retention, caches/canary/gateway off |
| [`alloy-values.yaml`](../monitoring/alloy-values.yaml) | Chart 1.15.0. Discovers pods, labels streams with `namespace`, `pod`, `container`, `app`, `node`, promotes the JSON `log.level` to a `level` label, pushes to Loki |
| [`app-monitoring.yaml`](../monitoring/app-monitoring.yaml) | `ServiceMonitor` (port `management`, `/actuator/prometheus`, every 15 s) and `PrometheusRule` with 4 alerts (§5) |
| [`alertmanager-config.yaml.tmpl`](../monitoring/alertmanager-config.yaml.tmpl) | Routes and the email receiver. `install.sh` renders it with `envsubst` from `secrets/` into the Secret `alertmanager-finsecure`; the rendered file only exists in `/dev/shm` during install |
| [`dashboards/finsecure-transaction-validator.json`](../monitoring/dashboards/finsecure-transaction-validator.json) | Grafana dashboard, loaded by the Grafana sidecar from a ConfigMap labelled `grafana_dashboard=1` |

Install (after `terraform apply` and `kubectl apply -k k8s/`):

```bash
# secrets/ (workspace, not the repo): grafana_admin_password, smtp_username, smtp_password (Gmail app password), alert_email_to
KUBECTL=~/.local/bin/kubectl HELM=~/.local/bin/helm ./monitoring/install.sh
```

### 3.1 Problem: Loki crash loop, read-only file system

The first install left `loki-0` in `CrashLoopBackOff`:

```
level=error msg="error running loki" err="mkdir /var/loki: read-only file system
error initialising module: ruler-storage"
```

The chart runs Loki with a read-only root filesystem and normally mounts a PVC at `/var/loki`. With `persistence.enabled: false`, nothing is mounted there. The fix is an explicit emptyDir (`singleBinary.extraVolumes`/`extraVolumeMounts`, 2 Gi limit). The interrupted release was left `failed`, not locked, so re-running `install.sh` (`helm upgrade --install`) recovered it.

### 3.2 Result

```
$ kubectl -n monitoring get pods
alertmanager-kps-alertmanager-0          2/2     Running
alloy-684fc8f7b5-9jq5p                   2/2     Running
kps-grafana-77764b677b-gll8c             3/3     Running
kps-kube-state-metrics-747b7b996-nrxxt   1/1     Running
kps-operator-77d9df8fcb-w57jn            1/1     Running
kps-prometheus-node-exporter-5vsd8       1/1     Running
kps-prometheus-node-exporter-fm9b4       1/1     Running
loki-0                                   2/2     Running
prometheus-kps-prometheus-0              2/2     Running
```

Prometheus targets for the app (Status → Targets), one per pod:

```
transaction-validator-metrics  transaction-validator-5bcd55b8dc-7mk97  http://10.0.18.4:8081/actuator/prometheus
transaction-validator-metrics  transaction-validator-5bcd55b8dc-x4fpg  http://10.0.8.163:8081/actuator/prometheus
```

Grafana datasources: Prometheus, Alertmanager, Loki. Dashboard: *FinSecure Pay – Transaction Validator*.

---

## 4. Metrics and dashboard

Access: `kubectl -n monitoring port-forward svc/kps-grafana 3000:80`, then http://localhost:3000, user `admin`, password in `secrets/grafana_admin_password`.

| Row | Panel | Query (PromQL / LogQL) |
| --- | ----- | ---------------------- |
| Service health | Ready replicas (stat; red < 1, orange 1, green 2) | `kube_deployment_status_replicas_available{namespace="finsecure", deployment="transaction-validator"}` |
| | Transactions / s | `sum(rate(finsecure_transactions_total{namespace="finsecure"}[2m]))` |
| | Rejection rate (red > 50%, matches the alert) | `sum(rate(finsecure_transactions_total{…, outcome="rejected"}[2m])) / sum(rate(finsecure_transactions_total{…}[2m]))` |
| | p95 latency `/validate` | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{…, uri="/validate"}[5m])))` |
| Business | Transactions by outcome | `sum by (outcome) (rate(finsecure_transactions_total[2m]))` |
| | Rejections by reason | `sum by (code) (rate(finsecure_validation_errors_total[2m])) > 0` |
| HTTP | Latency p50/p95/p99 | `histogram_quantile(…)` over `http_server_requests_seconds_bucket` |
| | Responses by status | `sum by (status) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[2m]))` |
| Resources | Pod CPU / pod memory / JVM heap | `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`, `jvm_memory_used_bytes{area="heap"}` |
| | Node CPU / node memory | node-exporter `node_cpu_seconds_total`, `node_memory_MemAvailable_bytes` |
| Logs | Application logs | `{namespace="finsecure", app="transaction-validator"} \| json` |

All the usual cluster dashboards that ship with kube-prometheus-stack (Kubernetes / Compute Resources / Namespace, Node Exporter / Nodes, …) are installed as well.

Live metrics from the alert demo (§6), Prometheus query `sum by (code) (increase(finsecure_validation_errors_total{namespace="finsecure"}[30m]))`:

```
AMOUNT_LIMIT_EXCEEDED     1166
SAME_ACCOUNT              1166
UNSUPPORTED_CURRENCY      1166
INVALID_ACCOUNT_FORMAT    0
INVALID_AMOUNT            0
INVALID_AMOUNT_PRECISION  0
MISSING_FIELD             0
```

(`increase()` extrapolates to the window edges, so it reads about 3% above the 1,136 requests actually sent; the Loki count in §8 is exact.)

---

## 5. Alert configuration

Alert rules: [`monitoring/app-monitoring.yaml`](../monitoring/app-monitoring.yaml) (`PrometheusRule transaction-validator`, evaluated every 15 s).

| Alert | Condition (threshold) | for | Severity |
| ----- | --------------------- | --- | -------- |
| **HighTransactionRejectionRate** | rejected / total transactions **> 50 %** over 2 min, **and** total > 0.2 tx/s (so a single bad request on an idle system doesn't page anyone) | 1m | warning |
| TransactionValidatorReplicasUnavailable | available replicas < desired replicas | 2m | critical |
| TransactionValidatorDown | no pod scraped successfully (`up == 0` or absent) | 1m | critical |
| TransactionValidatorHighLatency | p95 of `POST /validate` > 500 ms | 5m | warning |

Every alert carries `team: finsecure` and a description that says where to look next: the dashboard panel, the Loki query, or the kubectl command.

Routing: [`monitoring/alertmanager-config.yaml.tmpl`](../monitoring/alertmanager-config.yaml.tmpl).

- `team="finsecure"` alerts **and** any `severity="critical"` platform alert from the chart's default rules → **email** to `alert_email_to`, with `send_resolved: true`
- `Watchdog` / `InfoInhibitor` (always-firing heartbeats) → null
- grouped by `alertname, namespace`; group_wait 30 s, repeat every 4 h
- inhibit: `TransactionValidatorDown` suppresses `ReplicasUnavailable` (same root cause, one email)

---

## 6. Triggering the alert (rejection-rate spike)

A throwaway pod inside the cluster sent an invalid transaction (unsupported currency JPY, amount 99,999 over the 10,000 limit, same source and destination account) about every 0.2 s for 4 minutes against the Service:

```bash
kubectl -n finsecure run reject-burst --restart=Never --image=curlimages/curl:8.11.1 -- sh -c '
  end=$(( $(date +%s) + 240 )); n=0
  while [ $(date +%s) -lt $end ]; do n=$((n+1))
    curl -s -o /dev/null -w "%{http_code}\n" -X POST http://transaction-validator/validate -H "Content-Type: application/json" \
      -d "{\"transactionId\":\"DEMO-$n\",\"sourceAccount\":\"ACC12345678\",\"destinationAccount\":\"ACC12345678\",\"amount\":99999,\"currency\":\"JPY\"}"
    sleep 0.2
  done | sort | uniq -c'
# pod output after 4 minutes:
#    1136 422
```

Polling Prometheus every 10 s (load started at 17:21:32Z):

```
17:21:43Z tx/s=0     rejected=NaN alert=inactive
17:21:53Z tx/s=0.603 rejected=1   alert=inactive
17:22:03Z tx/s=1.137 rejected=1   alert=pending      <- threshold crossed, `for: 1m` starts
17:22:33Z tx/s=2.342 rejected=1   alert=pending
17:23:04Z tx/s=3.543 rejected=1   alert=firing       <- fired after 1 minute above the threshold
```

Alertmanager (`/api/v2/alerts`):

```
HighTransactionRejectionRate warning active 2026-11-02T17:22:57.421Z More than 50% of transactions are being rejected
```

The load stopped at 17:25:32Z. The 2-minute rate fell back under the threshold, the alert resolved, and Alertmanager sent the "resolved" email. Production was unaffected throughout: both pods stayed Ready and served every request (the 422s are correct business rejections).

---

## 7. Notifications

### 7.1 Alertmanager → email

Alertmanager's own counters after the demo (no SMTP errors of any kind):

```
alertmanager_notifications_total{integration="email"} 2          # [FIRING:1] and [RESOLVED] HighTransactionRejectionRate
alertmanager_notifications_failed_total{integration="email",...} 0   # authError, clientError, serverError, … all 0
```

Both emails arrived in the `alert_email_to` mailbox (screenshots: evidence checklist).

### 7.2 Jenkins → email (email-ext)

| File | Change |
| ---- | ------ |
| [`jenkins/plugins.txt`](../jenkins/plugins.txt) | `mailer`, `email-ext` |
| [`jenkins/casc.yaml`](../jenkins/casc.yaml) | `mailer` and `email-ext` SMTP (smtp.gmail.com:587, STARTTLS), credential `smtp`, `defaultRecipients`, admin (sender) address |
| [`jenkins/docker-compose.yml`](../jenkins/docker-compose.yml) | Docker secrets `smtp_username`, `smtp_password`, `alert_email_to` from the workspace `secrets/` folder |
| [`Jenkinsfile`](../Jenkinsfile) | `post { failure { emailext … attachLog } fixed { emailext … } }` |

Jenkins emails when a build **fails** (with the last 40 log lines and the compressed full log attached) and when the next build **fixes** it. It doesn't email on every green build, so the emails mean something.

Demo with a real deployment failure:

1. **Build #17** was started with `ROLLBACK_TAG=0.0.0-doesnotexist`. The new pods couldn't pull the image, the rollout timed out, and the pipeline rolled it back automatically. The build failed and sent the email:
   ```
   error: timed out waiting for the condition
   Rollout failed – rolling back to the previous revision
   deployment.apps/transaction-validator rolled back
   deployment "transaction-validator" successfully rolled out
   Sending email to: <alert_email_to>
   Finished: FAILURE
   ```
2. **Build #18**, a normal build of `main`, deployed `1.2.0-9456d89` and sent the *FIXED* email:
   ```
   deployment "transaction-validator" successfully rolled out
   Sending email to: <alert_email_to>
   Finished: SUCCESS
   ```

---

## 8. Logs and troubleshooting

The stdout/stderr logs of every pod in the cluster (the app as well as the monitoring components) are shipped by Alloy to Loki and kept for 48 h. EKS control-plane logs (API server, audit, authenticator) go to CloudWatch Logs `/aws/eks/finsecure-eks/cluster` (Task 3).

### Where to look

| Question | Where | How |
| -------- | ----- | --- |
| Why was transaction X rejected? | Grafana → Explore → Loki | `{namespace="finsecure", app="transaction-validator"} \|= "TXN-123"` |
| What's being rejected right now, and why? | Grafana → Explore → Loki | `{namespace="finsecure", app="transaction-validator"} \|= "REJECTED" \| json \| line_format "{{.message}}"` |
| Any errors / warnings across pods? | Grafana → Explore → Loki | `{namespace="finsecure", level=~"ERROR\|WARN"}` |
| Log volume per pod (spikes) | Grafana → Explore → Loki | `sum by (pod) (count_over_time({namespace="finsecure"}[5m]))` |
| Live tail of one deployment | kubectl | `kubectl -n finsecure logs deploy/transaction-validator -f` (add `--all-containers`, `-l app.kubernetes.io/name=transaction-validator --prefix` for all pods) |
| Why did a pod crash? | kubectl | `kubectl -n finsecure logs <pod> --previous` and `kubectl -n finsecure describe pod <pod>` (events: OOMKilled, probe failures, ImagePullBackOff) |
| Cluster events | kubectl | `kubectl -n finsecure get events --sort-by=.lastTimestamp` |
| Who changed what in the cluster? | CloudWatch Logs Insights | log group `/aws/eks/finsecure-eks/cluster`, stream `kube-apiserver-audit-*` |
| Pipeline failures | Jenkins | build console log (also attached to the FAILED email) |

### Evidence from the alert demo

```
# Loki query (Grafana Explore / Loki API): {namespace="finsecure", app="transaction-validator"} |= "REJECTED" | json   (newest 3)
transaction-validator-67cc47cc44-w247w INFO Transaction DEMO-1136 REJECTED codes=[AMOUNT_LIMIT_EXCEEDED, UNSUPPORTED_CURRENCY, SAME_ACCOUNT]
transaction-validator-67cc47cc44-w247w INFO Transaction DEMO-1135 REJECTED codes=[AMOUNT_LIMIT_EXCEEDED, UNSUPPORTED_CURRENCY, SAME_ACCOUNT]
transaction-validator-67cc47cc44-zhgm5 INFO Transaction DEMO-1134 REJECTED codes=[AMOUNT_LIMIT_EXCEEDED, UNSUPPORTED_CURRENCY, SAME_ACCOUNT]

# Loki metric query: sum by (pod) (count_over_time({namespace="finsecure", app="transaction-validator"} |= "REJECTED" [30m]))
transaction-validator-67cc47cc44-w247w 578
transaction-validator-67cc47cc44-zhgm5 558        # 578 + 558 = 1136 = exactly the requests sent, spread over both replicas
```

A troubleshooting walk-through for this alert: the email says the rejection rate is above 50% → the dashboard's *Rejections by reason* panel shows three rules breaking at the same rate (one client sending bad requests, not a config change, which would show only `UNSUPPORTED_CURRENCY`) → Loki shows the transaction IDs (`DEMO-*`) and that both pods are affected equally → the source is a client, not one bad replica.

---

## 9. Session workflow (additions to Task 4 §9)

```bash
# after terraform apply + aws eks update-kubeconfig + kubectl apply -k k8s/
KUBECTL=~/.local/bin/kubectl HELM=~/.local/bin/helm ./monitoring/install.sh     # ~5 min
kubectl -n monitoring port-forward svc/kps-grafana 3000:80

# end of session, before terraform destroy (the monitoring stack has no load balancers, deleting it is optional)
helm -n monitoring uninstall alloy loki kps
kubectl delete -k k8s/
```

---

## 10. Evidence checklist (Task 6 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| Monitoring dashboard | Grafana *FinSecure Pay – Transaction Validator* during the burst (§6): screenshot showing the rejection-rate stat red, *Transactions by outcome* and *Rejections by reason* spiking, the logs panel |
| Metrics | Prometheus targets page (2 app pods UP), `/actuator/prometheus` output (§2), PromQL results (§4); Node Exporter / Kubernetes compute dashboards screenshot |
| Alert configuration | [`monitoring/app-monitoring.yaml`](../monitoring/app-monitoring.yaml), [`alertmanager-config.yaml.tmpl`](../monitoring/alertmanager-config.yaml.tmpl); Prometheus → Alerts page screenshot |
| Evidence of a triggered alert | §6 timeline (inactive → pending → firing), Prometheus Alerts page and Alertmanager UI screenshots with `HighTransactionRejectionRate` firing |
| Evidence of a received notification | Gmail screenshots: `[FIRING:1] HighTransactionRejectionRate`, `[RESOLVED] …`, Jenkins `FAILED: transaction-validator #17 …`, `FIXED: transaction-validator #18 …`; Alertmanager `notifications_total{integration="email"} 2`, failed 0; Jenkins `Sending email to:` lines (§7) |
| Logging and troubleshooting evidence | §8: Loki queries and results, Grafana Explore screenshot, `kubectl logs` output; troubleshooting table |
