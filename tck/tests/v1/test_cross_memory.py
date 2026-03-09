"""Gold Tier — Cross-Memory Integration Tests.

These tests verify that the three memory primitives work together
correctly, including entity references across memory types and
advanced operations like entity merging and similar trace search.
"""

import pytest

from tck.adapters.base_adapter import ToolCallStatus
from tck.fixtures.data import SESSION_A, TRACE_TASK


@pytest.mark.gold
class TestCrossMemoryEntityReferences:
    """Tests for entities referenced across memory types."""

    async def test_entity_in_long_term_and_reasoning(self, adapter):
        """SPEC-5.1.1: An entity created via long-term memory MUST be referenceable in reasoning."""
        entity = await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON", description="Engineer"
        )

        trace = await adapter.start_trace(SESSION_A, "Look up Alice")
        step = await adapter.add_step(trace.id, action="entity_lookup")
        await adapter.record_tool_call(
            step.id,
            "entity_lookup",
            {"name": "Alice Johnson"},
            result={"entity_id": str(entity.id)},
        )

        full_trace = await adapter.get_trace_with_steps(trace.id)
        assert len(full_trace.steps) == 1
        assert full_trace.steps[0].tool_calls[0].tool_name == "entity_lookup"

    async def test_end_to_end_conversation_with_entities_and_reasoning(self, adapter):
        """SPEC-5.1.2: Full flow from conversation to entity creation to reasoning."""
        # 1. Add messages to a conversation
        await adapter.add_message(SESSION_A, "user", "Tell me about Alice Johnson at Acme Corp")
        await adapter.add_message(SESSION_A, "assistant", "I'll look that up for you.")

        # 2. Create entities from the conversation content
        alice = await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON", description="Engineer"
        )
        acme = await adapter.add_entity(
            name="Acme Corp", entity_type="ORGANIZATION", description="Tech company"
        )

        # 3. Create relationship between entities
        await adapter.add_relationship(alice.id, acme.id, "WORKS_AT")

        # 4. Start reasoning trace to find information
        trace = await adapter.start_trace(SESSION_A, TRACE_TASK)
        step = await adapter.add_step(trace.id, thought="Search for Alice's employer")
        await adapter.record_tool_call(
            step.id,
            "get_related_entities",
            {"entity_id": str(alice.id)},
            result={"related": [{"name": "Acme Corp", "type": "ORGANIZATION"}]},
            status=ToolCallStatus.SUCCESS,
        )
        await adapter.complete_trace(trace.id, outcome="Alice works at Acme Corp", success=True)

        # 5. Verify everything is connected
        conv = await adapter.get_conversation(SESSION_A)
        assert len(conv.messages) == 2

        related = await adapter.get_related_entities(alice.id)
        assert len(related) > 0

        full_trace = await adapter.get_trace_with_steps(trace.id)
        assert full_trace.success is True


@pytest.mark.gold
class TestAddRelationship:
    """Tests for creating typed relationships between entities."""

    async def test_add_relationship_between_entities(self, adapter):
        """SPEC-5.2.1: add_relationship MUST create a typed edge between entities."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        acme = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")

        rel = await adapter.add_relationship(alice.id, acme.id, "WORKS_AT")
        assert rel.source_id == alice.id
        assert rel.target_id == acme.id
        assert rel.relationship_type == "WORKS_AT"

    async def test_add_relationship_bidirectional_traversal(self, adapter):
        """SPEC-5.2.2: Related entities MUST be discoverable from the source."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        bob = await adapter.add_entity(name="Bob", entity_type="PERSON")
        await adapter.add_relationship(alice.id, bob.id, "KNOWS")

        related = await adapter.get_related_entities(alice.id)
        related_names = [e.name for e in related]
        assert "Bob" in related_names


@pytest.mark.gold
class TestMergeDuplicateEntities:
    """Tests for entity deduplication merging."""

    async def test_merge_duplicate_entities(self, adapter):
        """SPEC-5.3.1: merge_duplicate_entities MUST combine two entities into one."""
        alice1 = await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON", description="Engineer"
        )
        alice2 = await adapter.add_entity(
            name="A. Johnson", entity_type="PERSON", description="Developer"
        )

        try:
            merged = await adapter.merge_duplicate_entities(alice2.id, alice1.id)
            assert merged.id == alice1.id
        except NotImplementedError:
            pytest.skip("merge_duplicate_entities not implemented (Gold tier)")


@pytest.mark.gold
class TestGetSimilarTraces:
    """Tests for finding similar reasoning traces."""

    async def test_get_similar_traces(self, adapter):
        """SPEC-5.4.1: get_similar_traces MUST return traces with similar tasks."""
        await adapter.start_trace(SESSION_A, "Find Alice Johnson's role at Acme Corp")
        trace2 = await adapter.start_trace(SESSION_A, "Look up Bob Smith's job")
        await adapter.complete_trace(trace2.id, outcome="Found it", success=True)

        try:
            similar = await adapter.get_similar_traces(
                "What is Alice's position at Acme?", limit=5
            )
            assert isinstance(similar, list)
        except NotImplementedError:
            pytest.skip("get_similar_traces not implemented (Gold tier)")
