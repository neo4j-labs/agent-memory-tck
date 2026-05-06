"""HTTP Bridge Adapter for cross-language TCK conformance testing.

This adapter implements BaseAdapter by proxying every method call as an
HTTP POST to a conformance server. TypeScript and Go implementations
provide a thin HTTP server that maps these requests to their native client.

The bridge protocol is simple JSON-over-HTTP:
  - POST /{method_name}
  - Request body: JSON object with method parameters
  - Response body: JSON object with the return value
  - Error: HTTP 4xx/5xx with JSON {"error": "message"}

Usage:
    pytest -m bronze --bridge-url http://localhost:3001

See tck/bridge/protocol.md for the full protocol specification.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import UUID

import httpx

from tck.adapters.base_adapter import (
    BaseAdapter,
    TCKAgentStep,
    TCKAgentStepExplanation,
    TCKBulkMessageInput,
    TCKConversation,
    TCKConversationContext,
    TCKConversationTrace,
    TCKCypherResult,
    TCKEntity,
    TCKEntityFeedbackResult,
    TCKEntityGraph,
    TCKEntityGraphEdge,
    TCKEntityGraphNode,
    TCKEntityHistory,
    TCKEntityMention,
    TCKEntityMergeResult,
    TCKEntityProvenance,
    TCKFact,
    TCKMessage,
    TCKObservation,
    TCKPreference,
    TCKReasoningStep,
    TCKReasoningTrace,
    TCKReflection,
    TCKRelationship,
    TCKSessionInfo,
    TCKToolCall,
    TCKToolStats,
    ToolCallStatus,
)


def _parse_datetime(val: str | None) -> datetime:
    """Parse an ISO 8601 datetime string."""
    if val is None:
        return datetime.now(timezone.utc)
    return datetime.fromisoformat(val)


def _parse_uuid(val: str) -> UUID:
    """Parse a UUID string."""
    return UUID(val)


def _serialize(obj: Any) -> Any:
    """Serialize Python objects to JSON-safe types."""
    if isinstance(obj, UUID):
        return str(obj)
    if isinstance(obj, datetime):
        return obj.isoformat()
    if isinstance(obj, ToolCallStatus):
        return obj.value
    return obj


def _message_from_dict(d: dict) -> TCKMessage:
    """Construct a TCKMessage from a response dict."""
    return TCKMessage(
        id=_parse_uuid(d["id"]),
        role=d["role"],
        content=d["content"],
        timestamp=_parse_datetime(d["timestamp"]),
        embedding=d.get("embedding"),
        metadata=d.get("metadata", {}),
    )


def _conversation_from_dict(d: dict) -> TCKConversation:
    """Construct a TCKConversation from a response dict."""
    return TCKConversation(
        id=_parse_uuid(d["id"]),
        session_id=d["session_id"],
        messages=[_message_from_dict(m) for m in d.get("messages", [])],
        title=d.get("title"),
        created_at=_parse_datetime(d["created_at"]),
        updated_at=d.get("updated_at"),
    )


def _entity_from_dict(d: dict) -> TCKEntity:
    """Construct a TCKEntity from a response dict."""
    return TCKEntity(
        id=_parse_uuid(d["id"]),
        name=d["name"],
        type=d["type"],
        subtype=d.get("subtype"),
        description=d.get("description"),
        embedding=d.get("embedding"),
        canonical_name=d.get("canonical_name"),
        created_at=_parse_datetime(d["created_at"]),
    )


def _preference_from_dict(d: dict) -> TCKPreference:
    """Construct a TCKPreference from a response dict."""
    return TCKPreference(
        id=_parse_uuid(d["id"]),
        category=d["category"],
        preference=d["preference"],
        context=d.get("context"),
        embedding=d.get("embedding"),
    )


def _fact_from_dict(d: dict) -> TCKFact:
    """Construct a TCKFact from a response dict."""
    return TCKFact(
        id=_parse_uuid(d["id"]),
        subject=d["subject"],
        predicate=d["predicate"],
        object=d["object"],
        embedding=d.get("embedding"),
    )


def _tool_call_from_dict(d: dict) -> TCKToolCall:
    """Construct a TCKToolCall from a response dict."""
    return TCKToolCall(
        id=_parse_uuid(d["id"]),
        tool_name=d["tool_name"],
        arguments=d.get("arguments", {}),
        result=d.get("result"),
        status=ToolCallStatus(d.get("status", "success")),
        duration_ms=d.get("duration_ms"),
        error=d.get("error"),
    )


def _step_from_dict(d: dict) -> TCKReasoningStep:
    """Construct a TCKReasoningStep from a response dict."""
    return TCKReasoningStep(
        id=_parse_uuid(d["id"]),
        trace_id=_parse_uuid(d["trace_id"]),
        step_number=d["step_number"],
        thought=d.get("thought"),
        action=d.get("action"),
        observation=d.get("observation"),
        tool_calls=[_tool_call_from_dict(tc) for tc in d.get("tool_calls", [])],
    )


def _trace_from_dict(d: dict) -> TCKReasoningTrace:
    """Construct a TCKReasoningTrace from a response dict."""
    return TCKReasoningTrace(
        id=_parse_uuid(d["id"]),
        session_id=d["session_id"],
        task=d["task"],
        steps=[_step_from_dict(s) for s in d.get("steps", [])],
        outcome=d.get("outcome"),
        success=d.get("success"),
        started_at=_parse_datetime(d["started_at"]),
        completed_at=d.get("completed_at"),
    )


def _relationship_from_dict(d: dict) -> TCKRelationship:
    """Construct a TCKRelationship from a response dict."""
    return TCKRelationship(
        id=_parse_uuid(d["id"]),
        source_id=_parse_uuid(d["source_id"]),
        target_id=_parse_uuid(d["target_id"]),
        relationship_type=d["relationship_type"],
        properties=d.get("properties", {}),
    )


def _session_info_from_dict(d: dict) -> TCKSessionInfo:
    """Construct a TCKSessionInfo from a response dict."""
    return TCKSessionInfo(
        session_id=d["session_id"],
        message_count=d.get("message_count", 0),
        created_at=_parse_datetime(d["created_at"]),
        updated_at=d.get("updated_at"),
    )


def _tool_stats_from_dict(d: dict) -> TCKToolStats:
    """Construct a TCKToolStats from a response dict."""
    return TCKToolStats(
        name=d["name"],
        total_calls=d.get("total_calls", 0),
        successful_calls=d.get("successful_calls", 0),
        failed_calls=d.get("failed_calls", 0),
        success_rate=d.get("success_rate", 0.0),
        avg_duration_ms=d.get("avg_duration_ms"),
    )


def _observation_from_dict(d: dict) -> TCKObservation:
    return TCKObservation(
        id=_parse_uuid(d["id"]),
        conversation_id=_parse_uuid(d["conversation_id"]),
        content=d.get("content", ""),
        window_start=_parse_datetime(d["window_start"]) if d.get("window_start") else None,
        window_end=_parse_datetime(d["window_end"]) if d.get("window_end") else None,
        created_at=_parse_datetime(d.get("created_at")),
    )


def _reflection_from_dict(d: dict) -> TCKReflection:
    return TCKReflection(
        id=_parse_uuid(d["id"]),
        conversation_id=_parse_uuid(d["conversation_id"]),
        content=d.get("content", ""),
        created_at=_parse_datetime(d.get("created_at")),
    )


def _agent_step_from_dict(d: dict) -> TCKAgentStep:
    return TCKAgentStep(
        id=_parse_uuid(d["id"]),
        conversation_id=_parse_uuid(d["conversation_id"]),
        reasoning=d.get("reasoning", ""),
        action_taken=d.get("action_taken", ""),
        result=d.get("result"),
        created_at=_parse_datetime(d.get("created_at")),
    )


class HTTPBridgeAdapter(BaseAdapter):
    """Adapter that proxies all operations to an HTTP conformance server.

    This enables the Python TCK test suite to validate TypeScript, Go,
    or any other language implementation via a standard HTTP protocol.
    """

    def __init__(self, base_url: str, timeout: float = 30.0):
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        self.protocol_version: str | None = None

    async def _call(self, method: str, params: dict[str, Any] | None = None) -> Any:
        """Make an HTTP POST call to the conformance server."""
        body = {}
        if params:
            body = {k: _serialize(v) for k, v in params.items() if v is not None}

        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            headers={"Content-Type": "application/json"},
        ) as client:
            response = await client.post(f"/{method}", json=body)

            if response.status_code >= 400:
                try:
                    error_body = response.json()
                except Exception:
                    error_body = {"error": response.text}
                error_msg = error_body.get("error", f"HTTP {response.status_code}")
                raise RuntimeError(f"Bridge call {method} failed: {error_msg}")

            if response.status_code == 204 or not response.content:
                return None

            return response.json()

    # --- Lifecycle ---

    async def setup(self) -> None:
        result = await self._call("setup")
        if result and not result.get("ok", True):
            raise RuntimeError("Bridge setup failed")
        if result:
            self.protocol_version = result.get("protocol_version")

    async def teardown(self) -> None:
        await self._call("teardown")

    async def clear_all_data(self) -> None:
        await self._call("clear_all_data")

    # --- Short-Term Memory (Bronze) ---

    async def add_message(
        self,
        session_id: str,
        role: str,
        content: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> TCKMessage:
        result = await self._call(
            "add_message",
            {
                "session_id": session_id,
                "role": role,
                "content": content,
                "metadata": metadata,
            },
        )
        return _message_from_dict(result)

    async def get_conversation(
        self,
        session_id: str,
        *,
        limit: int | None = None,
    ) -> TCKConversation:
        result = await self._call(
            "get_conversation",
            {
                "session_id": session_id,
                "limit": limit,
            },
        )
        return _conversation_from_dict(result)

    async def search_messages(
        self,
        query: str,
        *,
        session_id: str | None = None,
        limit: int = 10,
        threshold: float = 0.7,
    ) -> list[TCKMessage]:
        result = await self._call(
            "search_messages",
            {
                "query": query,
                "session_id": session_id,
                "limit": limit,
                "threshold": threshold,
            },
        )
        return [_message_from_dict(m) for m in result]

    async def list_sessions(
        self,
        *,
        limit: int = 100,
    ) -> list[TCKSessionInfo]:
        result = await self._call("list_sessions", {"limit": limit})
        return [_session_info_from_dict(s) for s in result]

    async def delete_message(self, message_id: UUID) -> bool:
        result = await self._call("delete_message", {"message_id": message_id})
        return result.get("deleted", False)

    async def clear_session(self, session_id: str) -> None:
        await self._call("clear_session", {"session_id": session_id})

    # --- Long-Term Memory (Silver) ---

    async def add_entity(
        self,
        name: str,
        entity_type: str,
        *,
        description: str | None = None,
    ) -> TCKEntity:
        result = await self._call(
            "add_entity",
            {
                "name": name,
                "entity_type": entity_type,
                "description": description,
            },
        )
        return _entity_from_dict(result)

    async def add_preference(
        self,
        category: str,
        preference: str,
        *,
        context: str | None = None,
    ) -> TCKPreference:
        result = await self._call(
            "add_preference",
            {
                "category": category,
                "preference": preference,
                "context": context,
            },
        )
        return _preference_from_dict(result)

    async def add_fact(
        self,
        subject: str,
        predicate: str,
        obj: str,
    ) -> TCKFact:
        result = await self._call(
            "add_fact",
            {
                "subject": subject,
                "predicate": predicate,
                "obj": obj,
            },
        )
        return _fact_from_dict(result)

    async def search_entities(
        self,
        query: str,
        *,
        limit: int = 10,
    ) -> list[TCKEntity]:
        result = await self._call(
            "search_entities",
            {
                "query": query,
                "limit": limit,
            },
        )
        return [_entity_from_dict(e) for e in result]

    async def search_preferences(
        self,
        query: str,
        *,
        category: str | None = None,
        limit: int = 10,
    ) -> list[TCKPreference]:
        result = await self._call(
            "search_preferences",
            {
                "query": query,
                "category": category,
                "limit": limit,
            },
        )
        return [_preference_from_dict(p) for p in result]

    async def get_entity_by_name(self, name: str) -> TCKEntity | None:
        result = await self._call("get_entity_by_name", {"name": name})
        if result is None:
            return None
        return _entity_from_dict(result)

    async def get_related_entities(
        self,
        entity_id: UUID,
        *,
        relationship_type: str | None = None,
        depth: int = 1,
    ) -> list[TCKEntity]:
        result = await self._call(
            "get_related_entities",
            {
                "entity_id": entity_id,
                "relationship_type": relationship_type,
                "depth": depth,
            },
        )
        return [_entity_from_dict(e) for e in result]

    # --- Reasoning Memory (Silver) ---

    async def start_trace(
        self,
        session_id: str,
        task: str,
    ) -> TCKReasoningTrace:
        result = await self._call(
            "start_trace",
            {
                "session_id": session_id,
                "task": task,
            },
        )
        return _trace_from_dict(result)

    async def add_step(
        self,
        trace_id: UUID,
        *,
        thought: str | None = None,
        action: str | None = None,
        observation: str | None = None,
    ) -> TCKReasoningStep:
        result = await self._call(
            "add_step",
            {
                "trace_id": trace_id,
                "thought": thought,
                "action": action,
                "observation": observation,
            },
        )
        return _step_from_dict(result)

    async def record_tool_call(
        self,
        step_id: UUID,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        result: Any = None,
        status: ToolCallStatus = ToolCallStatus.SUCCESS,
        duration_ms: int | None = None,
        error: str | None = None,
    ) -> TCKToolCall:
        resp = await self._call(
            "record_tool_call",
            {
                "step_id": step_id,
                "tool_name": tool_name,
                "arguments": arguments,
                "result": result,
                "status": status,
                "duration_ms": duration_ms,
                "error": error,
            },
        )
        return _tool_call_from_dict(resp)

    async def complete_trace(
        self,
        trace_id: UUID,
        *,
        outcome: str | None = None,
        success: bool | None = None,
    ) -> TCKReasoningTrace:
        result = await self._call(
            "complete_trace",
            {
                "trace_id": trace_id,
                "outcome": outcome,
                "success": success,
            },
        )
        return _trace_from_dict(result)

    async def get_trace_with_steps(self, trace_id: UUID) -> TCKReasoningTrace | None:
        result = await self._call("get_trace_with_steps", {"trace_id": trace_id})
        if result is None:
            return None
        return _trace_from_dict(result)

    async def list_traces(
        self,
        *,
        session_id: str | None = None,
        limit: int = 100,
    ) -> list[TCKReasoningTrace]:
        result = await self._call(
            "list_traces",
            {
                "session_id": session_id,
                "limit": limit,
            },
        )
        return [_trace_from_dict(t) for t in result]

    async def get_tool_stats(
        self,
        tool_name: str | None = None,
    ) -> list[TCKToolStats]:
        result = await self._call("get_tool_stats", {"tool_name": tool_name})
        return [_tool_stats_from_dict(s) for s in result]

    # --- Gold Tier ---

    async def add_relationship(
        self,
        source_id: UUID,
        target_id: UUID,
        relationship_type: str,
        *,
        properties: dict[str, Any] | None = None,
    ) -> TCKRelationship:
        result = await self._call(
            "add_relationship",
            {
                "source_id": source_id,
                "target_id": target_id,
                "relationship_type": relationship_type,
                "properties": properties,
            },
        )
        return _relationship_from_dict(result)

    async def merge_duplicate_entities(
        self,
        source_id: UUID,
        target_id: UUID,
        *,
        canonical_name: str | None = None,
    ) -> TCKEntity:
        result = await self._call(
            "merge_duplicate_entities",
            {
                "source_id": source_id,
                "target_id": target_id,
                "canonical_name": canonical_name,
            },
        )
        return _entity_from_dict(result)

    async def get_similar_traces(
        self,
        task: str,
        *,
        limit: int = 5,
        success_only: bool = True,
    ) -> list[TCKReasoningTrace]:
        result = await self._call(
            "get_similar_traces",
            {
                "task": task,
                "limit": limit,
                "success_only": success_only,
            },
        )
        return [_trace_from_dict(t) for t in result]

    # --- Platinum Tier (Volume 5 — hosted-service operations) ---

    async def create_conversation(
        self,
        user_id: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> TCKConversation:
        result = await self._call(
            "create_conversation",
            {"user_id": user_id, "metadata": metadata},
        )
        return _conversation_from_dict(result)

    async def list_conversations(
        self,
        *,
        limit: int | None = None,
    ) -> list[TCKConversation]:
        result = await self._call("list_conversations", {"limit": limit})
        return [_conversation_from_dict(c) for c in result or []]

    async def get_conversation_metadata(self, conversation_id: UUID) -> TCKConversation:
        result = await self._call(
            "get_conversation_metadata", {"conversation_id": conversation_id}
        )
        return _conversation_from_dict(result)

    async def delete_conversation(self, conversation_id: UUID) -> None:
        await self._call("delete_conversation", {"conversation_id": conversation_id})

    async def get_context(self, conversation_id: UUID) -> TCKConversationContext:
        result = await self._call("get_context", {"conversation_id": conversation_id})
        result = result or {}
        return TCKConversationContext(
            reflections=[_reflection_from_dict(r) for r in result.get("reflections", [])],
            observations=[_observation_from_dict(o) for o in result.get("observations", [])],
            recent_messages=[_message_from_dict(m) for m in result.get("recent_messages", [])],
        )

    async def bulk_add_messages(
        self,
        conversation_id: UUID,
        messages: list[TCKBulkMessageInput],
    ) -> list[TCKMessage]:
        payload = [
            {"role": m.role, "content": m.content,
             **({"metadata": m.metadata} if m.metadata else {})}
            for m in messages
        ]
        result = await self._call(
            "bulk_add_messages",
            {"conversation_id": conversation_id, "messages": payload},
        )
        return [_message_from_dict(x) for x in result or []]

    async def get_observations(
        self,
        conversation_id: UUID,
        *,
        limit: int | None = None,
    ) -> list[TCKObservation]:
        result = await self._call(
            "get_observations",
            {"conversation_id": conversation_id, "limit": limit},
        )
        return [_observation_from_dict(o) for o in result or []]

    async def get_reflections(self, conversation_id: UUID) -> list[TCKReflection]:
        result = await self._call(
            "get_reflections", {"conversation_id": conversation_id}
        )
        return [_reflection_from_dict(r) for r in result or []]

    async def list_entities(
        self,
        *,
        type: str | None = None,
        limit: int | None = None,
    ) -> list[TCKEntity]:
        result = await self._call("list_entities", {"type": type, "limit": limit})
        return [_entity_from_dict(e) for e in result or []]

    async def get_entity(self, entity_id: UUID) -> TCKEntity:
        result = await self._call("get_entity", {"entity_id": entity_id})
        return _entity_from_dict(result)

    async def update_entity(
        self,
        entity_id: UUID,
        *,
        name: str | None = None,
        description: str | None = None,
    ) -> TCKEntity:
        result = await self._call(
            "update_entity",
            {"entity_id": entity_id, "name": name, "description": description},
        )
        return _entity_from_dict(result)

    async def delete_entity(self, entity_id: UUID) -> None:
        await self._call("delete_entity", {"entity_id": entity_id})

    async def set_entity_feedback(
        self,
        entity_id: UUID,
        *,
        user_score: float,
        confirmed: bool,
    ) -> TCKEntityFeedbackResult:
        result = await self._call(
            "set_entity_feedback",
            {
                "entity_id": entity_id,
                "user_score": user_score,
                "confirmed": confirmed,
            },
        )
        return TCKEntityFeedbackResult(
            id=_parse_uuid(result["id"]),
            updated=bool(result.get("updated", False)),
        )

    async def get_entity_history(self, entity_id: UUID) -> TCKEntityHistory:
        result = await self._call(
            "get_entity_history", {"entity_id": entity_id}
        )
        result = result or {}
        return TCKEntityHistory(
            entity_id=_parse_uuid(result.get("entity_id", str(entity_id))),
            mentions=[
                TCKEntityMention(
                    conversation_id=_parse_uuid(m["conversation_id"]),
                    message_id=_parse_uuid(m["message_id"]) if m.get("message_id") else None,
                    content=m.get("content", ""),
                    timestamp=_parse_datetime(m.get("timestamp")),
                )
                for m in result.get("mentions", [])
            ],
        )

    async def merge_entities(
        self,
        source_id: UUID,
        target_id: UUID,
    ) -> TCKEntityMergeResult:
        result = await self._call(
            "merge_entities",
            {"source_id": source_id, "target_id": target_id},
        )
        return TCKEntityMergeResult(
            source_id=_parse_uuid(result.get("source_id", str(source_id))),
            target_id=_parse_uuid(result.get("target_id", str(target_id))),
            status=result.get("status", ""),
        )

    async def get_entity_graph(self) -> TCKEntityGraph:
        result = await self._call("get_entity_graph")
        result = result or {}
        return TCKEntityGraph(
            nodes=[
                TCKEntityGraphNode(
                    id=_parse_uuid(n["id"]), name=n.get("name", ""), type=n.get("type", "")
                )
                for n in result.get("nodes", [])
            ],
            edges=[
                TCKEntityGraphEdge(
                    id=_parse_uuid(e["id"]),
                    source=_parse_uuid(e["source"]),
                    target=_parse_uuid(e["target"]),
                    type=e.get("type", ""),
                )
                for e in result.get("edges", [])
            ],
        )

    async def explain_step(self, step_id: UUID) -> TCKAgentStepExplanation:
        result = await self._call("explain_step", {"step_id": step_id})
        result = result or {}
        return TCKAgentStepExplanation(
            id=_parse_uuid(result["id"]),
            conversation_id=_parse_uuid(result["conversation_id"]),
            reasoning=result.get("reasoning", ""),
            action_taken=result.get("action_taken", ""),
            result=result.get("result"),
            created_at=_parse_datetime(result.get("created_at")),
            tool_calls=[_tool_call_from_dict(tc) for tc in result.get("tool_calls", [])],
            influenced_entities=[
                _entity_from_dict(e) for e in result.get("influenced_entities", [])
            ],
        )

    async def get_trace_by_conversation(
        self, conversation_id: UUID
    ) -> TCKConversationTrace:
        result = await self._call(
            "get_trace_by_conversation",
            {"conversation_id": conversation_id},
        )
        result = result or {}
        return TCKConversationTrace(
            conversation_id=_parse_uuid(result.get("conversation_id", str(conversation_id))),
            steps=[_agent_step_from_dict(s) for s in result.get("steps", [])],
            tool_calls=[_tool_call_from_dict(tc) for tc in result.get("tool_calls", [])],
        )

    async def get_entity_provenance(self, entity_id: UUID) -> TCKEntityProvenance:
        result = await self._call(
            "get_entity_provenance",
            {"entity_id": entity_id},
        )
        result = result or {}
        return TCKEntityProvenance(
            entity_id=_parse_uuid(result.get("entity_id", str(entity_id))),
            steps=[_agent_step_from_dict(s) for s in result.get("steps", [])],
        )

    async def record_step(
        self,
        *,
        conversation_id: UUID,
        reasoning: str,
        action_taken: str,
        result: str | None = None,
    ) -> TCKAgentStep:
        resp = await self._call(
            "record_step",
            {
                "conversation_id": conversation_id,
                "reasoning": reasoning,
                "action_taken": action_taken,
                "result": result,
            },
        )
        return _agent_step_from_dict(resp)

    async def list_steps(self, conversation_id: UUID) -> list[TCKAgentStep]:
        result = await self._call(
            "list_steps", {"conversation_id": conversation_id}
        )
        return [_agent_step_from_dict(s) for s in result or []]

    async def cypher_query(
        self,
        cypher: str,
        params: dict[str, Any] | None = None,
    ) -> TCKCypherResult:
        result = await self._call("cypher_query", {"cypher": cypher, "params": params or {}})
        result = result or {}
        return TCKCypherResult(
            columns=list(result.get("columns", [])),
            rows=list(result.get("rows", [])),
            stats=result.get("stats"),
        )
