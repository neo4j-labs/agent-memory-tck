# spool — Strands × Neo4j Agent Memory

[![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https%3A%2F%2Fgithub.com%2Fneo4j-labs%2Fagent-memory-tck&root-directory=demo%2Fagents%2Fspool&env=MEMORY_API_KEY,OPENAI_API_KEY&envDescription=Optional%3A%20unset%20both%20to%20browse%20in%20stub%20mode.)

A full-stack demo of an AWS Strands agent backed by the Neo4j Agent
Memory Service. Wired via the `@neo4j-labs/agent-memory/integrations/strands`
subpath.

## What you see

- **Chat** — three-turn dialogue with a Strands agent
- **Reasoning** — live trace of every step the agent took, with tool calls
- **Entities** — the long-term knowledge graph the service has extracted
- **Context** — reflections + observations the agent uses on every turn
- **Sessions** — resume any past conversation tied to your cookie

All four side panels poll the matching NAMS endpoint after each turn.
The reasoning panel is what makes the Strands integration tangible: every
`agent.invoke()` produces a `ReasoningStep` with `ToolCall` children, and
they show up here.

## Try it

### Option 1 — without keys (stub mode)

```bash
cp .env.example .env       # leave MEMORY_API_KEY and OPENAI_API_KEY blank
npm install
npm run dev
# open http://localhost:3000
```

You'll see canned conversations, a canned reasoning trace, and canned
entities. No NAMS calls happen. Useful for clicking through the demo
without setup.

### Option 2 — live mode

```bash
cp .env.example .env
# edit .env:
#   MEMORY_API_KEY=nams_...    (get one at https://memory.neo4jlabs.com)
#   OPENAI_API_KEY=sk-...
npm install
npm run dev
```

Real Strands agent (`gpt-4o-mini` via OpenAI), real NAMS persistence,
real reasoning capture, real entity extraction. Each browser session
gets its own anonymous cookie userId.

### Option 3 — Vercel deploy

Click the deploy button at the top of this README. Vercel will prompt
for `MEMORY_API_KEY` and `OPENAI_API_KEY`; leave both blank to run in
stub mode.

## Architecture

```
spool/                          Next.js 14 app (App Router)
├── app/
│   ├── page.tsx                 ← Chat (left) + tabbed SidePanel (right)
│   ├── layout.tsx
│   ├── providers.tsx            ← Chakra v3 + next-themes
│   └── api/
│       ├── chat/                ← Streaming chat endpoint
│       ├── trace/               ← Reasoning panel data
│       ├── entities/            ← Entities panel data
│       ├── context/             ← Context panel data
│       └── sessions/            ← Sessions panel data
├── components/
│   ├── Chat.tsx
│   ├── SidePanel.tsx            ← Tabs container
│   ├── ReasoningPanel.tsx
│   ├── EntitiesPanel.tsx
│   ├── ContextPanel.tsx
│   └── SessionsPanel.tsx
└── lib/
    ├── memory.ts                ← MemoryClient singleton + mode detect
    ├── session.ts               ← Cookie session helper
    ├── stub-model.ts            ← Canned responses for keys-less mode
    └── theme.ts                 ← Chakra theme tokens
```

## Notes

- **Not a security boundary.** Cookies aren't auth — anyone who knows
  your conversation id can resume it. Don't paste secrets into the chat
  on a public deploy.
- **Node runtime only.** The `/api/chat` route uses Node (not Edge)
  because Strands imports `@strands-agents/sdk` and its peer dependencies
  at runtime.
- **`file:` dependency.** The example depends on the local
  `@neo4j-labs/agent-memory` build via `file:../../../clients/typescript`.
  When forking this for your own project, pin to the published version
  on npm.

## See also

- [How-to: Strands integration](../../../docs/how-to/strands.adoc)
- [Strands Agents docs](https://strandsagents.com)
- [Neo4j Agent Memory Service](https://memory.neo4jlabs.com)
