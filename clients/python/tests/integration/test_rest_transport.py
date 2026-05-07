"""Integration tests — RestTransport with a mocked httpx backend (respx).

Validates that bridge-style method calls correctly assemble the right HTTP
request, headers, body, and parse the response back into typed dataclasses.
"""

from __future__ import annotations

import httpx
import pytest
import respx

from neo4j_agent_memory_client import MemoryClient
from neo4j_agent_memory_client.errors import (
    AuthenticationError,
    NotSupportedError,
    TransportError,
)


ENDPOINT = "https://memory.test/v1"


@pytest.fixture
async def client():
    c = MemoryClient(endpoint=ENDPOINT, api_key="nams_test_key")
    yield c
    await c.close()


@pytest.mark.integration
@respx.mock
async def test_create_conversation_request_and_parse(client: MemoryClient):
    route = respx.post(f"{ENDPOINT}/conversations").mock(
        return_value=httpx.Response(
            200,
            json={
                "id": "conv-uuid-1",
                "userId": "alice",
                "workspaceId": "ws-1",
                "createdAt": "2026-05-07T00:00:00Z",
            },
        )
    )

    conv = await client.short_term.create_conversation(
        user_id="alice", metadata={"source": "e2e"}
    )

    assert route.called
    request = route.calls[0].request
    assert request.headers["Authorization"] == "Bearer nams_test_key"
    assert request.headers["Content-Type"] == "application/json"
    body = httpx._content.encode_request(request.content)  # type: ignore[attr-defined]
    # Fall back to direct content read
    assert b"alice" in request.content

    assert conv.id == "conv-uuid-1"
    assert conv.user_id == "alice"
    assert conv.workspace_id == "ws-1"


@pytest.mark.integration
@respx.mock
async def test_path_params_substituted(client: MemoryClient):
    respx.get(f"{ENDPOINT}/conversations/conv-42/context").mock(
        return_value=httpx.Response(
            200,
            json={
                "reflections": [
                    {"id": "r1", "conversation_id": "conv-42", "content": "user values clarity", "created_at": "x"}
                ],
                "observations": [
                    {"id": "o1", "conversation_id": "conv-42", "content": "long-form messages", "created_at": "x"}
                ],
                "recent_messages": [{"id": "m1", "role": "user", "content": "hello"}],
            },
        )
    )

    ctx = await client.short_term.get_context("conv-42")
    assert len(ctx.reflections) == 1
    assert ctx.reflections[0].content == "user values clarity"
    assert len(ctx.observations) == 1
    assert len(ctx.recent_messages) == 1


@pytest.mark.integration
@respx.mock
async def test_list_conversations_unwraps_envelope(client: MemoryClient):
    respx.get(f"{ENDPOINT}/conversations").mock(
        return_value=httpx.Response(
            200,
            json={
                "conversations": [
                    {"id": "c1", "userId": "alice", "createdAt": "x"},
                    {"id": "c2", "userId": "bob", "createdAt": "y"},
                ]
            },
        )
    )

    convs = await client.short_term.list_conversations(limit=10)
    assert len(convs) == 2
    assert convs[0].id == "c1"
    assert convs[1].user_id == "bob"


@pytest.mark.integration
@respx.mock
async def test_search_entities_unwraps_envelope(client: MemoryClient):
    respx.post(f"{ENDPOINT}/entities/search").mock(
        return_value=httpx.Response(
            200,
            json={
                "entities": [
                    {"id": "e1", "name": "Alice", "type": "person", "createdAt": "x"}
                ]
            },
        )
    )

    entities = await client.long_term.search_entities("alice")
    assert len(entities) == 1
    assert entities[0].name == "Alice"


@pytest.mark.integration
@respx.mock
async def test_set_entity_feedback_round_trip(client: MemoryClient):
    respx.put(f"{ENDPOINT}/entities/e1/feedback").mock(
        return_value=httpx.Response(200, json={"id": "e1", "updated": True})
    )

    result = await client.long_term.set_entity_feedback(
        "e1", user_score=0.95, confirmed=True
    )
    assert result.id == "e1"
    assert result.updated is True


@pytest.mark.integration
@respx.mock
async def test_cypher_query_passes_params(client: MemoryClient):
    route = respx.post(f"{ENDPOINT}/query").mock(
        return_value=httpx.Response(
            200,
            json={"columns": ["name"], "rows": [["Alice"]], "stats": {"queryTime": 3}},
        )
    )

    result = await client.query.cypher(
        "MATCH (n:Entity) RETURN n.name AS name LIMIT $n", {"n": 1}
    )
    assert route.called
    body = route.calls[0].request.content
    assert b"MATCH" in body
    assert result.columns == ["name"]
    assert result.rows == [["Alice"]]


@pytest.mark.integration
@respx.mock
async def test_401_raises_authentication_error(client: MemoryClient):
    respx.get(f"{ENDPOINT}/conversations").mock(
        return_value=httpx.Response(401, json={"error": "bad token"})
    )

    with pytest.raises(AuthenticationError):
        await client.short_term.list_conversations()


@pytest.mark.integration
@respx.mock
async def test_500_raises_transport_error(client: MemoryClient):
    respx.post(f"{ENDPOINT}/conversations").mock(
        return_value=httpx.Response(500, json={"error": "boom"})
    )

    with pytest.raises(TransportError) as exc_info:
        await client.short_term.create_conversation(user_id="x")
    assert exc_info.value.status_code == 500


@pytest.mark.integration
async def test_legacy_method_unsupported_on_rest():
    c = MemoryClient(endpoint=ENDPOINT, api_key="k")
    with pytest.raises(NotSupportedError):
        await c.long_term.add_preference("style", "concise")
    await c.close()


@pytest.mark.integration
@respx.mock
async def test_token_provider_called_per_request():
    calls = {"n": 0}

    def fresh_token() -> str:
        calls["n"] += 1
        return f"nams_token_{calls['n']}"

    c = MemoryClient(endpoint=ENDPOINT, token_provider=fresh_token)
    route = respx.get(f"{ENDPOINT}/conversations").mock(
        return_value=httpx.Response(200, json={"conversations": []})
    )

    await c.short_term.list_conversations()
    await c.short_term.list_conversations()

    assert calls["n"] == 2
    auth1 = route.calls[0].request.headers["Authorization"]
    auth2 = route.calls[1].request.headers["Authorization"]
    assert auth1 == "Bearer nams_token_1"
    assert auth2 == "Bearer nams_token_2"
    await c.close()


@pytest.mark.integration
async def test_transport_auto_selection():
    rest_client = MemoryClient(endpoint="https://memory.neo4jlabs.com/v1", api_key="k")
    bridge_client = MemoryClient(endpoint="http://localhost:3001", api_key="k")

    from neo4j_agent_memory_client.transport import BridgeTransport, RestTransport

    assert isinstance(rest_client._transport, RestTransport)
    assert isinstance(bridge_client._transport, BridgeTransport)

    await rest_client.close()
    await bridge_client.close()
