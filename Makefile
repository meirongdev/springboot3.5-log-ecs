IMAGE ?= meirongdev/spring-log-demo:1.0.0
CLUSTER ?= log-demo
NAMESPACE ?= logging

.PHONY: help build lint test run clean image cluster deploy teardown logs verify

.DEFAULT_GOAL := help

help: ## Show this help message
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Variables:"
	@echo "  IMAGE      Docker image name (default: meirongdev/spring-log-demo:1.0.0)"
	@echo "  CLUSTER    Kind cluster name (default: log-demo)"
	@echo "  NAMESPACE  Kubernetes namespace (default: logging)"

build: ## Build the project (skip tests)
	mvn clean package -DskipTests -q

lint: ## Run SpotBugs static analysis and build
	mvn clean package -DskipTests -q
	mvn spotbugs:check

test: ## Run all tests
	mvn test

run: ## Run the application locally
	mvn spring-boot:run

clean: ## Clean build artifacts
	mvn clean -q

image: ## Build Docker image
	docker build -t $(IMAGE) .

cluster: ## Create Kind Kubernetes cluster
	kind create cluster --config docs/k8s/kind-cluster.yaml --name $(CLUSTER)

deploy: image ## Build image and deploy to Kind cluster
	kind load docker-image $(IMAGE) --name $(CLUSTER)
	kubectl apply -f docs/k8s/namespace.yaml
	kubectl apply -f docs/k8s/opensearch/
	kubectl -n logging wait --for=condition=ready pod -l app=opensearch --timeout=120s
	kubectl apply -f docs/k8s/logstash/
	kubectl apply -f docs/k8s/spring-app/

logs: ## Tail application logs
	kubectl -n logging logs deploy/spring-app -c spring-app -f

verify: ## Run automated post-deployment verification
	NAMESPACE=$(NAMESPACE) CLUSTER=$(CLUSTER) ./scripts/verify-deploy.sh

setup-dashboards: ## Create index pattern in OpenSearch Dashboards
	@echo "Creating index pattern 'spring-logs-*' in Dashboards..."
	@kubectl -n $(NAMESPACE) port-forward svc/opensearch-dashboards 5601:5601 &>/dev/null & \
	PID=$$!; \
	sleep 3; \
	curl -s -X POST "http://localhost:5601/api/saved_objects/index-pattern/spring-logs-*" \
	  -H "osd-xsrf: true" \
	  -H "Content-Type: application/json" \
	  -d '{"attributes":{"title":"spring-logs-*","timeFieldName":"@timestamp"}}' | jq .; \
	kill $$PID 2>/dev/null; \
	echo "Done. Dashboards available at http://localhost:5601"

teardown: ## Delete Kind cluster
	kind delete cluster --name $(CLUSTER)
