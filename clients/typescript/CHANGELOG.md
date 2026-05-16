# Changelog

All notable changes to `@neo4j-labs/agent-memory` will be documented in
this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project follows [Semantic Versioning](https://semver.org).

This is a Neo4j Labs project under Beta status — breaking changes may
appear in minor versions with a callout in this file.

## 0.1.0 — Initial Beta Release

Initial public release.

### Added

- `MemoryClient` with `shortTerm`, `longTerm`, `reasoning`, `query`, and
  `auth` subclients
- Featured framework integrations:
  - `@neo4j-labs/agent-memory/middleware/vercel-ai` — Vercel AI SDK
    middleware with three-tier context injection and automatic
    persistence
  - `@neo4j-labs/agent-memory/mcp` — the 12 standard MCP tool
    definitions plus a dispatcher
  - `@neo4j-labs/agent-memory/integrations/langchain` —
    `Neo4jChatMessageHistory` and `Neo4jEntityRetriever` (duck-typed)
  - `@neo4j-labs/agent-memory/integrations/mastra` — `Neo4jMastraMemory`
    provider (duck-typed)
- `@neo4j-labs/agent-memory/testing` — `BridgeTransport` for TCK
  conformance testing
- Zero-config construction: defaults endpoint to
  `https://memory.neo4jlabs.com/v1`; reads `MEMORY_API_KEY` from
  environment
- Lazy `connect()`: the first request acts as the implicit auth check;
  explicit `connect()` is supported for fail-fast startups
- Auto User-Agent header with caller override
- `requestId` propagated onto every `MemoryError` from the
  `x-request-id` (or equivalent) response header
- `logger` constructor option emitting typed `request` / `response` /
  `error` events
- Edge runtime support: Cloudflare Workers, Vercel Edge (with explicit
  `apiKey` pattern)
- TCK Bronze conformance via the polyglot test suite
