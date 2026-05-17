# Examples

Runnable examples demonstrating each integration shipped with
`@neo4j-labs/agent-memory`. Each example is a self-contained Node.js
script you can clone, install, and run against the hosted Neo4j Agent
Memory Service.

## Prerequisites

- Node.js 20+
- A `MEMORY_API_KEY` from [memory.neo4jlabs.com](https://memory.neo4jlabs.com)

## The examples

| Folder | What it shows |
|---|---|
| [`vercel-ai/`](./vercel-ai) | Memory-augmented chat using the Vercel AI SDK middleware |
| [`mcp/`](./mcp) | Registering the 12 memory tools with an MCP server |
| [`langchain/`](./langchain) | Chat history + entity retriever shapes for LangChain JS |
| [`mastra/`](./mastra) | Wrapping the client as a Mastra-compatible memory provider |
| [`strands/`](./strands) | AWS Strands agent with session persistence, three-tier context, and reasoning capture |

## Running an example

```bash
cd vercel-ai
cp .env.example .env       # then edit .env with your MEMORY_API_KEY
npm install
npm start
```

Each example's own README has the full step-by-step.

## Notes

- These examples use `"file:.."` to depend on the local client package
  during development. When using the examples as a template for your own
  project, replace that with a pinned version like
  `"@neo4j-labs/agent-memory": "^0.3.0"`.
- Examples are **not** exercised by CI — they're documentation. If you
  find one broken, please open an issue.
