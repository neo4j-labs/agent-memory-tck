"""Unit tests for RestTransport route selection and parameter handling.

These tests do not hit the network — they call private helpers and verify
the route table is structurally correct.
"""

from __future__ import annotations

import pytest

from neo4j_agent_memory_client.errors import NotSupportedError
from neo4j_agent_memory_client.transport import _ROUTES, _NOOP, _UNSUPPORTED, RestTransport


@pytest.mark.unit
class TestRouteTable:
    def test_lifecycle_routes_are_noop(self):
        assert _ROUTES["setup"] is _NOOP
        assert _ROUTES["teardown"] is _NOOP
        assert _ROUTES["clear_all_data"] is _NOOP

    def test_volume_5_routes_present(self):
        for method in (
            "create_conversation",
            "list_conversations",
            "get_context",
            "bulk_add_messages",
            "get_observations",
            "get_reflections",
            "list_entities",
            "get_entity",
            "set_entity_feedback",
            "get_entity_history",
            "merge_entities",
            "get_entity_graph",
            "explain_step",
            "get_trace_by_conversation",
            "get_entity_provenance",
            "record_step",
            "cypher_query",
            "list_api_keys",
            "create_api_key",
            "refresh_access_token",
        ):
            assert method in _ROUTES, f"missing route: {method}"
            assert _ROUTES[method] is not _UNSUPPORTED

    def test_unsupported_legacy_routes(self):
        for method in (
            "delete_message",
            "add_preference",
            "add_fact",
            "get_entity_by_name",
            "start_trace",
            "complete_trace",
            "list_traces",
        ):
            assert _ROUTES[method] is _UNSUPPORTED

    def test_create_conversation_route_shape(self):
        r = _ROUTES["create_conversation"]
        assert r.method == "POST"
        assert r.path == "/conversations"
        assert r.has_body is True
        assert r.path_params == ()

    def test_get_entity_route_shape(self):
        r = _ROUTES["get_entity"]
        assert r.method == "GET"
        assert r.path_params == ("entityId",)
        assert r.has_body is False


@pytest.mark.unit
class TestRestTransportConstruction:
    async def test_unknown_method_raises(self):
        t = RestTransport("https://example/v1", api_key="nams_x")
        with pytest.raises(NotSupportedError, match="not supported"):
            await t.request("totally_made_up_method", {})
        await t.close()

    async def test_unsupported_method_raises(self):
        t = RestTransport("https://example/v1", api_key="nams_x")
        with pytest.raises(NotSupportedError, match="no equivalent"):
            await t.request("add_preference", {"category": "x", "preference": "y"})
        await t.close()

    async def test_noop_methods_return_none(self):
        t = RestTransport("https://example/v1", api_key="nams_x")
        assert await t.request("setup") is None
        assert await t.request("teardown") is None
        assert await t.request("clear_all_data") is None
        await t.close()

    def test_endpoint_strips_trailing_slash(self):
        t = RestTransport("https://example/v1/", api_key="k")
        assert t._endpoint == "https://example/v1"
