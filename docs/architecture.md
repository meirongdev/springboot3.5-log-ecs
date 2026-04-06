# Architecture Decision Records

## Overview

This document explains the key design decisions in this logging architecture.

---

## ADR-001: Filebeat Sidecar vs DaemonSet

**Decision:** Deploy Filebeat as a sidecar container in the same pod as the Spring Boot app.

**Rationale:**
- **Isolation**: Each app pod controls its own log shipping config — no cross-tenant log leakage in multi-team clusters.
- **Lifecycle coupling**: Filebeat starts/stops with the app pod. No risk of log data being shipped after the app exits but before DaemonSet Filebeat is re-scheduled.
- **Independent versioning**: App team can update Filebeat version in their pod spec without coordinating with the platform team.
- **Simpler RBAC**: No cluster-level DaemonSet permissions required.

**Trade-off:** Higher resource overhead per pod (Filebeat ~50MB RAM).

---

## ADR-002: ECS (Elastic Common Schema) Log Format

**Decision:** Use Logback ECS Encoder to produce ECS-compliant JSON logs.

**Rationale:**
- **OpenSearch compatibility**: OpenSearch's built-in index templates understand ECS field names, enabling automatic field mapping.
- **Trace correlation**: ECS fields `trace.id` and `span.id` integrate natively with OpenTelemetry, enabling distributed trace → log correlation.
- **Standard vocabulary**: `log.level`, `service.name`, `http.request.method` etc. are unambiguous across tools.
- **Spring Boot 3.5 support**: Spring Boot 3.5 ships first-class structured logging support; Logback ECS Encoder plugs in with one dependency.

**Alternative considered:** Custom JSON layout via LogstashEncoder — rejected because it requires custom index mappings and doesn't get free OpenSearch ECS template support.

---

## ADR-003: Logstash as Processing Layer

**Decision:** Route logs through Logstash rather than shipping directly from Filebeat to OpenSearch.

**Rationale:**
- **Enrichment**: Logstash can add geo-IP, parse user-agents, enrich with Kubernetes metadata, or redact PII fields centrally.
- **Fan-out**: A single Logstash pipeline can route logs to multiple outputs (OpenSearch + S3 archive) without changing app or Filebeat config.
- **Buffering**: Logstash provides an in-memory/persistent queue that decouples spikes in log volume from OpenSearch write pressure.
- **Schema evolution**: Field renaming/mapping happens in one place, not in every Filebeat instance.

**Trade-off:** Adds operational complexity and latency (~1-2s). Acceptable for observability workloads.

---

## ADR-004: Java 25 Virtual Threads

**Decision:** Enable Spring Boot Virtual Threads (`spring.threads.virtual.enabled=true`).

**Impact on logging:**
- Thread names in logs will be `virtual-N` instead of `http-nio-8080-exec-N`. This is expected and harmless.
- MDC (Mapped Diagnostic Context) works correctly with virtual threads in SLF4J 2.x + Logback 1.5+.
- Trace context propagation (OpenTelemetry) is compatible with virtual threads via context propagation APIs.
- High concurrency means more log volume; the async Logback appender is important to avoid I/O blocking on virtual threads.

**Configuration:**
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

## ADR-005: Shared Volume for Log Files

**Decision:** Spring Boot writes logs to `/logs/app.log` on a shared `emptyDir` volume; Filebeat reads from that volume.

**Rationale:**
- **Simplicity**: No need for a log socket or syslog daemon.
- **Durability during restart**: `emptyDir` persists as long as the pod lives, so brief app crashes don't lose in-flight logs (Filebeat can catch up).
- **Separation of concerns**: App writes logs; Filebeat ships logs. Neither needs to know about the other's internals.

**Log rotation:** Logback's `RollingFileAppender` with `SizeAndTimeBasedRollingPolicy` keeps log files manageable. Filebeat's `close_inactive` and `clean_inactive` settings handle rotated files.

---

## Data Flow

```
Spring Boot App
  │  writes ECS JSON lines
  ▼
/logs/app.log  (emptyDir shared volume)
  │  Filebeat watches via inotify
  ▼
Filebeat (sidecar)
  │  adds k8s metadata (pod name, namespace, node)
  │  sends over TCP/5044
  ▼
Logstash :5044
  │  parses, enriches, routes
  │  outputs to OpenSearch index: spring-logs-YYYY.MM.dd
  ▼
OpenSearch :9200
  │  stores, indexes
  ▼
OpenSearch Dashboards :5601
  │  Discover, visualize, alert
  ▼
Developer / SRE
```

---

## Security Notes (Local Development Only)

The following are intentionally simplified for local development. Do NOT use in production:

| Area | Local Dev | Production |
|---|---|---|
| OpenSearch auth | Disabled (`plugins.security.disabled: true`) | TLS + fine-grained access control |
| Logstash → OpenSearch | Plain HTTP | HTTPS with client certs |
| Filebeat → Logstash | No TLS | Mutual TLS |
| Secrets | Hardcoded in ConfigMaps | Kubernetes Secrets / Vault |
| OpenSearch storage | `emptyDir` | PersistentVolumeClaim |
