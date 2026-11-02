#!/usr/bin/env bash
# Installs the monitoring + logging stack into the current kubectl context (EKS finsecure-eks):
#   kube-prometheus-stack (Prometheus, Alertmanager, Grafana, node-exporter, kube-state-metrics), Loki, Alloy,
#   the app ServiceMonitor + alert rules, and the FinSecure Grafana dashboard.
# Idempotent: re-run it after any change in monitoring/.
#
# Credentials are read from the workspace secrets/ folder (outside the repo), never from git:
#   grafana_admin_password   required
#   smtp_username            Gmail address used to send alert emails     } optional: without them alerts still
#   smtp_password            Gmail app password                          } fire in Prometheus/Alertmanager,
#   alert_email_to           recipient (defaults to smtp_username)       } but no email is sent
set -euo pipefail
cd "$(dirname "$0")"

SECRETS_DIR=${SECRETS_DIR:-../../secrets}
KUBECTL=${KUBECTL:-kubectl}
HELM=${HELM:-helm}
NS=monitoring
KPS_VERSION=92.17.0
LOKI_VERSION=7.5.0
ALLOY_VERSION=1.15.0

secret() { [[ -s "$SECRETS_DIR/$1" ]] && cat "$SECRETS_DIR/$1"; }
apply_secret() { "$KUBECTL" -n "$NS" create secret generic "$@" --dry-run=client -o yaml | "$KUBECTL" apply -f -; }

"$KUBECTL" create namespace "$NS" --dry-run=client -o yaml | "$KUBECTL" apply -f -

echo "==> Grafana admin credentials"
[[ -s "$SECRETS_DIR/grafana_admin_password" ]] || { echo "missing $SECRETS_DIR/grafana_admin_password" >&2; exit 1; }
apply_secret grafana-admin --from-literal=admin-user=admin \
  --from-file=admin-password="$SECRETS_DIR/grafana_admin_password"

echo "==> Alertmanager configuration"
export SMTP_HOST=${SMTP_HOST:-smtp.gmail.com} SMTP_PORT=${SMTP_PORT:-587}
SMTP_USERNAME=$(secret smtp_username || true); SMTP_PASSWORD=$(secret smtp_password || true)
ALERT_EMAIL_TO=$(secret alert_email_to || echo "$SMTP_USERNAME")
export SMTP_USERNAME SMTP_PASSWORD ALERT_EMAIL_TO
if [[ -z "$SMTP_USERNAME" || -z "$SMTP_PASSWORD" ]]; then
  echo "    WARNING: secrets/smtp_username or smtp_password missing: alerts will NOT be emailed" >&2
  SMTP_USERNAME=alerts@example.invalid SMTP_PASSWORD=unset ALERT_EMAIL_TO=alerts@example.invalid
fi
envsubst '${SMTP_HOST} ${SMTP_PORT} ${SMTP_USERNAME} ${SMTP_PASSWORD} ${ALERT_EMAIL_TO}' \
  < alertmanager-config.yaml.tmpl > /dev/shm/alertmanager.yaml.$$
trap 'rm -f /dev/shm/alertmanager.yaml.$$' EXIT
apply_secret alertmanager-finsecure --from-file=alertmanager.yaml=/dev/shm/alertmanager.yaml.$$

echo "==> Helm charts"
"$HELM" repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
"$HELM" repo add grafana https://grafana.github.io/helm-charts >/dev/null
"$HELM" repo update prometheus-community grafana >/dev/null

"$HELM" upgrade --install kps prometheus-community/kube-prometheus-stack --version "$KPS_VERSION" \
  -n "$NS" -f kube-prometheus-stack-values.yaml --wait --timeout 10m
"$HELM" upgrade --install loki grafana/loki --version "$LOKI_VERSION" \
  -n "$NS" -f loki-values.yaml --wait --timeout 10m
"$HELM" upgrade --install alloy grafana/alloy --version "$ALLOY_VERSION" \
  -n "$NS" -f alloy-values.yaml --wait --timeout 5m

echo "==> App ServiceMonitor, alert rules and dashboard"
"$KUBECTL" apply -f app-monitoring.yaml
"$KUBECTL" -n "$NS" create configmap grafana-dashboard-finsecure \
  --from-file=dashboards/finsecure-transaction-validator.json --dry-run=client -o yaml \
  | "$KUBECTL" label --local -f - grafana_dashboard=1 -o yaml | "$KUBECTL" apply -f -

echo
echo "Done. Grafana:       kubectl -n $NS port-forward svc/kps-grafana 3000:80       -> http://localhost:3000 (admin)"
echo "      Prometheus:    kubectl -n $NS port-forward svc/kps-prometheus 9090:9090  -> http://localhost:9090"
echo "      Alertmanager:  kubectl -n $NS port-forward svc/kps-alertmanager 9093:9093 -> http://localhost:9093"
