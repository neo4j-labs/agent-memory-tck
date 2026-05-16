"""MemoryClient root + sub-clients."""

from __future__ import annotations

import re
from typing import Any

from . import _parse as P
from .errors import ValidationError
from .transport import BridgeTransport, RestTransport, TokenProvider, Transport
from .types import (
    AccessTokenPair,
    AgentStep,
    AgentStepExplanation,
    ApiKey,
    BulkMessageInput,
    Conversation,
    ConversationContext,
    ConversationTrace,
    CypherResult,
    Entity,
    EntityFeedbackResult,
    EntityGraph,
    EntityHistory,
    EntityMergeResult,
    EntityProvenance,
    Fact,
    Message,
    Preference,
    ReasoningStep,
    ReasoningTrace,
    Reflection,
    Relationship,
    SessionInfo,
    ToolCall,
    ToolStats,
)
from .types import Observation as Observation


_REST_ENDPOINT_RE = re.compile(r"/v\d+(?:/|$)")


class _ShortTerm:
    def __init__(self, transport: Transport):
        self._t = transport

    async def add_message(
        self, session_id: str, role: str, content: str, *, metadata: dict[str, Any] | None = None
    ) -> Message:
        d = await self._t.request("add_message", {
            "session_id": session_id, "role": role, "content": content,
            "metadata": metadata,
        })
        return P.parse_message(d)  # type: ignore[return-value]

    async def get_conversation(self, session_id: str, *, limit: int | None = None) -> Conversation:
        d = await self._t.request("get_conversation", {
            "session_id": session_id, "limit": limit,
        })
        return P.parse_conversation(d)  # type: ignore[return-value]

    async def search_messages(
        self, query: str, *, session_id: str | None = None,
        limit: int = 10, threshold: float = 0.7,
    ) -> list[Message]:
        d = await self._t.request("search_messages", {
            "query": query, "session_id": session_id,
            "limit": limit, "threshold": threshold,
        })
        return [m for m in (P.parse_message(x) for x in d or []) if m]

    async def list_sessions(self, *, limit: int = 100) -> list[SessionInfo]:
        d = await self._t.request("list_sessions", {"limit": limit})
        return [P.parse_session_info(x) for x in d or []]

    async def delete_message(self, message_id: str) -> bool:
        d = await self._t.request("delete_message", {"message_id": message_id}) or {}
        return bool(d.get("deleted", False))

    async def clear_session(self, session_id: str) -> None:
        await self._t.request("clear_session", {"session_id": session_id})

    # ---- Volume 5 / hosted-native -----------------------------------------

    async def create_conversation(
        self, *, user_id: str, metadata: dict[str, Any] | None = None
    ) -> Conversation:
        d = await self._t.request("create_conversation", {
            "user_id": user_id, "metadata": metadata,
        })
        return P.parse_conversation(d)  # type: ignore[return-value]

    async def list_conversations(self, *, limit: int | None = None) -> list[Conversation]:
        d = await self._t.request("list_conversations", {"limit": limit})
        return [c for c in (P.parse_conversation(x) for x in d or []) if c]

    async def get_conversation_metadata(self, conversation_id: str) -> Conversation:
        d = await self._t.request("get_conversation_metadata", {"conversation_id": conversation_id})
        return P.parse_conversation(d)  # type: ignore[return-value]

    async def delete_conversation(self, conversation_id: str) -> None:
        await self._t.request("delete_conversation", {"conversation_id": conversation_id})

    async def get_context(self, conversation_id: str) -> ConversationContext:
        d = await self._t.request("get_context", {"conversation_id": conversation_id})
        return P.parse_context(d or {})

    async def bulk_add_messages(
        self, conversation_id: str, messages: list[BulkMessageInput | dict[str, Any]]
    ) -> list[Message]:
        if len(messages) > 100:
            raise ValidationError("bulk_add_messages accepts max 100 messages")
        payload = [
            (m if isinstance(m, dict) else
             {"role": m.role, "content": m.content, **({"metadata": m.metadata} if m.metadata else {})})
            for m in messages
        ]
        d = await self._t.request("bulk_add_messages", {
            "conversation_id": conversation_id, "messages": payload,
        })
        return [m for m in (P.parse_message(x) for x in d or []) if m]

    async def get_observations(
        self, conversation_id: str, *, limit: int | None = None
    ) -> list[Observation]:
        d = await self._t.request("get_observations", {
            "conversation_id": conversation_id, "limit": limit,
        })
        return [P.parse_observation(x) for x in d or []]

    async def get_reflections(self, conversation_id: str) -> list[Reflection]:
        d = await self._t.request("get_reflections", {"conversation_id": conversation_id})
        return [P.parse_reflection(x) for x in d or []]


class _LongTerm:
    def __init__(self, transport: Transport):
        self._t = transport

    async def add_entity(
        self, name: str, entity_type: str, *, description: str | None = None
    ) -> Entity:
        d = await self._t.request("add_entity", {
            "name": name, "entity_type": entity_type, "type": entity_type,
            "description": description,
        })
        return P.parse_entity(d)  # type: ignore[return-value]

    async def add_preference(
        self, category: str, preference: str, *, context: str | None = None
    ) -> Preference:
        d = await self._t.request("add_preference", {
            "category": category, "preference": preference, "context": context,
        })
        return P.parse_preference(d)

    async def add_fact(self, subject: str, predicate: str, obj: str) -> Fact:
        d = await self._t.request("add_fact", {
            "subject": subject, "predicate": predicate, "obj": obj,
        })
        return P.parse_fact(d)

    async def search_entities(
        self, query: str, *, limit: int = 10, type: str | None = None
    ) -> list[Entity]:
        d = await self._t.request("search_entities", {
            "query": query, "limit": limit, "type": type,
        })
        return [e for e in (P.parse_entity(x) for x in d or []) if e]

    async def search_preferences(
        self, query: str, *, category: str | None = None, limit: int = 10
    ) -> list[Preference]:
        d = await self._t.request("search_preferences", {
            "query": query, "category": category, "limit": limit,
        })
        return [P.parse_preference(x) for x in d or []]

    async def get_entity_by_name(self, name: str) -> Entity | None:
        d = await self._t.request("get_entity_by_name", {"name": name})
        return P.parse_entity(d) if d else None

    async def get_related_entities(
        self, entity_id: str, *, relationship_type: str | None = None, depth: int = 1
    ) -> list[Entity]:
        d = await self._t.request("get_related_entities", {
            "entity_id": entity_id, "relationship_type": relationship_type, "depth": depth,
        })
        return [e for e in (P.parse_entity(x) for x in d or []) if e]

    async def add_relationship(
        self, source_id: str, target_id: str, relationship_type: str,
        *, properties: dict[str, Any] | None = None,
    ) -> Relationship:
        d = await self._t.request("add_relationship", {
            "source_id": source_id, "target_id": target_id,
            "relationship_type": relationship_type, "properties": properties,
        })
        return P.parse_relationship(d)

    async def merge_duplicate_entities(
        self, source_id: str, target_id: str, *, canonical_name: str | None = None
    ) -> Entity:
        d = await self._t.request("merge_duplicate_entities", {
            "source_id": source_id, "target_id": target_id,
            "canonical_name": canonical_name,
        })
        return P.parse_entity(d)  # type: ignore[return-value]

    # ---- Volume 5 / hosted-native -----------------------------------------

    async def list_entities(
        self, *, type: str | None = None, limit: int | None = None
    ) -> list[Entity]:
        d = await self._t.request("list_entities", {"type": type, "limit": limit})
        return [e for e in (P.parse_entity(x) for x in d or []) if e]

    async def get_entity(self, entity_id: str) -> Entity:
        d = await self._t.request("get_entity", {"entity_id": entity_id})
        return P.parse_entity(d)  # type: ignore[return-value]

    async def update_entity(
        self, entity_id: str, *, name: str | None = None, description: str | None = None
    ) -> Entity:
        # The hosted PUT /v1/entities/{id} returns {"status": "updated"} on
        # success rather than the full entity. To keep the SDK contract —
        # "update returns the updated Entity" — we follow the PUT with a
        # GET. Bridge transports return the entity directly, so we tolerate
        # both shapes.
        d = await self._t.request("update_entity", {
            "entity_id": entity_id, "name": name, "description": description,
        })
        if isinstance(d, dict) and "id" in d:
            return P.parse_entity(d)  # type: ignore[return-value]
        return await self.get_entity(entity_id)

    async def delete_entity(self, entity_id: str) -> None:
        await self._t.request("delete_entity", {"entity_id": entity_id})

    async def set_entity_feedback(
        self, entity_id: str, *, user_score: float, confirmed: bool
    ) -> EntityFeedbackResult:
        d = await self._t.request("set_entity_feedback", {
            "entity_id": entity_id, "user_score": user_score, "confirmed": confirmed,
        })
        return P.parse_feedback(d or {})

    async def get_entity_history(self, entity_id: str) -> EntityHistory:
        d = await self._t.request("get_entity_history", {"entity_id": entity_id}) or {}
        return P.parse_entity_history(d)

    async def merge_entities(self, source_id: str, target_id: str) -> EntityMergeResult:
        d = await self._t.request("merge_entities", {
            "source_id": source_id, "target_id": target_id,
        }) or {}
        return P.parse_merge(d)

    async def get_entity_graph(self) -> EntityGraph:
        d = await self._t.request("get_entity_graph") or {}
        return P.parse_graph(d)


class _Reasoning:
    def __init__(self, transport: Transport):
        self._t = transport

    async def start_trace(self, session_id: str, task: str) -> ReasoningTrace:
        d = await self._t.request("start_trace", {"session_id": session_id, "task": task})
        return P.parse_trace(d)  # type: ignore[return-value]

    async def add_step(
        self, trace_id: str, *, thought: str | None = None,
        action: str | None = None, observation: str | None = None,
    ) -> ReasoningStep:
        d = await self._t.request("add_step", {
            "trace_id": trace_id, "thought": thought,
            "action": action, "observation": observation,
        })
        return P.parse_step(d or {})

    async def record_tool_call(
        self, step_id: str, tool_name: str, arguments: dict[str, Any], *,
        result: Any = None, status: str = "success", duration_ms: int | None = None,
        error: str | None = None,
    ) -> ToolCall:
        d = await self._t.request("record_tool_call", {
            "step_id": step_id, "tool_name": tool_name, "arguments": arguments,
            "result": result, "status": status, "duration_ms": duration_ms, "error": error,
        })
        return P.parse_tool_call(d or {})

    async def complete_trace(
        self, trace_id: str, *, outcome: str | None = None, success: bool | None = None
    ) -> ReasoningTrace:
        d = await self._t.request("complete_trace", {
            "trace_id": trace_id, "outcome": outcome, "success": success,
        })
        return P.parse_trace(d)  # type: ignore[return-value]

    async def get_trace_with_steps(self, trace_id: str) -> ReasoningTrace | None:
        d = await self._t.request("get_trace_with_steps", {"trace_id": trace_id})
        return P.parse_trace(d) if d else None

    async def list_traces(
        self, *, session_id: str | None = None, limit: int = 100
    ) -> list[ReasoningTrace]:
        d = await self._t.request("list_traces", {"session_id": session_id, "limit": limit})
        return [t for t in (P.parse_trace(x) for x in d or []) if t]

    async def get_tool_stats(self, tool_name: str | None = None) -> list[ToolStats]:
        d = await self._t.request("get_tool_stats", {"tool_name": tool_name})
        return [P.parse_tool_stats(x) for x in d or []]

    async def get_similar_traces(
        self, task: str, *, limit: int = 5, success_only: bool = True
    ) -> list[ReasoningTrace]:
        d = await self._t.request("get_similar_traces", {
            "task": task, "limit": limit, "success_only": success_only,
        })
        return [t for t in (P.parse_trace(x) for x in d or []) if t]

    # ---- Volume 5 / hosted-native -----------------------------------------

    async def record_step(
        self, *, conversation_id: str, reasoning: str, action_taken: str,
        result: str | None = None,
    ) -> AgentStep:
        d = await self._t.request("record_step", {
            "conversation_id": conversation_id, "reasoning": reasoning,
            "action_taken": action_taken, "result": result,
        })
        return P.parse_agent_step(d or {})

    async def list_steps(self, conversation_id: str) -> list[AgentStep]:
        d = await self._t.request("list_steps", {"conversation_id": conversation_id})
        return [P.parse_agent_step(x) for x in d or []]

    async def explain_step(self, step_id: str) -> AgentStepExplanation:
        d = await self._t.request("explain_step", {"step_id": step_id}) or {}
        return P.parse_agent_step_explanation(d)

    async def get_trace_by_conversation(self, conversation_id: str) -> ConversationTrace:
        d = await self._t.request("get_trace_by_conversation", {
            "conversation_id": conversation_id,
        }) or {}
        return P.parse_conversation_trace(d)

    async def get_entity_provenance(self, entity_id: str) -> EntityProvenance:
        d = await self._t.request("get_entity_provenance", {"entity_id": entity_id}) or {}
        return P.parse_provenance(d)


class _Query:
    def __init__(self, transport: Transport):
        self._t = transport

    async def cypher(self, cypher: str, params: dict[str, Any] | None = None) -> CypherResult:
        d = await self._t.request("cypher_query", {
            "cypher": cypher, "params": params or {},
        }) or {}
        return P.parse_cypher(d)


class _Auth:
    def __init__(self, transport: Transport):
        self._t = transport

    async def list_api_keys(self, workspace_id: str) -> list[ApiKey]:
        d = await self._t.request("list_api_keys", {"workspace_id": workspace_id})
        return [P.parse_api_key(x) for x in d or []]

    async def create_api_key(self, *, label: str, scopes: list[str], workspace_id: str) -> ApiKey:
        d = await self._t.request("create_api_key", {
            "label": label, "scopes": scopes, "workspace_id": workspace_id,
        })
        return P.parse_api_key(d or {})

    async def revoke_api_key(self, key_id: str) -> None:
        await self._t.request("revoke_api_key", {"key_id": key_id})

    async def reveal_api_key(self, key_id: str, *, workspace_id: str) -> ApiKey:
        d = await self._t.request("reveal_api_key", {
            "key_id": key_id, "workspace_id": workspace_id,
        })
        return P.parse_api_key(d or {})

    async def refresh_access_token(self, refresh_token: str) -> AccessTokenPair:
        d = await self._t.request("refresh_access_token", {"refresh_token": refresh_token})
        return P.parse_token_pair(d or {})


class MemoryClient:
    """Async client for the Neo4j Agent Memory Service."""

    def __init__(
        self,
        endpoint: str | None = None,
        *,
        api_key: str | None = None,
        transport: Transport | None = None,
        transport_mode: str = "auto",
        token_provider: TokenProvider | None = None,
        timeout: float = 30.0,
        headers: dict[str, str] | None = None,
    ):
        if transport is not None:
            self._transport: Transport = transport
        elif endpoint is not None:
            mode = transport_mode
            if mode == "auto":
                mode = "rest" if _REST_ENDPOINT_RE.search(endpoint) else "bridge"
            if mode == "rest":
                self._transport = RestTransport(
                    endpoint, api_key=api_key, timeout=timeout,
                    token_provider=token_provider, headers=headers,
                )
            else:
                self._transport = BridgeTransport(
                    endpoint, api_key=api_key, timeout=timeout, headers=headers,
                )
        else:
            raise ValidationError("Either endpoint or transport must be provided")

        self.short_term = _ShortTerm(self._transport)
        self.long_term = _LongTerm(self._transport)
        self.reasoning = _Reasoning(self._transport)
        self.query = _Query(self._transport)
        self.auth = _Auth(self._transport)

    async def connect(self) -> None:
        await self._transport.connect()

    async def close(self) -> None:
        await self._transport.close()

    async def clear_all_data(self) -> None:
        try:
            await self._transport.request("clear_all_data")
        except Exception:
            pass

    async def __aenter__(self) -> "MemoryClient":
        await self.connect()
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self.close()
