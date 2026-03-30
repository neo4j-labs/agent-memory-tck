"""Tools for Lenny's podcast research agent."""

from __future__ import annotations

from typing import TYPE_CHECKING

import httpx
from pydantic_ai import RunContext

if TYPE_CHECKING:
    from lenny.agent import LennyDeps


async def _memory_call(endpoint: str, method: str, params: dict) -> dict:
    """Make an HTTP call to the memory service."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(f"{endpoint}/{method}", json=params, timeout=30)
        resp.raise_for_status()
        if resp.status_code == 204:
            return {}
        return resp.json()


async def extract_entities_tool(
    ctx: RunContext["LennyDeps"],
    names: list[str],
    entity_types: list[str],
    descriptions: list[str],
) -> str:
    """Extract and store entities from podcast content.

    Args:
        names: List of entity names to store.
        entity_types: Corresponding entity types (PERSON, ORGANIZATION, etc.).
        descriptions: Corresponding descriptions.
    """
    endpoint = ctx.deps.memory_endpoint
    stored = []

    for name, etype, desc in zip(names, entity_types, descriptions):
        await _memory_call(
            endpoint,
            "add_entity",
            {
                "name": name,
                "entity_type": etype,
                "description": desc,
            },
        )
        stored.append(f"{name} ({etype})")

        # Also record a message about the extraction
        await _memory_call(
            endpoint,
            "add_message",
            {
                "session_id": ctx.deps.session_id,
                "role": "assistant",
                "content": f"Extracted entity: {name} ({etype}) - {desc}",
            },
        )

    return f"Stored {len(stored)} entities: {', '.join(stored)}"


async def search_knowledge_tool(
    ctx: RunContext["LennyDeps"],
    query: str,
) -> str:
    """Search the shared knowledge graph for existing entities.

    Args:
        query: Search query for entities.
    """
    endpoint = ctx.deps.memory_endpoint
    results = await _memory_call(
        endpoint,
        "search_entities",
        {
            "query": query,
            "limit": 5,
        },
    )

    if not results:
        return "No entities found."

    lines = []
    for entity in results:
        name = entity.get("name", "Unknown")
        etype = entity.get("type", "Unknown")
        desc = entity.get("description", "")
        lines.append(f"- {name} ({etype}): {desc}")

    return f"Found {len(results)} entities:\n" + "\n".join(lines)
