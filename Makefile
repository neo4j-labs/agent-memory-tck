# neo4j-agent-memory TCK — Project Makefile
#
# Usage:
#   make help          Show all available targets
#   make install       Install all dependencies
#   make test          Run the full TCK test suite
#   make lint          Lint all languages
#   make build         Build all client libraries
#   make docs          Build AsciiDoc documentation
#   make demo-up       Start the multi-agent demo
#
# Prerequisites:
#   - Python 3.10+ with uv (https://docs.astral.sh/uv/)
#   - Node.js 20+ with npm
#   - Go 1.21+
#   - Docker and Docker Compose (for demo)
#   - asciidoctor (for docs, install with: gem install asciidoctor)

.DEFAULT_GOAL := help
SHELL := /bin/bash

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

ROOT_DIR     := $(shell pwd)
TCK_DIR      := $(ROOT_DIR)/tck
TS_DIR       := $(ROOT_DIR)/clients/typescript
GO_DIR       := $(ROOT_DIR)/clients/go
DEMO_DIR     := $(ROOT_DIR)/demo
DOCS_DIR     := $(ROOT_DIR)/docs
CERTS_DIR    := $(ROOT_DIR)/certifications

# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------

UV           := uv
NPM          := npm
GO           := go
DOCKER       := docker compose
ASCIIDOCTOR  := asciidoctor

# ---------------------------------------------------------------------------
# Colors (for help target)
# ---------------------------------------------------------------------------

CYAN  := \033[36m
GREEN := \033[32m
RESET := \033[0m

# ===================================================================
# HELP
# ===================================================================

.PHONY: help
help: ## Show this help message
	@echo ""
	@echo "neo4j-agent-memory TCK"
	@echo "======================"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-24s$(RESET) %s\n", $$1, $$2}'
	@echo ""

# ===================================================================
# INSTALL
# ===================================================================

.PHONY: install install-python install-python-dev install-typescript install-go install-docs

install: install-python install-typescript install-go ## Install all dependencies
	@echo "$(GREEN)All dependencies installed.$(RESET)"

install-python: ## Install Python TCK dependencies
	$(UV) sync

install-python-dev: ## Install Python TCK with dev tools (ruff, mypy)
	$(UV) sync --all-extras

install-typescript: ## Install TypeScript client dependencies
	cd $(TS_DIR) && $(NPM) install

install-go: ## Download Go module dependencies
	cd $(GO_DIR) && $(GO) mod download 2>/dev/null || true

install-docs: ## Install documentation build tools (asciidoctor)
	gem install asciidoctor asciidoctor-pdf

# ===================================================================
# TEST — Python TCK
# ===================================================================

.PHONY: test test-bronze test-silver test-gold test-collect test-report

test: ## Run the full TCK test suite (all tiers)
	$(UV) run pytest -v

test-bronze: ## Run Bronze tier tests (93 scenarios)
	$(UV) run pytest -m bronze -v

test-silver: ## Run Silver tier tests (67 scenarios)
	$(UV) run pytest -m silver -v

test-gold: ## Run Gold tier tests (18 scenarios)
	$(UV) run pytest -m gold -v

test-collect: ## List all test scenarios without running them
	$(UV) run pytest --collect-only -q

test-report: ## Run all tests and generate compliance report
	$(UV) run pytest --json-report --json-report-file=results.json -v
	$(UV) run tck results.json \
		--name "neo4j-agent-memory (reference)" \
		--version "1.0.0" \
		--output compliance-report.json \
		--html compliance-report.html
	@echo "$(GREEN)Reports: compliance-report.json, compliance-report.html$(RESET)"

# ===================================================================
# TEST — Cross-Language Bridge
# ===================================================================

.PHONY: test-bridge-ts test-bridge-go

test-bridge-ts: ## Run TCK against TypeScript conformance server (must be running on :3001)
	$(UV) sync --extra bridge
	$(UV) run pytest -m bronze --bridge-url http://localhost:3001 -v

test-bridge-go: ## Run TCK against Go conformance server (must be running on :3001)
	$(UV) sync --extra bridge
	$(UV) run pytest -m bronze --bridge-url http://localhost:3001 -v

# ===================================================================
# TEST — TypeScript
# ===================================================================

.PHONY: test-typescript

test-typescript: ## Run TypeScript client tests (vitest)
	cd $(TS_DIR) && $(NPM) test

# ===================================================================
# TEST — Go
# ===================================================================

.PHONY: test-go

test-go: ## Run Go client tests with race detection
	cd $(GO_DIR) && $(GO) test -race ./memory/...

# ===================================================================
# CONFORMANCE SERVERS
# ===================================================================

.PHONY: conformance-ts conformance-go conformance-python

conformance-ts: ## Start TypeScript conformance server on :3001
	@echo "Starting TypeScript conformance server..."
	@echo "Set MEMORY_ENDPOINT to your upstream service URL"
	cd $(TS_DIR) && MEMORY_ENDPOINT=$${MEMORY_ENDPOINT:-http://localhost:7474} npx tsx conformance/server.ts

conformance-go: ## Start Go conformance server on :3001
	@echo "Starting Go conformance server..."
	@echo "Set MEMORY_ENDPOINT to your upstream service URL"
	cd $(GO_DIR) && MEMORY_ENDPOINT=$${MEMORY_ENDPOINT:-http://localhost:7474} $(GO) run ./conformance

conformance-python: ## Start Python reference bridge server on :3001
	@echo "Starting Python reference bridge server..."
	$(UV) sync --extra bridge --extra reference
	$(UV) run python -m tck.bridge.reference_server

# ===================================================================
# BUILD
# ===================================================================

.PHONY: build build-typescript build-go

build: build-typescript build-go ## Build all client libraries
	@echo "$(GREEN)All clients built.$(RESET)"

build-typescript: ## Build TypeScript client (ESM + DTS)
	cd $(TS_DIR) && $(NPM) run build

build-go: ## Build Go client library
	cd $(GO_DIR) && $(GO) build ./memory/...

# ===================================================================
# LINT
# ===================================================================

.PHONY: lint lint-python lint-typescript lint-go

lint: lint-python lint-typescript lint-go ## Lint all languages
	@echo "$(GREEN)All linting passed.$(RESET)"

lint-python: ## Lint Python (ruff check + ruff format)
	$(UV) run ruff check $(TCK_DIR)/
	$(UV) run ruff format --check $(TCK_DIR)/

lint-typescript: ## Lint TypeScript (tsc --noEmit)
	cd $(TS_DIR) && npx tsc --noEmit

lint-go: ## Lint Go (go vet)
	cd $(GO_DIR) && $(GO) vet ./memory/...

# ===================================================================
# FORMAT
# ===================================================================

.PHONY: format format-python

format: format-python ## Auto-format all code

format-python: ## Auto-format Python code with ruff
	$(UV) run ruff check --fix $(TCK_DIR)/
	$(UV) run ruff format $(TCK_DIR)/

# ===================================================================
# TYPE CHECK
# ===================================================================

.PHONY: typecheck typecheck-python typecheck-typescript

typecheck: typecheck-python typecheck-typescript ## Run type checkers for all languages

typecheck-python: ## Run mypy on Python TCK
	$(UV) run mypy $(TCK_DIR)/ || true

typecheck-typescript: ## Run tsc on TypeScript client
	cd $(TS_DIR) && npx tsc --noEmit

# ===================================================================
# VALIDATE
# ===================================================================

.PHONY: validate validate-registry validate-spec

validate: validate-registry lint test-collect ## Run all validation checks
	@echo "$(GREEN)All validations passed.$(RESET)"

validate-registry: ## Validate scenario ID registry consistency
	$(UV) run python -m tck.registry.validator

validate-spec: ## Show SPEC clause and test counts
	@echo "SPEC clauses:"
	@grep -c "^\- \*\*SPEC-" SPEC.md || true
	@echo ""
	@echo "Test counts by tier:"
	@$(UV) run pytest --collect-only -m bronze -q 2>&1 | tail -1
	@$(UV) run pytest --collect-only -m silver -q 2>&1 | tail -1
	@$(UV) run pytest --collect-only -m gold -q 2>&1 | tail -1
	@echo ""
	@echo "Total:"
	@$(UV) run pytest --collect-only -q 2>&1 | tail -1

# ===================================================================
# DOCUMENTATION
# ===================================================================

.PHONY: docs docs-html docs-pdf docs-clean

docs: docs-html ## Build documentation (alias for docs-html)

docs-html: ## Build HTML documentation with asciidoctor
	cd $(DOCS_DIR) && $(ASCIIDOCTOR) -D build index.adoc
	@for f in $(DOCS_DIR)/tutorials/*.adoc $(DOCS_DIR)/how-to/*.adoc \
	          $(DOCS_DIR)/reference/*.adoc $(DOCS_DIR)/explanation/*.adoc; do \
		[ -f "$$f" ] && $(ASCIIDOCTOR) -D $(DOCS_DIR)/build/$$(dirname $${f#$(DOCS_DIR)/}) $$f || true; \
	done
	@echo "$(GREEN)Docs built: $(DOCS_DIR)/build/$(RESET)"

docs-pdf: ## Build PDF documentation
	cd $(DOCS_DIR) && asciidoctor-pdf -D build index.adoc
	@echo "$(GREEN)PDF: $(DOCS_DIR)/build/index.pdf$(RESET)"

docs-clean: ## Remove built documentation
	rm -rf $(DOCS_DIR)/build

# ===================================================================
# DEMO
# ===================================================================

.PHONY: demo-up demo-down demo-logs demo-test demo-build

demo-up: ## Start the multi-agent demo (Docker Compose)
	cd $(DEMO_DIR)/infra && $(DOCKER) up -d
	@echo ""
	@echo "$(GREEN)Demo running:$(RESET)"
	@echo "  Dashboard: http://localhost:3000"
	@echo "  Lenny:     http://localhost:8001"
	@echo "  Scout:     http://localhost:8002"
	@echo "  Forge:     http://localhost:8003"
	@echo "  Atlas:     http://localhost:8004"
	@echo "  Neo4j:     http://localhost:7474"

demo-down: ## Stop the multi-agent demo
	cd $(DEMO_DIR)/infra && $(DOCKER) down

demo-logs: ## Tail logs from all demo services
	cd $(DEMO_DIR)/infra && $(DOCKER) logs -f

demo-test: ## Run the cross-language integration test
	cd $(DEMO_DIR)/infra && python integration_test.py

demo-build: ## Build all demo Docker images
	cd $(DEMO_DIR)/infra && $(DOCKER) build

# ===================================================================
# CLEAN
# ===================================================================

.PHONY: clean clean-python clean-typescript clean-go clean-docs clean-all

clean: clean-python clean-typescript clean-docs ## Clean build artifacts (keep dependencies)
	rm -f results.json compliance-report.json compliance-report.html
	@echo "$(GREEN)Cleaned.$(RESET)"

clean-python: ## Clean Python artifacts
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name .pytest_cache -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name .ruff_cache -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
	rm -rf .mypy_cache

clean-typescript: ## Clean TypeScript build output
	rm -rf $(TS_DIR)/dist

clean-go: ## Clean Go build cache
	cd $(GO_DIR) && $(GO) clean -cache 2>/dev/null || true

clean-docs: ## Clean documentation build output
	rm -rf $(DOCS_DIR)/build

clean-all: clean clean-go ## Clean everything including dependencies
	rm -rf $(TS_DIR)/node_modules
	rm -rf .venv
	@echo "$(GREEN)All artifacts and dependencies removed.$(RESET)"

# ===================================================================
# CI — Composite targets for CI pipelines
# ===================================================================

.PHONY: ci ci-python ci-typescript ci-go

ci: ci-python ci-typescript ci-go validate-registry ## Run full CI pipeline
	@echo "$(GREEN)CI pipeline complete.$(RESET)"

ci-python: install-python lint-python test ## CI: Python TCK
	@echo "$(GREEN)Python CI passed.$(RESET)"

ci-typescript: install-typescript lint-typescript build-typescript test-typescript ## CI: TypeScript client
	@echo "$(GREEN)TypeScript CI passed.$(RESET)"

ci-go: lint-go build-go test-go ## CI: Go client
	@echo "$(GREEN)Go CI passed.$(RESET)"

# ===================================================================
# RELEASE
# ===================================================================

.PHONY: release-check release-typescript release-python

release-check: validate lint build test-report ## Pre-release validation
	@echo ""
	@echo "$(GREEN)Release checks passed. Review compliance-report.html before publishing.$(RESET)"

release-typescript: build-typescript ## Publish TypeScript client to npm
	cd $(TS_DIR) && $(NPM) publish --access public
	@echo "$(GREEN)Published @neo4j-labs/agent-memory to npm.$(RESET)"

release-python: ## Publish Python TCK to PyPI
	$(UV) build
	$(UV) publish
	@echo "$(GREEN)Published neo4j-agent-memory-tck to PyPI.$(RESET)"
