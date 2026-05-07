"""Unit tests for wire-dict → typed dataclass parsers."""

from __future__ import annotations

import pytest

from neo4j_agent_memory_client._parse import (
    parse_agent_step,
    parse_context,
    parse_conversation,
    parse_cypher,
    parse_entity,
    parse_entity_history,
    parse_graph,
    parse_message,
    parse_session_info,
    parse_token_pair,
    parse_tool_call,
)


@pytest.mark.unit
class TestParseMessage:
    def test_basic(self):
        m = parse_message({"id": "abc", "role": "user", "content": "hi"})
        assert m is not None
        assert m.id == "abc"
        assert m.role == "user"
        assert m.metadata == {}

    def test_none_returns_none(self):
        assert parse_message(None) is None

    def test_timestamp_falls_back_to_created_at(self):
        m = parse_message({"id": "x", "role": "user", "content": "h", "created_at": "2026-05-07T00:00:00Z"})
        assert m and m.timestamp == "2026-05-07T00:00:00Z"

    def test_metadata_default_dict(self):
        m = parse_message({"id": "x", "role": "user", "content": "h"})
        assert m and m.metadata == {}


@pytest.mark.unit
class TestParseConversation:
    def test_with_messages(self):
        c = parse_conversation({
            "id": "conv-1",
            "session_id": "s",
            "messages": [{"id": "m1", "role": "user", "content": "hi"}],
            "created_at": "now",
        })
        assert c and len(c.messages) == 1
        assert c.messages[0].id == "m1"

    def test_session_id_falls_back_to_id(self):
        c = parse_conversation({"id": "abc", "messages": []})
        assert c and c.session_id == "abc"


@pytest.mark.unit
class TestParseEntity:
    def test_full(self):
        e = parse_entity({
            "id": "e1",
            "name": "Alice",
            "type": "person",
            "confidence": 0.9,
            "source_stage": "extract",
            "relationships": [{"id": "r1", "type": "KNOWS", "target_id": "e2"}],
        })
        assert e is not None
        assert e.confidence == 0.9
        assert e.source_stage == "extract"
        assert e.relationships and e.relationships[0].type == "KNOWS"


@pytest.mark.unit
class TestParseContext:
    def test_three_tier(self):
        ctx = parse_context({
            "reflections": [{"id": "r", "conversation_id": "c", "content": "x", "created_at": ""}],
            "observations": [{"id": "o", "conversation_id": "c", "content": "y", "created_at": ""}],
            "recent_messages": [{"id": "m", "role": "user", "content": "z"}],
        })
        assert len(ctx.reflections) == 1
        assert len(ctx.observations) == 1
        assert len(ctx.recent_messages) == 1


@pytest.mark.unit
class TestMisc:
    def test_session_info(self):
        s = parse_session_info({"session_id": "s", "message_count": 3, "created_at": ""})
        assert s.message_count == 3

    def test_tool_call_with_camel_aliases(self):
        # Hosted REST returns camelCase tool calls; ensure we tolerate both.
        tc = parse_tool_call({"id": "t", "toolName": "search", "status": "success"})
        assert tc.tool_name == "search"

    def test_agent_step(self):
        s = parse_agent_step({
            "id": "s1",
            "conversation_id": "c1",
            "reasoning": "r",
            "action_taken": "a",
        })
        assert s.action_taken == "a"

    def test_entity_history(self):
        h = parse_entity_history({"entity_id": "e", "mentions": []})
        assert h.entity_id == "e"
        assert h.mentions == []

    def test_graph(self):
        g = parse_graph({
            "nodes": [{"id": "n1", "name": "X", "type": "person"}],
            "edges": [{"id": "e1", "source": "n1", "target": "n1", "type": "SELF"}],
        })
        assert len(g.nodes) == 1
        assert len(g.edges) == 1

    def test_cypher(self):
        c = parse_cypher({"columns": ["a"], "rows": [[1]], "stats": {"queryTime": 5}})
        assert c.columns == ["a"]
        assert c.rows == [[1]]
        assert c.stats == {"queryTime": 5}

    def test_token_pair(self):
        t = parse_token_pair({"access_token": "a", "refresh_token": "r", "expires_in": 3600})
        assert t.expires_in == 3600
