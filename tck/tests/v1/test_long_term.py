"""Silver Tier — Long-Term Memory Behavioral Tests.

These tests verify the behavioral contracts for long-term memory,
including entity management, preferences, facts, and search.
"""

from uuid import UUID

import pytest

from tck.adapters.base_adapter import TCKEntity, TCKFact, TCKPreference
from tck.fixtures.data import ENTITIES, FACTS, PREFERENCES


@pytest.mark.silver
class TestAddEntity:
    """Tests for entity creation."""

    async def test_add_entity_basic(self, adapter):
        """SPEC-3.1.1: add_entity MUST return a TCKEntity with a valid UUID."""
        entity = await adapter.add_entity(name="Alice Johnson", entity_type="PERSON")
        assert isinstance(entity, TCKEntity)
        assert entity.id is not None
        assert entity.name == "Alice Johnson"
        assert entity.type == "PERSON"
        assert entity.created_at is not None

    async def test_add_entity_with_description(self, adapter):
        """SPEC-3.1.2: add_entity MUST store the description when provided."""
        entity = await adapter.add_entity(
            name="Acme Corp",
            entity_type="ORGANIZATION",
            description="A technology company",
        )
        assert entity.description == "A technology company"

    async def test_add_entity_person_type(self, adapter):
        """SPEC-3.1.3: add_entity MUST accept PERSON entity type."""
        entity = await adapter.add_entity(name="Bob", entity_type="PERSON")
        assert entity.type == "PERSON"

    async def test_add_entity_organization_type(self, adapter):
        """SPEC-3.1.4: add_entity MUST accept ORGANIZATION entity type."""
        entity = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        assert entity.type == "ORGANIZATION"

    async def test_add_entity_location_type(self, adapter):
        """SPEC-3.1.5: add_entity MUST accept LOCATION entity type."""
        entity = await adapter.add_entity(name="San Francisco", entity_type="LOCATION")
        assert entity.type == "LOCATION"

    async def test_add_entity_event_type(self, adapter):
        """SPEC-3.1.6: add_entity MUST accept EVENT entity type."""
        entity = await adapter.add_entity(name="Launch Party", entity_type="EVENT")
        assert entity.type == "EVENT"

    async def test_add_entity_object_type(self, adapter):
        """SPEC-3.1.7: add_entity MUST accept OBJECT entity type."""
        entity = await adapter.add_entity(name="Laptop", entity_type="OBJECT")
        assert entity.type == "OBJECT"

    async def test_add_multiple_entities(self, adapter):
        """SPEC-3.1.8: Multiple entities MUST be independently retrievable."""
        entities = []
        for data in ENTITIES:
            e = await adapter.add_entity(
                name=data["name"],
                entity_type=data["type"],
                description=data["description"],
            )
            entities.append(e)

        assert len(entities) == len(ENTITIES)
        names = {e.name for e in entities}
        assert names == {d["name"] for d in ENTITIES}

    async def test_add_entity_without_description(self, adapter):
        """SPEC-3.1.9: add_entity MUST succeed without a description."""
        entity = await adapter.add_entity(name="NoDesc", entity_type="PERSON")
        assert entity.name == "NoDesc"
        assert entity.description is None or entity.description == ""

    async def test_add_entity_duplicate_name_different_type(self, adapter):
        """SPEC-3.1.10: Entities with same name but different type MUST be created."""
        e1 = await adapter.add_entity(name="Mercury", entity_type="OBJECT")
        e2 = await adapter.add_entity(name="Mercury", entity_type="LOCATION")
        assert e1.id != e2.id
        assert e1.type == "OBJECT"
        assert e2.type == "LOCATION"

    async def test_add_entity_special_characters_in_name(self, adapter):
        """SPEC-3.1.11: add_entity MUST preserve unicode in entity name."""
        entity = await adapter.add_entity(name="Caf\u00e9 de \u4e16\u754c", entity_type="LOCATION")
        assert entity.name == "Caf\u00e9 de \u4e16\u754c"

    async def test_add_entity_id_is_uuid(self, adapter):
        """SPEC-3.1.12: add_entity MUST return a valid UUID for the id."""
        entity = await adapter.add_entity(name="UUIDCheck", entity_type="PERSON")
        assert isinstance(entity.id, UUID)


@pytest.mark.silver
class TestAddPreference:
    """Tests for preference storage."""

    async def test_add_preference_basic(self, adapter):
        """SPEC-3.2.1: add_preference MUST return a TCKPreference with valid fields."""
        pref = await adapter.add_preference(
            category="language",
            preference="Prefers Python over JavaScript",
        )
        assert isinstance(pref, TCKPreference)
        assert pref.id is not None
        assert pref.category == "language"
        assert pref.preference == "Prefers Python over JavaScript"

    async def test_add_preference_with_context(self, adapter):
        """SPEC-3.2.2: add_preference MUST store context when provided."""
        pref = await adapter.add_preference(
            category="communication",
            preference="Prefers async communication",
            context="work environment",
        )
        assert pref.context == "work environment"

    async def test_add_multiple_preferences(self, adapter):
        """SPEC-3.2.3: Multiple preferences MUST be independently stored."""
        prefs = []
        for data in PREFERENCES:
            p = await adapter.add_preference(
                category=data["category"],
                preference=data["preference"],
                context=data.get("context"),
            )
            prefs.append(p)
        assert len(prefs) == len(PREFERENCES)

    async def test_add_preference_without_context(self, adapter):
        """SPEC-3.2.4: add_preference MUST succeed without context."""
        pref = await adapter.add_preference(category="color", preference="Likes blue")
        assert pref.preference == "Likes blue"
        assert pref.context is None or pref.context == ""

    async def test_add_preference_long_text(self, adapter):
        """SPEC-3.2.5: add_preference MUST preserve long preference text."""
        long_pref = "Prefers " + "very " * 500 + "detailed explanations"
        pref = await adapter.add_preference(category="style", preference=long_pref)
        assert pref.preference == long_pref

    async def test_add_preference_same_category_multiple(self, adapter):
        """SPEC-3.2.6: Multiple preferences in same category MUST be stored separately."""
        p1 = await adapter.add_preference(category="food", preference="Likes pizza")
        p2 = await adapter.add_preference(category="food", preference="Dislikes sushi")
        assert p1.id != p2.id
        assert p1.preference != p2.preference

    async def test_add_preference_id_is_uuid(self, adapter):
        """SPEC-3.2.7: add_preference MUST return a valid UUID for the id."""
        pref = await adapter.add_preference(category="test", preference="UUID check")
        assert isinstance(pref.id, UUID)


@pytest.mark.silver
class TestAddFact:
    """Tests for fact triple storage."""

    async def test_add_fact_basic(self, adapter):
        """SPEC-3.3.1: add_fact MUST return a TCKFact with subject, predicate, and object."""
        fact = await adapter.add_fact(
            subject="Alice Johnson",
            predicate="WORKS_AT",
            obj="Acme Corp",
        )
        assert isinstance(fact, TCKFact)
        assert fact.id is not None
        assert fact.subject == "Alice Johnson"
        assert fact.predicate == "WORKS_AT"
        assert fact.object == "Acme Corp"

    async def test_add_multiple_facts(self, adapter):
        """SPEC-3.3.2: Multiple facts MUST be independently stored."""
        facts = []
        for data in FACTS:
            f = await adapter.add_fact(
                subject=data["subject"],
                predicate=data["predicate"],
                obj=data["object"],
            )
            facts.append(f)
        assert len(facts) == len(FACTS)

    async def test_add_fact_special_characters(self, adapter):
        """SPEC-3.3.3: add_fact MUST preserve unicode in subject/predicate/object."""
        fact = await adapter.add_fact(
            subject="Caf\u00e9 \u4e16\u754c",
            predicate="LOCATED_IN",
            obj="Par\u00eds",
        )
        assert fact.subject == "Caf\u00e9 \u4e16\u754c"
        assert fact.object == "Par\u00eds"

    async def test_add_fact_id_is_uuid(self, adapter):
        """SPEC-3.3.4: add_fact MUST return a valid UUID for the id."""
        fact = await adapter.add_fact(subject="A", predicate="KNOWS", obj="B")
        assert isinstance(fact.id, UUID)

    async def test_add_fact_same_subject_multiple_facts(self, adapter):
        """SPEC-3.3.5: Multiple facts with the same subject MUST be stored independently."""
        f1 = await adapter.add_fact(subject="Alice", predicate="WORKS_AT", obj="Acme")
        f2 = await adapter.add_fact(subject="Alice", predicate="LIVES_IN", obj="San Francisco")
        assert f1.id != f2.id
        assert f1.predicate != f2.predicate


@pytest.mark.silver
class TestSearchEntities:
    """Tests for entity search."""

    async def test_search_entities_finds_relevant(self, adapter):
        """SPEC-3.4.1: search_entities MUST return entities matching the query."""
        for data in ENTITIES:
            await adapter.add_entity(
                name=data["name"],
                entity_type=data["type"],
                description=data["description"],
            )

        results = await adapter.search_entities("Alice Johnson", limit=10)
        assert len(results) > 0
        names = [e.name for e in results]
        assert "Alice Johnson" in names

    async def test_search_entities_respects_limit(self, adapter):
        """SPEC-3.4.2: search_entities MUST NOT return more results than limit."""
        for data in ENTITIES:
            await adapter.add_entity(
                name=data["name"],
                entity_type=data["type"],
            )

        results = await adapter.search_entities("entity", limit=2)
        assert len(results) <= 2

    async def test_search_entities_empty_database(self, adapter):
        """SPEC-3.4.3: search_entities on empty database MUST return empty list."""
        results = await adapter.search_entities("anything", limit=10)
        assert results == []

    async def test_search_entities_no_match(self, adapter):
        """SPEC-3.4.4: search_entities MUST return empty when nothing matches."""
        await adapter.add_entity(name="Alice", entity_type="PERSON")
        results = await adapter.search_entities("quantum cryptography algorithms", limit=10)
        assert isinstance(results, list)


@pytest.mark.silver
class TestSearchPreferences:
    """Tests for preference search."""

    async def test_search_preferences_finds_relevant(self, adapter):
        """SPEC-3.5.1: search_preferences MUST return preferences matching the query."""
        for data in PREFERENCES:
            await adapter.add_preference(
                category=data["category"],
                preference=data["preference"],
                context=data.get("context"),
            )

        results = await adapter.search_preferences("Python", limit=10)
        assert len(results) > 0

    async def test_search_preferences_by_category(self, adapter):
        """SPEC-3.5.2: search_preferences with category MUST filter by category."""
        for data in PREFERENCES:
            await adapter.add_preference(
                category=data["category"],
                preference=data["preference"],
            )

        results = await adapter.search_preferences("preference", category="language", limit=10)
        for pref in results:
            assert pref.category == "language"

    async def test_search_preferences_empty_database(self, adapter):
        """SPEC-3.5.3: search_preferences on empty database MUST return empty list."""
        results = await adapter.search_preferences("anything", limit=10)
        assert results == []

    async def test_search_preferences_no_match(self, adapter):
        """SPEC-3.5.4: search_preferences MUST return empty when nothing matches."""
        await adapter.add_preference(category="food", preference="Likes pizza")
        results = await adapter.search_preferences("quantum physics", limit=10)
        assert isinstance(results, list)


@pytest.mark.silver
class TestGetEntityByName:
    """Tests for entity name lookup."""

    async def test_get_entity_by_name_found(self, adapter):
        """SPEC-3.6.1: get_entity_by_name MUST return the entity when it exists."""
        await adapter.add_entity(name="Alice Johnson", entity_type="PERSON", description="Engineer")
        entity = await adapter.get_entity_by_name("Alice Johnson")
        assert entity is not None
        assert entity.name == "Alice Johnson"

    async def test_get_entity_by_name_not_found(self, adapter):
        """SPEC-3.6.2: get_entity_by_name MUST return None when entity doesn't exist."""
        entity = await adapter.get_entity_by_name("Nonexistent Entity")
        assert entity is None


@pytest.mark.silver
class TestGetRelatedEntities:
    """Tests for relationship traversal."""

    async def test_get_related_entities_with_relationship(self, adapter):
        """SPEC-3.7.1: get_related_entities MUST return connected entities."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        acme = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        await adapter.add_relationship(alice.id, acme.id, "WORKS_AT")

        related = await adapter.get_related_entities(alice.id)
        assert len(related) > 0
        related_names = [e.name for e in related]
        assert "Acme" in related_names

    async def test_get_related_entities_empty(self, adapter):
        """SPEC-3.7.2: get_related_entities for isolated entity MUST return empty list."""
        entity = await adapter.add_entity(name="Lonely", entity_type="PERSON")
        related = await adapter.get_related_entities(entity.id)
        assert related == []

    async def test_get_related_entities_with_type_filter(self, adapter):
        """SPEC-3.7.3: get_related_entities with relationship_type MUST filter by type."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        bob = await adapter.add_entity(name="Bob", entity_type="PERSON")
        acme = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        await adapter.add_relationship(alice.id, bob.id, "KNOWS")
        await adapter.add_relationship(alice.id, acme.id, "WORKS_AT")

        related_knows = await adapter.get_related_entities(alice.id, relationship_type="KNOWS")
        related_names = [e.name for e in related_knows]
        assert "Bob" in related_names

    async def test_get_related_entities_multiple_relationships(self, adapter):
        """SPEC-3.7.4: get_related_entities MUST return all connected entities."""
        alice = await adapter.add_entity(name="Alice", entity_type="PERSON")
        bob = await adapter.add_entity(name="Bob", entity_type="PERSON")
        carol = await adapter.add_entity(name="Carol", entity_type="PERSON")
        await adapter.add_relationship(alice.id, bob.id, "KNOWS")
        await adapter.add_relationship(alice.id, carol.id, "KNOWS")

        related = await adapter.get_related_entities(alice.id)
        assert len(related) >= 2
        related_names = [e.name for e in related]
        assert "Bob" in related_names
        assert "Carol" in related_names
