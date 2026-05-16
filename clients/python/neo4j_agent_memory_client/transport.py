"""Transport abstraction with two implementations: bridge and REST."""

from __future__ import annotations

import asyncio
import inspect
from abc import ABC, abstractmethod
from typing import Any, Awaitable, Callable
from urllib.parse import quote, urlencode

import httpx

from .casing import camel_to_snake, snake_to_camel
from .casing import _camel_key as _snake_to_camel_key  # noqa: F401
from .errors import (
    AuthenticationError,
    ConnectionError as MemoryConnectionError,
    NotSupportedError,
    TransportError,
)


TokenProvider = Callable[[], str | Awaitable[str]]


class Transport(ABC):
    @abstractmethod
    async def connect(self) -> None: ...

    @abstractmethod
    async def close(self) -> None: ...

    @abstractmethod
    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any: ...


class BridgeTransport(Transport):
    """TCK bridge protocol — POST {endpoint}/{snake_case_method}."""

    def __init__(
        self,
        endpoint: str,
        api_key: str | None = None,
        timeout: float = 30.0,
        headers: dict[str, str] | None = None,
    ):
        self._endpoint = endpoint.rstrip("/")
        self._api_key = api_key
        self._headers = dict(headers or {})
        self._client = httpx.AsyncClient(timeout=timeout)

    async def connect(self) -> None:
        try:
            await self.request("setup")
        except Exception:
            pass

    async def close(self) -> None:
        await self._client.aclose()

    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        url = f"{self._endpoint}/{method}"
        body = {k: v for k, v in (params or {}).items() if v is not None}

        try:
            resp = await self._client.post(url, json=body, headers=self._build_headers())
        except httpx.RequestError as e:
            raise MemoryConnectionError(f"Request to {url} failed: {e}") from e

        return _parse_response(resp, method)

    def _build_headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", **self._headers}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        return headers


class _Route:
    __slots__ = ("method", "path", "path_params", "query_params", "has_body", "shape")

    def __init__(
        self,
        method: str,
        path: str,
        path_params: tuple[str, ...] = (),
        query_params: tuple[str, ...] = (),
        has_body: bool = False,
        shape: Callable[[Any, dict[str, Any]], Any] | None = None,
    ):
        self.method = method
        self.path = path
        self.path_params = path_params
        self.query_params = query_params
        self.has_body = has_body
        self.shape = shape


_NOOP = _Route("NOOP", "")
_UNSUPPORTED = _Route("UNSUPPORTED", "")


def _shape_messages(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "messages" in raw:
        return raw["messages"]
    return raw


def _shape_entities(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "entities" in raw:
        return raw["entities"]
    return raw


def _shape_conversations(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "conversations" in raw:
        return raw["conversations"]
    return raw


def _shape_observations(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "observations" in raw:
        return raw["observations"]
    return raw


def _shape_reflections(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "reflections" in raw:
        return raw["reflections"]
    return raw


def _shape_steps(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "steps" in raw:
        return raw["steps"]
    return raw


def _shape_api_keys(raw: Any, _: dict[str, Any]) -> Any:
    if isinstance(raw, dict) and "keys" in raw:
        return raw["keys"]
    if isinstance(raw, dict) and "api_keys" in raw:
        return raw["api_keys"]
    return raw


def _shape_get_conversation(raw: Any, params: dict[str, Any]) -> dict[str, Any]:
    messages = raw["messages"] if isinstance(raw, dict) and "messages" in raw else raw
    return {
        "id": params.get("sessionId"),
        "session_id": params.get("sessionId"),
        "messages": messages,
    }


def _shape_list_sessions(raw: Any, _: dict[str, Any]) -> list[dict[str, Any]]:
    convs = (raw or {}).get("conversations", []) if isinstance(raw, dict) else []
    return [
        {
            "session_id": c.get("id"),
            "message_count": c.get("messageCount") or 0,
            "created_at": c.get("createdAt"),
            "updated_at": c.get("updatedAt"),
        }
        for c in convs
    ]


_ROUTES: dict[str, _Route] = {
    "setup": _NOOP,
    "teardown": _NOOP,
    "clear_all_data": _NOOP,
    # Short-Term legacy
    "add_message": _Route(
        "POST", "/conversations/{sessionId}/messages",
        path_params=("sessionId",), has_body=True),
    "get_conversation": _Route(
        "GET", "/conversations/{sessionId}/messages",
        path_params=("sessionId",), query_params=("limit",),
        shape=_shape_get_conversation),
    "list_sessions": _Route(
        "GET", "/conversations", query_params=("limit",),
        shape=_shape_list_sessions),
    "search_messages": _Route(
        "POST", "/conversations/{sessionId}/search",
        path_params=("sessionId",), has_body=True, shape=_shape_messages),
    "clear_session": _Route(
        "DELETE", "/conversations/{sessionId}", path_params=("sessionId",)),
    "delete_message": _UNSUPPORTED,

    "add_entity": _Route("POST", "/entities", has_body=True),
    "search_entities": _Route(
        "POST", "/entities/search", has_body=True, shape=_shape_entities),
    "add_preference": _UNSUPPORTED,
    "add_fact": _UNSUPPORTED,
    "search_preferences": _UNSUPPORTED,
    "get_entity_by_name": _UNSUPPORTED,
    "get_related_entities": _UNSUPPORTED,
    "add_relationship": _UNSUPPORTED,
    "merge_duplicate_entities": _UNSUPPORTED,
    "start_trace": _UNSUPPORTED,
    "add_step": _UNSUPPORTED,
    "record_tool_call": _Route("POST", "/reasoning/tool-calls", has_body=True),
    "complete_trace": _UNSUPPORTED,
    "get_trace_with_steps": _UNSUPPORTED,
    "list_traces": _UNSUPPORTED,
    "get_tool_stats": _UNSUPPORTED,
    "get_similar_traces": _UNSUPPORTED,

    # ---- Volume 5 / hosted-native ---------------------------------------
    "create_conversation": _Route("POST", "/conversations", has_body=True),
    "list_conversations": _Route(
        "GET", "/conversations", query_params=("limit",),
        shape=_shape_conversations),
    "get_conversation_metadata": _Route(
        "GET", "/conversations/{conversationId}",
        path_params=("conversationId",)),
    "delete_conversation": _Route(
        "DELETE", "/conversations/{conversationId}",
        path_params=("conversationId",)),
    "get_context": _Route(
        "GET", "/conversations/{conversationId}/context",
        path_params=("conversationId",)),
    "bulk_add_messages": _Route(
        "POST", "/conversations/{conversationId}/messages/bulk",
        path_params=("conversationId",), has_body=True, shape=_shape_messages),
    "get_observations": _Route(
        "GET", "/conversations/{conversationId}/observations",
        path_params=("conversationId",), query_params=("limit",),
        shape=_shape_observations),
    "get_reflections": _Route(
        "GET", "/conversations/{conversationId}/reflections",
        path_params=("conversationId",), shape=_shape_reflections),

    "list_entities": _Route(
        "GET", "/entities", query_params=("type", "limit"),
        shape=_shape_entities),
    "get_entity": _Route(
        "GET", "/entities/{entityId}", path_params=("entityId",)),
    "update_entity": _Route(
        "PUT", "/entities/{entityId}",
        path_params=("entityId",), has_body=True),
    "delete_entity": _Route(
        "DELETE", "/entities/{entityId}", path_params=("entityId",)),
    "set_entity_feedback": _Route(
        "PUT", "/entities/{entityId}/feedback",
        path_params=("entityId",), has_body=True),
    "get_entity_history": _Route(
        "GET", "/entities/{entityId}/history",
        path_params=("entityId",)),
    "merge_entities": _Route(
        "POST", "/entities/{sourceId}/merge",
        path_params=("sourceId",), has_body=True),
    "get_entity_graph": _Route("GET", "/entities/graph"),

    "explain_step": _Route(
        "GET", "/reasoning/explain/{stepId}", path_params=("stepId",)),
    "get_trace_by_conversation": _Route(
        "GET", "/reasoning/trace/{conversationId}",
        path_params=("conversationId",)),
    "get_entity_provenance": _Route(
        "GET", "/reasoning/provenance/{entityId}",
        path_params=("entityId",)),
    "record_step": _Route("POST", "/reasoning/steps", has_body=True),
    "list_steps": _Route(
        "GET", "/reasoning/steps", query_params=("conversation_id",),
        shape=_shape_steps),
    "cypher_query": _Route("POST", "/query", has_body=True),

    "list_api_keys": _Route(
        "GET", "/auth/api-keys", query_params=("workspace_id",),
        shape=_shape_api_keys),
    "create_api_key": _Route("POST", "/auth/api-keys", has_body=True),
    "revoke_api_key": _Route(
        "DELETE", "/auth/api-keys/{keyId}", path_params=("keyId",)),
    "reveal_api_key": _Route(
        "GET", "/auth/api-keys/{keyId}/reveal",
        path_params=("keyId",), query_params=("workspace_id",)),
    "refresh_access_token": _Route("POST", "/auth/refresh", has_body=True),
}


class RestTransport(Transport):
    """Hosted REST API at https://memory.neo4jlabs.com/v1."""

    def __init__(
        self,
        endpoint: str,
        api_key: str | None = None,
        timeout: float = 30.0,
        token_provider: TokenProvider | None = None,
        headers: dict[str, str] | None = None,
    ):
        self._endpoint = endpoint.rstrip("/")
        self._api_key = api_key
        self._token_provider = token_provider
        self._headers = dict(headers or {})
        self._client = httpx.AsyncClient(timeout=timeout)

    async def connect(self) -> None:
        try:
            resp = await self._client.get(
                f"{self._endpoint}/conversations",
                params={"limit": 1},
                headers=await self._build_headers(),
            )
        except httpx.RequestError as e:
            raise MemoryConnectionError(f"Failed to connect: {e}") from e
        if resp.status_code in (401, 403):
            raise AuthenticationError(f"Authentication failed: {resp.status_code}")

    async def close(self) -> None:
        await self._client.aclose()

    async def request(self, method: str, params: dict[str, Any] | None = None) -> Any:
        route = _ROUTES.get(method)
        if route is None:
            raise NotSupportedError(
                f"Method '{method}' not supported by RestTransport"
            )
        if route is _NOOP:
            return None
        if route is _UNSUPPORTED:
            raise NotSupportedError(
                f"Method '{method}' has no equivalent in the hosted REST API"
            )

        original = params or {}
        camel = snake_to_camel(original)
        consumed: set[str] = set()

        # Path params use camelCase placeholders (matching the route literal).
        path = route.path
        for name in route.path_params:
            v = camel.get(name)
            if v is None or v == "":
                raise TransportError(
                    f"Missing path param '{name}' for method '{method}'", 400, camel
                )
            path = path.replace("{" + name + "}", quote(str(v), safe=""))
            consumed.add(name)

        # Query params on this hosted service are snake_case ("conversation_id",
        # "workspace_id", etc.) — NOT camelCase. So we look up the value from
        # the original snake_case params and emit the snake_case key on the
        # wire. We also mark the camelCase form as consumed so it doesn't
        # leak into the body.
        query: dict[str, Any] = {}
        for snake_name in route.query_params:
            v = original.get(snake_name)
            if v is None:
                # Fall back to the camelCase form just in case the caller
                # passed it that way.
                camel_name = _snake_to_camel_key(snake_name)
                v = camel.get(camel_name)
            if v is not None:
                query[snake_name] = v
                consumed.add(snake_name)
                consumed.add(_snake_to_camel_key(snake_name))

        # Body uses camelCase (matching the hosted POST/PUT body shape).
        body_obj: dict[str, Any] | None = None
        if route.has_body:
            body_obj = {
                k: v for k, v in camel.items() if k not in consumed and v is not None
            }

        url = f"{self._endpoint}{path}"
        if query:
            url = f"{url}?{urlencode(query)}"

        try:
            resp = await self._client.request(
                route.method,
                url,
                json=body_obj if route.has_body else None,
                headers=await self._build_headers(),
            )
        except httpx.RequestError as e:
            raise MemoryConnectionError(f"Request to {url} failed: {e}") from e

        if resp.status_code in (401, 403):
            raise AuthenticationError(f"Authentication failed: {resp.status_code}")
        if resp.status_code == 204:
            return None
        if resp.status_code >= 400:
            raise TransportError(
                f"{method} failed: HTTP {resp.status_code}",
                resp.status_code,
                _safe_json(resp),
            )

        if not resp.content:
            return None

        raw = resp.json()
        if route.shape is not None:
            raw = route.shape(raw, camel)
        return camel_to_snake(raw)

    async def _build_headers(self) -> dict[str, str]:
        headers = dict(self._headers)
        token: str | None = self._api_key
        if self._token_provider is not None:
            result = self._token_provider()
            if inspect.isawaitable(result):
                token = await result
            else:
                token = result  # type: ignore[assignment]
        if token:
            headers["Authorization"] = f"Bearer {token}"
        headers.setdefault("Content-Type", "application/json")
        return headers


def _parse_response(resp: httpx.Response, method: str) -> Any:
    if resp.status_code in (401, 403):
        raise AuthenticationError(f"Authentication failed: {resp.status_code}")
    if resp.status_code == 204:
        return None
    if resp.status_code >= 400:
        raise TransportError(
            f"{method} failed: HTTP {resp.status_code}",
            resp.status_code,
            _safe_json(resp),
        )
    if not resp.content:
        return None
    text = resp.text
    if not text or text == "null":
        return None
    return resp.json()


def _safe_json(resp: httpx.Response) -> Any:
    try:
        return resp.json()
    except Exception:
        return resp.text
