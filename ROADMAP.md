# neo4j-agent-memory TCK — Project Roadmap

**Last updated:** May 7, 2026
**TCK Version:** 1.0.0 + Volume 5 (Platinum tier added)

This document summarizes the implementation progress across all six phases, identifies gaps between the current state and the PRD requirements, and outlines the future roadmap for the project.

---

## Implementation Summary

### Starting State (March 9, 2026)

The repository began with a single commit containing:
- `SPEC.md` (v0.1.0, draft) with ~40 behavioral requirements
- `BaseAdapter` abstract interface with 26 abstract methods
- ~40 pytest tests across Bronze/Silver/Gold tiers
- Reference adapter wrapping the Python `neo4j-agent-memory` package
- Compliance report generator (HTML + JSON)
- Empty certification registry

### Current State (May 7, 2026)

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| SPEC clauses | ~40 | 161 | +303% |
| Test scenarios | ~40 | 189 | +373% |
| Bronze tests | ~27 | 93 | +244% |
| Silver tests | ~26 | 67 | +158% |
| Gold tests | ~6 | 18 | +200% |
| **Platinum tests** | 0 | 11 | new |
| Bridge methods | 24 | 47 | +96% (Volume 5) |
| Source files | ~15 | 130+ | +800% |
| Language clients | 1 (Python) | **5** (TS, Go, C#, R, Python) | +4 |
| Hosted-service support | 0 | All 5 clients (dual transport) | new |
| MCP tool surface | 7-8 (TS/Go) | 12 (parity, all 5 clients) | new |
| Framework integrations | 1 (Vercel AI) | 7 (+ LangChain JS, Mastra, LangGraph, PydanticAI, Semantic Kernel, ellmer) | +6 |

---

## Phase 1: TCK Foundation (M1) -- Complete

**Goal:** Establish the TCK as a credible, comprehensive specification with automated CI.

### Delivered

- **93 Bronze test scenarios** covering short-term memory edge cases: empty content, 10K+ character content, unicode/emoji, special characters, nested metadata, rapid succession (50 messages), UUID validation, timestamp recency, chain integrity after deletion, idempotency
- **Scenario ID registry** (`tck/registry/scenario_ids.yaml`) with 178 stable IDs in `SCN-{TIER}-{NUM}` format, validated by `tck/registry/validator.py` using AST parsing
- **SPEC.md expansion** with ~50 new behavioral clauses (SPEC-1.1.5 through SPEC-2.8.3), full traceability matrix mapping every test to its SPEC clause
- **GitHub Actions CI** (`tck-tests.yml`) with Python 3.10/3.11/3.12 matrix, Neo4j service container, tier-separated test runs, compliance report generation, registry validation, and ruff/mypy linting
- **HTTP bridge adapter** — the critical path deliverable enabling cross-language conformance:
  - `HTTPBridgeAdapter(BaseAdapter)` proxying all 24 methods over JSON/HTTP
  - `protocol.md` with full endpoint documentation and data type schemas
  - Reference bridge server (`reference_server.py`) wrapping `ReferenceAdapter`
  - `--bridge-url` CLI option in pytest conftest for seamless cross-language testing
  - `bridge` optional dependency group (httpx, aiohttp)

### Key Design Decision

The PRD specified Gherkin `.feature` files, but the plan adopted **pytest classes with markers** instead. Rationale: the existing pytest pattern was well-established, each test docstring references its SPEC clause for traceability, and the scenario ID registry satisfies the "stable ID" requirement without the overhead of Gherkin step definitions. This decision eliminated a full rewrite of existing tests.

---

## Phase 2: TypeScript Client (M2) -- Substantially Complete

**Goal:** Production-ready `@neo4j-labs/agent-memory` npm package with Vercel AI SDK integration.

### Delivered

- **Package structure** (`clients/typescript/`) with `package.json`, `tsconfig.json`, `tsup.config.ts`, `vitest.config.ts` — builds to ESM with `.d.ts` declarations via three subpath exports (`.`, `./middleware/vercel-ai`, `./mcp`)
- **Core types** (`src/types.ts`, 229 lines) mirroring TCK Pydantic models 1:1 with snake_case-to-camelCase wire format conversion
- **HTTP transport** (`src/transport/http.ts`, 152 lines) using only `fetch()` — compatible with Node.js 20+, Bun, Cloudflare Workers, and Vercel Edge Runtime
- **MemoryClient** with three sub-clients:
  - `ShortTermMemory` — addMessage, getConversation, searchMessages, listSessions, deleteMessage, clearSession
  - `LongTermMemory` — addEntity, addPreference, addFact, searchEntities, searchPreferences, getEntityByName, getRelatedEntities, addRelationship, mergeDuplicateEntities
  - `ReasoningMemory` — startTrace, addStep, recordToolCall, completeTrace, getTraceWithSteps, listTraces, getToolStats, getSimilarTraces
- **Vercel AI SDK middleware** (`src/middleware/vercel-ai.ts`) — auto-injects conversation history via `transformParams`, persists assistant responses via `wrapGenerate`
- **MCP tool definitions** (`src/mcp/index.ts`) — 8 tool definitions with JSON schemas + `handleMemoryToolCall` dispatcher
- **Error hierarchy** — MemoryError, ConnectionError, AuthenticationError, TransportError, ValidationError
- **Conformance server** (`conformance/server.ts`) — HTTP bridge server mapping all 24 endpoints to MemoryClient methods
- **Build verified** — TypeScript compiles with zero errors in strict mode; tsup produces ESM bundles + DTS for all entry points

### Gaps

| Item | Status | Notes |
|------|--------|-------|
| Neo4j direct transport (`transport/neo4j.ts`) | Not implemented | Planned as optional peer dependency; `ValidationError` thrown if `neo4jUri` used |
| Native TCK tests (`test/tck/bronze.test.ts`) | Scaffolded | Test structure and testdata present; individual test implementations need completion |

---

## Phase 3: Go Client + Silver Tests (M3) -- Substantially Complete

**Goal:** Production-ready Go module and 60+ Silver test scenarios.

### Delivered

- **67 Silver test scenarios** (up from 26):
  - Long-term memory: entity without description, duplicate name/different type, unicode names, UUID validation, preference without context, long text, same-category multiples, fact special characters, multiple facts per subject, empty database searches, relationship type filter, multiple relationships
  - Reasoning memory: observation-only steps, all-fields steps, 10+ step numbering, error/cancelled/pending tool call statuses, multiple tool calls per step, empty trace list, multiple tool stats, success rate accuracy
- **Go module** (`clients/go/`) with 10 source files, 1,090 lines:
  - `Entity[T any]` generic type for typed entity operations
  - Functional options pattern (`WithEndpoint`, `WithMetadata`, `WithThought`, etc.)
  - All methods take `context.Context` as first parameter
  - Goroutine-safe via stateless HTTP transport
  - MCP handler returning standard `net/http.Handler`
- **Conformance server** (`conformance/main.go`) — full bridge protocol implementation
- **Build verified** — `go vet` and `go build` pass cleanly

### Gaps

| Item | Status | Notes |
|------|--------|-------|
| Neo4j direct transport (`neo4j_transport.go`) | Not implemented | Would use `neo4j/neo4j-go-driver` |
| Native conformance tests (`memory/tck/conformance_test.go`) | Not implemented | Directory exists but no test file |
| `go.sum` | Not generated | Needs `go mod tidy` with a real build environment |

---

## Phase 4: Multi-Agent Demo (M4) -- Scaffolded

**Goal:** Four agents in three languages sharing one Neo4j graph, with a real-time dashboard.

### Delivered

- **18 Gold test scenarios** (up from 6): entity enrichment across sessions, fact/preference alongside entities, reasoning trace references, multiple relationship types, merge preserves relationships, similar traces limit/empty, and 3 multi-agent sharing tests
- **SPEC Volume 4** — 18 Gold clauses (SPEC-5.1.1 through SPEC-5.5.3) covering cross-memory references, entity relationships, merging, similar trace search, and multi-agent sharing semantics
- **Four agent scaffolds:**
  - **Lenny** (Python/PydanticAI) — `agent.py` with tools, `main.py` FastAPI server, Dockerfile
  - **Scout** (TypeScript/Vercel AI SDK) — Hono server with memory middleware, search/enrich endpoints
  - **Forge** (Go/custom HTTP) — Full handler implementation with entity enrichment pipeline
  - **Atlas** (Python/LangGraph) — State graph with `gather_entities` → `gather_traces` → `synthesize` flow
- **Next.js dashboard** — page layout, graph visualization component (SVG with agent color coding), agent panel component, API routes for graph data and agent health
- **Docker Compose** — all services with Neo4j health checks and dependency ordering
- **Integration test** — end-to-end Python script testing cross-language entity sharing flow

### Gaps

| Item | Status | Notes |
|------|--------|-------|
| Dashboard build configs (`next.config.ts`, `tsconfig.json`) | Missing | Dashboard cannot build without these |
| NVL integration | Complete | Real `@neo4j-nvl/react` `InteractiveNvlWrapper` integration |
| Agent integration testing | Not validated | Agents written but not run against a shared instance |
| Cloud Run deployment (`cloudbuild.yaml`, `terraform/`) | Not implemented | Demo runs locally via Docker Compose only |
| Gold test count | 18 of 20+ target | 2 tests short of plan target |

---

## Phase 5: Final Delivery (M5) -- Mostly Complete

**Goal:** Documentation, certification workflow, and Neo4j Labs compliance.

### Delivered

- **Documentation:**
  - `docs/writing-an-adapter.md` — step-by-step adapter implementation guide
  - `docs/certification.md` — compliance tiers, report generation, registry submission process
  - `docs/ci-integration.md` — GitHub Actions and cross-language CI patterns
  - `CONTRIBUTING.md` — development setup, code style, scenario ID rules, PR guidelines
  - `CODE_OF_CONDUCT.md` — Contributor Covenant v2.0
- **Certification schema** (`certifications/schema.json`) — JSON Schema defining registry entry format
- **SPEC v1.0.0** — bumped from 0.1.0 to 1.0.0 (Release Candidate), all volumes complete
- **README.md** — Neo4j Labs badges, compliance tier table, quick start, multi-agent demo instructions
- **Linting clean** — `ruff check tck/` passes with zero errors

### Gaps

| Item | Status | Notes |
|------|--------|-------|
| `tck/report/badge_generator.py` | Not implemented | Plan specified SVG badge generation |
| `tck/report/certify.py` | Not implemented | Plan specified CLI tool for automated certification |
| `certifications/registry.json` | Empty | No implementations certified yet; `tck_version` still says `0.1.0` |
| Client SDK badges/disclaimers | Missing | TS and Go READMEs have badges but clients not published to npm/pkg.go.dev |

---

## PRD Features Not Yet Addressed

These items were specified in the PRD but were intentionally deferred or are beyond the scope of the current milestones:

| PRD Feature | Status | Rationale |
|-------------|--------|-----------|
| Gherkin `.feature` files | Replaced by pytest + scenario registry | Same traceability, less overhead |
| `tck/service/` (Service API scenarios) | Not started | Requires NAMS API to be available |
| `tck/observational/` (Observer/Reflector) | Not started | Gold+ feature; requires observational memory in Python package |
| `tck/error-handling/` (Error codes, retry) | Not started | Deferred to post-v1.0 |
| `tck/idempotency/` (dedicated directory) | Merged into Bronze tests | `TestIdempotency` class in `test_short_term.py` |
| OpenAPI spec for bridge protocol | Not started | `protocol.md` serves as documentation; codegen not yet needed |
| Public compatibility registry web page | Not started | Requires deployment infrastructure |
| LangChain.js / OpenAI / Anthropic integrations | Not started | Vercel AI SDK middleware delivered; others deferred |
| LangChainGo / Genkit integrations | Not started | Go MCP handler delivered; framework integrations deferred |

---

## Future Roadmap

## Phase 7: Hosted Service Alignment (M7) — Complete

**Goal:** Make every TCK client a first-class citizen of the hosted Neo4j Agent Memory Service at `https://memory.neo4jlabs.com/v1`.

### Delivered (May 2026)

- **Dual-transport architecture** in all 5 clients (TS, Go, C#, R, Python):
  - `BridgeTransport` — existing TCK protocol (`POST {endpoint}/{snake_case}`, snake_case JSON)
  - `RestTransport` — hosted REST API (camelCase, REST topology, snake↔camel translation on the wire)
  - Auto-selection based on endpoint shape; explicit override via `transport: "rest" | "bridge"`
  - Bearer-token auth + `tokenProvider` callback for OAuth refresh-token rotation
- **New Python client** — `neo4j-agent-memory-client` package (httpx + asyncio). Distinct from the existing `neo4j-agent-memory` reference adapter on PyPI. Lenny + Atlas demos can drop their raw-httpx code.
- **SPEC.md Volume 5** — 15 new SPEC clauses (`SPEC-5.1.1`…`SPEC-5.5.1`) covering hosted-service operations
- **Platinum tier** — 11 new test scenarios (`SCN-P-001`…`SCN-P-011`); skips gracefully on adapters that don't implement Volume 5
- **23 new BaseAdapter methods + bridge routes** — `create_conversation`, `list_conversations`, `get_conversation_metadata`, `delete_conversation`, `get_context`, `bulk_add_messages`, `get_observations`, `get_reflections`, `list_entities`, `get_entity`, `update_entity`, `delete_entity`, `set_entity_feedback`, `get_entity_history`, `merge_entities`, `get_entity_graph`, `record_step`, `list_steps`, `explain_step`, `get_trace_by_conversation`, `get_entity_provenance`, `cypher_query` plus the 5 `auth` operations
- **MCP standardization** — 12-tool surface matching `memory.neo4jlabs.com/mcp` exposed by TS/Go/C#/R clients with deprecated v0.1 alias warnings
- **Framework integrations:**
  - Rewritten Vercel AI middleware around `getContext()` (3-tier reflections + observations + recent messages)
  - New LangChain JS adapter (`Neo4jChatMessageHistory`, `Neo4jEntityRetriever`)
  - New Mastra adapter (`Neo4jMastraMemory`)
  - New LangGraph checkpoint saver (Python)
  - New PydanticAI helper (`MemoryToolset`, `inject_memory_context`)
  - New Semantic Kernel connector (C#)
  - New ellmer wrapper (R) — `register_memory_tools()`
- **Documentation refresh** — 5 new docs pages: hosted-quickstart, authenticate, rest-api, mcp-tools, framework-integrations. CLAUDE.md and README updated.
- **Demo + infra** — `MEMORY_API_KEY` / `MEMORY_MODE` env vars wired into every agent; `compose.hosted.yml` overlay drops local Neo4j and points everything at the hosted service.
- **CI:** new `python-client-build` job; new `hosted-smoke` job gated on the `MEMORY_API_KEY` repo secret.

### Notable Design Decisions

- **Dual-transport over fork:** Each client speaks both protocols from a single API surface. This keeps TCK conformance servers working unchanged while production users get hosted-service support, and avoids fragmenting the SDK ecosystem into `*-bridge` and `*-rest` packages.
- **Platinum is opt-in, not a hard requirement:** Implementations that target only the bridge protocol can leave the 23 new BaseAdapter methods at their `NotImplementedError` defaults; the Platinum tests skip rather than fail. This mirrors how Gold optional methods already worked.
- **Hosted REST has no `clear_all_data` equivalent:** RestTransport's `clear_all_data` is a no-op. The TCK's `_check_adapter_and_clean` autouse fixture still runs, but tests against the hosted service are expected to use scoped workspaces / unique conversation IDs for isolation rather than global wipes.

## Phase 6: C# Client, R Client + Sage/Rune Agents — Complete

**Goal:** Add C# and R client libraries and two more demo agents (Sage using Semantic Kernel, Rune using ellmer).

### Delivered

- **C# client library** (`clients/csharp/`) — .NET 8.0 class library with `MemoryClient` composing `ShortTerm`, `LongTerm`, and `Reasoning` sub-clients
  - `ITransport` + `HttpTransport` abstraction mirroring TS/Go pattern
  - All 26 BaseAdapter methods implemented as async/await with CancellationToken
  - `IAsyncDisposable` lifecycle management
  - Custom exception hierarchy: `MemoryException`, `TransportException`, `ConnectionException`, `AuthenticationException`
  - System.Text.Json serialization with `[JsonPropertyName]` for wire format
- **Conformance server** (`conformance/Neo4j.AgentMemory.Conformance/`) — ASP.NET Minimal API mapping all 26 bridge endpoints
- **Unit tests** (`tests/Neo4j.AgentMemory.Tests/`) — xUnit tests with NSubstitute mocks for ShortTerm, LongTerm, and Reasoning
- **Sage agent** (`demo/agents/sage/`) — C#/Semantic Kernel knowledge validation agent:
  - ConflictDetector: Detects type mismatches and contradictions between entity facts
  - KnowledgeAuditor: Audits graph integrity, entity counts, session activity
  - Works without LLM key (like Forge) for programmatic validation
  - Port 8005, session prefix `sage-`
- **Infrastructure updates**: Docker Compose, dashboard agent registry, integration test, seed data
- **Documentation updates**: README, CLAUDE.md, ROADMAP.md, demo README, C# client README
- **CI conformance pipeline**: GitHub Actions job running Bronze TCK tests against C# bridge with Neo4j service container, pre-build step, and health-check-based startup
- **Reference adapter fix**: Custom `delete_message` Cypher query with proper chain repair and `DETACH DELETE` to work around upstream package bug
- **Bridge error handling**: Error-handling middleware in reference bridge server (JSON error responses instead of aiohttp default 500 pages)
- **R client library** (`clients/rlang/neo4j.memory/`) — R package with R6 classes, httr2 transport, jsonlite serialization:
  - `MemoryClient` composing `short_term`, `long_term`, and `reasoning` sub-clients
  - 98 `testthat` assertions across 7 test files
  - Conformance server (`conformance/server.R`) handling all 26 bridge endpoints via plumber
- **Rune agent** (`demo/agents/rune/`) — R/ellmer statistical-analysis agent with regression tools, port 8006
- **R conformance CI job** — GitHub Actions job running Bronze TCK tests against R bridge with Neo4j service container
- **Dashboard NVL integration** — Real `@neo4j-nvl/react` `InteractiveNvlWrapper`, chat panel, and node-detail drawer

---

### v1.0.1 — Completion (Weeks 21-22)

Close the gaps identified above to achieve full milestone acceptance:

1. **TypeScript native TCK tests** — Implement the 30+ Vitest test cases in `test/tck/bronze.test.ts` using the existing scaffolded structure and `testdata.ts`
2. **Go native conformance tests** — Create `memory/tck/conformance_test.go` with table-driven sub-tests mirroring the Python TCK, run with `-race` flag
3. **Badge generator** — `tck/report/badge_generator.py` generating SVG badges for Bronze/Silver/Gold
4. **Certify CLI** — `tck/report/certify.py` that runs the TCK, generates a report, and produces a registry entry
5. **Dashboard build configs** — Add `next.config.ts` and `tsconfig.json` to `demo/dashboard/`
6. **2 additional Gold tests** — Reach the 20+ target
7. **Update `certifications/registry.json`** — Populate with Python reference implementation entry

### v1.1.0 — Service API & Error Handling (Weeks 23-26)

Hosted REST coverage (the dual transport in every client + Platinum tier) has been delivered ahead of schedule (Phase 7). What remains for v1.1.0:

1. **Service API contract tests** (`tck/service/endpoints/`) — populate the previously-empty directory with REST-specific tests (status codes, error envelopes, rate-limit headers, pagination edge cases)
2. **Authentication scenarios** (`tck/service/auth/`) — `nams_*` key prefix validation, OAuth PKCE round-trip, scope enforcement
3. **Error handling scenarios** (`tck/error-handling/`) — Error codes, retry semantics, partial failure modes
4. **OpenAPI specification** (`tck/bridge/openapi.yaml`) — Machine-readable bridge protocol for code generation
5. **Neo4j direct transports** — `transport/neo4j.ts` (TypeScript) and `neo4j_transport.go` (Go) for local development

### v1.2.0 — Observational Memory (Weeks 27-32)

The hosted service exposes `observations` and `reflections` already (Volume 5 / Platinum). What's left for v1.2.0:

1. **Deep observational memory scenarios** (`tck/observational/`) — Observer/Reflector pipeline, token threshold triggering, graph-connected observations beyond the Platinum smoke tests
2. **Cross-session observation scenarios** — Observations linked across conversations via entity graph
3. **Reflection regeneration scenarios** — Verify reflections supersede prior versions correctly
4. **Reference adapter parity** — Wire the reference adapter to the upstream `neo4j-agent-memory` Python package's observation API once it lands

### v1.3.0 — MCP Full Surface (Weeks 33-36)

The 12-tool MCP surface is in place (Phase 7). What's still pending:

1. **MCP scenarios** (`tck/service/mcp/`) — TCK tests that hit the 12 standard tools through Streamable HTTP transport
2. **MCP resource definitions** — Memory state (entities, conversations, traces) exposed as MCP resources
3. **MCP prompt templates** — Pre-built prompts for common memory operations (e.g. "summarize this conversation", "what do we know about X")
4. **TypeScript MCP server** — Full `@modelcontextprotocol/sdk` integration alongside the existing tool-definition module

### v2.0.0 — Ecosystem Maturity

Long-term goals for ecosystem growth:

1. **Framework integrations** (in progress — see Phase 7):
   - ~~LangChain.js~~ — Complete (`Neo4jChatMessageHistory`, `Neo4jEntityRetriever`)
   - ~~Mastra~~ — Complete (`Neo4jMastraMemory`)
   - ~~LangGraph~~ — Complete (`MemoryCheckpointSaver`)
   - ~~PydanticAI~~ — Complete (`MemoryToolset`, `inject_memory_context`)
   - ~~Semantic Kernel~~ — Complete (`MemoryConnector`)
   - ~~ellmer~~ — Complete (`register_memory_tools`)
   - OpenAI Node SDK tool result capture hooks (still pending)
   - Anthropic TypeScript SDK message history management (still pending)
   - LangChainGo memory interface (still pending)
   - Google Genkit Go plugin (still pending)
2. **Automatic test generation** — Generate test scenarios from SPEC clauses (PRD v2 goal)
3. **Public compatibility registry** — Web page at `neo4j.com/labs/agent-memory/compatibility`
4. **Cloud Run deployment** — `cloudbuild.yaml` and Terraform configs for demo
5. ~~**NVL integration**~~ — Complete: `@neo4j-nvl/react` `InteractiveNvlWrapper` integrated in dashboard
6. **Third-party certification automation** — Run TCK against publicly accessible service endpoints (the new `hosted-smoke` CI job is the seed)

---

## Metrics Summary

| Metric | Target (PRD) | Current | Status |
|--------|-------------|---------|--------|
| Bronze scenarios | 80-120 | 93 | Met |
| Silver scenarios | 180-240 cumulative | 67 | Partial (Silver standalone; PRD counted cumulative) |
| Gold scenarios | 120-160 additional | 18 | In progress |
| Platinum scenarios (Volume 5) | new | 11 | Seed delivered; expansion planned in v1.1.0/v1.2.0 |
| Total scenarios | 380-520 | 189 | Phase 1 of multi-phase expansion |
| Python Bronze pass rate | 100% | 93/93 (local run confirmed) | Met |
| TypeScript Bronze pass rate | 100% | Tests scaffolded | Pending |
| Go Bronze pass rate | 100% | Tests not written | Pending |
| Hosted-service support | new | 5/5 clients (dual transport) | Met |
| Multi-agent demo | Live URL | Docker Compose + hosted-mode overlay | Pending deployment |
| Third-party registry entries | 3+ within 90 days | 0 | Not yet launched |

> **Note:** The PRD's scenario count targets (380-520) span all planned phases including Service API, Observational Memory, and MCP scenarios. The current 189 scenarios represent the core behavioral specification plus the Volume 5 seed. Service API contract tests, deep observational scenarios, and MCP integration tests are planned for v1.1.0 / v1.2.0 / v1.3.0 respectively.
