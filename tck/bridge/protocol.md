# HTTP Bridge Protocol

**Version:** 0.1.0

The HTTP bridge protocol enables the Python TCK test suite to validate
implementations in any language by proxying `BaseAdapter` method calls
over HTTP.

## Overview

A **conformance server** is a lightweight HTTP server that:
1. Listens on a configurable port (default: 3001)
2. Accepts POST requests mapping 1:1 to `BaseAdapter` methods
3. Returns JSON responses matching the TCK data model

The Python test suite connects via `HTTPBridgeAdapter`, which serializes
each method call as an HTTP POST and deserializes the response.

## Request Format

```
POST /{method_name}
Content-Type: application/json

{
  "param1": "value1",
  "param2": "value2"
}
```

- Method names match `BaseAdapter` method names exactly (snake_case)
- Parameters are serialized as a flat JSON object
- UUIDs are serialized as strings
- Datetimes are serialized as ISO 8601 strings
- `None`/`null` values are omitted from the request body

## Response Format

### Success (single object)
```
HTTP 200 OK
Content-Type: application/json

{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "role": "user",
  "content": "Hello",
  "timestamp": "2026-03-21T10:00:00Z",
  "metadata": {}
}
```

### Success (list)
```
HTTP 200 OK
Content-Type: application/json

[
  {"id": "...", ...},
  {"id": "...", ...}
]
```

### Success (boolean — e.g., delete_message)
```
HTTP 200 OK
Content-Type: application/json

{"deleted": true}
```

### Success (void — e.g., clear_session)
```
HTTP 204 No Content
```

### Null result (e.g., get_entity_by_name not found)
```
HTTP 200 OK
Content-Type: application/json

null
```

### Error
```
HTTP 400/500
Content-Type: application/json

{"error": "Description of what went wrong"}
```

## Endpoints

### Lifecycle

| Method | Parameters | Returns |
|--------|-----------|---------|
| `POST /setup` | (none) | `{"ok": true, "protocol_version": "0.1.0"}` |
| `POST /teardown` | (none) | 204 |
| `POST /clear_all_data` | (none) | 204 |

### Short-Term Memory (Bronze)

| Method | Parameters | Returns |
|--------|-----------|---------|
| `POST /add_message` | `session_id`, `role`, `content`, `metadata?` | `Message` |
| `POST /get_conversation` | `session_id`, `limit?` | `Conversation` |
| `POST /search_messages` | `query`, `session_id?`, `limit?`, `threshold?` | `Message[]` |
| `POST /list_sessions` | `limit?` | `SessionInfo[]` |
| `POST /delete_message` | `message_id` | `{"deleted": bool}` |
| `POST /clear_session` | `session_id` | 204 |

### Long-Term Memory (Silver)

| Method | Parameters | Returns |
|--------|-----------|---------|
| `POST /add_entity` | `name`, `entity_type`, `description?` | `Entity` |
| `POST /add_preference` | `category`, `preference`, `context?` | `Preference` |
| `POST /add_fact` | `subject`, `predicate`, `obj` | `Fact` |
| `POST /search_entities` | `query`, `limit?` | `Entity[]` |
| `POST /search_preferences` | `query`, `category?`, `limit?` | `Preference[]` |
| `POST /get_entity_by_name` | `name` | `Entity` or `null` |
| `POST /get_related_entities` | `entity_id`, `relationship_type?`, `depth?` | `Entity[]` |

### Reasoning Memory (Silver)

| Method | Parameters | Returns |
|--------|-----------|---------|
| `POST /start_trace` | `session_id`, `task` | `ReasoningTrace` |
| `POST /add_step` | `trace_id`, `thought?`, `action?`, `observation?` | `ReasoningStep` |
| `POST /record_tool_call` | `step_id`, `tool_name`, `arguments`, `result?`, `status?`, `duration_ms?`, `error?` | `ToolCall` |
| `POST /complete_trace` | `trace_id`, `outcome?`, `success?` | `ReasoningTrace` |
| `POST /get_trace_with_steps` | `trace_id` | `ReasoningTrace` or `null` |
| `POST /list_traces` | `session_id?`, `limit?` | `ReasoningTrace[]` |
| `POST /get_tool_stats` | `tool_name?` | `ToolStats[]` |

### Gold Tier

| Method | Parameters | Returns |
|--------|-----------|---------|
| `POST /add_relationship` | `source_id`, `target_id`, `relationship_type`, `properties?` | `Relationship` |
| `POST /merge_duplicate_entities` | `source_id`, `target_id`, `canonical_name?` | `Entity` |
| `POST /get_similar_traces` | `task`, `limit?`, `success_only?` | `ReasoningTrace[]` |

## Data Types

All response objects use the same field names as the TCK Pydantic models:

### Message
```json
{
  "id": "uuid-string",
  "role": "user|assistant|system",
  "content": "string",
  "timestamp": "ISO-8601",
  "embedding": [0.1, 0.2, ...] | null,
  "metadata": {}
}
```

### Conversation
```json
{
  "id": "uuid-string",
  "session_id": "string",
  "messages": [Message, ...],
  "title": "string" | null,
  "created_at": "ISO-8601",
  "updated_at": "ISO-8601" | null
}
```

### Entity
```json
{
  "id": "uuid-string",
  "name": "string",
  "type": "PERSON|ORGANIZATION|LOCATION|EVENT|OBJECT",
  "subtype": "string" | null,
  "description": "string" | null,
  "embedding": [...] | null,
  "canonical_name": "string" | null,
  "created_at": "ISO-8601"
}
```

### Preference
```json
{
  "id": "uuid-string",
  "category": "string",
  "preference": "string",
  "context": "string" | null,
  "embedding": [...] | null
}
```

### Fact
```json
{
  "id": "uuid-string",
  "subject": "string",
  "predicate": "string",
  "object": "string",
  "embedding": [...] | null
}
```

### ReasoningTrace
```json
{
  "id": "uuid-string",
  "session_id": "string",
  "task": "string",
  "steps": [ReasoningStep, ...],
  "outcome": "string" | null,
  "success": true | false | null,
  "started_at": "ISO-8601",
  "completed_at": "ISO-8601" | null
}
```

### ReasoningStep
```json
{
  "id": "uuid-string",
  "trace_id": "uuid-string",
  "step_number": 1,
  "thought": "string" | null,
  "action": "string" | null,
  "observation": "string" | null,
  "tool_calls": [ToolCall, ...]
}
```

### ToolCall
```json
{
  "id": "uuid-string",
  "tool_name": "string",
  "arguments": {},
  "result": any | null,
  "status": "pending|success|failure|error|timeout|cancelled",
  "duration_ms": 150 | null,
  "error": "string" | null
}
```

### ToolStats
```json
{
  "name": "string",
  "total_calls": 3,
  "successful_calls": 2,
  "failed_calls": 1,
  "success_rate": 0.667,
  "avg_duration_ms": 150.0 | null
}
```

### SessionInfo
```json
{
  "session_id": "string",
  "message_count": 5,
  "created_at": "ISO-8601",
  "updated_at": "ISO-8601" | null
}
```

### Relationship
```json
{
  "id": "uuid-string",
  "source_id": "uuid-string",
  "target_id": "uuid-string",
  "relationship_type": "string",
  "properties": {}
}
```

## Implementing a Conformance Server

1. Start an HTTP server on port 3001 (configurable)
2. Route POST requests by path to your client library methods
3. Serialize responses as JSON using the formats above
4. Run the TCK: `pytest -m bronze --bridge-url http://localhost:3001`

See `tck/bridge/reference_server.py` for a Python reference implementation.
