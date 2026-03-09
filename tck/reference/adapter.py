"""Reference adapter wrapping the neo4j-agent-memory Python package.

This adapter is the canonical implementation used to validate that the
TCK tests themselves are correct. It requires the `neo4j-agent-memory`
package and a running Neo4j instance.

Install with: pip install neo4j-agent-memory-tck[reference]
"""

from __future__ import annotations

import os
from datetime import datetime
from typing import Any
from uuid import UUID

from tck.adapters.base_adapter import (
    BaseAdapter,
    TCKConversation,
    TCKEntity,
    TCKFact,
    TCKMessage,
    TCKPreference,
    TCKReasoningStep,
    TCKReasoningTrace,
    TCKRelationship,
    TCKSessionInfo,
    TCKToolCall,
    TCKToolStats,
    ToolCallStatus,
)


def _to_datetime(val) -> datetime:
    """Convert a value to Python datetime, handling Neo4j DateTime objects."""
    if val is None:
        return datetime.utcnow()
    if isinstance(val, datetime):
        return val
    try:
        return val.to_native()
    except AttributeError:
        return datetime.utcnow()


class ReferenceAdapter(BaseAdapter):
    """Reference adapter using the neo4j-agent-memory Python package."""

    def __init__(
        self,
        neo4j_uri: str | None = None,
        neo4j_username: str | None = None,
        neo4j_password: str | None = None,
    ):
        self._uri = neo4j_uri or os.getenv("NEO4J_URI", "bolt://localhost:7687")
        self._username = neo4j_username or os.getenv("NEO4J_USERNAME", "neo4j")
        self._password = neo4j_password or os.getenv("NEO4J_PASSWORD", "password")
        self._client = None

    async def setup(self) -> None:
        from neo4j_agent_memory import MemoryClient, MemorySettings, Neo4jConfig
        from pydantic import SecretStr

        from tck.fixtures.mocks import MockEmbedder

        # Import mock components from the reference test suite
        try:
            from tests.conftest import MockExtractor, MockResolver
        except ImportError:
            MockExtractor = None  # type: ignore
            MockResolver = None  # type: ignore

        settings = MemorySettings(
            neo4j=Neo4jConfig(
                uri=self._uri,
                username=self._username,
                password=SecretStr(self._password),
            ),
        )

        kwargs: dict[str, Any] = {"embedder": MockEmbedder()}
        if MockExtractor is not None:
            kwargs["extractor"] = MockExtractor()
        if MockResolver is not None:
            kwargs["resolver"] = MockResolver()

        self._client = MemoryClient(settings, **kwargs)
        await self._client.connect()

    async def teardown(self) -> None:
        if self._client is not None:
            await self._client.close()
            self._client = None

    async def clear_all_data(self) -> None:
        if self._client and self._client._client:
            await self._client._client.execute_write(
                "MATCH (n) DETACH DELETE n", {}
            )

    # --- Short-Term Memory ---

    async def add_message(
        self,
        session_id: str,
        role: str,
        content: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> TCKMessage:
        msg = await self._client.short_term.add_message(
            session_id,
            role,
            content,
            metadata=metadata,
            extract_entities=False,
        )
        return TCKMessage(
            id=msg.id,
            role=msg.role.value if hasattr(msg.role, "value") else str(msg.role),
            content=msg.content,
            timestamp=_to_datetime(msg.created_at),
            embedding=msg.embedding,
            metadata=msg.metadata or {},
        )

    async def get_conversation(
        self,
        session_id: str,
        *,
        limit: int | None = None,
    ) -> TCKConversation:
        conv = await self._client.short_term.get_conversation(
            session_id, limit=limit
        )
        messages = [
            TCKMessage(
                id=m.id,
                role=m.role.value if hasattr(m.role, "value") else str(m.role),
                content=m.content,
                timestamp=_to_datetime(m.created_at),
                embedding=m.embedding,
                metadata=m.metadata or {},
            )
            for m in conv.messages
        ]
        return TCKConversation(
            id=conv.id,
            session_id=conv.session_id,
            messages=messages,
            title=conv.title,
            created_at=_to_datetime(conv.created_at),
            updated_at=conv.updated_at,
        )

    async def search_messages(
        self,
        query: str,
        *,
        session_id: str | None = None,
        limit: int = 10,
        threshold: float = 0.7,
    ) -> list[TCKMessage]:
        results = await self._client.short_term.search_messages(
            query, session_id=session_id, limit=limit, threshold=threshold
        )
        return [
            TCKMessage(
                id=m.id,
                role=m.role.value if hasattr(m.role, "value") else str(m.role),
                content=m.content,
                timestamp=_to_datetime(m.created_at),
                embedding=m.embedding,
                metadata=m.metadata or {},
            )
            for m in results
        ]

    async def list_sessions(
        self,
        *,
        limit: int = 100,
    ) -> list[TCKSessionInfo]:
        sessions = await self._client.short_term.list_sessions(limit=limit)
        return [
            TCKSessionInfo(
                session_id=s.session_id,
                message_count=s.message_count,
                created_at=_to_datetime(s.created_at),
                updated_at=s.updated_at,
            )
            for s in sessions
        ]

    async def delete_message(self, message_id: UUID) -> bool:
        return await self._client.short_term.delete_message(message_id)

    async def clear_session(self, session_id: str) -> None:
        await self._client.short_term.clear_session(session_id)

    # --- Long-Term Memory ---

    async def add_entity(
        self,
        name: str,
        entity_type: str,
        *,
        description: str | None = None,
    ) -> TCKEntity:
        entity, _dedup = await self._client.long_term.add_entity(
            name=name,
            entity_type=entity_type,
            description=description,
            resolve=False,
            deduplicate=False,
            geocode=False,
            enrich=False,
        )
        return TCKEntity(
            id=entity.id,
            name=entity.name,
            type=entity.type if isinstance(entity.type, str) else entity.type.value,
            subtype=entity.subtype,
            description=entity.description,
            embedding=entity.embedding,
            canonical_name=getattr(entity, "canonical_name", None),
            created_at=_to_datetime(entity.created_at),
        )

    async def add_preference(
        self,
        category: str,
        preference: str,
        *,
        context: str | None = None,
    ) -> TCKPreference:
        pref = await self._client.long_term.add_preference(
            category=category,
            preference=preference,
            context=context,
        )
        return TCKPreference(
            id=pref.id,
            category=pref.category,
            preference=pref.preference,
            context=pref.context,
            embedding=pref.embedding,
        )

    async def add_fact(
        self,
        subject: str,
        predicate: str,
        obj: str,
    ) -> TCKFact:
        fact = await self._client.long_term.add_fact(
            subject=subject,
            predicate=predicate,
            obj=obj,
        )
        return TCKFact(
            id=fact.id,
            subject=fact.subject,
            predicate=fact.predicate,
            object=fact.object,
            embedding=fact.embedding,
        )

    async def search_entities(
        self,
        query: str,
        *,
        limit: int = 10,
    ) -> list[TCKEntity]:
        results = await self._client.long_term.search_entities(
            query, limit=limit, threshold=0.0
        )
        return [
            TCKEntity(
                id=e.id,
                name=e.name,
                type=e.type if isinstance(e.type, str) else e.type.value,
                subtype=e.subtype,
                description=e.description,
                embedding=e.embedding,
                canonical_name=getattr(e, "canonical_name", None),
                created_at=_to_datetime(e.created_at),
            )
            for e in results
        ]

    async def search_preferences(
        self,
        query: str,
        *,
        category: str | None = None,
        limit: int = 10,
    ) -> list[TCKPreference]:
        results = await self._client.long_term.search_preferences(
            query, limit=limit
        )
        if category:
            results = [r for r in results if r.category == category]
        return [
            TCKPreference(
                id=p.id,
                category=p.category,
                preference=p.preference,
                context=p.context,
                embedding=p.embedding,
            )
            for p in results
        ]

    async def get_entity_by_name(self, name: str) -> TCKEntity | None:
        entity = await self._client.long_term.get_entity_by_name(name)
        if entity is None:
            return None
        return TCKEntity(
            id=entity.id,
            name=entity.name,
            type=entity.type if isinstance(entity.type, str) else entity.type.value,
            subtype=entity.subtype,
            description=entity.description,
            embedding=entity.embedding,
            canonical_name=getattr(entity, "canonical_name", None),
            created_at=_to_datetime(entity.created_at),
        )

    async def get_related_entities(
        self,
        entity_id: UUID,
        *,
        relationship_type: str | None = None,
        depth: int = 1,
    ) -> list[TCKEntity]:
        results = await self._client.long_term.get_related_entities(
            entity_id,
            relationship_types=[relationship_type] if relationship_type else None,
            depth=depth,
        )
        return [
            TCKEntity(
                id=e.id,
                name=e.name,
                type=e.type if isinstance(e.type, str) else e.type.value,
                subtype=e.subtype,
                description=e.description,
                embedding=e.embedding,
                canonical_name=getattr(e, "canonical_name", None),
                created_at=_to_datetime(e.created_at),
            )
            for e, _rel in results
        ]

    # --- Reasoning Memory ---

    async def start_trace(
        self,
        session_id: str,
        task: str,
    ) -> TCKReasoningTrace:
        trace = await self._client.reasoning.start_trace(
            session_id=session_id,
            task=task,
            generate_embedding=True,
        )
        return TCKReasoningTrace(
            id=trace.id,
            session_id=trace.session_id,
            task=trace.task,
            steps=[],
            outcome=trace.outcome,
            success=trace.success,
            started_at=_to_datetime(trace.started_at),
            completed_at=trace.completed_at,
        )

    async def add_step(
        self,
        trace_id: UUID,
        *,
        thought: str | None = None,
        action: str | None = None,
        observation: str | None = None,
    ) -> TCKReasoningStep:
        step = await self._client.reasoning.add_step(
            trace_id,
            thought=thought,
            action=action,
            observation=observation,
            generate_embedding=False,
        )
        return TCKReasoningStep(
            id=step.id,
            trace_id=step.trace_id,
            step_number=step.step_number,
            thought=step.thought,
            action=step.action,
            observation=step.observation,
            tool_calls=[],
        )

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
        tc = await self._client.reasoning.record_tool_call(
            step_id,
            tool_name,
            arguments,
            result=result,
            status=status.value if hasattr(status, "value") else status,
            duration_ms=duration_ms,
            error=error,
        )
        return TCKToolCall(
            id=tc.id,
            tool_name=tc.tool_name,
            arguments=tc.arguments or {},
            result=tc.result,
            status=ToolCallStatus(tc.status.value if hasattr(tc.status, "value") else tc.status),
            duration_ms=tc.duration_ms,
            error=tc.error,
        )

    async def complete_trace(
        self,
        trace_id: UUID,
        *,
        outcome: str | None = None,
        success: bool | None = None,
    ) -> TCKReasoningTrace:
        trace = await self._client.reasoning.complete_trace(
            trace_id,
            outcome=outcome,
            success=success,
        )
        steps = [
            TCKReasoningStep(
                id=s.id,
                trace_id=s.trace_id,
                step_number=s.step_number,
                thought=s.thought,
                action=s.action,
                observation=s.observation,
                tool_calls=[
                    TCKToolCall(
                        id=tc.id,
                        tool_name=tc.tool_name,
                        arguments=tc.arguments or {},
                        result=tc.result,
                        status=ToolCallStatus(tc.status.value if hasattr(tc.status, "value") else tc.status),
                        duration_ms=tc.duration_ms,
                        error=tc.error,
                    )
                    for tc in (s.tool_calls or [])
                ],
            )
            for s in (trace.steps or [])
        ]
        return TCKReasoningTrace(
            id=trace.id,
            session_id=trace.session_id,
            task=trace.task,
            steps=steps,
            outcome=trace.outcome,
            success=trace.success,
            started_at=_to_datetime(trace.started_at),
            completed_at=trace.completed_at,
        )

    async def get_trace_with_steps(self, trace_id: UUID) -> TCKReasoningTrace | None:
        trace = await self._client.reasoning.get_trace_with_steps(trace_id)
        if trace is None:
            return None
        steps = [
            TCKReasoningStep(
                id=s.id,
                trace_id=s.trace_id,
                step_number=s.step_number,
                thought=s.thought,
                action=s.action,
                observation=s.observation,
                tool_calls=[
                    TCKToolCall(
                        id=tc.id,
                        tool_name=tc.tool_name,
                        arguments=tc.arguments or {},
                        result=tc.result,
                        status=ToolCallStatus(tc.status.value if hasattr(tc.status, "value") else tc.status),
                        duration_ms=tc.duration_ms,
                        error=tc.error,
                    )
                    for tc in (s.tool_calls or [])
                ],
            )
            for s in (trace.steps or [])
        ]
        return TCKReasoningTrace(
            id=trace.id,
            session_id=trace.session_id,
            task=trace.task,
            steps=steps,
            outcome=trace.outcome,
            success=trace.success,
            started_at=_to_datetime(trace.started_at),
            completed_at=trace.completed_at,
        )

    async def list_traces(
        self,
        *,
        session_id: str | None = None,
        limit: int = 100,
    ) -> list[TCKReasoningTrace]:
        traces = await self._client.reasoning.list_traces(
            session_id=session_id, limit=limit
        )
        return [
            TCKReasoningTrace(
                id=t.id,
                session_id=t.session_id,
                task=t.task,
                steps=[],
                outcome=t.outcome,
                success=t.success,
                started_at=_to_datetime(t.started_at),
                completed_at=t.completed_at,
            )
            for t in traces
        ]

    async def get_tool_stats(
        self,
        tool_name: str | None = None,
    ) -> list[TCKToolStats]:
        stats = await self._client.reasoning.get_tool_stats(tool_name=tool_name)
        return [
            TCKToolStats(
                name=s.name,
                total_calls=s.total_calls,
                successful_calls=s.successful_calls,
                failed_calls=s.failed_calls,
                success_rate=s.success_rate,
                avg_duration_ms=s.avg_duration_ms,
            )
            for s in stats
        ]

    # --- Gold Tier ---

    async def add_relationship(
        self,
        source_id: UUID,
        target_id: UUID,
        relationship_type: str,
        *,
        properties: dict[str, Any] | None = None,
    ) -> TCKRelationship:
        rel = await self._client.long_term.add_relationship(
            source_id,
            target_id,
            relationship_type,
        )
        return TCKRelationship(
            id=rel.id,
            source_id=rel.source_id,
            target_id=rel.target_id,
            relationship_type=rel.type,
            properties=rel.properties or {},
        )

    async def merge_duplicate_entities(
        self,
        source_id: UUID,
        target_id: UUID,
        *,
        canonical_name: str | None = None,
    ) -> TCKEntity:
        result = await self._client.long_term.merge_duplicate_entities(
            source_id, target_id
        )
        if result is None:
            raise ValueError(f"Merge failed: entities {source_id} or {target_id} not found")
        _source, target = result
        return TCKEntity(
            id=target.id,
            name=target.name,
            type=target.type if isinstance(target.type, str) else target.type.value,
            subtype=target.subtype,
            description=target.description,
            embedding=target.embedding,
            canonical_name=getattr(target, "canonical_name", None),
            created_at=_to_datetime(target.created_at),
        )
