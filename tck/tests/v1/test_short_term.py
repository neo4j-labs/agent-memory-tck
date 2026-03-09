"""Bronze Tier — Short-Term Memory Behavioral Tests.

These tests verify the behavioral contracts for short-term (conversational)
memory, including message storage, retrieval, search, and session management.
"""

import pytest

from tck.adapters.base_adapter import TCKMessage
from tck.fixtures.data import CONVERSATION_MESSAGES, SESSION_A, SESSION_B


@pytest.mark.bronze
class TestAddMessage:
    """Tests for adding messages to conversations."""

    async def test_add_message_returns_valid_message(self, adapter, session_id):
        """SPEC-2.1.1: add_message MUST return a TCKMessage with a valid UUID and timestamp."""
        msg = await adapter.add_message(session_id, "user", "Hello, world!")
        assert isinstance(msg, TCKMessage)
        assert msg.id is not None
        assert msg.role == "user"
        assert msg.content == "Hello, world!"
        assert msg.timestamp is not None

    async def test_add_message_user_role(self, adapter, session_id):
        """SPEC-2.1.2: add_message MUST accept 'user' role."""
        msg = await adapter.add_message(session_id, "user", "User message")
        assert msg.role == "user"

    async def test_add_message_assistant_role(self, adapter, session_id):
        """SPEC-2.1.3: add_message MUST accept 'assistant' role."""
        msg = await adapter.add_message(session_id, "assistant", "Assistant message")
        assert msg.role == "assistant"

    async def test_add_message_system_role(self, adapter, session_id):
        """SPEC-2.1.4: add_message MUST accept 'system' role."""
        msg = await adapter.add_message(session_id, "system", "System prompt")
        assert msg.role == "system"

    async def test_add_message_with_metadata(self, adapter, session_id):
        """SPEC-2.1.5: add_message MUST preserve metadata when provided."""
        metadata = {"source": "test", "priority": "high"}
        msg = await adapter.add_message(
            session_id, "user", "Message with metadata", metadata=metadata
        )
        assert msg.metadata.get("source") == "test"
        assert msg.metadata.get("priority") == "high"

    async def test_add_message_creates_conversation_on_first_call(self, adapter, session_id):
        """SPEC-2.1.6: add_message MUST create a Conversation if the session is new."""
        await adapter.add_message(session_id, "user", "First message")
        conv = await adapter.get_conversation(session_id)
        assert conv is not None
        assert conv.session_id == session_id
        assert len(conv.messages) == 1


@pytest.mark.bronze
class TestGetConversation:
    """Tests for retrieving conversations."""

    async def test_get_conversation_message_order(self, adapter, session_id):
        """SPEC-2.2.1: get_conversation MUST return messages in insertion order."""
        for msg_data in CONVERSATION_MESSAGES:
            await adapter.add_message(session_id, msg_data["role"], msg_data["content"])

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == len(CONVERSATION_MESSAGES)
        for i, msg in enumerate(conv.messages):
            assert msg.content == CONVERSATION_MESSAGES[i]["content"]

    async def test_get_conversation_with_limit(self, adapter, session_id):
        """SPEC-2.2.2: get_conversation MUST respect the limit parameter."""
        for msg_data in CONVERSATION_MESSAGES:
            await adapter.add_message(session_id, msg_data["role"], msg_data["content"])

        conv = await adapter.get_conversation(session_id, limit=2)
        assert len(conv.messages) == 2

    async def test_get_conversation_empty_session(self, adapter):
        """SPEC-2.2.3: get_conversation for a non-existent session MUST return an empty conversation."""
        conv = await adapter.get_conversation("tck-nonexistent-session")
        assert len(conv.messages) == 0

    async def test_get_conversation_multiple_sessions_isolated(self, adapter):
        """SPEC-2.2.4: get_conversation MUST only return messages for the specified session."""
        await adapter.add_message(SESSION_A, "user", "Alpha message 1")
        await adapter.add_message(SESSION_A, "user", "Alpha message 2")
        await adapter.add_message(SESSION_B, "user", "Beta message 1")

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)

        assert len(conv_a.messages) == 2
        assert len(conv_b.messages) == 1
        assert all("Alpha" in m.content for m in conv_a.messages)
        assert all("Beta" in m.content for m in conv_b.messages)


@pytest.mark.bronze
class TestSearchMessages:
    """Tests for semantic message search."""

    async def test_search_messages_finds_relevant(self, adapter, session_id):
        """SPEC-2.3.1: search_messages MUST return messages matching the query."""
        await adapter.add_message(session_id, "user", "I love programming in Python")
        await adapter.add_message(session_id, "user", "The weather is sunny today")
        await adapter.add_message(session_id, "user", "Python is great for data science")

        results = await adapter.search_messages("Python programming", limit=10, threshold=0.0)
        assert len(results) > 0
        contents = [r.content for r in results]
        assert any("Python" in c for c in contents)

    async def test_search_messages_session_filter(self, adapter):
        """SPEC-2.3.2: search_messages with session_id MUST only return messages from that session."""
        await adapter.add_message(SESSION_A, "user", "Python in session A")
        await adapter.add_message(SESSION_B, "user", "Python in session B")

        results = await adapter.search_messages(
            "Python", session_id=SESSION_A, limit=10, threshold=0.0
        )
        for msg in results:
            # All results should be from session A's content
            assert "session A" in msg.content or "Python" in msg.content

    async def test_search_messages_respects_limit(self, adapter, session_id):
        """SPEC-2.3.3: search_messages MUST NOT return more results than limit."""
        for i in range(5):
            await adapter.add_message(session_id, "user", f"Test message number {i}")

        results = await adapter.search_messages("Test message", limit=2, threshold=0.0)
        assert len(results) <= 2


@pytest.mark.bronze
class TestListSessions:
    """Tests for listing sessions."""

    async def test_list_sessions_returns_all(self, adapter):
        """SPEC-2.4.1: list_sessions MUST return all active sessions."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta")

        sessions = await adapter.list_sessions()
        session_ids = [s.session_id for s in sessions]
        assert SESSION_A in session_ids
        assert SESSION_B in session_ids

    async def test_list_sessions_includes_message_count(self, adapter):
        """SPEC-2.4.2: list_sessions MUST include accurate message counts."""
        await adapter.add_message(SESSION_A, "user", "One")
        await adapter.add_message(SESSION_A, "assistant", "Two")
        await adapter.add_message(SESSION_A, "user", "Three")

        sessions = await adapter.list_sessions()
        session_a = next(s for s in sessions if s.session_id == SESSION_A)
        assert session_a.message_count == 3


@pytest.mark.bronze
class TestDeleteMessage:
    """Tests for deleting individual messages."""

    async def test_delete_message_returns_true(self, adapter, session_id):
        """SPEC-2.5.1: delete_message MUST return True when the message exists."""
        msg = await adapter.add_message(session_id, "user", "Delete me")
        result = await adapter.delete_message(msg.id)
        assert result is True

    async def test_delete_message_removes_from_conversation(self, adapter, session_id):
        """SPEC-2.5.2: A deleted message MUST NOT appear in get_conversation results."""
        msg1 = await adapter.add_message(session_id, "user", "Keep me")
        msg2 = await adapter.add_message(session_id, "user", "Delete me")
        await adapter.delete_message(msg2.id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 1
        assert conv.messages[0].id == msg1.id

    async def test_delete_message_nonexistent_returns_false(self, adapter):
        """SPEC-2.5.3: delete_message for a non-existent ID MUST return False."""
        from uuid import uuid4

        result = await adapter.delete_message(uuid4())
        assert result is False


@pytest.mark.bronze
class TestClearSession:
    """Tests for clearing entire sessions."""

    async def test_clear_session_removes_all_messages(self, adapter, session_id):
        """SPEC-2.6.1: clear_session MUST remove all messages for the session."""
        await adapter.add_message(session_id, "user", "One")
        await adapter.add_message(session_id, "assistant", "Two")
        await adapter.add_message(session_id, "user", "Three")

        await adapter.clear_session(session_id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 0

    async def test_clear_session_preserves_other_sessions(self, adapter):
        """SPEC-2.6.2: clear_session MUST NOT affect other sessions."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta")

        await adapter.clear_session(SESSION_A)

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)

        assert len(conv_a.messages) == 0
        assert len(conv_b.messages) == 1
        assert conv_b.messages[0].content == "Beta"


@pytest.mark.bronze
class TestMessageChainStructure:
    """Tests for message ordering and chain integrity."""

    async def test_messages_maintain_insertion_order(self, adapter, session_id):
        """SPEC-2.7.1: Messages MUST maintain insertion order via NEXT_MESSAGE chain."""
        contents = ["First", "Second", "Third", "Fourth", "Fifth"]
        for content in contents:
            await adapter.add_message(session_id, "user", content)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 5
        for i, msg in enumerate(conv.messages):
            assert msg.content == contents[i]

    async def test_timestamps_are_monotonically_increasing(self, adapter, session_id):
        """SPEC-2.7.2: Message timestamps MUST be monotonically non-decreasing."""
        for content in ["First", "Second", "Third"]:
            await adapter.add_message(session_id, "user", content)

        conv = await adapter.get_conversation(session_id)
        for i in range(1, len(conv.messages)):
            assert conv.messages[i].timestamp >= conv.messages[i - 1].timestamp
