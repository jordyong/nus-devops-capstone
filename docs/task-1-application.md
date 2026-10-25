# Task 1 – Application Development and Version Control

Step-by-step record of how the FinSecure Pay transaction validation API was scaffolded, tested and pushed to GitHub. Requirements: create a Git repository, implement `/health` and `/validate` with meaningful validation rules, write JUnit 5 unit tests, then build, test, and push to GitHub.

- **Repository:** `git@github.com:jordyong/nus-devops-capstone.git` (https://github.com/jordyong/nus-devops-capstone)
- **Date:** 2026-10-25

---

## 1. Technology choices

| Area | Choice | Why |
| ---- | ------ | --- |
| Framework | Spring Boot **3.5.16** | Recommended in the brief. The latest 3.x patch release was chosen over 4.x because 3.x is mature, well documented, and has stable testing APIs. |
| Language | Java 21 (LTS) | Current LTS release, supported by Spring Boot 3.5. |
| Build | Maven, with Maven Wrapper `./mvnw` (Maven 3.9.16) | Recommended tool. The wrapper pins the Maven version, so Jenkins and other machines build identically without installing Maven. |
| Tests | JUnit 5 (+ AssertJ, Mockito, MockMvc via `spring-boot-starter-test`) | Required by the brief. |
| Health / probes | Custom `/health` + Spring Boot Actuator | `/health` meets the requirement. Actuator's `/actuator/health/liveness` and `/readiness` are ready for the Kubernetes probes in Task 3. |

### Local toolchain: Maven in Docker

Java and Maven are **not installed on the dev machine** (WSL2). All Maven commands run inside the official `maven:3.9-eclipse-temurin-21` image, with the project and the local Maven cache (`~/.m2`) mounted:

```bash
docker pull maven:3.9-eclipse-temurin-21

# helper function (bash/zsh) – add to ~/.zshrc to make it permanent
mvnd() {
  docker run --rm -u "$(id -u):$(id -g)" \
    -v "$PWD":/app -w /app \
    -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 \
    maven:3.9-eclipse-temurin-21 mvn -Duser.home=/var/maven "$@"
}
```

- `-u $(id -u):$(id -g)`: files written to `target/` are owned by you, not root.
- `-v ~/.m2:/var/maven/.m2`: caches dependencies between runs, so builds after the first are fast.

> Note: in zsh, storing the command in a string variable (`MVN="docker run ..."; $MVN`) fails because zsh doesn't word-split variables. Use the function above instead.

---

## 2. Scaffold the project

The directory was already a git repo (`main`, with one "init commit" containing the assignment brief and `.gitignore`). The project was created by hand rather than with Spring Initializr:

```
pom.xml                                  groupId com.finsecure, artifactId transaction-validator, version 1.0.0
src/main/resources/application.yml       port + validation limits (env-overridable)
src/main/java/com/finsecure/validator/
  TransactionValidatorApplication.java   entry point (@ConfigurationPropertiesScan)
  config/ValidationProperties.java       binds validation.max-amount / supported-currencies
  model/TransactionRequest.java          request payload (record)
  model/ValidationResult.java            response: transactionId, valid, status, errors
  model/ValidationError.java             code, field, message
  service/TransactionValidator.java      business rules
  controller/HealthController.java       GET /health
  controller/ValidationController.java   POST /validate
  controller/GlobalExceptionHandler.java malformed JSON -> 400
src/test/java/com/finsecure/validator/   JUnit 5 tests (see §4)
```

`pom.xml` dependencies: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-test` (test scope). `<finalName>transaction-validator</finalName>` gives a stable jar name (`target/transaction-validator.jar`) for the Dockerfile in Task 2.

### Configuration (`application.yml`)

Each value can be overridden with an environment variable, ready for a Kubernetes ConfigMap in Task 3:

| Property | Env var | Default |
| -------- | ------- | ------- |
| `server.port` | `SERVER_PORT` | `8080` |
| `validation.max-amount` | `VALIDATION_MAX_AMOUNT` | `10000.00` |
| `validation.supported-currencies` | `VALIDATION_SUPPORTED_CURRENCIES` | `SGD,USD,EUR,GBP,MYR` |

Actuator exposes `health,info` and enables the liveness/readiness probe groups.

### Maven wrapper

```bash
mvnd -q -B wrapper:wrapper
```

This creates `mvnw` and `.mvn/wrapper/maven-wrapper.properties` (wrapper 3.3.4, Maven 3.9.16). On any machine with a JDK 21 installed (e.g. the Jenkins agent), `./mvnw clean verify` works without Maven being installed.

---

## 3. API design and validation rules

### `GET /health`

```json
{"status":"UP","service":"transaction-validator","timestamp":"2026-10-25T08:05:25.128982152Z"}
```

### `POST /validate`

Request:

```json
{
  "transactionId": "TXN-1001",
  "sourceAccount": "ACC12345678",
  "destinationAccount": "ACC87654321",
  "amount": 250.50,
  "currency": "SGD"
}
```

| HTTP status | Meaning |
| ----------- | ------- |
| `200 OK` | Transaction **APPROVED** (`valid: true`, `errors: []`) |
| `422 Unprocessable Entity` | Transaction **REJECTED**: the body lists every failed rule |
| `400 Bad Request` | Body missing or not valid JSON (`error: MALFORMED_REQUEST`) |

### Rules (implemented in `TransactionValidator`)

The validator evaluates **all** rules and returns every failure, so a client can fix everything in one round trip.

| # | Code | Rule |
| - | ---- | ---- |
| 1 | `MISSING_FIELD` | `transactionId`, `sourceAccount`, `destinationAccount`, `amount`, `currency` must be present and non-blank |
| 2 | `INVALID_AMOUNT` | amount must be > 0 |
| 3 | `INVALID_AMOUNT_PRECISION` | at most 2 decimal places (trailing zeros ignored, so `10.5000` is OK) |
| 4 | `AMOUNT_LIMIT_EXCEEDED` | amount must be ≤ `validation.max-amount` (inclusive limit) |
| 5 | `UNSUPPORTED_CURRENCY` | currency must be in `validation.supported-currencies` (case-sensitive ISO codes) |
| 6 | `INVALID_ACCOUNT_FORMAT` | account IDs must match `^[A-Z0-9]{8,20}$` |
| 7 | `SAME_ACCOUNT` | source and destination accounts must differ |

---

## 4. Unit tests (JUnit 5)

| Test class | Type | What it covers |
| ---------- | ---- | -------------- |
| `service/TransactionValidatorTest` | Plain unit test (no Spring) | Valid transaction; amount exactly at the limit passes; above the limit; zero/negative (parameterized); >2 decimals; trailing zeros allowed; unsupported currency (parameterized); bad account formats (parameterized); same account; missing/blank currency; all fields missing; several failures collected at once |
| `controller/ValidationControllerTest` | `@WebMvcTest` + `@MockitoBean` | 200 for approved, 422 with error body for rejected, 400 for malformed JSON |
| `controller/HealthControllerTest` | `@WebMvcTest` | `/health` returns `UP`, service name, timestamp |
| `TransactionValidatorApplicationTests` | `@SpringBootTest` | Spring context loads with the real config |

---

## 5. Build and test

```bash
mvnd -B clean verify
```

Result (2026-10-25):

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.finsecure.validator.TransactionValidatorApplicationTests
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0 -- in com.finsecure.validator.service.TransactionValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.finsecure.validator.controller.ValidationControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.finsecure.validator.controller.HealthControllerTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- Artifact: `target/transaction-validator.jar` (~24 MB executable jar)
- Detailed test reports: `target/surefire-reports/` (XML + TXT per test class; `target/` is git-ignored, so take screenshots for evidence)

Run a single test class or method:

```bash
mvnd -B test -Dtest=TransactionValidatorTest
mvnd -B test -Dtest=TransactionValidatorTest#rejectsAmountAboveLimit
```

---

## 6. Smoke test the running application

Run the packaged jar inside the same image (it includes a JRE), exposing port 8080:

```bash
docker run -d --rm --name tv-smoke -p 8080:8080 \
  -v "$PWD/target":/app -w /app \
  maven:3.9-eclipse-temurin-21 java -jar transaction-validator.jar
```

Requests and actual responses:

```bash
$ curl -s -w '\nHTTP %{http_code}\n' localhost:8080/health
{"timestamp":"2026-10-25T08:05:25.128982152Z","service":"transaction-validator","status":"UP"}
HTTP 200

$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-1001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.50,"currency":"SGD"}' \
  localhost:8080/validate
{"transactionId":"TXN-1001","valid":true,"status":"APPROVED","errors":[]}
HTTP 200

$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-1002","sourceAccount":"ACC12345678","destinationAccount":"ACC12345678","amount":15000.999,"currency":"JPY"}' \
  localhost:8080/validate
{"transactionId":"TXN-1002","valid":false,"status":"REJECTED","errors":[
  {"code":"INVALID_AMOUNT_PRECISION","field":"amount","message":"amount must have at most 2 decimal places"},
  {"code":"AMOUNT_LIMIT_EXCEEDED","field":"amount","message":"amount exceeds the limit of 10000.00"},
  {"code":"UNSUPPORTED_CURRENCY","field":"currency","message":"currency JPY is not supported"},
  {"code":"SAME_ACCOUNT","field":"destinationAccount","message":"source and destination accounts must differ"}]}
HTTP 422

$ curl -s -w '\nHTTP %{http_code}\n' -H 'Content-Type: application/json' -d '{not json' localhost:8080/validate
{"message":"Request body is missing or is not valid JSON","error":"MALFORMED_REQUEST"}
HTTP 400

$ curl -s localhost:8080/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
```

Stop it:

```bash
docker stop tv-smoke
```

---

## 7. Commit and push to GitHub

```bash
git add .
git commit -m "Task 1: Spring Boot transaction validation API with JUnit 5 tests"
git remote add origin git@github.com:jordyong/nus-devops-capstone.git
git push -u origin main
```

### One-time SSH setup (needed on this machine)

A pre-push check (`ssh -T git@github.com`) failed: `~/.ssh` had no key, and GitHub's host key wasn't in `known_hosts` (`Host key verification failed`). Fix:

```bash
ssh-keygen -t ed25519 -C "ongjordan98@gmail.com"      # accept the default path
cat ~/.ssh/id_ed25519.pub                             # add at GitHub > Settings > SSH and GPG keys
ssh-keyscan github.com >> ~/.ssh/known_hosts          # trust GitHub's host key
ssh -T git@github.com                                 # expect: "Hi jordyong! You've successfully authenticated..."
git push -u origin main
```

---

## 8. Evidence checklist (Task 1 expected evidence)

| Evidence | Where / how to capture |
| -------- | ---------------------- |
| GitHub repository link | https://github.com/jordyong/nus-devops-capstone (screenshot of the repo page after the push) |
| Application source code | `src/main/java/com/finsecure/validator/` in the repo |
| Unit-test results | Output in §5; screenshot of `mvnd -B clean verify` and/or `target/surefire-reports/` |
| Build and test evidence | `BUILD SUCCESS` + `Tests run: 27, Failures: 0` (§5); smoke-test curl output (§6) |

---

## 9. Repository housekeeping: workspace vs. repo

After the first push, the git repo was separated from the local working files. Only what's needed to build, test, deploy and document the application goes to GitHub.

```
capstone/                      ← local workspace (not a git repo)
├── CLAUDE.md                  ← AI assistant instructions (local only)
├── task-overview.md, task-1..7.md   ← assignment brief + progress table (local only)
└── nus-devops-capstone/       ← git repo → github.com/jordyong/nus-devops-capstone
    ├── src/, pom.xml, mvnw, mvnw.cmd, .mvn/
    ├── docs/                  ← per-task step-by-step docs (submission evidence)
    └── .gitignore
```

Steps taken:

1. **Removed `CLAUDE.md` from all history.** It had been committed in both commits. History was rewritten and force-pushed:
   ```bash
   git branch backup/before-claude-md-removal            # safety copy of the old history
   git filter-branch --index-filter 'git rm --cached --ignore-unmatch -q CLAUDE.md' -- main
   git push --force-with-lease=main:<old-sha> origin main  # refuses if the remote changed meanwhile
   ```
2. **Moved the repo into a subfolder.** `.git`, `.gitignore`, `.mvn/`, `docs/`, `src/`, `mvnw*` and `pom.xml` were moved into `nus-devops-capstone/`. `CLAUDE.md` and the `task-*.md` files stay in the parent workspace.
3. **Removed the brief files from the repo** (`git rm` of `task-*.md` in a normal commit, so no further history rewrite).
4. **Squashed the whole history into a single Task 1 commit**, so no trace of `CLAUDE.md` or the brief remains on GitHub:
   ```bash
   OLD=$(git rev-parse HEAD)
   NEW=$(git commit-tree HEAD^{tree} -m "Task 1: ...")   # new root commit with the current file tree
   git reset --soft $NEW
   git diff --stat $OLD HEAD                              # empty = identical content
   git push --force-with-lease=main:$OLD origin main
   ```

Why: future tasks bring cloud credentials, kubeconfigs, evidence screenshots and notes. Keeping them outside the repo directory means they can't be committed by accident, so no ever-growing `.gitignore` list is needed.

All build commands now run from `nus-devops-capstone/`.
