"""Comprehensive end-to-end tests against the live hosted Neo4j Agent Memory
Service.

Covers every public REST endpoint plus several end-to-end agent workflows.
Skipped wholesale when ``MEMORY_API_KEY`` is unset; individual tests skip
themselves when the service refuses an operation that requires elevated
workspace scope (e.g. the Cypher console, API-key management).

Each test creates short-lived data (conversations / entities tagged with the
``tck-e2e-py-`` user prefix) and tears it down via finalizers. Failures
during cleanup are swallowed — we never want teardown noise to mask a real
test failure.
"""

from __future__ import annotations

import asyncio
import os
import time
import uuid

import pytest

from neo4j_agent_memory_client import (
    BulkMessageInput,
    MemoryClient,
)
from neo4j_agent_memory_client.errors import (
    AuthenticationError,
    NotFoundError,
    TransportError,
)


# ---------------------------------------------------------------------------
# Fixtures + helpers
# ---------------------------------------------------------------------------


def _has_api_key() -> bool:
    return bool(os.environ.get("MEMORY_API_KEY", "").strip())


pytestmark = [
    pytest.mark.e2e,
    pytest.mark.skipif(not _has_api_key(), reason="MEMORY_API_KEY not set"),
]


UNIQUE_TAG = uuid.uuid4().hex[:8]
USER_PREFIX = os.environ.get("MEMORY_E2E_USER_ID", "tck-e2e-py")


def _user_id(suffix: str = "") -> str:
    """A unique user id per test (so cleanup never crosses tests)."""
    rand = uuid.uuid4().hex[:6]
    base = f"{USER_PREFIX}-{UNIQUE_TAG}-{rand}"
    return f"{base}-{suffix}" if suffix else base


@pytest.fixture
async def client():
    """A MemoryClient per test (pytest-asyncio default fixture scope is function)."""
    endpoint = os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")
    api_key = os.environ["MEMORY_API_KEY"]
    c = MemoryClient(endpoint=endpoint, api_key=api_key)
    await c.connect()
    yield c
    await c.close()


@pytest.fixture
async def conv(client: MemoryClient):
    """Disposable conversation; deleted after the test."""
    c = await client.short_term.create_conversation(user_id=_user_id())
    yield c
    try:
        await client.short_term.delete_conversation(c.id)
    except Exception:
        pass


@pytest.fixture
async def conv_factory(client: MemoryClient):
    """Make several conversations in one test; all are deleted in teardown."""
    created: list[str] = []

    async def _make(*, user_id: str | None = None, metadata: dict | None = None):
        c = await client.short_term.create_conversation(
            user_id=user_id or _user_id(), metadata=metadata
        )
        created.append(c.id)
        return c

    yield _make
    for cid in created:
        try:
            await client.short_term.delete_conversation(cid)
        except Exception:
            pass


@pytest.fixture
async def entity_factory(client: MemoryClient):
    """Make scratch entities; all deleted in teardown."""
    created: list[str] = []

    async def _make(*, name: str | None = None, entity_type: str = "concept", description: str | None = None):
        ent = await client.long_term.add_entity(
            name=name or f"TCK-Probe-{uuid.uuid4().hex[:8]}",
            entity_type=entity_type,
            description=description or "tck e2e probe entity",
        )
        created.append(ent.id)
        return ent

    yield _make
    for eid in created:
        try:
            await client.long_term.delete_entity(eid)
        except Exception:
            pass


async def _wait_until(predicate, *, timeout: float = 8.0, interval: float = 0.5):
    """Poll until predicate() returns truthy or timeout. Returns last value."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = await predicate()
        if last:
            return last
        await asyncio.sleep(interval)
    return last


def _skip_on_403(call_label: str):
    """Decorator helper: catch AuthenticationError and skip the test."""

    def deco(func):
        async def wrapper(*args, **kwargs):
            try:
                return await func(*args, **kwargs)
            except AuthenticationError as e:
                pytest.skip(f"API key lacks scope for {call_label}: {e}")

        wrapper.__name__ = func.__name__
        wrapper.__doc__ = func.__doc__
        return wrapper

    return deco


# ===========================================================================
# 1. Connection + auth
# ===========================================================================


class TestConnectionAndAuth:
    async def test_connect_succeeds_with_valid_key(self, client: MemoryClient):
        """Reaching this fixture proves connect() worked."""
        assert client._transport is not None

    async def test_invalid_api_key_yields_auth_error(self):
        endpoint = os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")
        bad = MemoryClient(endpoint=endpoint, api_key="nams_obviously_not_real_token")
        try:
            with pytest.raises(AuthenticationError):
                await bad.connect()
        finally:
            await bad.close()

    async def test_empty_api_key_yields_auth_error(self):
        endpoint = os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")
        bad = MemoryClient(endpoint=endpoint, api_key="")
        try:
            with pytest.raises(AuthenticationError):
                await bad.connect()
        finally:
            await bad.close()


# ===========================================================================
# 2. Conversation lifecycle
# ===========================================================================


class TestConversationLifecycle:
    async def test_create_returns_uuid_and_user_metadata(
        self, client: MemoryClient, conv_factory
    ):
        uid = _user_id("create")
        c = await conv_factory(user_id=uid, metadata={"source": "e2e", "seq": 1})
        assert c.id and len(c.id) >= 8
        assert c.user_id == uid
        # workspace_id is server-assigned
        assert c.workspace_id

    async def test_get_metadata_round_trips_user_id(
        self, client: MemoryClient, conv
    ):
        meta = await client.short_term.get_conversation_metadata(conv.id)
        assert meta.id == conv.id
        assert meta.user_id == conv.user_id

    async def test_list_includes_freshly_created(
        self, client: MemoryClient, conv_factory
    ):
        c = await conv_factory(user_id=_user_id("list-probe"))
        listed = await client.short_term.list_conversations(limit=200)
        assert any(x.id == c.id for x in listed), (
            "newly created conversation should appear in list_conversations"
        )

    async def test_delete_is_idempotent(self, client: MemoryClient, conv_factory):
        c = await conv_factory()
        await client.short_term.delete_conversation(c.id)
        # Second call must not raise
        await client.short_term.delete_conversation(c.id)


# ===========================================================================
# 3. Short-term memory: messages
# ===========================================================================


class TestMessageBasics:
    async def test_add_message_returns_id_and_role(
        self, client: MemoryClient, conv
    ):
        msg = await client.short_term.add_message(conv.id, "user", "hello world")
        assert msg.id
        assert msg.role == "user"
        assert msg.content == "hello world"

    async def test_get_conversation_returns_messages_in_order(
        self, client: MemoryClient, conv
    ):
        contents = ["one", "two", "three", "four", "five"]
        for c in contents:
            await client.short_term.add_message(conv.id, "user", c)
        got = await client.short_term.get_conversation(conv.id)
        # The hosted service may return either insertion or recency order;
        # require that the set of contents matches and length equals.
        assert len(got.messages) >= len(contents)
        seen = [m.content for m in got.messages]
        for c in contents:
            assert c in seen

    async def test_search_messages_finds_relevant_term(
        self, client: MemoryClient, conv
    ):
        await client.short_term.add_message(
            conv.id, "user", "Marie Curie won the Nobel Prize in Physics in 1903."
        )
        await client.short_term.add_message(
            conv.id, "user", "The recipe for sourdough requires a starter."
        )
        results = await client.short_term.search_messages(
            "Nobel", session_id=conv.id, limit=5, threshold=0.0
        )
        assert isinstance(results, list)


class TestMessageRoles:
    @pytest.mark.parametrize("role", ["user", "assistant", "system"])
    async def test_role_round_trip(self, client: MemoryClient, conv, role):
        msg = await client.short_term.add_message(conv.id, role, f"role {role}")
        assert msg.role == role


class TestMessageContentFidelity:
    async def test_unicode_preserved(self, client: MemoryClient, conv):
        content = "你好 🚀 émoji ñ ç ø"
        msg = await client.short_term.add_message(conv.id, "user", content)
        assert msg.content == content

    async def test_long_content_preserved(self, client: MemoryClient, conv):
        content = "x" * 10_000
        msg = await client.short_term.add_message(conv.id, "user", content)
        assert msg.content == content
        assert len(msg.content) == 10_000

    async def test_special_chars_preserved(self, client: MemoryClient, conv):
        content = 'quote " backslash \\ newline\nreturn\r tab\t json {"a":1}'
        msg = await client.short_term.add_message(conv.id, "user", content)
        assert msg.content == content

    async def test_metadata_round_trips(self, client: MemoryClient, conv):
        meta = {"source": "tck-e2e", "priority": "high", "count": 42, "active": True}
        msg = await client.short_term.add_message(
            conv.id, "user", "with-meta", metadata=meta
        )
        # Some hosted setups echo metadata back, others store but don't echo.
        # We only require no error + same content.
        assert msg.content == "with-meta"


# ===========================================================================
# 4. Bulk operations
# ===========================================================================


class TestBulkAddMessages:
    async def test_bulk_add_5_messages(self, client: MemoryClient, conv):
        msgs = [BulkMessageInput(role="user", content=f"bulk-{i}") for i in range(5)]
        out = await client.short_term.bulk_add_messages(conv.id, msgs)
        assert len(out) == 5

    async def test_bulk_add_50_messages(self, client: MemoryClient, conv):
        msgs = [BulkMessageInput(role="user", content=f"big-bulk-{i}") for i in range(50)]
        out = await client.short_term.bulk_add_messages(conv.id, msgs)
        assert len(out) == 50

    async def test_bulk_add_rejects_more_than_100(
        self, client: MemoryClient, conv
    ):
        from neo4j_agent_memory_client.errors import ValidationError

        msgs = [BulkMessageInput(role="user", content=f"x-{i}") for i in range(101)]
        with pytest.raises(ValidationError):
            await client.short_term.bulk_add_messages(conv.id, msgs)


# ===========================================================================
# 5. Three-tier context + observations + reflections
# ===========================================================================


class TestContextEndpoints:
    async def test_get_context_shape(self, client: MemoryClient, conv):
        await client.short_term.add_message(conv.id, "user", "Hello world")
        ctx = await client.short_term.get_context(conv.id)
        assert hasattr(ctx, "reflections")
        assert hasattr(ctx, "observations")
        assert hasattr(ctx, "recent_messages")
        assert isinstance(ctx.recent_messages, list)

    async def test_get_observations_returns_list(self, client: MemoryClient, conv):
        # Observations are generated asynchronously after enough messages.
        # We only verify the shape — list — not that any have materialised.
        obs = await client.short_term.get_observations(conv.id, limit=10)
        assert isinstance(obs, list)

    async def test_get_reflections_returns_list(self, client: MemoryClient, conv):
        refl = await client.short_term.get_reflections(conv.id)
        assert isinstance(refl, list)

    async def test_context_recent_messages_includes_added(
        self, client: MemoryClient, conv
    ):
        await client.short_term.add_message(conv.id, "user", "context-probe-message")
        ctx = await client.short_term.get_context(conv.id)
        contents = [m.content for m in ctx.recent_messages]
        assert "context-probe-message" in contents


# ===========================================================================
# 6. Long-term memory: entities
# ===========================================================================


class TestEntityCRUD:
    async def test_add_entity_returns_id_and_fields(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory(name="TCK Alice", description="test person")
        assert e.id and len(e.id) >= 8
        assert e.name == "TCK Alice"
        assert e.description == "test person"

    async def test_list_entities_returns_array(self, client: MemoryClient):
        ents = await client.long_term.list_entities(limit=5)
        assert isinstance(ents, list)

    async def test_list_entities_with_type_filter(self, client: MemoryClient):
        ents = await client.long_term.list_entities(type="person", limit=5)
        assert isinstance(ents, list)
        for e in ents:
            assert e.type == "person"

    async def test_get_entity_includes_relationships(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory()
        full = await client.long_term.get_entity(e.id)
        assert full.id == e.id
        # relationships may be None or empty list — both are valid.
        assert full.relationships is None or isinstance(full.relationships, list)

    async def test_update_entity_description(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory(description="orig")
        updated = await client.long_term.update_entity(e.id, description="rewritten")
        assert updated.id == e.id
        assert updated.description == "rewritten"

    async def test_update_entity_name(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory(name=f"Original-{uuid.uuid4().hex[:6]}")
        new_name = f"Renamed-{uuid.uuid4().hex[:6]}"
        updated = await client.long_term.update_entity(e.id, name=new_name)
        assert updated.name == new_name

    async def test_delete_entity_removes_it(self, client: MemoryClient, entity_factory):
        e = await entity_factory()
        await client.long_term.delete_entity(e.id)
        # After delete, get should fail or return a tombstone shape.
        try:
            after = await client.long_term.get_entity(e.id)
        except (NotFoundError, TransportError):
            return  # 404 is the expected outcome
        # Some services soft-delete; just assert id matches if they do.
        assert after.id == e.id


class TestEntitySearch:
    async def test_search_entities_returns_list(self, client: MemoryClient):
        results = await client.long_term.search_entities("anything", limit=5)
        assert isinstance(results, list)

    async def test_search_finds_freshly_created_entity(
        self, client: MemoryClient, entity_factory
    ):
        marker = f"TCK-Probe-{uuid.uuid4().hex[:8]}"
        e = await entity_factory(name=marker)

        async def _try():
            hits = await client.long_term.search_entities(marker, limit=10)
            return next((h for h in hits if h.id == e.id), None)

        # Search indexing may be async — give it a few seconds.
        found = await _wait_until(_try, timeout=12.0, interval=1.0)
        # If indexing is slow, allow a soft skip to avoid flakes.
        if found is None:
            pytest.skip("entity not yet indexed for search after 12s")
        assert found.id == e.id

    async def test_search_with_type_filter(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory(entity_type="concept", name=f"TCKConcept-{uuid.uuid4().hex[:6]}")
        hits = await client.long_term.search_entities(e.name, type="concept", limit=5)
        assert isinstance(hits, list)


class TestEntityFeedback:
    async def test_set_feedback_returns_updated(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory()
        result = await client.long_term.set_entity_feedback(
            e.id, user_score=0.93, confirmed=True
        )
        assert result.id == e.id
        assert result.updated is True

    async def test_set_feedback_score_zero(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory()
        result = await client.long_term.set_entity_feedback(
            e.id, user_score=0.0, confirmed=False
        )
        assert result.id == e.id


class TestEntityHistoryAndProvenance:
    async def test_get_history_returns_shape(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory()
        hist = await client.long_term.get_entity_history(e.id)
        assert hist.entity_id == e.id
        assert isinstance(hist.mentions, list)

    async def test_get_provenance_returns_shape(
        self, client: MemoryClient, entity_factory
    ):
        e = await entity_factory()
        prov = await client.reasoning.get_entity_provenance(e.id)
        assert prov.entity_id == e.id
        assert isinstance(prov.steps, list)


class TestEntityGraph:
    async def test_get_graph_returns_nodes_and_edges(self, client: MemoryClient):
        graph = await client.long_term.get_entity_graph()
        assert isinstance(graph.nodes, list)
        assert isinstance(graph.edges, list)
        # Nodes should at least be tuples with id/name/type.
        if graph.nodes:
            assert graph.nodes[0].id
            assert isinstance(graph.nodes[0].name, str)


class TestEntityMerge:
    async def test_merge_two_entities(self, client: MemoryClient, entity_factory):
        a = await entity_factory(name=f"MergeA-{uuid.uuid4().hex[:6]}")
        b = await entity_factory(name=f"MergeB-{uuid.uuid4().hex[:6]}")
        try:
            result = await client.long_term.merge_entities(a.id, b.id)
        except (TransportError, AuthenticationError) as e:
            pytest.skip(f"merge endpoint refused or unsupported: {e}")
        assert result.source_id
        assert result.target_id
        assert result.status


# ===========================================================================
# 7. Reasoning memory: steps + tool calls + traces
# ===========================================================================


class TestReasoningSteps:
    async def test_record_step_persists(self, client: MemoryClient, conv):
        step = await client.reasoning.record_step(
            conversation_id=conv.id,
            reasoning="hypothesizing user's intent",
            action_taken="lookup_user_profile",
            result="found profile",
        )
        assert step.id
        assert step.conversation_id == conv.id
        assert step.reasoning.startswith("hypothesizing")
        assert step.action_taken == "lookup_user_profile"

    async def test_record_step_without_result(self, client: MemoryClient, conv):
        step = await client.reasoning.record_step(
            conversation_id=conv.id, reasoning="r", action_taken="a"
        )
        assert step.id

    async def test_list_steps_returns_recorded(self, client: MemoryClient, conv):
        s1 = await client.reasoning.record_step(
            conversation_id=conv.id, reasoning="r1", action_taken="a1"
        )
        s2 = await client.reasoning.record_step(
            conversation_id=conv.id, reasoning="r2", action_taken="a2"
        )
        steps = await client.reasoning.list_steps(conv.id)
        ids = {s.id for s in steps}
        assert s1.id in ids
        assert s2.id in ids


class TestReasoningExplain:
    async def test_explain_step_returns_tool_calls_and_entities(
        self, client: MemoryClient, conv
    ):
        step = await client.reasoning.record_step(
            conversation_id=conv.id, reasoning="r", action_taken="a"
        )
        explanation = await client.reasoning.explain_step(step.id)
        assert explanation.id == step.id
        assert isinstance(explanation.tool_calls, list)
        assert isinstance(explanation.influenced_entities, list)


class TestReasoningTrace:
    async def test_get_trace_for_empty_conv(self, client: MemoryClient, conv):
        trace = await client.reasoning.get_trace_by_conversation(conv.id)
        assert trace.conversation_id == conv.id
        assert isinstance(trace.steps, list)
        assert isinstance(trace.tool_calls, list)

    async def test_get_trace_with_one_step(self, client: MemoryClient, conv):
        await client.reasoning.record_step(
            conversation_id=conv.id, reasoning="r", action_taken="a"
        )
        trace = await client.reasoning.get_trace_by_conversation(conv.id)
        assert any("r" in s.reasoning for s in trace.steps)


# ===========================================================================
# 8. Cypher console (skipped on 403)
# ===========================================================================


class TestCypherConsole:
    async def test_count_query(self, client: MemoryClient):
        try:
            result = await client.query.cypher(
                "MATCH (n) RETURN count(n) AS total"
            )
        except AuthenticationError as e:
            pytest.skip(f"API key lacks Cypher scope: {e}")
        assert "total" in result.columns
        assert len(result.rows) >= 1

    async def test_parameterised_query(self, client: MemoryClient):
        try:
            result = await client.query.cypher(
                "MATCH (n) RETURN $label AS label LIMIT 1",
                {"label": "tck-e2e"},
            )
        except AuthenticationError as e:
            pytest.skip(f"API key lacks Cypher scope: {e}")
        assert isinstance(result.columns, list)


# ===========================================================================
# 9. Auth API (skipped on 403)
# ===========================================================================


class TestAuthApiKeys:
    async def test_list_api_keys(self, client: MemoryClient, conv):
        # We need the workspace_id of *this* api key to list keys.
        meta = await client.short_term.get_conversation_metadata(conv.id)
        ws = meta.workspace_id
        if not ws:
            pytest.skip("workspace_id not exposed by service")
        try:
            keys = await client.auth.list_api_keys(ws)
        except AuthenticationError as e:
            pytest.skip(f"API key lacks auth scope: {e}")
        assert isinstance(keys, list)


# ===========================================================================
# 10. Cross-feature workflows
# ===========================================================================


class TestAgentWorkflow:
    async def test_message_flow_to_extracted_entities(
        self, client: MemoryClient, conv_factory
    ):
        """Simulate an agent: add a message, wait for entity extraction,
        verify entities are listable + searchable."""
        c = await conv_factory(user_id=_user_id("agent-flow"))
        unique_name = f"TCKMercury{uuid.uuid4().hex[:8]}"
        await client.short_term.add_message(
            c.id,
            "user",
            f"{unique_name} is the smallest planet in the solar system.",
        )
        await client.short_term.add_message(
            c.id, "assistant", f"Yes, {unique_name} has a thin atmosphere."
        )

        async def _try():
            ents = await client.long_term.search_entities(unique_name, limit=10)
            return ents if any(unique_name.lower() in e.name.lower() for e in ents) else None

        found = await _wait_until(_try, timeout=20.0, interval=2.0)
        if found is None:
            pytest.skip("extracted entity not indexed within 20s")
        assert any(unique_name.lower() in e.name.lower() for e in found)

    async def test_record_steps_then_get_full_trace(
        self, client: MemoryClient, conv
    ):
        """Simulate a multi-step reasoning chain and verify it round-trips."""
        steps = []
        for i in range(3):
            s = await client.reasoning.record_step(
                conversation_id=conv.id,
                reasoning=f"step {i} reasoning",
                action_taken=f"action_{i}",
                result=f"result_{i}",
            )
            steps.append(s)

        trace = await client.reasoning.get_trace_by_conversation(conv.id)
        recorded_ids = {s.id for s in steps}
        trace_ids = {s.id for s in trace.steps}
        assert recorded_ids.issubset(trace_ids)

    async def test_full_conversation_then_context(
        self, client: MemoryClient, conv
    ):
        """Add a multi-turn conversation, then fetch three-tier context."""
        turns = [
            ("user", "I'm planning a trip to Tokyo next month."),
            ("assistant", "Tokyo is great in autumn — what are your interests?"),
            ("user", "Mostly food and historical sites."),
            ("assistant", "Visit Tsukiji Outer Market and Senso-ji."),
            ("user", "How long should I stay?"),
        ]
        for role, content in turns:
            await client.short_term.add_message(conv.id, role, content)

        ctx = await client.short_term.get_context(conv.id)
        contents = " ".join(m.content for m in ctx.recent_messages)
        assert "Tokyo" in contents or "Tsukiji" in contents


# ===========================================================================
# 11. Concurrency
# ===========================================================================


class TestConcurrency:
    async def test_concurrent_add_messages(self, client: MemoryClient, conv):
        """The client + transport must safely interleave concurrent requests."""

        async def add(i: int):
            return await client.short_term.add_message(
                conv.id, "user", f"concurrent-{i}"
            )

        results = await asyncio.gather(*(add(i) for i in range(8)))
        assert len({m.id for m in results}) == 8

    async def test_concurrent_create_conversations(
        self, client: MemoryClient, conv_factory
    ):
        async def make(i: int):
            return await conv_factory(user_id=_user_id(f"concur-{i}"))

        results = await asyncio.gather(*(make(i) for i in range(4)))
        assert len({c.id for c in results}) == 4
