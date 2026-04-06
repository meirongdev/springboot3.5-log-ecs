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

### 3. Access Services

```bash
# OpenSearch Dashboards (http://localhost:5601)
kubectl -n logging port-forward svc/opensearch-dashboards 5601:5601

# Spring Boot App (http://localhost:8080)
kubectl -n logging port-forward svc/spring-app 8080:8080

# OpenSearch API (http://localhost:9200)
kubectl -n logging port-forward svc/opensearch 9200:9200
```

### 4. Generate Test Logs

```bash
# Trigger some HTTP calls to generate logs
curl http://localhost:8080/api/hello
curl http://localhost:8080/api/orders/123
curl http://localhost:8080/api/error  # intentional error
```

### 5. View in OpenSearch Dashboards

1. Open `http://localhost:5601`
2. Go to **Stack Management** → **Index Patterns** → Create `spring-logs-*`
3. Go to **Discover** to explore ECS-formatted logs

### 6. Cleanup

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
