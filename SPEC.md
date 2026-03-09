# neo4j-agent-memory Specification

**Version:** 0.1.0
**Status:** Draft
**Date:** March 2026

This document is the normative specification for the neo4j-agent-memory data model and behavioral contracts. Conformant implementations MUST satisfy all requirements marked with RFC 2119 keywords (MUST, SHOULD, MAY).

---

## Volume 1 — Context Graph Schema

### 1.1 Conversation and Messages

#### Node Types

**Conversation**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique conversation identifier |
| `session_id` | string | MUST | Session identifier for grouping |
| `title` | string | MAY | Human-readable title |
| `created_at` | datetime | MUST | Creation timestamp |
| `updated_at` | datetime | MAY | Last update timestamp |

**Message**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique message identifier |
| `role` | string | MUST | One of: `user`, `assistant`, `system` |
| `content` | string | MUST | Message text content |
| `timestamp` | datetime | MUST | Message creation time |
| `embedding` | list[float] | MAY | Vector embedding |
| `metadata` | string (JSON) | MAY | Arbitrary metadata |

#### Relationships

| Type | From | To | Description |
|------|------|----|-------------|
| `HAS_MESSAGE` | Conversation | Message | Message membership |
| `FIRST_MESSAGE` | Conversation | Message | First message in sequence |
| `NEXT_MESSAGE` | Message | Message | Temporal ordering chain |

#### Behavioral Requirements

- **SPEC-1.1.1**: Adding a message to a new session MUST create a `Conversation` node.
- **SPEC-1.1.2**: Subsequent messages in the same session MUST reuse the existing `Conversation`.
- **SPEC-1.1.3**: Messages in different sessions MUST NOT be visible to each other.
- **SPEC-1.1.4**: A deleted message MUST NOT appear in conversation retrieval.

### 1.2 Entities

**Entity**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique entity identifier |
| `name` | string | MUST | Entity name |
| `type` | string | MUST | Entity type (PERSON, ORGANIZATION, LOCATION, EVENT, OBJECT) |
| `subtype` | string | MAY | Entity subtype |
| `description` | string | MAY | Entity description |
| `embedding` | list[float] | MAY | Vector embedding |
| `canonical_name` | string | MAY | Canonical form of the name |
| `created_at` | datetime | MUST | Creation timestamp |

#### Requirements

- **SPEC-1.2.1**: Entity nodes MUST have `id`, `name`, `type`, and `created_at` properties.

### 1.3 Preferences

**Preference**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique preference identifier |
| `category` | string | MUST | Preference category |
| `preference` | string | MUST | Preference statement |
| `context` | string | MAY | Contextual information |
| `embedding` | list[float] | MAY | Vector embedding |

### 1.4 Facts

**Fact**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique fact identifier |
| `subject` | string | MUST | Subject of the fact |
| `predicate` | string | MUST | Relationship/action |
| `object` | string | MUST | Object of the fact |
| `embedding` | list[float] | MAY | Vector embedding |

### 1.5 Reasoning Trace

**ReasoningTrace**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique trace identifier |
| `session_id` | string | MUST | Associated session |
| `task` | string | MUST | Task description |
| `task_embedding` | list[float] | MAY | Task embedding for similarity |
| `outcome` | string | MAY | Final outcome description |
| `success` | boolean | MAY | Whether task succeeded |
| `started_at` | datetime | MUST | Trace start time |
| `completed_at` | datetime | MAY | Trace completion time |

**ReasoningStep**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique step identifier |
| `step_number` | integer | MUST | Sequential step number |
| `thought` | string | MAY | Agent's reasoning |
| `action` | string | MAY | Action taken |
| `observation` | string | MAY | Observation from action |
| `embedding` | list[float] | MAY | Step embedding |

**ToolCall**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique call identifier |
| `tool_name` | string | MUST | Name of the tool |
| `arguments` | string (JSON) | MUST | Tool arguments |
| `result` | string (JSON) | MAY | Tool result |
| `status` | string | MUST | One of: `pending`, `success`, `failure`, `error`, `timeout`, `cancelled` |
| `duration_ms` | integer | MAY | Duration in milliseconds |
| `error` | string | MAY | Error message |

#### Reasoning Relationships

| Type | From | To | Description |
|------|------|----|-------------|
| `HAS_STEP` | ReasoningTrace | ReasoningStep | Step membership |
| `HAS_TOOL_CALL` | ReasoningStep | ToolCall | Tool call membership |

### 1.6 Cross-Memory Relationships

| Type | From | To | Description |
|------|------|----|-------------|
| `MENTIONS` | Message | Entity | Message references entity |
| `ABOUT` | Preference | Entity | Preference concerns entity |
| `ABOUT` | Fact | Entity | Fact concerns entity |
| `INITIATED_BY` | ReasoningTrace | Message | Trace triggered by message |
| `TRIGGERED_BY` | ToolCall | Message | Tool call triggered by message |

### 1.7 Entity Relationships

| Type | From | To | Description |
|------|------|----|-------------|
| `RELATED_TO` | Entity | Entity | Generic relationship |
| `KNOWS` | Entity (Person) | Entity (Person) | Personal connection |
| `WORKS_AT` | Entity (Person) | Entity (Organization) | Employment |
| `LOCATED_AT` | Entity | Entity (Location) | Location association |
| Additional POLE+O relationship types | Entity | Entity | See schema configuration |

---

## Volume 2 — Memory Primitive Contracts

### 2.1 Short-Term Memory: add_message

- **SPEC-2.1.1**: `add_message` MUST return a message with a valid UUID and timestamp.
- **SPEC-2.1.2**: `add_message` MUST accept `user` role.
- **SPEC-2.1.3**: `add_message` MUST accept `assistant` role.
- **SPEC-2.1.4**: `add_message` MUST accept `system` role.
- **SPEC-2.1.5**: `add_message` MUST preserve metadata when provided.
- **SPEC-2.1.6**: `add_message` MUST create a Conversation if the session is new.

### 2.2 Short-Term Memory: get_conversation

- **SPEC-2.2.1**: `get_conversation` MUST return messages in insertion order.
- **SPEC-2.2.2**: `get_conversation` MUST respect the `limit` parameter.
- **SPEC-2.2.3**: `get_conversation` for a non-existent session MUST return an empty conversation.
- **SPEC-2.2.4**: `get_conversation` MUST only return messages for the specified session.

### 2.3 Short-Term Memory: search_messages

- **SPEC-2.3.1**: `search_messages` MUST return messages matching the query.
- **SPEC-2.3.2**: `search_messages` with `session_id` MUST only return messages from that session.
- **SPEC-2.3.3**: `search_messages` MUST NOT return more results than `limit`.

### 2.4 Short-Term Memory: list_sessions

- **SPEC-2.4.1**: `list_sessions` MUST return all active sessions.
- **SPEC-2.4.2**: `list_sessions` MUST include accurate message counts.

### 2.5 Short-Term Memory: delete_message

- **SPEC-2.5.1**: `delete_message` MUST return `True` when the message exists.
- **SPEC-2.5.2**: A deleted message MUST NOT appear in `get_conversation` results.
- **SPEC-2.5.3**: `delete_message` for a non-existent ID MUST return `False`.

### 2.6 Short-Term Memory: clear_session

- **SPEC-2.6.1**: `clear_session` MUST remove all messages for the session.
- **SPEC-2.6.2**: `clear_session` MUST NOT affect other sessions.

### 2.7 Short-Term Memory: Message Ordering

- **SPEC-2.7.1**: Messages MUST maintain insertion order via NEXT_MESSAGE chain.
- **SPEC-2.7.2**: Message timestamps MUST be monotonically non-decreasing.

### 3.1 Long-Term Memory: add_entity

- **SPEC-3.1.1**: `add_entity` MUST return a TCKEntity with a valid UUID.
- **SPEC-3.1.2**: `add_entity` MUST store the description when provided.
- **SPEC-3.1.3–3.1.7**: `add_entity` MUST accept PERSON, ORGANIZATION, LOCATION, EVENT, and OBJECT entity types.
- **SPEC-3.1.8**: Multiple entities MUST be independently retrievable.

### 3.2 Long-Term Memory: add_preference

- **SPEC-3.2.1**: `add_preference` MUST return a TCKPreference with valid fields.
- **SPEC-3.2.2**: `add_preference` MUST store context when provided.
- **SPEC-3.2.3**: Multiple preferences MUST be independently stored.

### 3.3 Long-Term Memory: add_fact

- **SPEC-3.3.1**: `add_fact` MUST return a TCKFact with subject, predicate, and object.
- **SPEC-3.3.2**: Multiple facts MUST be independently stored.

### 3.4 Long-Term Memory: search_entities

- **SPEC-3.4.1**: `search_entities` MUST return entities matching the query.
- **SPEC-3.4.2**: `search_entities` MUST NOT return more results than limit.

### 3.5 Long-Term Memory: search_preferences

- **SPEC-3.5.1**: `search_preferences` MUST return preferences matching the query.
- **SPEC-3.5.2**: `search_preferences` with category MUST filter by category.

### 3.6 Long-Term Memory: get_entity_by_name

- **SPEC-3.6.1**: `get_entity_by_name` MUST return the entity when it exists.
- **SPEC-3.6.2**: `get_entity_by_name` MUST return None when entity doesn't exist.

### 3.7 Long-Term Memory: get_related_entities

- **SPEC-3.7.1**: `get_related_entities` MUST return connected entities.
- **SPEC-3.7.2**: `get_related_entities` for isolated entity MUST return empty list.

### 4.1 Reasoning Memory: start_trace

- **SPEC-4.1.1**: `start_trace` MUST return a TCKReasoningTrace with valid fields.
- **SPEC-4.1.2**: Each trace MUST have a unique ID.

### 4.2 Reasoning Memory: add_step

- **SPEC-4.2.1**: `add_step` MUST return a TCKReasoningStep linked to the trace.
- **SPEC-4.2.2**: Steps MUST have monotonically increasing step_number values.
- **SPEC-4.2.3**: `add_step` MUST accept partial fields.

### 4.3 Reasoning Memory: record_tool_call

- **SPEC-4.3.1**: `record_tool_call` MUST return a TCKToolCall with valid fields.
- **SPEC-4.3.2**: `record_tool_call` MUST store result and duration when provided.
- **SPEC-4.3.3**: `record_tool_call` MUST support failure statuses.
- **SPEC-4.3.4**: `record_tool_call` MUST support timeout status.

### 4.4 Reasoning Memory: complete_trace

- **SPEC-4.4.1**: `complete_trace` MUST set outcome, success, and completed_at.
- **SPEC-4.4.2**: `complete_trace` MUST support failure outcomes.

### 4.5 Reasoning Memory: get_trace_with_steps

- **SPEC-4.5.1**: `get_trace_with_steps` MUST return the full trace with all steps.
- **SPEC-4.5.2**: `get_trace_with_steps` MUST include tool calls in each step.
- **SPEC-4.5.3**: `get_trace_with_steps` for nonexistent ID MUST return None.

### 4.6 Reasoning Memory: list_traces

- **SPEC-4.6.1**: `list_traces` MUST return all traces.
- **SPEC-4.6.2**: `list_traces` with session_id MUST filter by session.
- **SPEC-4.6.3**: `list_traces` MUST respect the limit parameter.

### 4.7 Reasoning Memory: get_tool_stats

- **SPEC-4.7.1**: `get_tool_stats` MUST return accurate aggregated statistics.

---

## Volume 3 — Adapter Interface

### BaseAdapter

The `BaseAdapter` abstract class defines the interface that all TCK-testable implementations must satisfy. See `tck/adapters/base_adapter.py` for the full interface definition.

#### Lifecycle Methods

| Method | Tier | Description |
|--------|------|-------------|
| `setup()` | Bronze | Initialize implementation |
| `teardown()` | Bronze | Clean up resources |
| `clear_all_data()` | Bronze | Delete all data for test isolation |

#### Short-Term Memory Methods (Bronze)

| Method | Description |
|--------|-------------|
| `add_message(session_id, role, content, *, metadata)` | Add message to session |
| `get_conversation(session_id, *, limit)` | Retrieve conversation |
| `search_messages(query, *, session_id, limit, threshold)` | Semantic search |
| `list_sessions(*, limit)` | List all sessions |
| `delete_message(message_id)` | Delete specific message |
| `clear_session(session_id)` | Clear entire session |

#### Long-Term Memory Methods (Silver)

| Method | Description |
|--------|-------------|
| `add_entity(name, entity_type, *, description)` | Create entity |
| `add_preference(category, preference, *, context)` | Store preference |
| `add_fact(subject, predicate, obj)` | Store fact triple |
| `search_entities(query, *, limit)` | Search entities |
| `search_preferences(query, *, category, limit)` | Search preferences |
| `get_entity_by_name(name)` | Lookup entity by name |
| `get_related_entities(entity_id, *, relationship_type, depth)` | Traverse relationships |

#### Reasoning Memory Methods (Silver)

| Method | Description |
|--------|-------------|
| `start_trace(session_id, task)` | Start reasoning trace |
| `add_step(trace_id, *, thought, action, observation)` | Add step to trace |
| `record_tool_call(step_id, tool_name, arguments, *, result, status, ...)` | Record tool call |
| `complete_trace(trace_id, *, outcome, success)` | Complete trace |
| `get_trace_with_steps(trace_id)` | Get full trace |
| `list_traces(*, session_id, limit)` | List traces |
| `get_tool_stats(*, tool_name)` | Get tool statistics |

#### Gold Tier Methods (optional)

| Method | Description |
|--------|-------------|
| `add_relationship(source_id, target_id, relationship_type, *, properties)` | Create entity relationship |
| `merge_duplicate_entities(source_id, target_id, *, canonical_name)` | Merge entities |
| `get_similar_traces(task, *, limit, success_only)` | Find similar traces |

---

## Compliance Tiers

### Bronze — Core Schema + Short-Term Memory

All REQUIRED node types and relationships supported. All SPEC-1.x and SPEC-2.x MUST clauses pass.

### Silver — Full Memory Primitives

Bronze + all SPEC-3.x (Long-Term) and SPEC-4.x (Reasoning) MUST clauses pass.

### Gold — Full Specification

Silver + all SPEC-5.x (Cross-Memory) clauses pass. SHOULD clauses satisfied.

---

## Traceability Matrix

| Spec Clause | Test ID |
|-------------|---------|
| SPEC-1.1.1 | `test_schema::TestSchemaConversationCreation::test_first_message_creates_conversation` |
| SPEC-1.1.2 | `test_schema::TestSchemaConversationCreation::test_subsequent_messages_reuse_conversation` |
| SPEC-1.1.3 | `test_schema::TestSchemaSessionIsolation::test_messages_isolated_between_sessions` |
| SPEC-1.1.4 | `test_schema::TestSchemaMessageDeletion::test_deleted_message_not_retrievable` |
| SPEC-1.2.1 | `test_schema::TestSchemaEntityCreation::test_entity_created_with_required_fields` |
| SPEC-2.1.1 | `test_short_term::TestAddMessage::test_add_message_returns_valid_message` |
| SPEC-2.1.2–2.1.4 | `test_short_term::TestAddMessage::test_add_message_*_role` |
| SPEC-2.1.5 | `test_short_term::TestAddMessage::test_add_message_with_metadata` |
| SPEC-2.1.6 | `test_short_term::TestAddMessage::test_add_message_creates_conversation_on_first_call` |
| SPEC-2.2.1 | `test_short_term::TestGetConversation::test_get_conversation_message_order` |
| SPEC-2.2.2 | `test_short_term::TestGetConversation::test_get_conversation_with_limit` |
| SPEC-2.2.3 | `test_short_term::TestGetConversation::test_get_conversation_empty_session` |
| SPEC-2.2.4 | `test_short_term::TestGetConversation::test_get_conversation_multiple_sessions_isolated` |
| SPEC-2.3.1–2.3.3 | `test_short_term::TestSearchMessages::test_search_messages_*` |
| SPEC-2.4.1–2.4.2 | `test_short_term::TestListSessions::test_list_sessions_*` |
| SPEC-2.5.1–2.5.3 | `test_short_term::TestDeleteMessage::test_delete_message_*` |
| SPEC-2.6.1–2.6.2 | `test_short_term::TestClearSession::test_clear_session_*` |
| SPEC-2.7.1–2.7.2 | `test_short_term::TestMessageChainStructure::test_*` |
| SPEC-3.1.x | `test_long_term::TestAddEntity::test_*` |
| SPEC-3.2.x | `test_long_term::TestAddPreference::test_*` |
| SPEC-3.3.x | `test_long_term::TestAddFact::test_*` |
| SPEC-3.4.x | `test_long_term::TestSearchEntities::test_*` |
| SPEC-3.5.x | `test_long_term::TestSearchPreferences::test_*` |
| SPEC-3.6.x | `test_long_term::TestGetEntityByName::test_*` |
| SPEC-3.7.x | `test_long_term::TestGetRelatedEntities::test_*` |
| SPEC-4.1.x | `test_reasoning::TestStartTrace::test_*` |
| SPEC-4.2.x | `test_reasoning::TestAddStep::test_*` |
| SPEC-4.3.x | `test_reasoning::TestRecordToolCall::test_*` |
| SPEC-4.4.x | `test_reasoning::TestCompleteTrace::test_*` |
| SPEC-4.5.x | `test_reasoning::TestGetTraceWithSteps::test_*` |
| SPEC-4.6.x | `test_reasoning::TestListTraces::test_*` |
| SPEC-4.7.x | `test_reasoning::TestGetToolStats::test_*` |
| SPEC-5.x | `test_cross_memory::Test*` |
