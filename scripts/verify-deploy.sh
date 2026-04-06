#!/usr/bin/env bash
#
# verify-deploy.sh — Automated post-deployment verification for the logging stack
#
# Usage:
#   ./scripts/verify-deploy.sh
#   make verify
#
# Exit codes:
#   0 — All checks passed
#   1 — One or more checks failed
#

set -euo pipefail

# ── Colors ──────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ── Configuration ───────────────────────────────────────────────────────
NAMESPACE="${NAMESPACE:-logging}"
CLUSTER="${CLUSTER:-log-demo}"
OPENSEARCH_PORT=9200
DASHBOARDS_PORT=5601
APP_PORT=8080
INDEX_PATTERN="spring-logs-*"
MAX_RETRIES=10
RETRY_INTERVAL=5 # seconds

# ── State tracking ──────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0
PIDS=()

# ── Helpers ─────────────────────────────────────────────────────────────
pass() {
  echo -e "  ${GREEN}✓${NC} $1"
  ((PASS_COUNT++)) || true
}

fail() {
  echo -e "  ${RED}✗${NC} $1"
  ((FAIL_COUNT++)) || true
}

warn() {
  echo -e "  ${YELLOW}⚠${NC} $1"
}

info() {
  echo -e "${CYAN}▶${NC} $1"
}

cleanup() {
  info "Cleaning up port-forward processes..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
}

trap cleanup EXIT

port_forward() {
  local svc="$1"
  local local_port="$2"
  local remote_port="${3:-$2}"

  kubectl -n "$NAMESPACE" port-forward "svc/$svc" "$local_port:$remote_port" &>/dev/null &
  local pid=$!
  PIDS+=("$pid")

  # Wait for port to become available
  local i=0
  while ! curl -s "http://localhost:$local_port" &>/dev/null; do
    ((i++)) || true
    if [ "$i" -ge "$MAX_RETRIES" ]; then
      return 1
    fi
    sleep 1
  done
  return 0
}

# ── Main verification logic ────────────────────────────────────────────
main() {
  echo ""
  echo -e "${CYAN}========================================${NC}"
  echo -e "${CYAN}  Logging Stack Verification${NC}"
  echo -e "${CYAN}========================================${NC}"
  echo ""

  # ── 1. Cluster connectivity ─────────────────────────────────────────
  info "Checking Kind cluster connectivity..."
  if kubectl cluster-info --context "kind-$CLUSTER" &>/dev/null; then
    pass "Connected to cluster: $CLUSTER"
  else
    fail "Cannot connect to cluster: $CLUSTER"
    echo ""
    echo -e "${RED}Cluster not reachable. Run 'make cluster' first.${NC}"
    exit 1
  fi

  # ── 2. Pod health ───────────────────────────────────────────────────
  info "Checking pod status in namespace '$NAMESPACE'..."

  local pod_names pod_phases pod_readiness
  pod_names=$(kubectl -n "$NAMESPACE" get pods -o jsonpath='{.items[*].metadata.name}' 2>/dev/null)
  pod_phases=$(kubectl -n "$NAMESPACE" get pods -o jsonpath='{.items[*].status.phase}' 2>/dev/null)
  pod_readiness=$(kubectl -n "$NAMESPACE" get pods -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.ready}{" "}{end}{"|"}{end}' 2>/dev/null)

  if [ -z "$pod_names" ]; then
    fail "No pods found in namespace '$NAMESPACE'"
  else
    # Convert to arrays
    read -r -a names <<< "$pod_names"
    read -r -a phases <<< "$pod_phases"
    read -r -a readiness_list <<< "$pod_readiness"

    for i in "${!names[@]}"; do
      local name="${names[$i]}"
      local phase="${phases[$i]:-Unknown}"
      local ready_str="${readiness_list[$i]:-}"

      # Check if ALL containers are ready (no "false" in the readiness string)
      if [ "$phase" = "Running" ] && [[ ! "$ready_str" =~ "false" ]]; then
        pass "Pod '$name' is Running (ready)"
      elif [ "$phase" = "Running" ]; then
        warn "Pod '$name' is Running but not all containers ready yet"
      else
        fail "Pod '$name' is $phase"
      fi
    done
  fi

  # ── 2.5 Wait for all pods to be fully ready ─────────────────────────
  info "Waiting for all pods to be fully ready (up to 60s)..."
  local wait_for_ready=0
  local all_ready=false
  while [ "$wait_for_ready" -lt 60 ]; do
    local not_ready_count
    not_ready_count=$(kubectl -n "$NAMESPACE" get pods -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.ready}{"\n"}{end}{end}' 2>/dev/null | grep -c "false" || true)
    if [ "$not_ready_count" -eq 0 ]; then
      all_ready=true
      break
    fi
    sleep 5
    ((wait_for_ready += 5)) || true
  done

  if [ "$all_ready" = true ]; then
    pass "All pods are fully ready after ${wait_for_ready}s"
  else
    warn "Not all pods are ready after 60s — verification may be incomplete"
    warn "Check pod details: kubectl -n $NAMESPACE get pods"
  fi

  # ── 3. OpenSearch health ────────────────────────────────────────────
  info "Checking OpenSearch cluster health..."

  if port_forward "opensearch" "$OPENSEARCH_PORT"; then
    local health
    health=$(curl -s "http://localhost:$OPENSEARCH_PORT/_cluster/health" 2>/dev/null || echo "{}")
    local status
    status=$(echo "$health" | jq -r '.status' 2>/dev/null || echo "unknown")

    case "$status" in
      green)
        pass "OpenSearch cluster health: GREEN"
        ;;
      yellow)
        pass "OpenSearch cluster health: YELLOW (normal for single-node)"
        ;;
      red)
        fail "OpenSearch cluster health: RED — check OpenSearch pod logs"
        ;;
      *)
        fail "Cannot determine OpenSearch health"
        ;;
    esac
  else
    fail "Cannot port-forward to OpenSearch on port $OPENSEARCH_PORT"
    exit 1
  fi

  # ── 4. Index existence ──────────────────────────────────────────────
  info "Checking for log indices matching '$INDEX_PATTERN'..."

  local indices
  indices=$(curl -s "http://localhost:$OPENSEARCH_PORT/_cat/indices/$INDEX_PATTERN?h=index" 2>/dev/null || echo "")

  if [ -n "$indices" ]; then
    while IFS= read -r idx; do
      pass "Index exists: $idx"
    done <<< "$indices"
  else
    warn "No indices found matching '$INDEX_PATTERN' — application may not have produced logs yet"
  fi

  # ── 5. Document count ───────────────────────────────────────────────
  info "Checking log document count..."

  local count_result
  count_result=$(curl -s "http://localhost:$OPENSEARCH_PORT/$INDEX_PATTERN/_count" 2>/dev/null || echo "{}")
  local count
  count=$(echo "$count_result" | jq -r '.count' 2>/dev/null || echo "0")

  if [ "$count" -gt 0 ] 2>/dev/null; then
    pass "Found $count log documents in OpenSearch"
  else
    warn "No log documents found — logs may not have been shipped yet"
  fi

  # ── 6. Sample document validation ───────────────────────────────────
  info "Validating sample log document structure..."

  local sample
  sample=$(curl -s "http://localhost:$OPENSEARCH_PORT/$INDEX_PATTERN/_search?size=1" 2>/dev/null || echo "{}")
  local has_source
  has_source=$(echo "$sample" | jq 'if .hits.hits[0]._source then true else false end' 2>/dev/null || echo "false")

  if [ "$has_source" = "true" ]; then
    local source
    source=$(echo "$sample" | jq '.hits.hits[0]._source')

    # Check for key ECS fields — handle both flat and dotted key names
    # ECS uses dotted keys like "log.level" which jq needs quoted access for
    local has_timestamp has_level has_message
    has_timestamp=$(echo "$source" | jq 'has("@timestamp")' 2>/dev/null || echo "false")
    # Try both "log.level" (dotted string key) and nested [log][level]
    has_level=$(echo "$source" | jq 'has("log.level") or (has("log") and (.log | has("level")))' 2>/dev/null || echo "false")
    has_message=$(echo "$source" | jq 'has("message")' 2>/dev/null || echo "false")

    if [ "$has_timestamp" = "true" ] && [ "$has_level" = "true" ] && [ "$has_message" = "true" ]; then
      pass "Sample document contains valid ECS structure (@timestamp, log.level, message)"
    else
      fail "Sample document missing ECS fields (has @timestamp=$has_timestamp, log.level=$has_level, message=$has_message)"
    fi

    # Display sample (truncated) — show actual field names
    echo -e "  ${CYAN}Sample document (truncated):${NC}"
    echo "$source" | jq '{
      timestamp: (."@timestamp" // .timestamp // null),
      level: (."log.level" // .log.level // .level // null),
      message: .message,
      service: (."service.name" // .service.name // .service // null)
    }' | sed 's/^/    /'
  else
    warn "No sample document available to validate"
  fi

  # ── 7. Generate load (optional) ─────────────────────────────────────
  info "Generating test traffic to produce logs..."

  if port_forward "spring-app" "$APP_PORT"; then
    local requests_made=0
    local requests_ok=0

    for _ in $(seq 1 5); do
      if curl -s "http://localhost:$APP_PORT/api/hello" &>/dev/null; then
        ((requests_ok++)) || true
      fi
      ((requests_made++)) || true
      sleep 0.2
    done

    if [ "$requests_ok" -gt 0 ]; then
      pass "Generated $requests_ok successful requests to spring-app"

      # Allow time for logs to flow through the pipeline (Filebeat → Logstash → OpenSearch)
      info "Waiting 10s for logs to flow through Filebeat → Logstash → OpenSearch..."
      local wait_elapsed=0
      local new_count="$count"
      while [ "$new_count" -le "$count" ] 2>/dev/null && [ "$wait_elapsed" -lt 15 ]; do
        sleep 2
        ((wait_elapsed += 2)) || true
        new_count=$(curl -s "http://localhost:$OPENSEARCH_PORT/$INDEX_PATTERN/_count" 2>/dev/null | jq -r '.count' 2>/dev/null || echo "$count")
      done

      if [ "$new_count" -gt "$count" ] 2>/dev/null; then
        pass "Document count increased from $count to $new_count after load"
      else
        warn "Document count unchanged ($count → $new_count) — check Filebeat/Logstash logs"
        warn "  kubectl -n logging logs deploy/spring-app -c filebeat --tail=20"
        warn "  kubectl -n logging logs deploy/logstash --tail=20"
      fi
    else
      fail "Failed to reach spring-app on port $APP_PORT"
    fi
  else
    warn "Cannot port-forward to spring-app — skipping load generation"
  fi

  # ── 8. OpenSearch Dashboards availability ───────────────────────────
  info "Checking OpenSearch Dashboards availability..."

  if port_forward "opensearch-dashboards" "$DASHBOARDS_PORT"; then
    local dashboards_status
    dashboards_status=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$DASHBOARDS_PORT/api/status" 2>/dev/null || echo "000")

    if [ "$dashboards_status" = "200" ]; then
      pass "OpenSearch Dashboards is reachable at http://localhost:$DASHBOARDS_PORT"
    else
      fail "OpenSearch Dashboards returned HTTP $dashboards_status"
    fi

    # ── 8.5 Auto-create index pattern if not exists ───────────────────
    info "Checking for index pattern '$INDEX_PATTERN'..."

    local existing_pattern
    existing_pattern=$(curl -s "http://localhost:$DASHBOARDS_PORT/api/saved_objects/index-pattern/$INDEX_PATTERN" \
      -H "osd-xsrf: true" 2>/dev/null || echo '{"error":true}')

    if echo "$existing_pattern" | jq -e '.attributes.title' &>/dev/null; then
      pass "Index pattern '$INDEX_PATTERN' already exists"
    else
      info "Creating index pattern '$INDEX_PATTERN' with time field '@timestamp'..."

      local create_result
      create_result=$(curl -s -X POST "http://localhost:$DASHBOARDS_PORT/api/saved_objects/index-pattern/$INDEX_PATTERN" \
        -H "osd-xsrf: true" \
        -H "Content-Type: application/json" \
        -d "{\"attributes\":{\"title\":\"$INDEX_PATTERN\",\"timeFieldName\":\"@timestamp\"}}" 2>/dev/null)

      if echo "$create_result" | jq -e '.id' &>/dev/null; then
        pass "Index pattern '$INDEX_PATTERN' created successfully"
      else
        warn "Failed to create index pattern — you can create it manually in Dashboards UI"
        warn "Response: $create_result"
      fi
    fi
  else
    fail "Cannot port-forward to OpenSearch Dashboards on port $DASHBOARDS_PORT"
  fi

  # ── Summary ─────────────────────────────────────────────────────────
  echo ""
  echo -e "${CYAN}========================================${NC}"
  echo -e "${CYAN}  Verification Summary${NC}"
  echo -e "${CYAN}========================================${NC}"
  echo -e "  ${GREEN}Passed: $PASS_COUNT${NC}"
  echo -e "  ${RED}Failed: $FAIL_COUNT${NC}"
  echo ""

  if [ "$FAIL_COUNT" -eq 0 ]; then
    echo -e "${GREEN}All checks passed! Logging stack is healthy.${NC}"
    echo ""
    echo "Next steps:"
    echo "  • Open Dashboards: http://localhost:$DASHBOARDS_PORT"
    echo "  • Create index pattern: spring-logs-*"
    echo "  • Explore logs in Discover"
    echo ""
    exit 0
  else
    echo -e "${RED}Some checks failed. Review the output above.${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  • View pod logs:        make logs"
    echo "  • Check pod status:     kubectl -n $NAMESPACE get pods"
    echo "  • View setup guide:     cat docs/setup-guide.md"
    echo ""
    exit 1
  fi
}

main "$@"
