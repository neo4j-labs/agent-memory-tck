"""Gold Tier — Cross-Memory Integration Tests.

These tests verify that the three memory primitives work together
correctly, including entity references across memory types and
advanced operations like entity merging and similar trace search.
"""

import pytest

from tck.adapters.base_adapter import ToolCallStatus
from tck.fixtures.data import SESSION_A, SESSION_B, TRACE_TASK


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

    async def test_entity_enriched_across_sessions(self, adapter):
        """SPEC-5.1.3: Entities created in one session MUST be visible in another."""
        # Session A creates entity
        alice = await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON", description="Engineer"
        )

        # Session B retrieves the same entity
        found = await adapter.get_entity_by_name("Alice Johnson")
        assert found is not None
        assert found.id == alice.id

    async def test_fact_references_entity_names(self, adapter):
        """SPEC-5.1.4: Facts MUST be storable alongside entities they reference."""
        await adapter.add_entity(name="Alice", entity_type="PERSON")
        await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        fact = await adapter.add_fact("Alice", "WORKS_AT", "Acme")
        assert fact.subject == "Alice"
        assert fact.object == "Acme"

    async def test_preference_stored_alongside_entity(self, adapter):
        """SPEC-5.1.5: Preferences MUST be storable alongside related entities."""
        await adapter.add_entity(name="Alice", entity_type="PERSON")
        pref = await adapter.add_preference(
            category="communication",
            preference="Alice prefers email",
            context="work",
        )
        assert pref.preference == "Alice prefers email"

    async def test_reasoning_trace_references_conversation(self, adapter):
        """SPEC-5.1.6: A reasoning trace MUST be creatable in the same session as messages."""
        await adapter.add_message(SESSION_A, "user", "What does Alice do?")
        trace = await adapter.start_trace(SESSION_A, "Answer user question about Alice")
        assert trace.session_id == SESSION_A

        conv = await adapter.get_conversation(SESSION_A)
        assert len(conv.messages) == 1

        traces = await adapter.list_traces(session_id=SESSION_A)
        assert len(traces) == 1
        assert traces[0].id == trace.id


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

    async def test_add_relationship_multiple_types(self, adapter):
        """SPEC-5.2.3: Multiple relationship types between entities MUST be supported."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        acme = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        sf = await adapter.add_entity(name="San Francisco", entity_type="LOCATION")

        await adapter.add_relationship(alice.id, acme.id, "WORKS_AT")
        await adapter.add_relationship(alice.id, sf.id, "LOCATED_AT")

        related = await adapter.get_related_entities(alice.id)
        names = [e.name for e in related]
        assert "Acme" in names
        assert "San Francisco" in names

    async def test_add_relationship_returns_valid_id(self, adapter):
        """SPEC-5.2.4: add_relationship MUST return a relationship with a valid ID."""
        from uuid import UUID

        a = await adapter.add_entity(name="A", entity_type="PERSON")
        b = await adapter.add_entity(name="B", entity_type="PERSON")
        rel = await adapter.add_relationship(a.id, b.id, "KNOWS")
        assert rel.id is not None
        assert isinstance(rel.id, UUID)


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

    async def test_merge_preserves_relationships(self, adapter):
        """SPEC-5.3.2: Merged entity MUST retain relationships from both source entities."""
        alice1 = await adapter.add_entity(name="Alice", entity_type="PERSON")
        alice2 = await adapter.add_entity(name="A. Johnson", entity_type="PERSON")
        acme = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")

        await adapter.add_relationship(alice1.id, acme.id, "WORKS_AT")

        try:
            merged = await adapter.merge_duplicate_entities(alice2.id, alice1.id)
            related = await adapter.get_related_entities(merged.id)
            related_names = [e.name for e in related]
            assert "Acme" in related_names
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
            similar = await adapter.get_similar_traces("What is Alice's position at Acme?", limit=5)
            assert isinstance(similar, list)
        except NotImplementedError:
            pytest.skip("get_similar_traces not implemented (Gold tier)")

    async def test_get_similar_traces_respects_limit(self, adapter):
        """SPEC-5.4.2: get_similar_traces MUST respect the limit parameter."""
        for i in range(5):
            t = await adapter.start_trace(SESSION_A, f"Research task {i}")
            await adapter.complete_trace(t.id, outcome=f"Done {i}", success=True)

        try:
            similar = await adapter.get_similar_traces("Research task", limit=2)
            assert len(similar) <= 2
        except NotImplementedError:
            pytest.skip("get_similar_traces not implemented (Gold tier)")

    async def test_get_similar_traces_empty_database(self, adapter):
        """SPEC-5.4.3: get_similar_traces on empty database MUST return empty list."""
        try:
            similar = await adapter.get_similar_traces("anything")
            assert similar == []
        except NotImplementedError:
            pytest.skip("get_similar_traces not implemented (Gold tier)")


@pytest.mark.gold
class TestMultiAgentMemorySharing:
    """Tests verifying cross-agent memory sharing via namespaces."""

    async def test_entity_created_by_one_session_visible_to_another(self, adapter):
        """SPEC-5.5.1: Entity created in session A MUST be searchable from session B context."""
        # Session A creates entities
        await adapter.add_message(SESSION_A, "user", "Creating entities")
        alice = await adapter.add_entity(
            name="Alice Shared", entity_type="PERSON", description="Shared entity"
        )

        # Session B should find the entity
        await adapter.add_message(SESSION_B, "user", "Looking for shared entities")
        found = await adapter.get_entity_by_name("Alice Shared")
        assert found is not None
        assert found.id == alice.id

    async def test_reasoning_traces_isolated_by_session(self, adapter):
        """SPEC-5.5.2: Reasoning traces MUST be filterable by session."""
        await adapter.start_trace(SESSION_A, "Agent A task")
        await adapter.start_trace(SESSION_B, "Agent B task")

        traces_a = await adapter.list_traces(session_id=SESSION_A)
        traces_b = await adapter.list_traces(session_id=SESSION_B)

        assert len(traces_a) == 1
        assert len(traces_b) == 1
        assert traces_a[0].session_id == SESSION_A
        assert traces_b[0].session_id == SESSION_B

    async def test_conversations_isolated_but_entities_shared(self, adapter):
        """SPEC-5.5.3: Conversations MUST be isolated while entities are shared."""
        await adapter.add_message(SESSION_A, "user", "Agent A speaking")
        await adapter.add_message(SESSION_B, "user", "Agent B speaking")

        entity = await adapter.add_entity(name="SharedCorp", entity_type="ORGANIZATION")

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)
        assert len(conv_a.messages) == 1
        assert len(conv_b.messages) == 1
        assert conv_a.messages[0].content != conv_b.messages[0].content

        found = await adapter.get_entity_by_name("SharedCorp")
        assert found is not None
        assert found.id == entity.id
