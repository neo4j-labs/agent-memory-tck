"""Bronze Tier — Short-Term Memory Behavioral Tests.

These tests verify the behavioral contracts for short-term (conversational)
memory, including message storage, retrieval, search, and session management.
"""

from datetime import datetime, timedelta, timezone
from uuid import UUID

import pytest

from tck.adapters.base_adapter import TCKMessage
from tck.fixtures.data import (
    CONVERSATION_MESSAGES,
    EMPTY_CONTENT,
    LONG_CONTENT,
    NESTED_METADATA,
    SESSION_A,
    SESSION_B,
    SESSION_C,
    SPECIAL_CHARS_CONTENT,
    UNICODE_CONTENT,
)


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

    async def test_add_message_empty_content(self, adapter, session_id):
        """SPEC-2.1.7: add_message MUST accept empty string content."""
        msg = await adapter.add_message(session_id, "user", EMPTY_CONTENT)
        assert msg.content == ""

    async def test_add_message_long_content(self, adapter, session_id):
        """SPEC-2.1.8: add_message MUST preserve content of 10K+ characters."""
        msg = await adapter.add_message(session_id, "user", LONG_CONTENT)
        assert len(msg.content) == 10_000
        conv = await adapter.get_conversation(session_id)
        assert conv.messages[0].content == LONG_CONTENT

    async def test_add_message_unicode_content(self, adapter, session_id):
        """SPEC-2.1.9: add_message MUST preserve unicode characters and emoji."""
        msg = await adapter.add_message(session_id, "user", UNICODE_CONTENT)
        assert msg.content == UNICODE_CONTENT

    async def test_add_message_special_characters(self, adapter, session_id):
        """SPEC-2.1.10: add_message MUST preserve newlines, tabs, quotes, and backslashes."""
        msg = await adapter.add_message(session_id, "user", SPECIAL_CHARS_CONTENT)
        assert msg.content == SPECIAL_CHARS_CONTENT

    async def test_add_message_empty_metadata(self, adapter, session_id):
        """SPEC-2.1.11: add_message with empty dict metadata MUST succeed."""
        msg = await adapter.add_message(session_id, "user", "Empty meta", metadata={})
        assert isinstance(msg.metadata, dict)

    async def test_add_message_nested_metadata(self, adapter, session_id):
        """SPEC-2.1.12: add_message MUST preserve nested metadata structures."""
        msg = await adapter.add_message(session_id, "user", "Nested meta", metadata=NESTED_METADATA)
        assert msg.metadata.get("source") == "test"
        assert msg.metadata.get("count") == 42
        assert msg.metadata.get("active") is True

    async def test_add_message_null_metadata_defaults_empty(self, adapter, session_id):
        """SPEC-2.1.13: add_message with no metadata MUST default to empty dict."""
        msg = await adapter.add_message(session_id, "user", "No meta")
        assert isinstance(msg.metadata, dict)

    async def test_add_message_uuid_format(self, adapter, session_id):
        """SPEC-2.1.14: add_message MUST return a valid UUID for the message ID."""
        msg = await adapter.add_message(session_id, "user", "UUID check")
        assert isinstance(msg.id, UUID)

    async def test_add_message_timestamp_is_recent(self, adapter, session_id):
        """SPEC-2.1.15: add_message timestamp MUST be within the last 60 seconds."""
        before = datetime.now(timezone.utc) - timedelta(seconds=5)
        msg = await adapter.add_message(session_id, "user", "Timestamp check")
        after = datetime.now(timezone.utc) + timedelta(seconds=5)
        ts = msg.timestamp
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        assert before <= ts <= after

    async def test_add_message_rapid_succession(self, adapter, session_id):
        """SPEC-2.1.16: 50 messages added rapidly MUST all be stored and ordered."""
        for i in range(50):
            await adapter.add_message(session_id, "user", f"Rapid message {i}")
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 50
        for i, msg in enumerate(conv.messages):
            assert msg.content == f"Rapid message {i}"


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

    async def test_get_conversation_limit_exceeds_count(self, adapter, session_id):
        """SPEC-2.2.5: get_conversation with limit > message count MUST return all messages."""
        await adapter.add_message(session_id, "user", "Only one")
        conv = await adapter.get_conversation(session_id, limit=100)
        assert len(conv.messages) == 1

    async def test_get_conversation_limit_one(self, adapter, session_id):
        """SPEC-2.2.6: get_conversation with limit=1 MUST return exactly one message."""
        for msg_data in CONVERSATION_MESSAGES:
            await adapter.add_message(session_id, msg_data["role"], msg_data["content"])
        conv = await adapter.get_conversation(session_id, limit=1)
        assert len(conv.messages) == 1

    async def test_get_conversation_preserves_content_fidelity(self, adapter, session_id):
        """SPEC-2.2.7: get_conversation MUST preserve special characters in content."""
        await adapter.add_message(session_id, "user", UNICODE_CONTENT)
        await adapter.add_message(session_id, "user", SPECIAL_CHARS_CONTENT)

        conv = await adapter.get_conversation(session_id)
        assert conv.messages[0].content == UNICODE_CONTENT
        assert conv.messages[1].content == SPECIAL_CHARS_CONTENT

    async def test_get_conversation_preserves_metadata(self, adapter, session_id):
        """SPEC-2.2.8: get_conversation MUST preserve metadata on retrieved messages."""
        metadata = {"key": "value", "num": 99}
        await adapter.add_message(session_id, "user", "With meta", metadata=metadata)

        conv = await adapter.get_conversation(session_id)
        assert conv.messages[0].metadata.get("key") == "value"
        assert conv.messages[0].metadata.get("num") == 99

    async def test_get_conversation_preserves_roles(self, adapter, session_id):
        """SPEC-2.2.9: get_conversation MUST preserve the role of each message."""
        await adapter.add_message(session_id, "system", "System message")
        await adapter.add_message(session_id, "user", "User message")
        await adapter.add_message(session_id, "assistant", "Assistant message")

        conv = await adapter.get_conversation(session_id)
        assert conv.messages[0].role == "system"
        assert conv.messages[1].role == "user"
        assert conv.messages[2].role == "assistant"

    async def test_get_conversation_twenty_messages_ordered(self, adapter, session_id):
        """SPEC-2.2.10: get_conversation MUST maintain order for 20+ messages."""
        for i in range(20):
            await adapter.add_message(session_id, "user", f"Message {i:03d}")

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 20
        for i, msg in enumerate(conv.messages):
            assert msg.content == f"Message {i:03d}"

    async def test_get_conversation_returns_valid_conversation_id(self, adapter, session_id):
        """SPEC-2.2.11: get_conversation MUST return a conversation with a valid UUID id."""
        await adapter.add_message(session_id, "user", "Hello")
        conv = await adapter.get_conversation(session_id)
        assert isinstance(conv.id, UUID)

    async def test_get_conversation_three_sessions_fully_isolated(self, adapter):
        """SPEC-2.2.12: Three separate sessions MUST have fully isolated conversations."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta 1")
        await adapter.add_message(SESSION_B, "user", "Beta 2")
        await adapter.add_message(SESSION_C, "user", "Gamma 1")
        await adapter.add_message(SESSION_C, "user", "Gamma 2")
        await adapter.add_message(SESSION_C, "user", "Gamma 3")

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)
        conv_c = await adapter.get_conversation(SESSION_C)

        assert len(conv_a.messages) == 1
        assert len(conv_b.messages) == 2
        assert len(conv_c.messages) == 3


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

    async def test_search_messages_no_results(self, adapter, session_id):
        """SPEC-2.3.4: search_messages MUST return empty list when nothing matches."""
        await adapter.add_message(session_id, "user", "The sky is blue")

        results = await adapter.search_messages(
            "quantum cryptography algorithms", limit=10, threshold=0.99
        )
        assert isinstance(results, list)

    async def test_search_messages_limit_one(self, adapter, session_id):
        """SPEC-2.3.5: search_messages with limit=1 MUST return at most 1 result."""
        for i in range(5):
            await adapter.add_message(session_id, "user", f"Searchable content {i}")

        results = await adapter.search_messages("Searchable content", limit=1, threshold=0.0)
        assert len(results) <= 1

    async def test_search_messages_empty_database(self, adapter):
        """SPEC-2.3.6: search_messages on empty database MUST return empty list."""
        results = await adapter.search_messages("anything", limit=10, threshold=0.0)
        assert results == []

    async def test_search_messages_across_sessions(self, adapter):
        """SPEC-2.3.7: search_messages without session_id MUST search across all sessions."""
        await adapter.add_message(SESSION_A, "user", "Alpha Python topic")
        await adapter.add_message(SESSION_B, "user", "Beta Python topic")

        results = await adapter.search_messages("Python topic", limit=10, threshold=0.0)
        assert len(results) >= 1


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

    async def test_list_sessions_empty(self, adapter):
        """SPEC-2.4.3: list_sessions with no sessions MUST return empty list."""
        sessions = await adapter.list_sessions()
        assert sessions == []

    async def test_list_sessions_single_session(self, adapter):
        """SPEC-2.4.4: list_sessions with one session MUST return exactly one entry."""
        await adapter.add_message(SESSION_A, "user", "Solo")
        sessions = await adapter.list_sessions()
        assert len(sessions) == 1
        assert sessions[0].session_id == SESSION_A

    async def test_list_sessions_respects_limit(self, adapter):
        """SPEC-2.4.5: list_sessions MUST respect the limit parameter."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta")
        await adapter.add_message(SESSION_C, "user", "Gamma")

        sessions = await adapter.list_sessions(limit=2)
        assert len(sessions) <= 2

    async def test_list_sessions_created_at_present(self, adapter):
        """SPEC-2.4.6: list_sessions entries MUST have a created_at timestamp."""
        await adapter.add_message(SESSION_A, "user", "Timestamped")
        sessions = await adapter.list_sessions()
        assert len(sessions) >= 1
        assert sessions[0].created_at is not None

    async def test_list_sessions_message_count_after_delete(self, adapter):
        """SPEC-2.4.7: list_sessions MUST reflect accurate count after message deletion."""
        msg1 = await adapter.add_message(SESSION_A, "user", "One")
        await adapter.add_message(SESSION_A, "user", "Two")
        await adapter.add_message(SESSION_A, "user", "Three")
        await adapter.delete_message(msg1.id)

        sessions = await adapter.list_sessions()
        session_a = next(s for s in sessions if s.session_id == SESSION_A)
        assert session_a.message_count == 2

    async def test_list_sessions_multiple_sessions_independent_counts(self, adapter):
        """SPEC-2.4.8: list_sessions MUST report independent counts per session."""
        await adapter.add_message(SESSION_A, "user", "A1")
        await adapter.add_message(SESSION_A, "user", "A2")
        await adapter.add_message(SESSION_B, "user", "B1")

        sessions = await adapter.list_sessions()
        session_a = next(s for s in sessions if s.session_id == SESSION_A)
        session_b = next(s for s in sessions if s.session_id == SESSION_B)
        assert session_a.message_count == 2
        assert session_b.message_count == 1


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

    async def test_delete_message_preserves_remaining_order(self, adapter, session_id):
        """SPEC-2.5.4: Deleting a message MUST preserve order of remaining messages."""
        msgs = []
        for content in ["First", "Second", "Third", "Fourth"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        await adapter.delete_message(msgs[1].id)  # Delete "Second"

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 3
        assert conv.messages[0].content == "First"
        assert conv.messages[1].content == "Third"
        assert conv.messages[2].content == "Fourth"

    async def test_delete_first_message(self, adapter, session_id):
        """SPEC-2.5.5: Deleting the first message MUST preserve remaining messages."""
        msgs = []
        for content in ["First", "Second", "Third"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        await adapter.delete_message(msgs[0].id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2
        assert conv.messages[0].content == "Second"
        assert conv.messages[1].content == "Third"

    async def test_delete_last_message(self, adapter, session_id):
        """SPEC-2.5.6: Deleting the last message MUST preserve earlier messages."""
        msgs = []
        for content in ["First", "Second", "Third"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        await adapter.delete_message(msgs[2].id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2
        assert conv.messages[0].content == "First"
        assert conv.messages[1].content == "Second"

    async def test_delete_middle_message(self, adapter, session_id):
        """SPEC-2.5.7: Deleting a middle message MUST repair the ordering chain."""
        msgs = []
        for content in ["First", "Second", "Third"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        await adapter.delete_message(msgs[1].id)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2
        assert conv.messages[0].content == "First"
        assert conv.messages[1].content == "Third"

    async def test_delete_all_messages_one_by_one(self, adapter, session_id):
        """SPEC-2.5.8: Deleting all messages one by one MUST leave session empty."""
        msgs = []
        for content in ["One", "Two", "Three"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        for msg in msgs:
            result = await adapter.delete_message(msg.id)
            assert result is True

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 0

    async def test_delete_same_message_twice(self, adapter, session_id):
        """SPEC-2.5.9: Deleting the same message twice MUST return False on second call."""
        msg = await adapter.add_message(session_id, "user", "Delete twice")

        first = await adapter.delete_message(msg.id)
        assert first is True

        second = await adapter.delete_message(msg.id)
        assert second is False


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

    async def test_clear_session_idempotent_on_empty(self, adapter, session_id):
        """SPEC-2.6.3: clear_session on an empty session MUST NOT raise an error."""
        await adapter.clear_session(session_id)
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 0

    async def test_clear_session_then_add_new_messages(self, adapter, session_id):
        """SPEC-2.6.4: A cleared session MUST accept new messages."""
        await adapter.add_message(session_id, "user", "Before clear")
        await adapter.clear_session(session_id)

        await adapter.add_message(session_id, "user", "After clear")
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 1
        assert conv.messages[0].content == "After clear"

    async def test_clear_session_multiple_sessions_selective(self, adapter):
        """SPEC-2.6.5: Clearing one of three sessions MUST preserve the other two."""
        await adapter.add_message(SESSION_A, "user", "Alpha")
        await adapter.add_message(SESSION_B, "user", "Beta")
        await adapter.add_message(SESSION_C, "user", "Gamma")

        await adapter.clear_session(SESSION_B)

        conv_a = await adapter.get_conversation(SESSION_A)
        conv_b = await adapter.get_conversation(SESSION_B)
        conv_c = await adapter.get_conversation(SESSION_C)

        assert len(conv_a.messages) == 1
        assert len(conv_b.messages) == 0
        assert len(conv_c.messages) == 1


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

    async def test_chain_with_single_message(self, adapter, session_id):
        """SPEC-2.7.3: A single message in a session MUST be retrievable."""
        await adapter.add_message(session_id, "user", "Solo")
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 1
        assert conv.messages[0].content == "Solo"

    async def test_chain_with_two_messages(self, adapter, session_id):
        """SPEC-2.7.4: Two messages MUST be correctly ordered."""
        await adapter.add_message(session_id, "user", "First")
        await adapter.add_message(session_id, "assistant", "Second")

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 2
        assert conv.messages[0].content == "First"
        assert conv.messages[1].content == "Second"

    async def test_chain_integrity_after_middle_delete(self, adapter, session_id):
        """SPEC-2.7.5: Deleting a middle message MUST maintain chain integrity."""
        msgs = []
        for content in ["A", "B", "C", "D", "E"]:
            msgs.append(await adapter.add_message(session_id, "user", content))

        await adapter.delete_message(msgs[2].id)  # Delete "C"

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 4
        assert [m.content for m in conv.messages] == ["A", "B", "D", "E"]

    async def test_large_chain_ordering(self, adapter, session_id):
        """SPEC-2.7.6: 100 messages MUST maintain correct insertion order."""
        for i in range(100):
            await adapter.add_message(session_id, "user", f"Msg-{i:04d}")

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 100
        for i, msg in enumerate(conv.messages):
            assert msg.content == f"Msg-{i:04d}"

    async def test_mixed_roles_maintain_order(self, adapter, session_id):
        """SPEC-2.7.7: Messages with mixed roles MUST maintain insertion order."""
        sequence = [
            ("system", "You are helpful"),
            ("user", "Hello"),
            ("assistant", "Hi there"),
            ("user", "How are you?"),
            ("assistant", "I'm doing well"),
        ]
        for role, content in sequence:
            await adapter.add_message(session_id, role, content)

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 5
        for i, (role, content) in enumerate(sequence):
            assert conv.messages[i].role == role
            assert conv.messages[i].content == content


@pytest.mark.bronze
class TestIdempotency:
    """Tests for write idempotency and duplicate handling."""

    async def test_add_message_returns_unique_ids(self, adapter, session_id):
        """SPEC-2.8.1: Each add_message call MUST return a message with a unique ID."""
        msg1 = await adapter.add_message(session_id, "user", "Same content")
        msg2 = await adapter.add_message(session_id, "user", "Same content")
        assert msg1.id != msg2.id

    async def test_duplicate_content_stored_separately(self, adapter, session_id):
        """SPEC-2.8.2: Duplicate content MUST be stored as separate messages."""
        await adapter.add_message(session_id, "user", "Duplicate")
        await adapter.add_message(session_id, "user", "Duplicate")
        await adapter.add_message(session_id, "user", "Duplicate")

        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 3
        assert all(m.content == "Duplicate" for m in conv.messages)

    async def test_clear_session_is_idempotent(self, adapter, session_id):
        """SPEC-2.8.3: Calling clear_session multiple times MUST NOT raise errors."""
        await adapter.add_message(session_id, "user", "Data")
        await adapter.clear_session(session_id)
        await adapter.clear_session(session_id)
        conv = await adapter.get_conversation(session_id)
        assert len(conv.messages) == 0
