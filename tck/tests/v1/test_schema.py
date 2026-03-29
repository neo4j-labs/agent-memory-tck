"""Bronze Tier — Schema Compliance Tests.

These tests verify that the implementation correctly creates and maintains
the core Context Graph schema through behavioral validation.
"""

from datetime import datetime, timedelta, timezone
from uuid import UUID

import pytest

from tck.fixtures.data import ENTITIES, SESSION_A, SESSION_B


@pytest.mark.bronze
class TestSchemaConversationCreation:
    """Verify that conversations are auto-created when messages are added."""

    async def test_first_message_creates_conversation(self, adapter, session_id):
        """SPEC-1.1.1: Adding a message to a new session MUST create a Conversation node."""
        await adapter.add_message(session_id, "user", "Hello")
        conv = await adapter.get_conversation(session_id)
        assert conv is not None
        assert conv.session_id == session_id
        assert len(conv.messages) == 1

    async def test_subsequent_messages_reuse_conversation(self, adapter, session_id):
        """SPEC-1.1.2: Subsequent messages in the same session MUST reuse the existing Conversation."""
        await adapter.add_message(session_id, "user", "First message")
        await adapter.add_message(session_id, "assistant", "Second message")
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2

    async def test_conversation_id_is_valid_uuid(self, adapter, session_id):
        """SPEC-1.1.5: Conversation MUST have a valid UUID id property."""
        await adapter.add_message(session_id, "user", "Hello")
        conv = await adapter.get_conversation(session_id)
        assert isinstance(conv.id, UUID)

    async def test_conversation_created_at_is_set(self, adapter, session_id):
        """SPEC-1.1.6: Conversation MUST have a created_at timestamp."""
        before = datetime.now(timezone.utc) - timedelta(seconds=5)
        await adapter.add_message(session_id, "user", "Hello")
        conv = await adapter.get_conversation(session_id)
        assert conv.created_at is not None
        ts = conv.created_at
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        assert ts >= before

    async def test_conversation_title_is_optional(self, adapter, session_id):
        """SPEC-1.1.7: Conversation title MAY be None by default."""
        await adapter.add_message(session_id, "user", "Hello")
        conv = await adapter.get_conversation(session_id)
        # title is optional; either None or a string is acceptable
        assert conv.title is None or isinstance(conv.title, str)

    async def test_conversation_reuses_same_id_across_messages(self, adapter, session_id):
        """SPEC-1.1.8: The same session MUST yield the same conversation ID across calls."""
        await adapter.add_message(session_id, "user", "First")
        conv1 = await adapter.get_conversation(session_id)

        await adapter.add_message(session_id, "user", "Second")
        conv2 = await adapter.get_conversation(session_id)

        assert conv1.id == conv2.id


@pytest.mark.bronze
class TestSchemaSessionIsolation:
    """Verify that sessions are isolated from each other."""

    async def test_messages_isolated_between_sessions(self, adapter):
        """SPEC-1.1.3: Messages in different sessions MUST NOT be visible to each other."""
        await adapter.add_message(SESSION_A, "user", "Message in session A")
        await adapter.add_message(SESSION_B, "user", "Message in session B")

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)

        assert len(conv_a.messages) == 1
        assert len(conv_b.messages) == 1
        assert conv_a.messages[0].content == "Message in session A"
        assert conv_b.messages[0].content == "Message in session B"

    async def test_sessions_have_different_conversation_ids(self, adapter):
        """SPEC-1.1.9: Different sessions MUST produce different Conversation nodes."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta")

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)

        assert conv_a.id != conv_b.id


@pytest.mark.bronze
class TestSchemaMessageDeletion:
    """Verify that message deletion works correctly."""

    async def test_deleted_message_not_retrievable(self, adapter, session_id):
        """SPEC-1.1.4: A deleted message MUST NOT appear in conversation retrieval."""
        msg = await adapter.add_message(session_id, "user", "Delete me")
        deleted = await adapter.delete_message(msg.id)
        assert deleted is True

        conv = await adapter.get_conversation(session_id)
        msg_ids = [m.id for m in conv.messages]
        assert msg.id not in msg_ids

    async def test_deletion_does_not_affect_other_messages(self, adapter, session_id):
        """SPEC-1.1.10: Deleting one message MUST NOT alter other messages in the conversation."""
        await adapter.add_message(session_id, "user", "Keep me")
        msg2 = await adapter.add_message(session_id, "user", "Delete me")
        await adapter.add_message(session_id, "user", "Keep me too")

        await adapter.delete_message(msg2.id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2
        assert conv.messages[0].content == "Keep me"
        assert conv.messages[1].content == "Keep me too"


@pytest.mark.bronze
class TestSchemaMessageProperties:
    """Verify that message nodes have all required properties."""

    async def test_message_has_id(self, adapter, session_id):
        """SPEC-1.1.11: Message MUST have a non-null id property."""
        msg = await adapter.add_message(session_id, "user", "Hello")
        assert msg.id is not None

    async def test_message_has_role(self, adapter, session_id):
        """SPEC-1.1.12: Message MUST have a role property matching the input role."""
        msg = await adapter.add_message(session_id, "user", "Hello")
        assert msg.role == "user"

    async def test_message_has_content(self, adapter, session_id):
        """SPEC-1.1.13: Message MUST have a content property matching the input content."""
        msg = await adapter.add_message(session_id, "user", "Hello world")
        assert msg.content == "Hello world"

    async def test_message_has_timestamp(self, adapter, session_id):
        """SPEC-1.1.14: Message MUST have a non-null timestamp property."""
        msg = await adapter.add_message(session_id, "user", "Hello")
        assert msg.timestamp is not None
        assert isinstance(msg.timestamp, datetime)


@pytest.mark.bronze
class TestSchemaEntityCreation:
    """Verify basic entity creation through the adapter."""

    async def test_entity_created_with_required_fields(self, adapter):
        """SPEC-1.2.1: Entity nodes MUST have id, name, type, and created_at properties."""
        entity_data = ENTITIES[0]
        entity = await adapter.add_entity(
            name=entity_data["name"],
            entity_type=entity_data["type"],
            description=entity_data["description"],
        )
        assert entity.id is not None
        assert entity.name == entity_data["name"]
        assert entity.type == entity_data["type"]
        assert entity.created_at is not None

    async def test_entity_id_is_valid_uuid(self, adapter):
        """SPEC-1.2.2: Entity id MUST be a valid UUID."""
        entity = await adapter.add_entity(name="Test Entity", entity_type="PERSON")
        assert isinstance(entity.id, UUID)

    async def test_entity_person_type_schema(self, adapter):
        """SPEC-1.2.3: PERSON entity MUST have correct type label."""
        entity = await adapter.add_entity(name="Alice", entity_type="PERSON")
        assert entity.type == "PERSON"
        assert entity.name == "Alice"

    async def test_entity_organization_type_schema(self, adapter):
        """SPEC-1.2.4: ORGANIZATION entity MUST have correct type label."""
        entity = await adapter.add_entity(name="Acme", entity_type="ORGANIZATION")
        assert entity.type == "ORGANIZATION"

    async def test_entity_location_type_schema(self, adapter):
        """SPEC-1.2.5: LOCATION entity MUST have correct type label."""
        entity = await adapter.add_entity(name="NYC", entity_type="LOCATION")
        assert entity.type == "LOCATION"

    async def test_entity_event_type_schema(self, adapter):
        """SPEC-1.2.6: EVENT entity MUST have correct type label."""
        entity = await adapter.add_entity(name="Launch", entity_type="EVENT")
        assert entity.type == "EVENT"

    async def test_entity_object_type_schema(self, adapter):
        """SPEC-1.2.7: OBJECT entity MUST have correct type label."""
        entity = await adapter.add_entity(name="Laptop", entity_type="OBJECT")
        assert entity.type == "OBJECT"

    async def test_entity_created_at_is_set(self, adapter):
        """SPEC-1.2.8: Entity created_at MUST be a non-null timestamp."""
        entity = await adapter.add_entity(name="Timestamped", entity_type="PERSON")
        assert entity.created_at is not None
        assert isinstance(entity.created_at, datetime)


@pytest.mark.bronze
class TestSchemaPreferenceCreation:
    """Verify basic preference creation schema."""

    async def test_preference_has_required_fields(self, adapter):
        """SPEC-1.3.1: Preference MUST have id, category, and preference properties."""
        pref = await adapter.add_preference(category="language", preference="Prefers Python")
        assert pref.id is not None
        assert pref.category == "language"
        assert pref.preference == "Prefers Python"

    async def test_preference_id_is_valid_uuid(self, adapter):
        """SPEC-1.3.2: Preference id MUST be a valid UUID."""
        pref = await adapter.add_preference(category="food", preference="Likes pizza")
        assert isinstance(pref.id, UUID)


@pytest.mark.bronze
class TestSchemaFactCreation:
    """Verify basic fact creation schema."""

    async def test_fact_has_required_fields(self, adapter):
        """SPEC-1.4.1: Fact MUST have id, subject, predicate, and object properties."""
        fact = await adapter.add_fact(subject="Alice", predicate="WORKS_AT", obj="Acme")
        assert fact.id is not None
        assert fact.subject == "Alice"
        assert fact.predicate == "WORKS_AT"
        assert fact.object == "Acme"

    async def test_fact_id_is_valid_uuid(self, adapter):
        """SPEC-1.4.2: Fact id MUST be a valid UUID."""
        fact = await adapter.add_fact(subject="Bob", predicate="KNOWS", obj="Alice")
        assert isinstance(fact.id, UUID)
