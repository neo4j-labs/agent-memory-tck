# MCP example

A self-hosted MCP server that exposes the 12 standard neo4j-agent-memory
tools, backed by a `MemoryClient`. Communicates over stdio.

## When to use this

Most users **don't** need to self-host MCP — the hosted service already
exposes the same tools at `https://memory.neo4jlabs.com/mcp`. Reach for
this pattern when you want to:

- Add a logging / auditing layer between an MCP client and the service
- Filter or rewrite tool calls before they reach the service
- Run an MCP server inside a corporate boundary that can't talk to the
  public service directly

## Run it

```bash
cp .env.example .env       # set MEMORY_API_KEY
npm install
npm start                  # waits on stdio for an MCP client
```

To point Claude Desktop at it, add this to your Claude Desktop config:

```jsonc
{
  "mcpServers": {
    "agent-memory": {
      "command": "node",
      "args": ["--import", "tsx", "/absolute/path/to/this/src/index.ts"],
      "env": { "MEMORY_API_KEY": "nams_..." }
    }
  }
}
```

## See also

- [How-to: MCP tools](../../../../docs/how-to/mcp.adoc)
- [Model Context Protocol spec](https://modelcontextprotocol.io)
