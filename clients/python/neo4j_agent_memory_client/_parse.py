"""Parse helpers — wire dict → typed dataclass."""

from __future__ import annotations

from typing import Any

from .types import (
    AccessTokenPair,
    AgentStep,
    AgentStepExplanation,
    ApiKey,
    Conversation,
    ConversationContext,
    ConversationTrace,
    CypherResult,
    Entity,
    EntityFeedbackResult,
    EntityGraph,
    EntityGraphEdge,
    EntityGraphNode,
    EntityHistory,
    EntityMention,
    EntityMergeResult,
    EntityProvenance,
    EntityRelationshipRef,
    Fact,
    Message,
    Observation,
    Preference,
    ReasoningStep,
    ReasoningTrace,
    Reflection,
    Relationship,
    SessionInfo,
    ToolCall,
    ToolStats,
)


def _g(d: dict[str, Any], *keys: str, default: Any = None) -> Any:
    for k in keys:
        if k in d:
            return d[k]
    return default


def parse_message(d: dict[str, Any] | None) -> Message | None:
    if d is None:
        return None
    return Message(
        id=d["id"],
        role=d.get("role", "user"),
        content=d.get("content", ""),
        timestamp=_g(d, "timestamp", "created_at", default=""),
        embedding=d.get("embedding"),
        metadata=d.get("metadata") or {},
        conversation_id=d.get("conversation_id"),
    )


def parse_conversation(d: dict[str, Any] | None) -> Conversation | None:
    if d is None:
        return None
    return Conversation(
        id=d["id"],
        session_id=d.get("session_id") or d["id"],
        messages=[m for m in (parse_message(x) for x in d.get("messages") or []) if m],
        title=d.get("title"),
        created_at=d.get("created_at") or "",
        updated_at=d.get("updated_at"),
        workspace_id=d.get("workspace_id"),
        user_id=d.get("user_id"),
        metadata=d.get("metadata"),
    )


def parse_session_info(d: dict[str, Any]) -> SessionInfo:
    return SessionInfo(
        session_id=d.get("session_id") or d.get("id") or "",
        message_count=int(d.get("message_count") or 0),
        created_at=d.get("created_at") or "",
        updated_at=d.get("updated_at"),
    )


def parse_observation(d: dict[str, Any]) -> Observation:
    return Observation(
        id=d["id"],
        conversation_id=d["conversation_id"],
        content=d.get("content", ""),
        window_start=d.get("window_start"),
        window_end=d.get("window_end"),
        created_at=d.get("created_at", ""),
    )


def parse_reflection(d: dict[str, Any]) -> Reflection:
    return Reflection(
        id=d["id"],
        conversation_id=d["conversation_id"],
        content=d.get("content", ""),
        created_at=d.get("created_at", ""),
    )


def parse_context(d: dict[str, Any]) -> ConversationContext:
    return ConversationContext(
        reflections=[parse_reflection(x) for x in d.get("reflections") or []],
        observations=[parse_observation(x) for x in d.get("observations") or []],
        recent_messages=[m for m in (parse_message(x) for x in d.get("recent_messages") or []) if m],
    )


def parse_relrefs(items: list[dict[str, Any]] | None) -> list[EntityRelationshipRef]:
    return [
        EntityRelationshipRef(
            id=x["id"],
            type=x.get("type", ""),
            target_id=x.get("target_id", ""),
            target_name=x.get("target_name"),
            properties=x.get("properties"),
        )
        for x in items or []
    ]


def parse_entity(d: dict[str, Any] | None) -> Entity | None:
    if d is None:
        return None
    return Entity(
        id=d["id"],
        name=d.get("name", ""),
        type=d.get("type", ""),
        subtype=d.get("subtype"),
        description=d.get("description"),
        embedding=d.get("embedding"),
        canonical_name=d.get("canonical_name"),
        created_at=d.get("created_at", ""),
        updated_at=d.get("updated_at"),
        confidence=d.get("confidence"),
        source_stage=d.get("source_stage"),
        relationships=parse_relrefs(d.get("relationships")) if d.get("relationships") else None,
    )


def parse_entity_history(d: dict[str, Any]) -> EntityHistory:
    return EntityHistory(
        entity_id=d.get("entity_id", ""),
        mentions=[
            EntityMention(
                conversation_id=m.get("conversation_id", ""),
                message_id=m.get("message_id"),
                content=m.get("content", ""),
                timestamp=m.get("timestamp", ""),
            )
            for m in d.get("mentions") or []
        ],
    )


def parse_graph(d: dict[str, Any]) -> EntityGraph:
    nodes: list[EntityGraphNode] = []
    for n in d.get("nodes") or []:
        nodes.append(
            EntityGraphNode(
                id=str(n.get("id") or ""),
                name=n.get("name", ""),
                type=n.get("type", ""),
            )
        )
    edges: list[EntityGraphEdge] = []
    for e in d.get("edges") or []:
        # Hosted edges expose source_id/target_id and no top-level id.
        source = str(e.get("source") or e.get("source_id") or "")
        target = str(e.get("target") or e.get("target_id") or "")
        edge_type = e.get("type") or e.get("predicate") or ""
        edge_id = str(e.get("id") or f"{source}-{edge_type}-{target}")
        edges.append(
            EntityGraphEdge(id=edge_id, source=source, target=target, type=edge_type)
        )
    return EntityGraph(nodes=nodes, edges=edges)


def parse_preference(d: dict[str, Any]) -> Preference:
    return Preference(
        id=d["id"],
        category=d.get("category", ""),
        preference=d.get("preference", ""),
        context=d.get("context"),
        embedding=d.get("embedding"),
    )


def parse_fact(d: dict[str, Any]) -> Fact:
    return Fact(
        id=d["id"],
        subject=d.get("subject", ""),
        predicate=d.get("predicate", ""),
        object=d.get("object", ""),
        embedding=d.get("embedding"),
    )


def parse_relationship(d: dict[str, Any]) -> Relationship:
    return Relationship(
        id=d["id"],
        source_id=d.get("source_id", ""),
        target_id=d.get("target_id", ""),
        relationship_type=d.get("relationship_type", ""),
        properties=d.get("properties"),
    )


def parse_tool_call(d: dict[str, Any]) -> ToolCall:
    return ToolCall(
        id=d.get("id", ""),
        tool_name=d.get("tool_name") or d.get("toolName") or "",
        arguments=d.get("arguments") or {},
        result=d.get("result") or d.get("output"),
        status=d.get("status", "success"),
        duration_ms=d.get("duration_ms"),
        error=d.get("error"),
    )


def parse_step(d: dict[str, Any]) -> ReasoningStep:
    return ReasoningStep(
        id=d["id"],
        trace_id=d.get("trace_id", ""),
        step_number=int(d.get("step_number") or 0),
        thought=d.get("thought"),
        action=d.get("action"),
        observation=d.get("observation"),
        tool_calls=[parse_tool_call(x) for x in d.get("tool_calls") or []],
    )


def parse_trace(d: dict[str, Any] | None) -> ReasoningTrace | None:
    if d is None:
        return None
    return ReasoningTrace(
        id=d["id"],
        session_id=d.get("session_id", ""),
        task=d.get("task", ""),
        steps=[parse_step(x) for x in d.get("steps") or []],
        outcome=d.get("outcome"),
        success=d.get("success"),
        started_at=d.get("started_at", ""),
        completed_at=d.get("completed_at"),
    )


def parse_tool_stats(d: dict[str, Any]) -> ToolStats:
    return ToolStats(
        name=d.get("name", ""),
        total_calls=int(d.get("total_calls") or 0),
        successful_calls=int(d.get("successful_calls") or 0),
        failed_calls=int(d.get("failed_calls") or 0),
        success_rate=float(d.get("success_rate") or 0.0),
        avg_duration_ms=d.get("avg_duration_ms"),
    )


def parse_agent_step(d: dict[str, Any]) -> AgentStep:
    return AgentStep(
        id=d["id"],
        conversation_id=d.get("conversation_id", ""),
        reasoning=d.get("reasoning", ""),
        action_taken=d.get("action_taken", ""),
        result=d.get("result"),
        created_at=d.get("created_at", ""),
    )


def parse_agent_step_explanation(d: dict[str, Any]) -> AgentStepExplanation:
    return AgentStepExplanation(
        id=d["id"],
        conversation_id=d.get("conversation_id", ""),
        reasoning=d.get("reasoning", ""),
        action_taken=d.get("action_taken", ""),
        result=d.get("result"),
        created_at=d.get("created_at", ""),
        tool_calls=[parse_tool_call(x) for x in d.get("tool_calls") or []],
        influenced_entities=[
            e for e in (parse_entity(x) for x in d.get("influenced_entities") or []) if e
        ],
    )


def parse_conversation_trace(d: dict[str, Any]) -> ConversationTrace:
    return ConversationTrace(
        conversation_id=d.get("conversation_id", ""),
        steps=[parse_agent_step(x) for x in d.get("steps") or []],
        tool_calls=[parse_tool_call(x) for x in d.get("tool_calls") or []],
    )


def parse_provenance(d: dict[str, Any]) -> EntityProvenance:
    return EntityProvenance(
        entity_id=d.get("entity_id", ""),
        steps=[parse_agent_step(x) for x in d.get("steps") or []],
    )


def parse_cypher(d: dict[str, Any]) -> CypherResult:
    return CypherResult(
        columns=list(d.get("columns") or []),
        rows=list(d.get("rows") or []),
        stats=d.get("stats"),
    )


def parse_api_key(d: dict[str, Any]) -> ApiKey:
    return ApiKey(
        id=d["id"],
        label=d.get("label", ""),
        workspace_id=d.get("workspace_id", ""),
        created_at=d.get("created_at", ""),
        scopes=list(d.get("scopes") or []),
        expires_at=d.get("expires_at"),
        key=d.get("key"),
    )


def parse_token_pair(d: dict[str, Any]) -> AccessTokenPair:
    return AccessTokenPair(
        access_token=d["access_token"],
        refresh_token=d["refresh_token"],
        expires_in=int(d.get("expires_in") or 0),
    )


def parse_feedback(d: dict[str, Any]) -> EntityFeedbackResult:
    return EntityFeedbackResult(id=d["id"], updated=bool(d.get("updated")))


def parse_merge(d: dict[str, Any]) -> EntityMergeResult:
    return EntityMergeResult(
        source_id=d.get("source_id", ""),
        target_id=d.get("target_id", ""),
        status=d.get("status", ""),
    )
