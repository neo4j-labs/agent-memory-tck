"""Reference HTTP bridge conformance server.

Wraps the Python ReferenceAdapter in an HTTP server to validate that
the bridge protocol works end-to-end. Also serves as a template for
TypeScript and Go conformance server implementations.

Usage:
    python -m tck.bridge.reference_server
    # Then in another terminal:
    pytest -m bronze --bridge-url http://localhost:3001
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any
from uuid import UUID

from aiohttp import web

logger = logging.getLogger(__name__)


def _json_serializer(obj: Any) -> Any:
    """Custom JSON serializer for objects not serializable by default json."""
    if isinstance(obj, UUID):
        return str(obj)
    if hasattr(obj, "isoformat"):
        return obj.isoformat()
    if hasattr(obj, "value"):
        return obj.value
    if hasattr(obj, "model_dump"):
        return obj.model_dump(mode="json")
    raise TypeError(f"Object of type {type(obj)} is not JSON serializable")


def _json_response(data: Any, status: int = 200) -> web.Response:
    """Create a JSON response."""
    if data is None:
        return web.Response(
            text="null",
            content_type="application/json",
            status=200,
        )
    body = json.dumps(data, default=_json_serializer)
    return web.Response(text=body, content_type="application/json", status=status)


def _model_to_dict(obj: Any) -> dict | list | None:
    """Convert a Pydantic model or list of models to dict."""
    if obj is None:
        return None
    if isinstance(obj, list):
        return [_model_to_dict(item) for item in obj]
    if hasattr(obj, "model_dump"):
        return obj.model_dump(mode="json")
    return obj


class BridgeServer:
    """HTTP server wrapping a BaseAdapter for conformance testing."""

    def __init__(self, adapter):
        self.adapter = adapter
        self.app = web.Application()
        self._setup_routes()

    def _setup_routes(self):
        routes = [
            ("setup", self.handle_setup),
            ("teardown", self.handle_teardown),
            ("clear_all_data", self.handle_clear_all_data),
            ("add_message", self.handle_add_message),
            ("get_conversation", self.handle_get_conversation),
            ("search_messages", self.handle_search_messages),
            ("list_sessions", self.handle_list_sessions),
            ("delete_message", self.handle_delete_message),
            ("clear_session", self.handle_clear_session),
            ("add_entity", self.handle_add_entity),
            ("add_preference", self.handle_add_preference),
            ("add_fact", self.handle_add_fact),
            ("search_entities", self.handle_search_entities),
            ("search_preferences", self.handle_search_preferences),
            ("get_entity_by_name", self.handle_get_entity_by_name),
            ("get_related_entities", self.handle_get_related_entities),
            ("start_trace", self.handle_start_trace),
            ("add_step", self.handle_add_step),
            ("record_tool_call", self.handle_record_tool_call),
            ("complete_trace", self.handle_complete_trace),
            ("get_trace_with_steps", self.handle_get_trace_with_steps),
            ("list_traces", self.handle_list_traces),
            ("get_tool_stats", self.handle_get_tool_stats),
            ("add_relationship", self.handle_add_relationship),
            ("merge_duplicate_entities", self.handle_merge_duplicate_entities),
            ("get_similar_traces", self.handle_get_similar_traces),
        ]
        for name, handler in routes:
            self.app.router.add_post(f"/{name}", handler)

    async def _get_body(self, request: web.Request) -> dict:
        if request.content_length and request.content_length > 0:
            return await request.json()
        return {}

    # --- Lifecycle ---

    async def handle_setup(self, request: web.Request) -> web.Response:
        return _json_response({"ok": True})

    async def handle_teardown(self, request: web.Request) -> web.Response:
        return web.Response(status=204)

    async def handle_clear_all_data(self, request: web.Request) -> web.Response:
        await self.adapter.clear_all_data()
        return web.Response(status=204)

    # --- Short-Term Memory ---

    async def handle_add_message(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_message(
            body["session_id"],
            body["role"],
            body["content"],
            metadata=body.get("metadata"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_get_conversation(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_conversation(
            body["session_id"],
            limit=body.get("limit"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_search_messages(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.search_messages(
            body["query"],
            session_id=body.get("session_id"),
            limit=body.get("limit", 10),
            threshold=body.get("threshold", 0.7),
        )
        return _json_response(_model_to_dict(result))

    async def handle_list_sessions(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.list_sessions(limit=body.get("limit", 100))
        return _json_response(_model_to_dict(result))

    async def handle_delete_message(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.delete_message(UUID(body["message_id"]))
        return _json_response({"deleted": result})

    async def handle_clear_session(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        await self.adapter.clear_session(body["session_id"])
        return web.Response(status=204)

    # --- Long-Term Memory ---

    async def handle_add_entity(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_entity(
            name=body["name"],
            entity_type=body["entity_type"],
            description=body.get("description"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_add_preference(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_preference(
            category=body["category"],
            preference=body["preference"],
            context=body.get("context"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_add_fact(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_fact(
            subject=body["subject"],
            predicate=body["predicate"],
            obj=body["obj"],
        )
        return _json_response(_model_to_dict(result))

    async def handle_search_entities(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.search_entities(
            body["query"],
            limit=body.get("limit", 10),
        )
        return _json_response(_model_to_dict(result))

    async def handle_search_preferences(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.search_preferences(
            body["query"],
            category=body.get("category"),
            limit=body.get("limit", 10),
        )
        return _json_response(_model_to_dict(result))

    async def handle_get_entity_by_name(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_entity_by_name(body["name"])
        return _json_response(_model_to_dict(result))

    async def handle_get_related_entities(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_related_entities(
            UUID(body["entity_id"]),
            relationship_type=body.get("relationship_type"),
            depth=body.get("depth", 1),
        )
        return _json_response(_model_to_dict(result))

    # --- Reasoning Memory ---

    async def handle_start_trace(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.start_trace(
            body["session_id"],
            body["task"],
        )
        return _json_response(_model_to_dict(result))

    async def handle_add_step(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_step(
            UUID(body["trace_id"]),
            thought=body.get("thought"),
            action=body.get("action"),
            observation=body.get("observation"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_record_tool_call(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        from tck.adapters.base_adapter import ToolCallStatus

        status = ToolCallStatus(body.get("status", "success"))
        result = await self.adapter.record_tool_call(
            UUID(body["step_id"]),
            body["tool_name"],
            body.get("arguments", {}),
            result=body.get("result"),
            status=status,
            duration_ms=body.get("duration_ms"),
            error=body.get("error"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_complete_trace(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.complete_trace(
            UUID(body["trace_id"]),
            outcome=body.get("outcome"),
            success=body.get("success"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_get_trace_with_steps(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_trace_with_steps(UUID(body["trace_id"]))
        return _json_response(_model_to_dict(result))

    async def handle_list_traces(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.list_traces(
            session_id=body.get("session_id"),
            limit=body.get("limit", 100),
        )
        return _json_response(_model_to_dict(result))

    async def handle_get_tool_stats(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_tool_stats(
            tool_name=body.get("tool_name"),
        )
        return _json_response(_model_to_dict(result))

    # --- Gold Tier ---

    async def handle_add_relationship(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.add_relationship(
            UUID(body["source_id"]),
            UUID(body["target_id"]),
            body["relationship_type"],
            properties=body.get("properties"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_merge_duplicate_entities(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.merge_duplicate_entities(
            UUID(body["source_id"]),
            UUID(body["target_id"]),
            canonical_name=body.get("canonical_name"),
        )
        return _json_response(_model_to_dict(result))

    async def handle_get_similar_traces(self, request: web.Request) -> web.Response:
        body = await self._get_body(request)
        result = await self.adapter.get_similar_traces(
            body["task"],
            limit=body.get("limit", 5),
            success_only=body.get("success_only", True),
        )
        return _json_response(_model_to_dict(result))


async def main():
    """Start the reference bridge server."""
    import os

    from tck.reference.adapter import ReferenceAdapter

    port = int(os.getenv("TCK_BRIDGE_PORT", "3001"))

    adapter = ReferenceAdapter()
    await adapter.setup()

    server = BridgeServer(adapter)

    runner = web.AppRunner(server.app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()

    logger.info(f"Reference bridge server running on http://0.0.0.0:{port}")
    print(f"Reference bridge server running on http://0.0.0.0:{port}")
    print("Press Ctrl+C to stop")

    try:
        await asyncio.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        await adapter.teardown()
        await runner.cleanup()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
