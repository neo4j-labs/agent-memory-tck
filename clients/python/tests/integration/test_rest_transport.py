"""Integration tests — RestTransport with a mocked httpx backend (respx).

Validates that bridge-style method calls correctly assemble the right HTTP
request, headers, body, and parse the response back into typed dataclasses.
"""

from __future__ import annotations

import json

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

    conv = await client.short_term.create_conversation(user_id="alice", metadata={"source": "e2e"})

    assert route.called
    request = route.calls[0].request
    assert request.headers["Authorization"] == "Bearer nams_test_key"
    assert request.headers["Content-Type"] == "application/json"
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
                    {
                        "id": "r1",
                        "conversation_id": "conv-42",
                        "content": "user values clarity",
                        "created_at": "x",
                    }
                ],
                "observations": [
                    {
                        "id": "o1",
                        "conversation_id": "conv-42",
                        "content": "long-form messages",
                        "created_at": "x",
                    }
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
async def test_get_conversation_returns_newest_first_listing_in_insertion_order(
    client: MemoryClient,
):
    respx.get(f"{ENDPOINT}/conversations/conv-1/messages").mock(
        return_value=httpx.Response(
            200,
            json={
                "messages": [
                    {"id": "m2", "role": "assistant", "content": "second"},
                    {"id": "m1", "role": "user", "content": "first"},
                ]
            },
        )
    )

    conv = await client.short_term.get_conversation("conv-1")
    assert [m.content for m in conv.messages] == ["first", "second"]


@pytest.mark.integration
@respx.mock
async def test_search_messages_omits_unset_threshold(client: MemoryClient):
    route = respx.post(f"{ENDPOINT}/conversations/conv-1/search").mock(
        return_value=httpx.Response(200, json={"messages": []})
    )

    await client.short_term.search_messages("nobel", session_id="conv-1")
    assert json.loads(route.calls[0].request.content) == {"query": "nobel", "limit": 10}


@pytest.mark.integration
@respx.mock
async def test_search_entities_unwraps_envelope(client: MemoryClient):
    respx.post(f"{ENDPOINT}/entities/search").mock(
        return_value=httpx.Response(
            200,
            json={"entities": [{"id": "e1", "name": "Alice", "type": "person", "createdAt": "x"}]},
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

    result = await client.long_term.set_entity_feedback("e1", user_score=0.95, confirmed=True)
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

    result = await client.query.cypher("MATCH (n:Entity) RETURN n.name AS name LIMIT $n", {"n": 1})
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
@respx.mock
async def test_add_relationship_posts_and_parses(client: MemoryClient):
    route = respx.post(f"{ENDPOINT}/relationships").mock(
        return_value=httpx.Response(
            201,
            json={
                "id": "11111111-2222-3333-4444-555555555555",
                "sourceId": "e-src",
                "targetId": "e-tgt",
                "relationshipType": "WORKS_AT",
                "confidence": 1.0,
                "created": True,
            },
        )
    )

    rel = await client.long_term.add_relationship("e-src", "e-tgt", "WORKS_AT")

    assert route.called
    body = json.loads(route.calls[0].request.content)
    assert body == {
        "sourceId": "e-src",
        "targetId": "e-tgt",
        "relationshipType": "WORKS_AT",
    }
    assert rel.id == "11111111-2222-3333-4444-555555555555"
    assert rel.source_id == "e-src"
    assert rel.target_id == "e-tgt"
    assert rel.relationship_type == "WORKS_AT"


@pytest.mark.integration
@respx.mock
async def test_add_relationship_collapsed_type(client: MemoryClient):
    # A type outside the workspace allowlist comes back as RELATED_TO with the
    # original preserved as "predicate" — the client surfaces the written type.
    respx.post(f"{ENDPOINT}/relationships").mock(
        return_value=httpx.Response(
            201,
            json={
                "id": "11111111-2222-3333-4444-555555555555",
                "sourceId": "a",
                "targetId": "b",
                "relationshipType": "RELATED_TO",
                "predicate": "located_at",
                "confidence": 1.0,
                "created": True,
            },
        )
    )

    rel = await client.long_term.add_relationship("a", "b", "LOCATED_AT")
    assert rel.relationship_type == "RELATED_TO"


@pytest.mark.integration
@respx.mock
async def test_get_entity_by_name_found(client: MemoryClient):
    route = respx.get(f"{ENDPOINT}/entities/by-name").mock(
        return_value=httpx.Response(
            200,
            json={
                "entities": [
                    {"id": "e1", "name": "Alice Johnson", "type": "person", "matchKind": "name"},
                    {
                        "id": "e2",
                        "name": "Alice Johnson",
                        "type": "organization",
                        "matchKind": "alias",
                    },
                ]
            },
        )
    )

    entity = await client.long_term.get_entity_by_name("Alice Johnson")

    assert route.called
    assert route.calls[0].request.url.params["name"] == "Alice Johnson"
    assert entity is not None
    assert entity.id == "e1"  # first element is the best match
    assert entity.name == "Alice Johnson"


@pytest.mark.integration
@respx.mock
async def test_get_entity_by_name_not_found_returns_none(client: MemoryClient):
    respx.get(f"{ENDPOINT}/entities/by-name").mock(
        return_value=httpx.Response(200, json={"entities": []})
    )

    entity = await client.long_term.get_entity_by_name("Nonexistent Entity")
    assert entity is None


_RELATED_ENTITY_JSON = {
    "id": "e-alice",
    "name": "Alice",
    "type": "person",
    "relationships": [
        {"relType": "KNOWS", "targetId": "e-bob", "targetName": "Bob", "targetType": "person"},
        {
            "relType": "WORKS_AT",
            "targetId": "e-acme",
            "targetName": "Acme",
            "targetType": "organization",
        },
    ],
}


@pytest.mark.integration
@respx.mock
async def test_get_related_entities_maps_relationships(client: MemoryClient):
    respx.get(f"{ENDPOINT}/entities/e-alice").mock(
        return_value=httpx.Response(200, json=_RELATED_ENTITY_JSON)
    )

    related = await client.long_term.get_related_entities("e-alice")
    assert [(e.id, e.name, e.type) for e in related] == [
        ("e-bob", "Bob", "person"),
        ("e-acme", "Acme", "organization"),
    ]


@pytest.mark.integration
@respx.mock
async def test_get_related_entities_type_filter(client: MemoryClient):
    respx.get(f"{ENDPOINT}/entities/e-alice").mock(
        return_value=httpx.Response(200, json=_RELATED_ENTITY_JSON)
    )

    related = await client.long_term.get_related_entities("e-alice", relationship_type="WORKS_AT")
    assert [e.name for e in related] == ["Acme"]


@pytest.mark.integration
@respx.mock
async def test_get_related_entities_empty(client: MemoryClient):
    respx.get(f"{ENDPOINT}/entities/e-lonely").mock(
        return_value=httpx.Response(
            200, json={"id": "e-lonely", "name": "Lonely", "type": "person", "relationships": []}
        )
    )

    related = await client.long_term.get_related_entities("e-lonely")
    assert related == []


@pytest.mark.integration
async def test_get_related_entities_depth_not_supported():
    c = MemoryClient(endpoint=ENDPOINT, api_key="k")
    with pytest.raises(NotSupportedError):
        await c.long_term.get_related_entities("e1", depth=2)
    await c.close()


@pytest.mark.integration
@respx.mock
async def test_merge_duplicate_entities_merges_then_fetches(client: MemoryClient):
    # The merge response's targetId ("e-canonical") wins over the
    # caller-supplied one — it is canonical-resolved server-side.
    merge_route = respx.post(f"{ENDPOINT}/entities/e-src/merge").mock(
        return_value=httpx.Response(
            200, json={"status": "merged", "sourceId": "e-src", "targetId": "e-canonical"}
        )
    )
    get_route = respx.get(f"{ENDPOINT}/entities/e-canonical").mock(
        return_value=httpx.Response(
            200, json={"id": "e-canonical", "name": "Alice Johnson", "type": "person"}
        )
    )

    merged = await client.long_term.merge_duplicate_entities("e-src", "e-tgt")

    assert merge_route.called
    assert get_route.called
    assert merged.id == "e-canonical"
    assert merged.name == "Alice Johnson"


@pytest.mark.integration
@respx.mock
async def test_merge_duplicate_entities_canonical_name(client: MemoryClient):
    respx.post(f"{ENDPOINT}/entities/e-src/merge").mock(
        return_value=httpx.Response(
            200, json={"status": "merged", "sourceId": "e-src", "targetId": "e-canonical"}
        )
    )
    rename_route = respx.put(f"{ENDPOINT}/entities/e-canonical").mock(
        return_value=httpx.Response(200, json={"status": "updated"})
    )
    respx.get(f"{ENDPOINT}/entities/e-canonical").mock(
        return_value=httpx.Response(
            200,
            json={
                "id": "e-canonical",
                "name": "Alice J. Johnson",
                "type": "person",
                "relationships": [
                    {
                        "relType": "WORKS_AT",
                        "targetId": "e-acme",
                        "targetName": "Acme",
                        "targetType": "organization",
                    }
                ],
            },
        )
    )

    merged = await client.long_term.merge_duplicate_entities(
        "e-src", "e-tgt", canonical_name="Alice J. Johnson"
    )

    assert rename_route.called
    assert b"Alice J. Johnson" in rename_route.calls[0].request.content
    assert merged.name == "Alice J. Johnson"
    # Hosted rel refs (no edge id, relType key) must parse without error.
    assert merged.relationships is not None
    assert merged.relationships[0].type == "WORKS_AT"
    assert merged.relationships[0].target_id == "e-acme"


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
