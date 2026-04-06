IMAGE ?= meirongdev/spring-log-demo:1.0.0
CLUSTER ?= log-demo

.PHONY: help build lint test run clean image cluster deploy teardown logs

.DEFAULT_GOAL := help

help: ## Show this help message
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Variables:"
	@echo "  IMAGE    Docker image name (default: meirongdev/spring-log-demo:1.0.0)"
	@echo "  CLUSTER  Kind cluster name (default: log-demo)"

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

teardown: ## Delete Kind cluster
	kind delete cluster --name $(CLUSTER)
