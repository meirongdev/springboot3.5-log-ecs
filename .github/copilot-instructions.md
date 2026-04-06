# Copilot instructions — springboot3.5-log-ecs

Purpose: Short, actionable guidance for Copilot sessions working on this repository.

---

## Build, test, and lint commands (detected / specified)
- Build system: Maven (specified). A minimal root-level `pom.xml` and the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.jar`) are present at the repository root.
- Preferred invocations use the wrapper: `./mvnw`; fallback to `mvn` if the wrapper is not available.

Quick verification and common Maven commands (use `./mvnw` if present):

```bash
# Verify the wrapper & Maven version
./mvnw -v

# Build and run tests (full build)
./mvnw -B clean verify
# or without wrapper:
mvn -B clean verify

# Build (package) without running tests
./mvnw -B clean package -DskipTests
mvn -B clean package -DskipTests

# Run the full unit test suite
./mvnw test
mvn test

# Run a single test class
./mvnw -Dtest=ClassNameTest test
# Run a single test method
./mvnw -Dtest=ClassNameTest#testMethod test

# If using the Failsafe plugin for integration tests (convention):
./mvnw verify -DskipITs=false

# Lint / static analysis (if configured via plugins):
./mvnw checkstyle:check       # Checkstyle
./mvnw spotbugs:check        # SpotBugs
./mvnw spotless:check        # Spotless / formatting checks
```

- Notes:
  - For multi-module projects, target modules with `-pl`/`-am` (e.g., `./mvnw -pl :module-name test`).
  - The repo includes a minimal `pom.xml` (tests will pass once application sources are added). Update the `pom.xml` to add Spring Boot, Logback ECS Encoder, and any other runtime dependencies as needed.

---

## High-level architecture (big picture)
- Local Kind cluster runs the full demo: Spring Boot app (Java 25), Filebeat sidecar, Logstash, OpenSearch, and OpenSearch Dashboards.
- Data flow:
  1. Spring Boot writes ECS-compliant JSON log lines to `/logs/app.log` on a shared `emptyDir` volume.
  2. Filebeat (sidecar in the app pod) tails `/logs/app.log`, enriches with k8s metadata, and ships to Logstash (TCP/5044).
  3. Logstash parses/enriches and writes to OpenSearch indices (prefix `spring-logs-YYYY.MM.dd`).
  4. OpenSearch Dashboards (5601) is used to explore and visualize logs.
- Runtime notes from docs: Java 25 with virtual threads enabled; Logback ECS Encoder produces structured JSON; RollingFileAppender is used for rotation.

---

## Key conventions and repo-specific patterns
- Logs: ECS JSON format (Logback ECS Encoder). Key fields: `@timestamp`, `log.level`, `service.name`, `trace.id`, `span.id`.
- Filebeat: deployed as a sidecar (not a DaemonSet). See `docs/k8s/spring-app/configmap.yaml` for config (Filebeat reads from the shared `/logs` path).
- Log files: application writes to `/logs/app.log` on an `emptyDir` shared volume; Filebeat expects this path and handles rotated files.
- Logstash: central processing/enrichment layer; pipeline config is in `docs/k8s/logstash/` (configmap/pipeline). Port: 5044.
- Indexing: OpenSearch index prefix is `spring-logs-`; dashboards expect `@timestamp` as the time field.
- Demo security: many demo settings are intentionally simplified (OpenSearch security disabled, plaintext transport). See `docs/architecture.md` Security Notes — do not use those defaults for production.

---

## Where to look first (important files)
- `README.md` — quick overview and quick-start commands.
- `docs/setup-guide.md` — step-by-step cluster and verification commands.
- `docs/architecture.md` — ADRs and rationale (Filebeat sidecar, ECS, Logstash decisions).
- `docs/k8s/` — manifests and ConfigMaps used for the demo (`kind-cluster.yaml`, `namespace.yaml`, `opensearch/`, `logstash/`, `spring-app/`).

---

## AI assistant / agent configs scanned
No repository AI assistant config files were found among common filenames checked: `CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.cursor/`, `.windsurfrules`, `CONVENTIONS.md`, `AIDER_CONVENTIONS.md`, `.clinerules`, `.cline_rules`, or an existing `.github/copilot-instructions.md`.

---

## Guidance for Copilot sessions (brief)
- Verify the repo contains application sources and build files before assuming compile/test steps. If absent, ask whether to scaffold `app/` and add a wrapper.
- When editing infra, prefer updating `docs/k8s/*` manifests and test locally with Kind per `docs/setup-guide.md`.
- Preserve ADRs in `docs/architecture.md`; when proposing changes that affect logging semantics, reference the ADRs and update them.
- Use exact commands from `docs/setup-guide.md` when reproducing or verifying runtime behavior.

---

If this file should include CI commands, single-test invocation examples, or additional file locations (once the app is added), say which build system will be used and they will be added.
