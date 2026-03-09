"""Silver Tier — Long-Term Memory Behavioral Tests.

These tests verify the behavioral contracts for long-term memory,
including entity management, preferences, facts, and search.
"""

import pytest

from tck.adapters.base_adapter import TCKEntity, TCKFact, TCKPreference
from tck.fixtures.data import ENTITIES, FACTS, PREFERENCES


@pytest.mark.silver
class TestAddEntity:
    """Tests for entity creation."""

    async def test_add_entity_basic(self, adapter):
        """SPEC-3.1.1: add_entity MUST return a TCKEntity with a valid UUID."""
        entity = await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON"
        )
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

        results = await adapter.search_preferences(
            "preference", category="language", limit=10
        )
        for pref in results:
            assert pref.category == "language"


@pytest.mark.silver
class TestGetEntityByName:
    """Tests for entity name lookup."""

    async def test_get_entity_by_name_found(self, adapter):
        """SPEC-3.6.1: get_entity_by_name MUST return the entity when it exists."""
        await adapter.add_entity(
            name="Alice Johnson", entity_type="PERSON", description="Engineer"
        )
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
        await adapter.add_relationship(
            alice.id, acme.id, "WORKS_AT"
        )

        related = await adapter.get_related_entities(alice.id)
        assert len(related) > 0
        related_names = [e.name for e in related]
        assert "Acme" in related_names

    async def test_get_related_entities_empty(self, adapter):
        """SPEC-3.7.2: get_related_entities for isolated entity MUST return empty list."""
        entity = await adapter.add_entity(name="Lonely", entity_type="PERSON")
        related = await adapter.get_related_entities(entity.id)
        assert related == []
