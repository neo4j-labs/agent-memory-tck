# neo4j-agent-memory TCK

[![neo4j-labs](https://img.shields.io/badge/Neo4j_Labs-Experimental-orange?logo=neo4j)](https://neo4j.com/labs/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Python 3.10+](https://img.shields.io/badge/Python-3.10+-blue.svg)](https://www.python.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.5+-blue.svg?logo=typescript)](https://www.typescriptlang.org/)
[![Go 1.21+](https://img.shields.io/badge/Go-1.21+-00ADD8.svg?logo=go)](https://go.dev/)

Technology Compatibility Kit for [neo4j-agent-memory](https://github.com/neo4j-labs/agent-memory) implementations.

The TCK provides a formal behavioral specification, 178 executable test scenarios, and a compliance framework that enables any implementation — in any language — to verify conformance with the neo4j-agent-memory data model. It also includes production-ready TypeScript and Go client libraries and a multi-agent reference demo.

> **Neo4j Labs Project** — This project is maintained by Neo4j Labs as an experimental, community-supported project. It is not officially supported by Neo4j. For community support, use [GitHub Issues](https://github.com/neo4j-labs/agent-memory-tck/issues).

## Repository Contents

```
agent-memory-tck/
  tck/                  Python TCK specification + 178 test scenarios
  clients/typescript/   @neo4j-labs/agent-memory npm package
  clients/go/           agent-memory-go module
  demo/                 4-agent polyglot demo (Python, TypeScript, Go)
  docs/                 AsciiDoc documentation (Diataxis framework)
  SPEC.md               Normative specification v1.0.0
```

| Component | Description |
|-----------|-------------|
| **TCK Specification** | 178 test scenarios across 3 compliance tiers, backed by `SPEC.md` with 98+ behavioral clauses |
| **Scenario Registry** | 178 stable scenario IDs (`SCN-B-001` through `SCN-G-018`) with SPEC clause traceability |
| **HTTP Bridge** | Cross-language conformance protocol enabling the Python test suite to validate TypeScript, Go, or any implementation |
| **TypeScript Client** | `@neo4j-labs/agent-memory` with `MemoryClient`, Vercel AI SDK middleware, and MCP tool definitions |
| **Go Client** | `memory` package with context-aware API, functional options, generic `Entity[T]`, and MCP handler |
| **Multi-Agent Demo** | Lenny (Python/PydanticAI), Scout (TypeScript/Vercel AI SDK), Forge (Go), Atlas (Python/LangGraph) |
| **Documentation** | AsciiDoc docs following the Diataxis framework: tutorials, how-to guides, reference, explanation |

## Compliance Tiers

| Tier | Scenarios | Scope |
|------|-----------|-------|
| **Bronze** | 93 | Schema compliance + short-term (conversational) memory |
| **Silver** | 67 | Bronze + long-term (entity/preference/fact) memory + reasoning (trace/step/tool call) memory |
| **Gold** | 18 | Silver + cross-memory integration + multi-agent sharing semantics |

## Quick Start

### Install the TCK

```bash
# Using uv (recommended)
uv add neo4j-agent-memory-tck

# Or with pip
pip install neo4j-agent-memory-tck
```

### Write an Adapter

Implement the `BaseAdapter` interface for your memory system:

```python
from tck.adapters.base_adapter import BaseAdapter, TCKMessage

class MyAdapter(BaseAdapter):
    async def setup(self) -> None:
        # Connect to your backend
        ...

    async def teardown(self) -> None:
        # Close connections
        ...

    async def clear_all_data(self) -> None:
        # Delete all data (called before each test)
        ...

    async def add_message(self, session_id, role, content, *, metadata=None) -> TCKMessage:
        # Store a message and return a TCKMessage
        ...

    # ... implement remaining methods for your target tier
```

### Register and Run

```python
# conftest.py
import pytest

@pytest.fixture(scope="session")
async def adapter():
    adapter = MyAdapter(uri="bolt://localhost:7687")
    await adapter.setup()
    yield adapter
    await adapter.teardown()
```

```bash
# Run Bronze tier (93 scenarios)
uv run pytest -m bronze -v

# Run all tiers (178 scenarios)
uv run pytest -v

# Generate compliance report
uv run pytest --json-report --json-report-file=results.json
uv run tck results.json --name "My Implementation" -o report.json --html report.html
```

### Cross-Language Testing

Test TypeScript or Go implementations via the HTTP bridge protocol:

```bash
# Start a conformance server (TypeScript example)
cd clients/typescript
MEMORY_ENDPOINT=http://localhost:7474 npm run conformance:server

# Run the Python TCK against it
uv run pytest -m bronze --bridge-url http://localhost:3001 -v
```

## TypeScript Client

```typescript
import { MemoryClient } from "@neo4j-labs/agent-memory";

const client = new MemoryClient({ endpoint: "https://memory.cypherlite.cloud" });
await client.connect();

// Short-term memory
await client.shortTerm.addMessage("session-1", "user", "Hello!");

// Long-term memory
await client.longTerm.addEntity("Alice", "PERSON", { description: "Engineer" });

// Reasoning memory
const trace = await client.reasoning.startTrace("session-1", "Research task");
```

Includes [Vercel AI SDK middleware](clients/typescript/src/middleware/vercel-ai.ts) and [MCP tool definitions](clients/typescript/src/mcp/index.ts). See the [TypeScript client README](clients/typescript/README.md).

## Go Client

```go
client, _ := memory.New(memory.WithEndpoint("https://memory.cypherlite.cloud"))
defer client.Close(ctx)

// Short-term memory
msg, _ := client.ShortTerm.AddMessage(ctx, "session-1", memory.RoleUser, "Hello!")

// Long-term memory
entity, _ := client.LongTerm.AddEntity(ctx, "Alice", "PERSON", memory.WithDescription("Engineer"))

// Reasoning memory
trace, _ := client.Reasoning.StartTrace(ctx, "session-1", "Research task")
```

Includes [MCP handler](clients/go/memory/mcp_handler.go) (`http.Handler`). See the [Go client README](clients/go/README.md).

## Multi-Agent Demo

Four agents in three languages sharing one Neo4j graph:

| Agent | Language | Framework | Role |
|-------|----------|-----------|------|
| **Lenny** | Python | PydanticAI | Podcast research, primary entity builder |
| **Scout** | TypeScript | Vercel AI SDK | Web search, graph enrichment |
| **Forge** | Go | Custom HTTP | Data pipeline, property enrichment |
| **Atlas** | Python | LangGraph | Orchestrator, cross-agent synthesis |

```bash
cd demo/infra
docker compose up
# Dashboard: http://localhost:3000
```

## Documentation

Documentation is written in AsciiDoc following the [Diataxis framework](https://diataxis.fr/):

```bash
cd docs
make html         # Build HTML docs (requires asciidoctor)
make install-deps # Install asciidoctor if needed
```

| Section | Contents |
|---------|----------|
| **Tutorials** | [Getting Started](docs/tutorials/getting-started.adoc), [First TypeScript Agent](docs/tutorials/first-typescript-agent.adoc) |
| **How-To Guides** | [Writing an Adapter](docs/how-to/writing-an-adapter.adoc), [Cross-Language Testing](docs/how-to/cross-language-testing.adoc), [CI Integration](docs/how-to/ci-integration.adoc), [Certification](docs/how-to/certification.adoc) |
| **Reference** | [BaseAdapter Interface](docs/reference/base-adapter.adoc), [Bridge Protocol](docs/reference/bridge-protocol.adoc), [Compliance Tiers](docs/reference/compliance-tiers.adoc), [Scenario Registry](docs/reference/scenario-registry.adoc) |
| **Explanation** | [Architecture](docs/explanation/architecture.adoc), [Memory Model](docs/explanation/memory-model.adoc), [Multi-Agent Sharing](docs/explanation/multi-agent-sharing.adoc) |

## Certification Badges

Implementations that pass the TCK can display the appropriate badge:

```markdown
![Bronze Certified](https://img.shields.io/badge/agent--memory--tck-Bronze-F97316?logo=neo4j)
![Silver Certified](https://img.shields.io/badge/agent--memory--tck-Silver-22C55E?logo=neo4j)
![Gold Certified](https://img.shields.io/badge/agent--memory--tck-Gold-6366F1?logo=neo4j)
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding standards, and contribution guidelines.

## Roadmap

See [ROADMAP.md](ROADMAP.md) for implementation status, gap analysis, and future plans.

## License

Apache 2.0 — See [LICENSE](LICENSE).
