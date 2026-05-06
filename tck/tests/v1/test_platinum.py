"""Volume 5 / Platinum tier tests — hosted-service operations.

Covers the operations exposed by the hosted Neo4j Agent Memory Service at
`https://memory.neo4jlabs.com/v1` that aren't in the bridge protocol's
Bronze / Silver / Gold tiers.

Implementations that don't expose these features should leave the BaseAdapter
default `NotImplementedError` in place; the Platinum tests will skip them.

Mark each test class with `@pytest.mark.platinum`.
"""

from __future__ import annotations

from uuid import uuid4

import pytest


def _skip_if_unsupported(call):
    """Wrap a test call so NotImplementedError → pytest.skip, not failure."""
    async def runner(*args, **kwargs):
        try:
            return await call(*args, **kwargs)
        except NotImplementedError as e:
            pytest.skip(f"adapter does not implement Platinum tier: {e}")
    return runner


@pytest.mark.platinum
class TestConversationLifecycle:
    """SPEC-5.1.x — explicit conversation create/list/delete + bulk add."""

    async def test_create_conversation_returns_uuid(self, adapter):
        """SPEC-5.1.1: create_conversation MUST return a Conversation with id, user_id."""
        try:
            conv = await adapter.create_conversation(user_id="alice")
        except NotImplementedError:
            pytest.skip("Platinum: create_conversation not supported")
        assert conv.id is not None
        assert conv.user_id == "alice" or conv.user_id is None  # workspace echo

    async def test_list_conversations(self, adapter):
        """SPEC-5.1.2: list_conversations returns conversations the key can see."""
        try:
            convs = await adapter.list_conversations(limit=10)
        except NotImplementedError:
            pytest.skip("Platinum: list_conversations not supported")
        assert isinstance(convs, list)

    async def test_delete_conversation_idempotent(self, adapter):
        """SPEC-5.1.3: delete_conversation is idempotent."""
        try:
            conv = await adapter.create_conversation(user_id="tck-delete")
            await adapter.delete_conversation(conv.id)
            # Second call should not raise.
            await adapter.delete_conversation(conv.id)
        except NotImplementedError:
            pytest.skip("Platinum: delete_conversation not supported")

    async def test_bulk_add_messages(self, adapter):
        """SPEC-5.1.4: bulk_add_messages preserves order, caps at 100."""
        try:
            conv = await adapter.create_conversation(user_id="tck-bulk")
            from tck.adapters.base_adapter import TCKBulkMessageInput
            inputs = [
                TCKBulkMessageInput(role="user", content=f"msg-{i}")
                for i in range(5)
            ]
            msgs = await adapter.bulk_add_messages(conv.id, inputs)
        except NotImplementedError:
            pytest.skip("Platinum: bulk_add_messages not supported")
        assert len(msgs) == 5
        assert msgs[0].content == "msg-0"
        assert msgs[-1].content == "msg-4"


@pytest.mark.platinum
class TestContext:
    """SPEC-5.2.x — three-tier context (reflections + observations + recent)."""

    async def test_get_context_shape(self, adapter):
        """SPEC-5.2.1: get_context returns the three sub-lists."""
        try:
            conv = await adapter.create_conversation(user_id="tck-ctx")
            await adapter.add_message(str(conv.id), "user", "Hello")
            ctx = await adapter.get_context(conv.id)
        except NotImplementedError:
            pytest.skip("Platinum: get_context not supported")
        assert hasattr(ctx, "reflections")
        assert hasattr(ctx, "observations")
        assert hasattr(ctx, "recent_messages")

    async def test_observations_may_be_empty(self, adapter):
        """SPEC-5.2.2: observations may be empty for new conversations."""
        try:
            conv = await adapter.create_conversation(user_id="tck-obs")
            obs = await adapter.get_observations(conv.id)
        except NotImplementedError:
            pytest.skip("Platinum: get_observations not supported")
        assert isinstance(obs, list)


@pytest.mark.platinum
class TestEntityFeedbackAndGraph:
    """SPEC-5.3.x — entity feedback, history, merge, graph."""

    async def test_set_entity_feedback_returns_updated(self, adapter):
        """SPEC-5.3.1: set_entity_feedback returns {id, updated}."""
        try:
            entity = await adapter.add_entity("Alice", "person", description="test")
            result = await adapter.set_entity_feedback(
                entity.id, user_score=0.9, confirmed=True
            )
        except NotImplementedError:
            pytest.skip("Platinum: set_entity_feedback not supported")
        assert hasattr(result, "id")
        assert hasattr(result, "updated")

    async def test_get_entity_graph(self, adapter):
        """SPEC-5.3.4: get_entity_graph returns nodes + edges."""
        try:
            graph = await adapter.get_entity_graph()
        except NotImplementedError:
            pytest.skip("Platinum: get_entity_graph not supported")
        assert hasattr(graph, "nodes")
        assert hasattr(graph, "edges")


@pytest.mark.platinum
class TestReasoningProvenance:
    """SPEC-5.4.x — flat reasoning steps + explain + provenance."""

    async def test_record_step_persists_under_conversation(self, adapter):
        """SPEC-5.4.1: record_step persists under the conversation."""
        try:
            conv = await adapter.create_conversation(user_id="tck-step")
            step = await adapter.record_step(
                conversation_id=conv.id,
                reasoning="hypothesizing X",
                action_taken="ran test",
                result="passed",
            )
        except NotImplementedError:
            pytest.skip("Platinum: record_step not supported")
        assert step.conversation_id == conv.id
        assert step.reasoning == "hypothesizing X"

    async def test_get_trace_by_conversation(self, adapter):
        """SPEC-5.4.3: get_trace_by_conversation returns steps + tool calls."""
        try:
            conv = await adapter.create_conversation(user_id="tck-trace")
            await adapter.record_step(
                conversation_id=conv.id, reasoning="r", action_taken="a"
            )
            trace = await adapter.get_trace_by_conversation(conv.id)
        except NotImplementedError:
            pytest.skip("Platinum: get_trace_by_conversation not supported")
        assert trace.conversation_id == conv.id


@pytest.mark.platinum
class TestCypherConsole:
    """SPEC-5.5.x — read-only Cypher query."""

    async def test_cypher_query_returns_columns_and_rows(self, adapter):
        """SPEC-5.5.1: cypher_query returns {columns, rows, stats}."""
        try:
            result = await adapter.cypher_query(
                "MATCH (n) RETURN count(n) AS total LIMIT 1"
            )
        except NotImplementedError:
            pytest.skip("Platinum: cypher_query not supported")
        assert hasattr(result, "columns")
        assert hasattr(result, "rows")
