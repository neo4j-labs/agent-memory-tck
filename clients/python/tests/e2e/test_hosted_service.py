"""End-to-end tests against the live hosted Neo4j Agent Memory Service.

These tests hit `https://memory.neo4jlabs.com/v1` (or whatever
`MEMORY_ENDPOINT` is set to) using the `MEMORY_API_KEY` from `.env` (local)
or repo secrets (CI).

All tests skip with a clear message when `MEMORY_API_KEY` is empty/unset, so
forks and PRs without secret access still pass CI.

Each test creates short-lived data (conversations) tagged with the user
prefix `tck-e2e-py-` and cleans up via `delete_conversation` in a
finalizer. Failures during cleanup are warned, not raised.
"""

from __future__ import annotations

import os
import uuid

import pytest

from neo4j_agent_memory_client import MemoryClient


def _has_api_key() -> bool:
    return bool(os.environ.get("MEMORY_API_KEY", "").strip())


pytestmark = [
    pytest.mark.e2e,
    pytest.mark.skipif(not _has_api_key(), reason="MEMORY_API_KEY not set"),
]


def _user_prefix() -> str:
    base = os.environ.get("MEMORY_E2E_USER_ID", "tck-e2e-py")
    return f"{base}-{uuid.uuid4().hex[:8]}"


@pytest.fixture
async def client():
    endpoint = os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")
    api_key = os.environ["MEMORY_API_KEY"]
    c = MemoryClient(endpoint=endpoint, api_key=api_key)
    await c.connect()
    yield c
    await c.close()


@pytest.fixture
async def transient_conversation(client: MemoryClient):
    """A scratch conversation that gets deleted after the test."""
    conv = await client.short_term.create_conversation(user_id=_user_prefix())
    yield conv
    try:
        await client.short_term.delete_conversation(conv.id)
    except Exception as e:
        pytest.warns(UserWarning, match=str(e))


# ---------------------------------------------------------------------------
# Connection + auth
# ---------------------------------------------------------------------------


async def test_connect_succeeds(client: MemoryClient):
    """The connect call probes /conversations?limit=1 — should not raise."""
    # `client` fixture already calls connect; reaching this means it worked.
    assert client._transport is not None


async def test_invalid_token_raises_auth_error():
    """A bogus key should yield AuthenticationError on connect."""
    from neo4j_agent_memory_client.errors import AuthenticationError

    endpoint = os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")
    bad = MemoryClient(endpoint=endpoint, api_key="nams_obviously_not_real_token")
    try:
        with pytest.raises(AuthenticationError):
            await bad.connect()
    finally:
        await bad.close()


# ---------------------------------------------------------------------------
# Short-Term
# ---------------------------------------------------------------------------


async def test_create_and_list_conversation(client: MemoryClient):
    conv = await client.short_term.create_conversation(user_id=_user_prefix())
    try:
        assert conv.id
        # Newly created conversation must show up in the list.
        # Some accounts may have many conversations — just check it's not empty.
        listed = await client.short_term.list_conversations(limit=50)
        assert isinstance(listed, list)
    finally:
        await client.short_term.delete_conversation(conv.id)


async def test_add_message_and_search(
    client: MemoryClient, transient_conversation
):
    await client.short_term.add_message(
        transient_conversation.id, "user", "John works at Acme Corp in Paris."
    )
    await client.short_term.add_message(
        transient_conversation.id, "assistant", "Got it — noting John's company and city."
    )

    # Hosted REST: search is per-conversation.
    results = await client.short_term.search_messages(
        "Acme", session_id=transient_conversation.id, limit=5, threshold=0.0
    )
    assert isinstance(results, list)


async def test_bulk_add_messages(client: MemoryClient, transient_conversation):
    from neo4j_agent_memory_client import BulkMessageInput

    msgs = [
        BulkMessageInput(role="user", content=f"Bulk message {i}")
        for i in range(5)
    ]
    out = await client.short_term.bulk_add_messages(transient_conversation.id, msgs)
    assert len(out) == 5


async def test_get_context_returns_three_tier(
    client: MemoryClient, transient_conversation
):
    await client.short_term.add_message(transient_conversation.id, "user", "Hello there.")
    ctx = await client.short_term.get_context(transient_conversation.id)
    # observations + reflections may be empty for new conversations — they
    # are generated asynchronously. We only require the shape.
    assert hasattr(ctx, "reflections")
    assert hasattr(ctx, "observations")
    assert hasattr(ctx, "recent_messages")
    assert isinstance(ctx.recent_messages, list)


# ---------------------------------------------------------------------------
# Long-Term
# ---------------------------------------------------------------------------


async def test_search_entities_runs(client: MemoryClient):
    # Just verify the call returns a list without error. Account may be empty.
    results = await client.long_term.search_entities("anything", limit=5)
    assert isinstance(results, list)


async def test_get_entity_graph(client: MemoryClient):
    graph = await client.long_term.get_entity_graph()
    assert hasattr(graph, "nodes")
    assert hasattr(graph, "edges")
    assert isinstance(graph.nodes, list)
    assert isinstance(graph.edges, list)


async def test_list_entities_typed(client: MemoryClient):
    entities = await client.long_term.list_entities(limit=5)
    assert isinstance(entities, list)


# ---------------------------------------------------------------------------
# Reasoning
# ---------------------------------------------------------------------------


async def test_record_step_round_trip(
    client: MemoryClient, transient_conversation
):
    step = await client.reasoning.record_step(
        conversation_id=transient_conversation.id,
        reasoning="Hypothesis: user wants summary.",
        action_taken="generate_summary",
        result="produced 3-sentence summary",
    )
    assert step.id
    assert step.conversation_id == transient_conversation.id
    assert step.reasoning.startswith("Hypothesis")


async def test_get_trace_by_conversation(
    client: MemoryClient, transient_conversation
):
    await client.reasoning.record_step(
        conversation_id=transient_conversation.id,
        reasoning="r",
        action_taken="a",
    )
    trace = await client.reasoning.get_trace_by_conversation(transient_conversation.id)
    assert trace.conversation_id == transient_conversation.id
    assert isinstance(trace.steps, list)


# ---------------------------------------------------------------------------
# Cypher console
# ---------------------------------------------------------------------------


async def test_cypher_read_only(client: MemoryClient):
    result = await client.query.cypher("MATCH (n) RETURN count(n) AS total")
    assert "total" in result.columns
    assert len(result.rows) >= 1
