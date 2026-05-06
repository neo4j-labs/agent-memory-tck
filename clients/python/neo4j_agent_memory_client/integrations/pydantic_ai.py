"""PydanticAI integration — system-prompt context injection.

Provides `inject_memory_context()`, an async function you wire into your
PydanticAI Agent's `system_prompt` to seed every model call with the
three-tier conversation context (reflections + observations + recent messages)
returned by the hosted memory service.
"""

from __future__ import annotations

from ..client import MemoryClient


async def inject_memory_context(
    client: MemoryClient,
    conversation_id: str,
    *,
    header: str = "Background context from memory:",
) -> str:
    """Build a system-prompt string from memory context."""
    try:
        ctx = await client.short_term.get_context(conversation_id)
    except Exception:
        return ""

    lines: list[str] = []
    if ctx.reflections:
        lines.append("Reflections:")
        for r in ctx.reflections:
            lines.append(f"- {r.content}")
    if ctx.observations:
        lines.append("Observations:")
        for o in ctx.observations:
            lines.append(f"- {o.content}")
    if ctx.recent_messages:
        lines.append("Recent messages:")
        for m in ctx.recent_messages:
            lines.append(f"- [{m.role}] {m.content}")

    if not lines:
        return ""
    return header + "\n" + "\n".join(lines)


class MemoryToolset:
    """Lightweight tool wrapper exposing the 12 memory tools to a PydanticAI agent.

    Use as ``Agent(deps_type=..., tools=[*MemoryToolset(client).tools()])`` —
    each tool is a regular async function that closes over `client`.
    """

    def __init__(self, client: MemoryClient):
        self._client = client

    def tools(self) -> list[object]:
        c = self._client

        async def memory_create_conversation(user_id: str) -> str:
            conv = await c.short_term.create_conversation(user_id=user_id)
            return conv.id

        async def memory_add_message(conversation_id: str, role: str, content: str) -> str:
            m = await c.short_term.add_message(conversation_id, role, content)
            return m.id

        async def memory_get_context(conversation_id: str) -> str:
            ctx = await c.short_term.get_context(conversation_id)
            return "\n".join(
                ["Reflections: " + " ".join(r.content for r in ctx.reflections),
                 "Observations: " + " ".join(o.content for o in ctx.observations),
                 "Recent: " + " ".join(f"[{m.role}] {m.content}" for m in ctx.recent_messages)]
            )

        async def memory_search_entities(query: str, limit: int = 10) -> list[str]:
            ents = await c.long_term.search_entities(query, limit=limit)
            return [f"{e.name} ({e.type})" for e in ents]

        async def memory_get_entity_history(entity_id: str) -> str:
            h = await c.long_term.get_entity_history(entity_id)
            return f"{len(h.mentions)} mentions"

        async def memory_record_step(conversation_id: str, reasoning: str, action_taken: str) -> str:
            s = await c.reasoning.record_step(
                conversation_id=conversation_id,
                reasoning=reasoning,
                action_taken=action_taken,
            )
            return s.id

        return [
            memory_create_conversation,
            memory_add_message,
            memory_get_context,
            memory_search_entities,
            memory_get_entity_history,
            memory_record_step,
        ]
