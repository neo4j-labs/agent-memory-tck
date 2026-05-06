# CLAUDE.md

## Project Overview

This is the **neo4j-agent-memory Technology Compatibility Kit (TCK)** — a monorepo containing a behavioral specification, executable test suite, TypeScript client, Go client, C# client, R client, and multi-agent demo for the neo4j-agent-memory ecosystem.

The TCK defines what it means to be a compliant implementation of neo4j-agent-memory across any language. Implementations pass the TCK by implementing a `BaseAdapter` and running the test suite against it.

## Repository Structure

```
agent-memory-tck/
├── tck/                          # Python TCK core (specification + test runner)
│   ├── adapters/                 # BaseAdapter interface + HTTP bridge adapter
│   ├── bridge/                   # HTTP bridge protocol for cross-language testing
│   ├── fixtures/                 # Deterministic test data + mock embedder
│   ├── reference/                # Reference adapter wrapping neo4j-agent-memory Python package
│   ├── registry/                 # Scenario ID registry (YAML) + validator
│   ├── report/                   # Compliance report generator (JSON + HTML)
│   └── tests/v1/                 # 189 test scenarios across Bronze/Silver/Gold/Platinum tiers
├── clients/
│   ├── typescript/               # @neo4j-labs/agent-memory npm package
│   ├── go/                       # agent-memory-go Go module
│   ├── csharp/                   # Neo4j.AgentMemory NuGet package
│   ├── rlang/                    # neo4j.memory R package (httr2 + R6)
│   └── python/                   # neo4j-agent-memory-client Python package (httpx + asyncio)
├── demo/
│   ├── agents/{lenny,scout,forge,atlas,sage,rune}/  # 6 demo agents (Python, TS, Go, C#, R)
│   ├── dashboard/                # Next.js + NVL real-time visualization
│   └── infra/                    # Docker Compose + integration tests
├── docs/                         # Adapter guide, certification, CI integration
├── certifications/               # Registry schema + certification entries
├── SPEC.md                       # Normative specification (v1.0.0)
└── ROADMAP.md                    # Implementation status + future plans
```

## Package Management

**Always use `uv` for Python**. Never use pip/pip3 directly.

```bash
uv sync                    # Install core dependencies
uv sync --extra dev        # Include ruff + mypy
uv sync --extra bridge     # Include httpx + aiohttp for bridge adapter
uv sync --extra reference  # Include neo4j-agent-memory + neo4j driver
```

## Common Commands

### Running Tests

```bash
# Run by tier
uv run pytest -m bronze -v          # 93 Bronze tests (schema + short-term memory)
uv run pytest -m silver -v          # 67 Silver tests (long-term + reasoning memory)
uv run pytest -m gold -v            # 18 Gold tests (cross-memory + multi-agent)
uv run pytest -v                    # All 178 tests

# With JSON report (for compliance reporting)
uv run pytest --json-report --json-report-file=results.json -v

# Cross-language testing via HTTP bridge
uv run pytest -m bronze --bridge-url http://localhost:3001

# Collect only (no execution, useful for counting)
uv run pytest --collect-only -m bronze -q
```

Tests require a `BaseAdapter` implementation. Without one, all tests skip with a message. Provide an adapter via:
1. A conftest.py `adapter` fixture (for Python implementations)
2. The `--bridge-url` flag (for TypeScript/Go implementations via HTTP bridge)

### Linting

```bash
uv run ruff check tck/              # Lint Python
uv run ruff format --check tck/     # Check formatting
uv run mypy tck/                    # Type checking (may have some issues)
```

### Scenario Registry

```bash
uv run python -m tck.registry.validator   # Validates all 178 scenarios match tests
```

This checks bidirectional consistency: every test has a scenario ID, every scenario ID maps to an existing test.

### TypeScript Client

```bash
cd clients/typescript
npm install
npm run build                         # tsup → ESM + DTS
npx tsc --noEmit                      # Type check only
npm test                              # Vitest
MEMORY_ENDPOINT=http://... npm run conformance:server   # Bridge server on :3001
```

### Go Client

```bash
cd clients/go
go vet ./memory/                      # Lint
go build ./memory/                    # Build library
go build -o /dev/null ./conformance/  # Build conformance server
MEMORY_ENDPOINT=http://... go run ./conformance   # Bridge server on :3001
```

### C# Client

```bash
cd clients/csharp
dotnet build Neo4j.AgentMemory.sln              # Build all projects
dotnet test tests/Neo4j.AgentMemory.Tests/      # Unit tests
MEMORY_ENDPOINT=http://... dotnet run --project conformance/Neo4j.AgentMemory.Conformance  # Bridge server on :3001
```

### R Client

```bash
cd clients/rlang
R CMD INSTALL neo4j.memory/                           # Install package
R CMD check neo4j.memory/                             # Check package
cd conformance
MEMORY_ENDPOINT=http://... Rscript server.R           # Bridge server on :3001
```

### Demo

```bash
cd demo/infra
docker compose up                     # All agents + dashboard + Neo4j
python integration_test.py            # Cross-language entity sharing test
```

### Compliance Report

```bash
uv run tck results.json --name "My Impl" --version "1.0" -o report.json --html report.html
```

## Architecture

### Compliance Tiers

| Tier | Tests | Scope | BaseAdapter Methods |
|------|-------|-------|---------------------|
| **Bronze** | 93 | Schema + short-term memory | 9 (lifecycle + messages) |
| **Silver** | 67 | + Long-term + reasoning memory | 23 (+ entities, preferences, facts, traces) |
| **Gold** | 18 | + Cross-memory + multi-agent | 26 (+ relationships, merging, similar traces) |

### Test Organization

Tests live in `tck/tests/v1/` and are organized by memory type:

- `test_schema.py` — Bronze: conversation creation, session isolation, message/entity/preference/fact properties
- `test_short_term.py` — Bronze: add_message, get_conversation, search, list_sessions, delete, clear, chain structure, idempotency
- `test_long_term.py` — Silver: entities (5 types, search, lookup, relationships), preferences, facts
- `test_reasoning.py` — Silver: traces, steps, tool calls (6 statuses), completion, stats
- `test_cross_memory.py` — Gold: cross-memory references, entity relationships, merging, similar traces, multi-agent sharing

Every test method has a docstring referencing its SPEC clause (e.g., `"""SPEC-2.1.1: add_message MUST return..."`).

### BaseAdapter Pattern

All tests interact with implementations through `tck/adapters/base_adapter.py`:

- `BaseAdapter` is an abstract class with async methods
- Tests receive it via the `adapter` pytest fixture
- Implementations subclass it and map TCK types to their native types
- The `_check_adapter_and_clean` autouse fixture calls `clear_all_data()` before each test

### HTTP Bridge Protocol

The bridge enables the Python test suite to validate non-Python implementations:

1. TS/Go/C# implements a thin HTTP server mapping `POST /{method_name}` → native client calls
2. Python `HTTPBridgeAdapter` serializes each `BaseAdapter` method as an HTTP POST
3. Run via `pytest --bridge-url http://localhost:3001`

Protocol documented in `tck/bridge/protocol.md`. Reference server in `tck/bridge/reference_server.py`.

### Scenario ID Registry

Every test has a stable ID in `tck/registry/scenario_ids.yaml`:

```yaml
SCN-B-001:
  spec_clause: SPEC-1.1.1
  test_id: test_schema::TestSchemaConversationCreation::test_first_message_creates_conversation
  tier: bronze
  description: First message creates conversation node
```

IDs are permanent — once published, never reassigned. Format: `SCN-{B|S|G|P}-{NNN}`.

### Dual-Transport Architecture

Every language client (TS, Go, C#, R, Python) ships **two transports** behind
a single `Transport` interface:

- **BridgeTransport** — TCK bridge protocol (`POST {endpoint}/{snake_case}`,
  snake_case JSON). Used by conformance servers and the local reference adapter.
- **RestTransport** — Hosted REST API at `https://memory.neo4jlabs.com/v1`
  (camelCase JSON, REST topology). Used for production deployments.

Transport selection is automatic: endpoints containing a `/v1` segment route
through REST; otherwise BridgeTransport. Override per-language via
`transport: "rest" | "bridge"` (TS), `WithTransportMode(...)` (Go),
`Transport = TransportMode.Rest` (C#), or `transport_mode = "rest"`
(R / Python).

The hosted service exposes 25+ operations beyond Bronze/Silver/Gold —
three-tier context, observations, reflections, entity feedback, history,
graph view, reasoning provenance, Cypher console, and API-key management.
These are the **Volume 5 / Platinum tier** in SPEC.md and are exposed via
the same client API. Implementations targeting only the bridge can leave
Platinum methods unimplemented; the Platinum tests will skip.

### TypeScript Client Architecture

`clients/typescript/src/` uses a **Transport abstraction**:

- `Transport` interface with `request<T>(method, params)`, `connect()`, `close()`
- `HttpTransport` uses only `fetch()` (edge-compatible, no `node:` imports)
- `MemoryClient` composes three sub-clients: `shortTerm`, `longTerm`, `reasoning`
- Wire format uses snake_case (matching bridge protocol); client types use camelCase
- Three subpath exports: `.` (main), `./middleware/vercel-ai`, `./mcp`

### Go Client Architecture

`clients/go/memory/` uses **functional options** pattern:

- All methods take `context.Context` first parameter
- Options like `WithMetadata(m)`, `WithThought(t)`, `WithLimit(n)` for optional params
- `Entity[T any]` generic type; `BaseEntity = Entity[struct{}]` for common case
- `Client.MCPHandler()` returns `http.Handler` for MCP endpoint exposure
- Goroutine-safe (no shared mutable state, `http.Client` is thread-safe)

### C# Client Architecture

`clients/csharp/src/Neo4j.AgentMemory/` uses **async/await** pattern:

- All methods are `async Task<T>` with optional `CancellationToken`
- `MemoryClient` composes three sub-clients: `ShortTerm`, `LongTerm`, `Reasoning`
- `ITransport` interface with `HttpTransport` implementation using `HttpClient`
- Wire format uses `[JsonPropertyName]` attributes for snake_case serialization
- Implements `IAsyncDisposable` for deterministic cleanup
- .NET 8.0 LTS target

### R Client Architecture

`clients/rlang/neo4j.memory/R/` uses **R6 classes** with Tidyverse conventions:

- `HttpTransport` R6 class wraps `httr2::request()` for HTTP POST to memory service
- `MemoryClient` composes three sub-clients: `short_term`, `long_term`, `reasoning`
- Wire format uses snake_case (matching bridge protocol); R naturally uses snake_case
- Parse helpers (`parse_message()`, `parse_entity()`, etc.) convert JSON lists to consistent R structures
- Dependencies: httr2, R6, jsonlite (all CRAN packages from Posit/Tidyverse ecosystem)

## Key Files

| File | Purpose |
|------|---------|
| `SPEC.md` | Normative specification — all behavioral requirements with SPEC-N.N.N IDs |
| `tck/adapters/base_adapter.py` | The contract all implementations must satisfy — Pydantic models + abstract methods |
| `tck/fixtures/data.py` | Deterministic test data — must be replicated exactly in TS/Go test fixtures |
| `tck/tests/conftest.py` | Adapter fixture wiring + `--bridge-url` CLI option |
| `tck/adapters/http_bridge.py` | Cross-language bridge adapter |
| `tck/bridge/protocol.md` | HTTP bridge protocol specification |
| `tck/registry/scenario_ids.yaml` | 178 stable scenario IDs |
| `tck/report/compliance_report.py` | Report generator with tier classification logic |

## Conventions

- **SPEC clauses**: Every behavioral requirement has a unique `SPEC-N.N.N` identifier. Tests reference them in docstrings.
- **Scenario IDs**: Stable `SCN-{TIER}-{NUM}` format, never reassigned once published.
- **Test markers**: `@pytest.mark.bronze`, `@pytest.mark.silver`, `@pytest.mark.gold` on test classes.
- **Async throughout**: All adapter methods and tests are async. Tests use `pytest-asyncio` with `asyncio_mode = "auto"`.
- **Python line length**: 100 (ruff default).
- **TypeScript**: Strict mode, no `any` in public API, ESM only.
- **Go**: Standard `gofmt`, context-first parameters, functional options for optional params.
- **C#**: .NET 8.0, nullable reference types, async/await with CancellationToken, System.Text.Json.
- **R**: R6 classes, snake_case methods, httr2 for HTTP, jsonlite for serialization, Tidyverse conventions.

## Environment Variables

| Variable | Used By | Purpose |
|----------|---------|---------|
| `NEO4J_URI` | Reference adapter, CI | Neo4j connection string |
| `NEO4J_USERNAME` | Reference adapter, CI | Neo4j username (default: `neo4j`) |
| `NEO4J_PASSWORD` | Reference adapter, CI | Neo4j password |
| `MEMORY_ENDPOINT` | Conformance servers, demo agents | HTTP endpoint for memory service. Set to `https://memory.neo4jlabs.com/v1` for the hosted service. |
| `MEMORY_API_KEY` | Conformance servers, demo agents | `nams_*` Bearer token for the hosted Neo4j Agent Memory Service |
| `MEMORY_MODE` | Demo agents | `bridge` (default) or `hosted` — picks which endpoint family the demo points at |
| `TCK_BRIDGE_PORT` | Bridge servers | Port for conformance server (default: `3001`) |
| `OPENAI_API_KEY` | Demo agents (Lenny, Scout, Atlas) | Required for LLM-powered agents |

## Adding Tests

1. Add a SPEC clause to `SPEC.md` with a unique `SPEC-N.N.N` identifier
2. Write the test method in the appropriate `tck/tests/v1/test_*.py` file
3. Apply the correct tier marker (`@pytest.mark.bronze`, etc.) on the test class
4. Add a scenario ID to `tck/registry/scenario_ids.yaml`
5. Run `uv run python -m tck.registry.validator` to verify consistency
6. Run `uv run ruff check tck/` to verify linting passes
