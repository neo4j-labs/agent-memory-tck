from __future__ import annotations

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from tck.adapters.http_bridge import HTTPBridgeAdapter
from tck.bridge.reference_server import BridgeServer


class MissingOptionalAdapter:
    async def get_similar_traces(self, task: str, *, limit: int = 5, success_only: bool = True):
        raise NotImplementedError("Gold tier: get_similar_traces")


@pytest.fixture
async def bridge_url():
    app = web.Application()

    async def not_implemented(request):
        return web.json_response({"error": "NotImplementedError: optional"}, status=501)

    async def server_error(request):
        return web.json_response({"error": "boom"}, status=500)

    app.router.add_post("/not_implemented", not_implemented)
    app.router.add_post("/server_error", server_error)

    async with TestServer(app) as server:
        yield str(server.make_url("/"))


async def test_reference_server_returns_501_for_not_implemented():
    server = BridgeServer(MissingOptionalAdapter())

    async with TestClient(TestServer(server.app)) as client:
        response = await client.post("/get_similar_traces", json={"task": "find"})
        body = await response.json()

    assert response.status == 501
    assert body == {"error": "NotImplementedError: Gold tier: get_similar_traces"}


async def test_bridge_adapter_maps_501_to_not_implemented(bridge_url):
    adapter = HTTPBridgeAdapter(bridge_url)

    with pytest.raises(NotImplementedError, match="optional"):
        await adapter._call("not_implemented")


async def test_bridge_adapter_maps_missing_optional_platinum_route_to_not_implemented(bridge_url):
    adapter = HTTPBridgeAdapter(bridge_url)

    with pytest.raises(NotImplementedError, match="404"):
        await adapter._call("create_conversation", {"user_id": "alice"})


async def test_bridge_adapter_keeps_missing_required_route_as_runtime_error(bridge_url):
    adapter = HTTPBridgeAdapter(bridge_url)

    with pytest.raises(RuntimeError, match="Bridge call add_message failed:"):
        await adapter._call("add_message", {"session_id": "s1"})


async def test_bridge_adapter_keeps_other_server_errors_as_runtime_error(bridge_url):
    adapter = HTTPBridgeAdapter(bridge_url)

    with pytest.raises(RuntimeError, match="Bridge call server_error failed: boom"):
        await adapter._call("server_error")
