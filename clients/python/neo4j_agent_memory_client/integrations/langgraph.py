"""LangGraph integration — `MemoryCheckpointSaver` backed by `MemoryClient`.

Stores LangGraph checkpoints as messages on a memory conversation, so the
state of every step is replayable through the same memory graph that powers
all other agent operations.

Duck-types LangGraph's `BaseCheckpointSaver` interface to avoid a hard
dependency on the LangGraph package at install time.
"""

from __future__ import annotations

import json
import re
from typing import Any

from ..client import MemoryClient


class MemoryCheckpointSaver:
    """LangGraph-compatible checkpoint store backed by neo4j-agent-memory.

    Each thread is a memory conversation; each checkpoint is a `system` message
    whose JSON content is the serialized checkpoint dict.
    """

    def __init__(self, client: MemoryClient, *, prefix: str = "checkpoint"):
        self._client = client
        self._prefix = prefix
        # In-memory map: thread_id -> conversation_id (REST mode only).
        self._conversations: dict[str, str] = {}

    async def _conversation_id(self, thread_id: str) -> str:
        if thread_id in self._conversations:
            return self._conversations[thread_id]
        try:
            conv = await self._client.short_term.create_conversation(user_id=thread_id)
            self._conversations[thread_id] = conv.id
            return conv.id
        except Exception:
            self._conversations[thread_id] = thread_id
            return thread_id

    async def aput(self, config: dict[str, Any], checkpoint: dict[str, Any], metadata: dict[str, Any] | None = None) -> dict[str, Any]:
        thread_id = config.get("configurable", {}).get("thread_id") or "default"
        conv_id = await self._conversation_id(thread_id)
        await self._client.short_term.add_message(
            conv_id,
            "system",
            f"{self._prefix}:{json.dumps(checkpoint)}",
            metadata={"checkpoint": True, **(metadata or {})},
        )
        return config

    async def aget(self, config: dict[str, Any]) -> dict[str, Any] | None:
        thread_id = config.get("configurable", {}).get("thread_id") or "default"
        conv_id = await self._conversation_id(thread_id)
        try:
            conv = await self._client.short_term.get_conversation(conv_id)
        except Exception:
            return None
        prefix = f"{self._prefix}:"
        for m in reversed(conv.messages):
            if m.role == "system" and m.content.startswith(prefix):
                payload = m.content[len(prefix):]
                try:
                    return json.loads(payload)
                except json.JSONDecodeError:
                    continue
        return None

    async def alist(self, config: dict[str, Any]) -> list[dict[str, Any]]:
        thread_id = config.get("configurable", {}).get("thread_id") or "default"
        conv_id = await self._conversation_id(thread_id)
        try:
            conv = await self._client.short_term.get_conversation(conv_id)
        except Exception:
            return []
        prefix = f"{self._prefix}:"
        out: list[dict[str, Any]] = []
        for m in conv.messages:
            if m.role == "system" and m.content.startswith(prefix):
                try:
                    out.append(json.loads(m.content[len(prefix):]))
                except json.JSONDecodeError:
                    continue
        return out


_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)


def is_uuid(s: str) -> bool:
    return bool(_UUID_RE.match(s))
