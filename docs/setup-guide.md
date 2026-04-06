# Setup Guide

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Docker | 24+ | https://docs.docker.com/get-docker/ |
| Kind | 0.24+ | `brew install kind` |
| kubectl | 1.31+ | `brew install kubectl` |
| Java | 25 | `sdk install java 25-open` or via SDKMAN |

---

## Step 1: Create Kind Cluster

```bash
kind create cluster \
  --config docs/k8s/kind-cluster.yaml \
  --name log-demo
```

Verify:
```bash
kubectl cluster-info --context kind-log-demo
kubectl get nodes
```

Expected output:
```
NAME                     STATUS   ROLES           AGE   VERSION
log-demo-control-plane   Ready    control-plane   30s   v1.31.x
```

---

## Step 2: Deploy the Logging Stack

### 2a. Namespace

```bash
kubectl apply -f docs/k8s/namespace.yaml
kubectl get namespace logging
```

### 2b. OpenSearch

```bash
kubectl apply -f docs/k8s/opensearch/
kubectl -n logging rollout status deployment/opensearch
kubectl -n logging rollout status deployment/opensearch-dashboards
```

Wait ~60s for OpenSearch to initialize. Check readiness:
```bash
kubectl -n logging get pods -l app=opensearch
```

### 2c. Logstash

```bash
kubectl apply -f docs/k8s/logstash/
kubectl -n logging rollout status deployment/logstash
```

### 2d. Spring Boot App + Filebeat Sidecar

```bash
kubectl apply -f docs/k8s/spring-app/
kubectl -n logging rollout status deployment/spring-app
```

Check both containers are running in the pod:
```bash
kubectl -n logging get pods -l app=spring-app
# NAME                          READY   STATUS    RESTARTS   AGE
# spring-app-xxx-yyy            2/2     Running   0          30s
#                               ^^^
#                               2 containers: app + filebeat
```

---

## Step 3: Automated Verification

After deployment, run the automated verification script to validate the entire logging stack:

```bash
make verify
# or: ./scripts/verify-deploy.sh
```

This script checks:
- ✅ Cluster connectivity
- ✅ Pod health and readiness
- ✅ OpenSearch cluster health
- ✅ Log index existence (`spring-logs-*`)
- ✅ Log document count
- ✅ ECS field validation on sample documents
- ✅ Test traffic generation
- ✅ OpenSearch Dashboards availability

If all checks pass, your logging stack is ready. If any check fails, refer to the troubleshooting section below.

---

## Step 4: Verify Log Flow (Manual)

### Check app is producing logs

```bash
kubectl -n logging logs deploy/spring-app -c spring-app --tail=20
```

Expected: ECS JSON lines like:
```json
{"@timestamp":"2026-04-06T10:00:00.000Z","log.level":"INFO","message":"Started DemoApplication in 2.1 seconds","service.name":"spring-log-demo"}
```

### Check Filebeat is shipping

```bash
kubectl -n logging logs deploy/spring-app -c filebeat --tail=20
```

Look for lines like `Successfully published N events`.

### Check Logstash received events

```bash
kubectl -n logging logs deploy/logstash --tail=30
```

Look for: `Pipeline started successfully` and event processing lines.

### Check OpenSearch has data

```bash
# Port-forward OpenSearch
kubectl -n logging port-forward svc/opensearch 9200:9200 &

# Query index
curl -s http://localhost:9200/spring-logs-*/_count | jq .
# {"count": 42, "_shards": {...}}

# View a sample document
curl -s http://localhost:9200/spring-logs-*/_search?size=1 | jq '.hits.hits[0]._source'
```

---

## Step 5: Access OpenSearch Dashboards

```bash
kubectl -n logging port-forward svc/opensearch-dashboards 5601:5601 &
open http://localhost:5601
```

### Index Pattern (Auto-created)

The verification script (`make verify`) automatically creates the `spring-logs-*` index pattern for you. Just go to **Discover** and start exploring.

If you need to create it manually or standalone:
```bash
make setup-dashboards
```

Or via Dashboards UI:
1. **Stack Management** → **Index Patterns** → **Create index pattern**
   - Pattern: `spring-logs-*`
   - Time field: `@timestamp`

---

## Step 6: Generate Load

```bash
# Port-forward the app
kubectl -n logging port-forward svc/spring-app 8080:8080 &

# Send requests
for i in $(seq 1 20); do
  curl -s http://localhost:8080/api/hello > /dev/null
  curl -s http://localhost:8080/api/orders/$((RANDOM % 100)) > /dev/null
  sleep 0.5
done

# Trigger an error log
curl http://localhost:8080/api/error
```

---

## Teardown

```bash
# Remove port-forwards
kill $(lsof -t -i:8080) $(lsof -t -i:5601) $(lsof -t -i:9200) 2>/dev/null

# Delete cluster
kind delete cluster --name log-demo
```

---

## Troubleshooting

### Pod stuck in `Pending`

```bash
kubectl -n logging describe pod <pod-name>
```
Usually a resource constraint. Kind default cluster has limited CPU/RAM. Check `kind-cluster.yaml` resource limits.

### Filebeat not shipping logs

```bash
kubectl -n logging logs deploy/spring-app -c filebeat | grep -i error
```
Common causes:
- Log file path mismatch between Filebeat config and Logback config
- Logstash not yet ready (Filebeat will retry automatically)

### OpenSearch yellow/red health

```bash
curl http://localhost:9200/_cluster/health | jq .status
```
Yellow is normal for a single-node cluster (replica shards can't be assigned). Red means primary shards are missing — check OpenSearch pod logs.

### No data in Dashboards

1. Verify index exists: `curl http://localhost:9200/_cat/indices?v`
2. Check the time range in Dashboards is wide enough (last 15 minutes → last 24 hours)
3. Check Logstash output logs for errors
