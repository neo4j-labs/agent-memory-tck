# neo4j-agent-memory-client

Python client for the Neo4j Agent Memory Service. Speaks both the TCK bridge
protocol (POST `/{snake_case_method}`) and the hosted REST API at
`https://memory.neo4jlabs.com/v1` from a single API.

```bash
pip install neo4j-agent-memory-client
# or
uv add neo4j-agent-memory-client
```

## Usage

```python
import asyncio
from neo4j_agent_memory_client import MemoryClient

async def main():
    async with MemoryClient(
        endpoint="https://memory.neo4jlabs.com/v1",
        api_key="nams_...",
    ) as client:
        conv = await client.short_term.create_conversation(user_id="alice")
        await client.short_term.add_message(conv.id, "user", "Hello!")
        ctx = await client.short_term.get_context(conv.id)

asyncio.run(main())
```

Distinct from the `neo4j-agent-memory` PyPI package (which is the reference
*adapter* used by the TCK). Use this client for production apps; use the
adapter inside the TCK reference adapter.
