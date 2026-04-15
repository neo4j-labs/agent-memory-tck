# Multi-Agent Memory Demo

This demo showcases five AI agents written in four languages sharing the same Neo4j knowledge graph. It proves the core TCK value proposition: if implementations pass the TCK, their memory writes are interoperable.

## Architecture

```
                    ┌─────────────────────┐
                    │   Neo4j (Graph DB)   │
                    │   localhost:7687     │
                    └──────────┬──────────┘
                               │
                    ┌──────────┴──────────┐
                    │  Python Bridge       │
                    │  Server (:3001)      │
                    │  (memory service)    │
                    └──────────┬──────────┘
                               │
         ┌─────────┬───────────┼───────────┬──────────┬──────────┐
         │         │           │           │          │          │
    ┌────┴────┐ ┌──┴───┐  ┌───┴────┐ ┌────┴───┐ ┌───┴───┐ ┌───┴──────┐
    │ Lenny   │ │Scout │  │ Forge  │ │ Atlas  │ │ Sage  │ │Dashboard │
    │ Python  │ │  TS  │  │   Go   │ │ Python │ │  C#   │ │ Next.js  │
    │ :8001   │ │:8002 │  │ :8003  │ │ :8004  │ │:8005  │ │ :3000    │
    └─────────┘ └──────┘  └────────┘ └────────┘ └───────┘ └──────────┘
```

### Agents

| Agent | Language | Framework | Role | Port |
|-------|----------|-----------|------|------|
| **Lenny** | Python | PydanticAI | Podcast research — extracts entities from transcripts | 8001 |
| **Scout** | TypeScript | Vercel AI SDK | Web search — enriches graph with web findings | 8002 |
| **Forge** | Go | Custom HTTP | Data pipeline — adds structured properties to entities | 8003 |
| **Atlas** | Python | LangGraph | Orchestrator — synthesizes knowledge from all agents | 8004 |
| **Sage** | C# | Semantic Kernel | Knowledge validation — detects contradictions and conflicts | 8005 |

### Shared Memory Model

- **Entities are shared** — An entity created by Lenny (Python) is immediately readable by Forge (Go) and Scout (TypeScript).
- **Conversations are isolated** — Each agent has its own session prefix (`lenny-*`, `scout-*`, `forge-*`, `atlas-*`, `sage-*`).
- **Reasoning traces are per-agent** — Each agent records its own reasoning, but Atlas can read all agents' traces for synthesis.

## Quick Start

### Prerequisites

- Docker Desktop (for Neo4j)
- Python 3.10+ with [uv](https://docs.astral.sh/uv/)
- Go 1.21+
- .NET 8.0+ SDK
- Node.js 20+

### Step 1: Start Neo4j

```bash
cd demo/infra
docker compose up neo4j -d
```

Wait for Neo4j to be healthy (~10 seconds). You can verify at http://localhost:7474 (login: `neo4j` / `password`).

### Step 2: Start the Bridge Server

The bridge server wraps the Python `neo4j-agent-memory` package and exposes it as an HTTP API that all agents connect to.

```bash
# From the repo root
uv sync --extra reference --extra bridge
NEO4J_URI=bolt://localhost:7687 NEO4J_USERNAME=neo4j NEO4J_PASSWORD=password \
  uv run python -m tck.bridge.reference_server
```

This starts on port 3001. Keep this terminal open.

### Step 3: Seed the Graph

In a new terminal, populate the graph with a realistic AI industry research scenario:

```bash
uv run python demo/seed-data.py
```

This creates:
- **18 entities** — Sam Altman, Dario Amodei, Jensen Huang, OpenAI, Anthropic, NVIDIA, etc.
- **36 facts** — CEO_OF, INVESTED_IN, CREATED, INTERVIEWED relationships
- **5 conversations** — one per agent, showing their distinct workflows
- **6 reasoning traces** — with steps and tool calls from each agent
- **24 messages** — across all five agent sessions

### Step 4: Start the Dashboard

```bash
cd demo/dashboard
npm install
NEO4J_URI=bolt://localhost:7687 NEO4J_USERNAME=neo4j NEO4J_PASSWORD=password \
  npx next dev -p 3000
```

Open **http://localhost:3000** to see:
- Force-directed graph visualization with nodes colored by agent
- Agent status panels with per-agent metrics
- Activity feed showing recent operations
- Click any node to see its properties and connections

### Step 5 (Optional): Start the Go Agent

Forge is the only agent that works without an LLM API key:

```bash
cd demo/agents/forge
MEMORY_ENDPOINT=http://localhost:3001 PORT=8003 go run .
```

Then enrich an entity:

```bash
curl -X POST http://localhost:8003/enrich \
  -H "Content-Type: application/json" \
  -d '{"entity_name":"Sam Altman","properties":{"NET_WORTH":"$1B","BORN":"1985"}}'
```

Refresh the dashboard to see the new facts appear in the graph.

## Running All Agents

Lenny, Scout, and Atlas require an `OPENAI_API_KEY` since they use LLM-powered reasoning. Forge and Sage work without one. If you have a key:

```bash
# Terminal 1 — Lenny (Python/PydanticAI)
cd demo/agents/lenny
MEMORY_ENDPOINT=http://localhost:3001 OPENAI_API_KEY=sk-... \
  uv run uvicorn lenny.main:app --port 8001

# Terminal 2 — Scout (TypeScript/Vercel AI SDK)
cd demo/agents/scout
npm install
MEMORY_ENDPOINT=http://localhost:3001 OPENAI_API_KEY=sk-... \
  npx tsx src/index.ts

# Terminal 3 — Forge (Go)
cd demo/agents/forge
MEMORY_ENDPOINT=http://localhost:3001 PORT=8003 go run .

# Terminal 4 — Sage (C#/Semantic Kernel)
cd demo/agents/sage
MEMORY_ENDPOINT=http://localhost:3001 PORT=8005 dotnet run

# Terminal 5 — Atlas (Python/LangGraph)
cd demo/agents/atlas
MEMORY_ENDPOINT=http://localhost:3001 OPENAI_API_KEY=sk-... \
  uv run uvicorn atlas.main:app --port 8004
```

## Demo Scenario

The seed data tells a coherent story:

1. **Lenny** (Python/PydanticAI) analyzed a Lex Fridman podcast episode with Sam Altman. It extracted 8 people, 7 organizations, and 3 events, then recorded 25 facts connecting them.

2. **Scout** (TypeScript/Vercel AI SDK) searched the web for the latest OpenAI and Anthropic developments. It enriched the graph with information about GPT-4 Turbo, Anthropic's Series D funding, and the competitive landscape.

3. **Forge** (Go/Custom HTTP) ran a data pipeline that added structured properties to key entities — Sam Altman's education, NVIDIA's market cap, Anthropic's headquarters.

4. **Sage** (C#/Semantic Kernel) validated the knowledge graph for contradictions. It checked entity types and relationships for consistency, confirming high confidence scores across all agent contributions.

5. **Atlas** (Python/LangGraph) orchestrated a synthesis across all agents. It gathered entities and reasoning traces from Lenny, Scout, Forge, and Sage, then produced a unified AI industry landscape report.

Each agent wrote to the same Neo4j graph using different languages and frameworks, but because they all use the TCK-compliant memory interface, their writes are fully interoperable.

## Directory Structure

```
demo/
├── seed-data.py              # Populates the graph via the bridge server
├── agents/
│   ├── lenny/                # Python / PydanticAI
│   │   ├── lenny/
│   │   │   ├── agent.py      # PydanticAI agent with system prompt
│   │   │   ├── tools.py      # extract_entities, search_knowledge
│   │   │   └── main.py       # FastAPI server
│   │   ├── pyproject.toml
│   │   └── Dockerfile
│   ├── scout/                # TypeScript / Vercel AI SDK
│   │   ├── src/
│   │   │   └── index.ts      # Hono server with memory middleware
│   │   ├── package.json
│   │   └── Dockerfile
│   ├── forge/                # Go / Custom HTTP
│   │   ├── main.go           # net/http server with enrich/pipeline
│   │   ├── go.mod
│   │   └── Dockerfile
│   ├── sage/                 # C# / Semantic Kernel
│   │   ├── Program.cs        # ASP.NET Minimal API with validate/audit
│   │   ├── Services/
│   │   │   ├── ConflictDetector.cs
│   │   │   └── KnowledgeAuditor.cs
│   │   ├── Sage.csproj
│   │   └── Dockerfile
│   └── atlas/                # Python / LangGraph
│       ├── atlas/
│       │   ├── graph.py      # LangGraph state machine
│       │   └── main.py       # FastAPI server
│       ├── pyproject.toml
│       └── Dockerfile
├── dashboard/                # Next.js real-time visualization
│   ├── app/
│   │   ├── page.tsx          # Three-panel layout
│   │   ├── layout.tsx        # Root layout
│   │   └── api/
│   │       ├── graph/        # Neo4j graph data for visualization
│   │       ├── agents/       # Agent health + metrics
│   │       ├── activity/     # Recent operations feed
│   │       └── node-detail/  # Node properties + connections
│   ├── components/
│   │   ├── graph-viz.tsx     # Canvas force-directed visualization
│   │   ├── agent-panel.tsx   # Agent status cards
│   │   ├── header-bar.tsx    # Top bar with counts
│   │   ├── activity-feed.tsx # Scrolling event log
│   │   └── entity-detail.tsx # Node detail sidebar
│   ├── package.json
│   └── next.config.mjs
└── infra/
    ├── docker-compose.yml    # Neo4j + Forge services
    └── integration_test.py   # Cross-language entity sharing test
```

## API Endpoints

### Agents

| Agent | Endpoint | Method | Description |
|-------|----------|--------|-------------|
| Lenny | `/research` | POST | Extract entities from podcast transcript |
| Lenny | `/health` | GET | Health check |
| Scout | `/search` | POST | Search web and enrich graph |
| Scout | `/enrich` | POST | Enrich a specific entity |
| Scout | `/health` | GET | Health check |
| Forge | `/enrich` | POST | Add structured properties to entity |
| Forge | `/pipeline` | POST | Search entities by query |
| Forge | `/health` | GET | Health check |
| Atlas | `/synthesize` | POST | Cross-agent knowledge synthesis |
| Atlas | `/health` | GET | Health check |
| Sage | `/validate` | POST | Detect contradictions in entity facts |
| Sage | `/audit` | POST | Audit knowledge graph integrity |
| Sage | `/health` | GET | Health check |

### Dashboard API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/graph` | GET | All nodes and edges for visualization |
| `/api/agents` | GET | Agent health status + metrics |
| `/api/activity` | GET | Recent operations (last 30) |
| `/api/node-detail` | POST | Node properties + connections |

## Exploring the Graph

Open Neo4j Browser at http://localhost:7474 and try these queries:

```cypher
-- See all entities and their relationships
MATCH (e:Entity)-[r]-(other)
RETURN e, r, other

-- Find all facts about a person
MATCH (f:Fact)
WHERE f.subject = 'Sam Altman'
RETURN f.subject, f.predicate, f.object

-- See reasoning traces by agent
MATCH (t:ReasoningTrace)
RETURN t.session_id, t.task, t.outcome
ORDER BY t.started_at DESC

-- Count contributions per agent
MATCH (c:Conversation)-[:HAS_MESSAGE]->(m:Message)
RETURN c.session_id AS session,
       split(c.session_id, '-')[0] AS agent,
       count(m) AS messages
ORDER BY agent
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Bridge server won't start | Check Neo4j is running: `curl http://localhost:7474` |
| Dashboard shows 0 nodes | Run `uv run python demo/seed-data.py` to populate the graph |
| Forge can't connect | Ensure bridge is running on port 3001 |
| Agent panels show red dots | Only Forge runs without `OPENAI_API_KEY`; others need it |
| Port already in use | Kill existing process: `lsof -ti:3001 \| xargs kill -9` |
