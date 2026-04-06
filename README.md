# Spring Boot 3.5 ECS Logging Demo

A production-ready logging architecture demo featuring structured ECS (Elastic Common Schema) logs flowing from a Spring Boot 3.5 / Java 25 application through Filebeat → Logstash → OpenSearch, all running on a local Kind Kubernetes cluster.

## Architecture

```
┌─────────────────────────────────────────────┐
│              Kind Kubernetes Cluster         │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │          Pod: spring-app             │   │
│  │  ┌─────────────┐  ┌──────────────┐  │   │
│  │  │ Spring Boot │  │   Filebeat   │  │   │
│  │  │   (Java25)  │  │  (sidecar)   │  │   │
│  │  │             │  │              │  │   │
│  │  │  ECS JSON   │  │  reads logs  │  │   │
│  │  │  → /logs/   │  │  → Logstash  │  │   │
│  │  └──────┬──────┘  └──────┬───────┘  │   │
│  │         │   shared vol   │          │   │
│  └─────────┼────────────────┼──────────┘   │
│            └────────────────┘               │
│                     │                       │
│            ┌────────▼────────┐              │
│            │    Logstash     │              │
│            │  (parse/enrich) │              │
│            └────────┬────────┘              │
│                     │                       │
│            ┌────────▼────────┐              │
│            │   OpenSearch    │              │
│            │  (store/index)  │              │
│            └────────┬────────┘              │
│                     │                       │
│       ┌─────────────▼──────────────┐        │
│       │  OpenSearch Dashboards     │        │
│       │  (visualize/explore)       │        │
│       └────────────────────────────┘        │
└─────────────────────────────────────────────┘
```

## Key Technologies

| Component | Version | Role |
|---|---|---|
| Spring Boot | 3.5.x | Application framework |
| Java | 25 (LTS) | Runtime (Virtual Threads enabled) |
| Logback ECS Encoder | 1.6.x | Structured ECS JSON logging |
| Filebeat | 8.x | Log shipper (sidecar) |
| Logstash | 8.x | Log processing pipeline |
| OpenSearch | 2.x | Search & analytics engine |
| OpenSearch Dashboards | 2.x | Log visualization UI |
| Kind | 0.24+ | Local Kubernetes cluster |

## Quick Start

### Prerequisites

```bash
# Install required tools
brew install kind kubectl helm
```

### Development Commands

This project includes a Makefile for common operations:

```bash
# Show available commands
make help

# Build the project (skip tests)
make build

# Run tests
make test

# Run static analysis (SpotBugs)
make lint

# Run locally (outside Kubernetes)
make run

# Build Docker image
make image
```

### 1. Create Kind Cluster

```bash
make cluster
# or: kind create cluster --config docs/k8s/kind-cluster.yaml --name log-demo
```

### 2. Deploy the Stack

```bash
make deploy
```

This will build the Docker image and deploy the entire stack to your Kind cluster.

### 3. Verify the Deployment

Run the automated verification script to ensure all components are healthy:

```bash
make verify
```

This script performs the following checks:
- Cluster connectivity
- Pod health and readiness
- OpenSearch cluster health (green/yellow/red)
- Log index existence (`spring-logs-*`)
- Log document count in OpenSearch
- ECS field validation on sample documents
- Test traffic generation to produce logs
- OpenSearch Dashboards availability

Expected output:
```
========================================
  Logging Stack Verification
========================================

▶ Checking Kind cluster connectivity...
  ✓ Connected to cluster: log-demo
▶ Checking pod status in namespace 'logging'...
  ✓ Pod 'opensearch-xxx' is Running (ready)
  ✓ Pod 'opensearch-dashboards-xxx' is Running (ready)
  ✓ Pod 'logstash-xxx' is Running (ready)
  ✓ Pod 'spring-app-xxx' is Running (ready)
▶ Checking OpenSearch cluster health...
  ✓ OpenSearch cluster health: YELLOW (normal for single-node)
▶ Checking for log indices matching 'spring-logs-*'...
  ✓ Index exists: spring-logs-2026.04.06
▶ Checking log document count...
  ✓ Found 42 log documents in OpenSearch
...
========================================
  Verification Summary
========================================
  Passed: 12
  Failed: 0

All checks passed! Logging stack is healthy.
```

### 4. Manual Verification (OpenSearch)

If you want to inspect OpenSearch directly:

```bash
# Port-forward OpenSearch API
kubectl -n logging port-forward svc/opensearch 9200:9200 &

# Check cluster health
curl -s http://localhost:9200/_cluster/health | jq .status

# List indices
curl -s http://localhost:9200/_cat/indices?v

# Query log count
curl -s http://localhost:9200/spring-logs-*/_count | jq .

# View a sample log document
curl -s http://localhost:9200/spring-logs-*/_search?size=1 | jq '.hits.hits[0]._source'
```

### 5. Access Services

```bash
# OpenSearch Dashboards (http://localhost:5601)
kubectl -n logging port-forward svc/opensearch-dashboards 5601:5601

# Spring Boot App (http://localhost:8080)
kubectl -n logging port-forward svc/spring-app 8080:8080

# OpenSearch API (http://localhost:9200)
kubectl -n logging port-forward svc/opensearch 9200:9200
```

### 6. Generate Test Logs

```bash
# Trigger some HTTP calls to generate logs
curl http://localhost:8080/api/hello
curl http://localhost:8080/api/orders/123
curl http://localhost:8080/api/error  # intentional error
```

### 7. View in OpenSearch Dashboards

1. Open `http://localhost:5601`
2. Go to **Discover** → select `spring-logs-*` to explore ECS-formatted logs

> **Note:** The index pattern `spring-logs-*` is automatically created by `make verify`.
> If it's missing, create it manually: **Stack Management** → **Index Patterns** → `spring-logs-*` (time field: `@timestamp`)

### 8. Cleanup

```bash
# View application logs
make logs

# Delete the Kind cluster
make teardown
```

## Log Format (ECS)

Each log line is structured JSON conforming to the [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html):

```json
{
  "@timestamp": "2026-04-06T10:00:00.000Z",
  "log.level": "INFO",
  "message": "Order 123 created successfully",
  "ecs.version": "8.11.0",
  "service.name": "spring-log-demo",
  "service.version": "1.0.0",
  "service.environment": "production",
  "process.thread.name": "virtual-12",
  "log.logger": "dev.meirong.demo.OrderController",
  "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span.id": "00f067aa0ba902b7",
  "http.request.method": "POST",
  "http.response.status_code": 200,
  "url.path": "/api/orders"
}
```

## Project Layout

```
.
├── README.md
├── Dockerfile
├── Makefile
├── pom.xml
├── spotbugs-include.xml
├── src/
│   ├── main/
│   │   ├── java/dev/meirong/demo/
│   │   │   ├── DemoApplication.java
│   │   │   ├── DemoController.java     # REST endpoints with structured logging
│   │   │   └── LoggingFilter.java      # MDC requestId injection per request
│   │   └── resources/
│   │       ├── application.yaml        # ECS logging, virtual threads, actuator
│   │       └── logback-spring.xml      # Async appender wrapping built-in ECS appenders
│   └── test/java/dev/meirong/demo/
│       ├── DemoControllerTest.java     # HTTP endpoint assertions (MockMvc)
│       ├── DemoControllerLogTest.java  # Log field assertions (in-memory appender)
│       ├── HarnessTest.java            # Integration: MDC, traceId, concurrency
│       ├── LoggingFilterTest.java      # Filter unit tests
│       └── LoggingConventionsTest.java # ArchUnit logging convention rules
└── docs/
    ├── architecture.md       # Architecture decisions (ADRs)
    ├── setup-guide.md        # Step-by-step Kind cluster setup
    └── k8s/
        ├── kind-cluster.yaml
        ├── namespace.yaml
        ├── spring-app/       # Deployment + Filebeat sidecar ConfigMap
        ├── logstash/         # Pipeline configuration
        └── opensearch/       # OpenSearch + Dashboards
```

## Design Decisions

See [docs/architecture.md](docs/architecture.md) for detailed rationale on:
- Why Filebeat sidecar over DaemonSet
- ECS over custom log format
- Logstash vs direct Filebeat-to-OpenSearch
- Java 25 Virtual Threads impact on log correlation
