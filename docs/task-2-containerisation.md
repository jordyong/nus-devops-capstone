# Task 2 – Containerisation

Step-by-step record of how the transaction validation API was containerised, tested locally, versioned and pushed to Docker Hub.

Requirements: create a Dockerfile, build and run the image locally, test `/health`, tag the image with a meaningful version, and push it to a container registry.

- **Image:** `docker.io/jordyong/transaction-validator`
- **Digest:** `sha256:e4a61e163bdbdb0e8ccfecfc684ce37c1e63fbbfede59cc348dff613961edb8c`
- **Date:** 2026-10-25

---

## 1. Dockerfile design

[`Dockerfile`](../Dockerfile) is a **multi-stage** build.

| Stage | Base image | Purpose |
| ----- | ---------- | ------- |
| `build` | `maven:3.9-eclipse-temurin-21` | Resolves dependencies, compiles and packages the jar, then splits it into Spring Boot layers |
| runtime | `eclipse-temurin:21-jre-alpine` | Runs the app on a JRE only (no JDK, Maven or source code) |

Decisions:

| Decision | Why |
| -------- | --- |
| Build from source inside Docker | The host has no JDK or Maven, so `docker build` alone produces the image on any machine. |
| `COPY pom.xml` + `dependency:go-offline` before `COPY src` | The dependency layer stays cached until `pom.xml` changes, so code-only rebuilds are fast. |
| `-DskipTests` in the image build | Tests run in their own CI **Test** stage (Task 4), which must pass before the Container Build stage. Running them again in the image would only slow the build. |
| Spring Boot layered extraction (`-Djarmode=tools extract --layers --launcher`) | Dependencies (~20 MB, rarely change) and application classes (KBs, change every commit) become separate image layers. A new version then only pushes and pulls the small application layer. |
| JRE on Alpine | Smaller image and fewer OS packages, so less surface for the Trivy scan in Task 5. |
| Non-root user `app` (uid 10001) | If the process is compromised, it doesn't run as root inside the container. This also matches Kubernetes `runAsNonRoot`. |
| `HEALTHCHECK` on `/health` via `wget` | Docker reports `healthy`/`unhealthy`. Kubernetes uses its own probes (Task 3). |
| `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` | The JVM sizes its heap from the container memory limit, not the host's RAM. Can be overridden at runtime. |
| `exec java …` via `sh -c` | Expands `$JAVA_OPTS` while keeping Java as PID 1, so it receives SIGTERM and shuts down gracefully. |
| OCI labels `version` / `revision` | Every image records which app version and git commit it was built from. |

[`.dockerignore`](../.dockerignore) excludes `target/`, `.git/`, `docs/`, IDE files and Markdown. This keeps the build context small and stops local build output from leaking into the image.

---

## 2. Versioning scheme

| Tag | Example | Meaning |
| --- | ------- | ------- |
| `<pom version>` | `1.0.0` | Semantic version of the application (from `pom.xml`) |
| `<pom version>-<git short sha>` | `1.0.0-6161ce2` | Immutable, traceable to an exact commit. Used for deployments and rollback. |
| `latest` | `latest` | Convenience pointer to the newest build. Never used for deployments. |

The version and SHA are read automatically:

```bash
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | sed -n 2p)   # 2nd <version> = project version (1st is the parent)
REV=$(git rev-parse --short HEAD)
```

---

## 3. Build the image

Run from the repo root (`nus-devops-capstone/`):

```bash
IMG=jordyong/transaction-validator
docker build \
  --build-arg VERSION=$VERSION --build-arg REVISION=$REV \
  -t $IMG:$VERSION -t $IMG:$VERSION-$REV -t $IMG:latest .
```

The first build took about 60s, mostly dependency downloads. Result:

```
REPOSITORY                       TAG             IMAGE ID       SIZE
jordyong/transaction-validator   1.0.0           7d14a8366c23   232MB
jordyong/transaction-validator   1.0.0-6161ce2   7d14a8366c23   232MB
jordyong/transaction-validator   latest          7d14a8366c23   232MB
```

Labels baked into the image:

```bash
$ docker inspect -f '{{json .Config.Labels}}' jordyong/transaction-validator:1.0.0
{"org.opencontainers.image.description":"FinSecure Pay transaction validation API",
 "org.opencontainers.image.revision":"6161ce2",
 "org.opencontainers.image.source":"https://github.com/jordyong/nus-devops-capstone",
 "org.opencontainers.image.title":"transaction-validator",
 "org.opencontainers.image.version":"1.0.0"}
```

> **Gotcha: the `latest` tag was missing** after one build with three `-t` flags on the legacy builder. It was fixed with `docker tag $IMG:1.0.0 $IMG:latest`. Always check with `docker images $IMG` before pushing.

> **Gotcha: BuildKit / buildx.** `docker-buildx` here is a symlink into Docker Desktop's WSL mount (`/mnt/wsl/docker-desktop/cli-tools/...`). When that mount isn't available, `docker build` falls back to the deprecated legacy builder with a warning. The image still builds correctly. Restarting Docker Desktop restores BuildKit.

---

## 4. Run and test locally

```bash
docker run -d --name tv -p 8080:8080 jordyong/transaction-validator:1.0.0
docker ps --filter name=tv
```

```
NAMES     IMAGE                                  STATUS                    PORTS
tv        jordyong/transaction-validator:1.0.0   Up 16 seconds (healthy)   0.0.0.0:8080->8080/tcp
```

`/health`:

```bash
$ curl -s -w '\nHTTP %{http_code}\n' localhost:8080/health
{"service":"transaction-validator","timestamp":"2026-10-25T11:43:46.822475263Z","status":"UP"}
HTTP 200
```

`/validate` (valid transaction):

```bash
$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-2002","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.50,"currency":"SGD"}' \
  localhost:8080/validate
{"transactionId":"TXN-2002","valid":true,"status":"APPROVED","errors":[]}
HTTP 200
```

**Config via environment variables.** The container was started with the limit lowered to 500, as a ConfigMap will do in Task 3:

```bash
$ docker run -d --name tv -p 8080:8080 -e VALIDATION_MAX_AMOUNT=500 jordyong/transaction-validator:1.0.0
$ curl ... -d '{"transactionId":"TXN-2001",...,"amount":600,"currency":"SGD"}' localhost:8080/validate
{"transactionId":"TXN-2001","valid":false,"status":"REJECTED","errors":[{"code":"AMOUNT_LIMIT_EXCEEDED","field":"amount","message":"amount exceeds the limit of 500"}]}
HTTP 422
```

**Runs as non-root:**

```bash
$ docker exec tv id
uid=10001(app) gid=101(app) groups=101(app)
```

**Startup logs** (`docker logs tv`):

```
Tomcat started on port 8080 (http) with context path '/'
Started TransactionValidatorApplication in 2.019 seconds (process running for 2.287)
```

Clean up:

```bash
docker rm -f tv
```

---

## 5. Push to Docker Hub

### Log in

Create a Docker Hub **personal access token** with the **Read & Write** scope (Docker Hub → Account settings → Personal access tokens). Log in with it as the password:

```bash
docker login -u jordyong
```

The token is kept by Docker's credential helper (`credsStore: desktop.exe`, the Windows credential manager via Docker Desktop). It is never written to the repo or to a plain-text file.

> **Gotcha: `unauthorized: access token has insufficient scopes`.** The first token was read-only, so pushes were rejected. Fix: create the repository `transaction-validator` on Docker Hub, create a **Read & Write** token, then run `docker logout && docker login -u jordyong`.

### Push all tags

```bash
for t in 1.0.0 1.0.0-6161ce2 latest; do docker push jordyong/transaction-validator:$t; done
```

```
1.0.0:         digest: sha256:e4a61e163bdbdb0e8ccfecfc684ce37c1e63fbbfede59cc348dff613961edb8c size: 2616
1.0.0-6161ce2: digest: sha256:e4a61e163bdbdb0e8ccfecfc684ce37c1e63fbbfede59cc348dff613961edb8c size: 2616
latest:        digest: sha256:e4a61e163bdbdb0e8ccfecfc684ce37c1e63fbbfede59cc348dff613961edb8c size: 2616
```

All three tags point to the same digest, so layers are uploaded only once ("Layer already exists").

### Verify by pulling from the registry

Delete the local copies, pull from Docker Hub and run the pulled image:

```bash
docker rmi -f jordyong/transaction-validator:{1.0.0,1.0.0-6161ce2,latest}
docker pull jordyong/transaction-validator:1.0.0
docker image inspect -f '{{.RepoDigests}} version={{index .Config.Labels "org.opencontainers.image.version"}} revision={{index .Config.Labels "org.opencontainers.image.revision"}} user={{.Config.User}}' jordyong/transaction-validator:1.0.0
```

```
Status: Downloaded newer image for jordyong/transaction-validator:1.0.0
[jordyong/transaction-validator@sha256:e4a61e163bdbdb0e8ccfecfc684ce37c1e63fbbfede59cc348dff613961edb8c] version=1.0.0 revision=6161ce2 user=10001
```

The pulled image starts `healthy`, and `/health` and `/validate` respond as in §4.

### Make the repository public

The repository was created **private**, so its public API returned 404. Because the image contains no secrets (configuration comes from environment variables, and credentials will come from Kubernetes Secrets), it was made **public** so the Kubernetes cluster can pull it without an `imagePullSecret`. In the Docker Hub UI: Repository → Settings → Visibility settings → Make public.

Verify through the public API and an **anonymous** pull (an empty Docker config, so no credentials are sent):

```bash
$ curl -s https://hub.docker.com/v2/repositories/jordyong/transaction-validator/ | jq .is_private
false
$ curl -s 'https://hub.docker.com/v2/repositories/jordyong/transaction-validator/tags/' | jq -r '.results[] | "\(.name) \(.digest)"'
latest        sha256:e4a61e163bdb...
1.0.0-6161ce2 sha256:e4a61e163bdb...
1.0.0         sha256:e4a61e163bdb...

$ mkdir -p /tmp/anon-docker && echo '{}' > /tmp/anon-docker/config.json
$ DOCKER_CONFIG=/tmp/anon-docker docker pull jordyong/transaction-validator:1.0.0-6161ce2
Status: Downloaded newer image for jordyong/transaction-validator:1.0.0-6161ce2
```

---

## 6. Evidence checklist (Task 2 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| Dockerfile | [`Dockerfile`](../Dockerfile) in the repo, design in §1 |
| Image and version evidence | `docker images jordyong/transaction-validator` + label inspect (§3) |
| Container execution evidence | `docker ps` showing `(healthy)`, `curl /health` → 200 UP, logs (§4) |
| Container registry evidence | `docker push` digests (§5); screenshot of hub.docker.com/r/jordyong/transaction-validator/tags showing `1.0.0`, `1.0.0-6161ce2`, `latest` (public repo) |
