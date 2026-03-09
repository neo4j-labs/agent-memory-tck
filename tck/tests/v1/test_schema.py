"""Bronze Tier — Schema Compliance Tests.

These tests verify that the implementation correctly creates and maintains
the core Context Graph schema through behavioral validation.
"""

import pytest

from tck.adapters.base_adapter import ToolCallStatus
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
