# LangChain JS example

Exercises `Neo4jChatMessageHistory` and `Neo4jEntityRetriever` directly,
without depending on any specific `@langchain/*` package version. These
classes are duck-typed against the LangChain JS interfaces, so they fit
into `RunnableWithMessageHistory` and a retriever chain regardless of
LangChain JS version skew.

## Run it

```bash
cp .env.example .env       # set MEMORY_API_KEY
npm install
npm start
```

The script:
1. Creates a fresh conversation.
2. Persists three messages via the `BaseChatMessageHistory`-shaped API.
3. Reads them back.
4. Adds an entity and queries it via the retriever interface.

## Using inside a real LangChain JS app

```ts
import { ChatOpenAI } from "@langchain/openai";
import { RunnableWithMessageHistory } from "@langchain/core/runnables";
import { Neo4jChatMessageHistory } from "@neo4j-labs/agent-memory/integrations/langchain";

const chain = new ChatOpenAI({ model: "gpt-4o-mini" }).pipe(...);

const memoryChain = new RunnableWithMessageHistory({
  runnable: chain,
  getMessageHistory: (sessionId) => new Neo4jChatMessageHistory(memory, sessionId),
  inputMessagesKey: "input",
  historyMessagesKey: "history",
});
```

## See also

- [How-to: LangChain JS](../../../../docs/how-to/langchain.adoc)
- [LangChain JS docs](https://js.langchain.com)
