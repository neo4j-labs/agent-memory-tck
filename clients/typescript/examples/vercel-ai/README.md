# Vercel AI SDK example

Memory-augmented chat using the Vercel AI SDK and the
`agentMemoryMiddleware` from `@neo4j-labs/agent-memory/middleware/vercel-ai`.

## What it shows

- Wiring `agentMemoryMiddleware` around an `openai("gpt-4o-mini")` model
  via `experimental_wrapLanguageModel`.
- Three-tier context (reflections + observations + recent messages)
  prepended to every model call.
- Automatic persistence of both the user input and the assistant response.

## Run it

```bash
cp .env.example .env
# edit .env: set MEMORY_API_KEY and OPENAI_API_KEY
npm install
npm start
```

You'll see a three-turn chat where the assistant remembers context from
prior turns. Re-run the script to see context recalled across sessions
(the conversation id is printed at the end of each run).

## See also

- [How-to: Vercel AI middleware](../../../../docs/how-to/vercel-ai.adoc)
- [Vercel AI SDK docs](https://sdk.vercel.ai)
