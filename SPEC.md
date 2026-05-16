# neo4j-agent-memory Specification

**Version:** 1.0.0
**Status:** Release Candidate
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
- **SPEC-1.1.5**: Conversation MUST have a valid UUID `id` property.
- **SPEC-1.1.6**: Conversation MUST have a `created_at` timestamp set at creation time.
- **SPEC-1.1.7**: Conversation `title` MAY be `None` by default.
- **SPEC-1.1.8**: The same session MUST yield the same conversation ID across multiple `get_conversation` calls.
- **SPEC-1.1.9**: Different sessions MUST produce different Conversation nodes with distinct IDs.
- **SPEC-1.1.10**: Deleting one message MUST NOT alter other messages in the conversation.
- **SPEC-1.1.11**: Message MUST have a non-null `id` property.
- **SPEC-1.1.12**: Message MUST have a `role` property matching the input role.
- **SPEC-1.1.13**: Message MUST have a `content` property matching the input content.
- **SPEC-1.1.14**: Message MUST have a non-null `timestamp` property of type datetime.

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
- **SPEC-1.2.2**: Entity `id` MUST be a valid UUID.
- **SPEC-1.2.3**: Entity MUST accept and preserve the `PERSON` type label.
- **SPEC-1.2.4**: Entity MUST accept and preserve the `ORGANIZATION` type label.
- **SPEC-1.2.5**: Entity MUST accept and preserve the `LOCATION` type label.
- **SPEC-1.2.6**: Entity MUST accept and preserve the `EVENT` type label.
- **SPEC-1.2.7**: Entity MUST accept and preserve the `OBJECT` type label.
- **SPEC-1.2.8**: Entity `created_at` MUST be a non-null timestamp.

### 1.3 Preferences

**Preference**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique preference identifier |
| `category` | string | MUST | Preference category |
| `preference` | string | MUST | Preference statement |
| `context` | string | MAY | Contextual information |
| `embedding` | list[float] | MAY | Vector embedding |

#### Requirements

- **SPEC-1.3.1**: Preference MUST have `id`, `category`, and `preference` properties.
- **SPEC-1.3.2**: Preference `id` MUST be a valid UUID.

### 1.4 Facts

**Fact**

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | UUID (string) | MUST | Unique fact identifier |
| `subject` | string | MUST | Subject of the fact |
| `predicate` | string | MUST | Relationship/action |
| `object` | string | MUST | Object of the fact |
| `embedding` | list[float] | MAY | Vector embedding |

#### Requirements

- **SPEC-1.4.1**: Fact MUST have `id`, `subject`, `predicate`, and `object` properties.
- **SPEC-1.4.2**: Fact `id` MUST be a valid UUID.

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
- **SPEC-2.1.7**: `add_message` MUST accept empty string content.
- **SPEC-2.1.8**: `add_message` MUST preserve content of 10K+ characters.
- **SPEC-2.1.9**: `add_message` MUST preserve unicode characters and emoji.
- **SPEC-2.1.10**: `add_message` MUST preserve newlines, tabs, quotes, and backslashes.
- **SPEC-2.1.11**: `add_message` with empty dict metadata MUST succeed.
- **SPEC-2.1.12**: `add_message` MUST preserve nested metadata structures.
- **SPEC-2.1.13**: `add_message` with no metadata MUST default to an empty dict.
- **SPEC-2.1.14**: `add_message` MUST return a valid UUID for the message ID.
- **SPEC-2.1.15**: `add_message` timestamp MUST be within a reasonable window of the current time.
- **SPEC-2.1.16**: 50 messages added rapidly MUST all be stored and ordered correctly.

### 2.2 Short-Term Memory: get_conversation

- **SPEC-2.2.1**: `get_conversation` MUST return messages in insertion order.
- **SPEC-2.2.2**: `get_conversation` MUST respect the `limit` parameter.
- **SPEC-2.2.3**: `get_conversation` for a non-existent session MUST return an empty conversation.
- **SPEC-2.2.4**: `get_conversation` MUST only return messages for the specified session.
- **SPEC-2.2.5**: `get_conversation` with limit greater than message count MUST return all messages.
- **SPEC-2.2.6**: `get_conversation` with limit=1 MUST return exactly one message.
- **SPEC-2.2.7**: `get_conversation` MUST preserve special characters in content.
- **SPEC-2.2.8**: `get_conversation` MUST preserve metadata on retrieved messages.
- **SPEC-2.2.9**: `get_conversation` MUST preserve the role of each message.
- **SPEC-2.2.10**: `get_conversation` MUST maintain order for 20+ messages.
- **SPEC-2.2.11**: `get_conversation` MUST return a conversation with a valid UUID id.
- **SPEC-2.2.12**: Three separate sessions MUST have fully isolated conversations.

### 2.3 Short-Term Memory: search_messages

- **SPEC-2.3.1**: `search_messages` MUST return messages matching the query.
- **SPEC-2.3.2**: `search_messages` with `session_id` MUST only return messages from that session.
- **SPEC-2.3.3**: `search_messages` MUST NOT return more results than `limit`.
- **SPEC-2.3.4**: `search_messages` MUST return empty list when nothing matches at high threshold.
- **SPEC-2.3.5**: `search_messages` with limit=1 MUST return at most 1 result.
- **SPEC-2.3.6**: `search_messages` on empty database MUST return empty list.
- **SPEC-2.3.7**: `search_messages` without session_id MUST search across all sessions.

### 2.4 Short-Term Memory: list_sessions

- **SPEC-2.4.1**: `list_sessions` MUST return all active sessions.
- **SPEC-2.4.2**: `list_sessions` MUST include accurate message counts.
- **SPEC-2.4.3**: `list_sessions` with no sessions MUST return empty list.
- **SPEC-2.4.4**: `list_sessions` with one session MUST return exactly one entry.
- **SPEC-2.4.5**: `list_sessions` MUST respect the `limit` parameter.
- **SPEC-2.4.6**: `list_sessions` entries MUST have a `created_at` timestamp.
- **SPEC-2.4.7**: `list_sessions` MUST reflect accurate count after message deletion.
- **SPEC-2.4.8**: `list_sessions` MUST report independent counts per session.

### 2.5 Short-Term Memory: delete_message

- **SPEC-2.5.1**: `delete_message` MUST return `True` when the message exists.
- **SPEC-2.5.2**: A deleted message MUST NOT appear in `get_conversation` results.
- **SPEC-2.5.3**: `delete_message` for a non-existent ID MUST return `False`.
- **SPEC-2.5.4**: Deleting a message MUST preserve the order of remaining messages.
- **SPEC-2.5.5**: Deleting the first message MUST preserve remaining messages.
- **SPEC-2.5.6**: Deleting the last message MUST preserve earlier messages.
- **SPEC-2.5.7**: Deleting a middle message MUST repair the ordering chain.
- **SPEC-2.5.8**: Deleting all messages one by one MUST leave session empty.
- **SPEC-2.5.9**: Deleting the same message twice MUST return `False` on second call.

### 2.6 Short-Term Memory: clear_session

- **SPEC-2.6.1**: `clear_session` MUST remove all messages for the session.
- **SPEC-2.6.2**: `clear_session` MUST NOT affect other sessions.
- **SPEC-2.6.3**: `clear_session` on an empty or non-existent session MUST NOT raise an error.
- **SPEC-2.6.4**: A cleared session MUST accept new messages after clearing.
- **SPEC-2.6.5**: Clearing one of multiple sessions MUST preserve all other sessions.

### 2.7 Short-Term Memory: Message Ordering

- **SPEC-2.7.1**: Messages MUST maintain insertion order via NEXT_MESSAGE chain.
- **SPEC-2.7.2**: Message timestamps MUST be monotonically non-decreasing.
- **SPEC-2.7.3**: A single message in a session MUST be retrievable.
- **SPEC-2.7.4**: Two messages MUST be correctly ordered.
- **SPEC-2.7.5**: Deleting a middle message MUST maintain chain integrity for remaining messages.
- **SPEC-2.7.6**: 100 messages MUST maintain correct insertion order.
- **SPEC-2.7.7**: Messages with mixed roles MUST maintain insertion order.

### 2.8 Short-Term Memory: Idempotency

- **SPEC-2.8.1**: Each `add_message` call MUST return a message with a unique ID.
- **SPEC-2.8.2**: Duplicate content MUST be stored as separate messages.
- **SPEC-2.8.3**: Calling `clear_session` multiple times MUST NOT raise errors.

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

## Volume 4 — Cross-Memory and Multi-Agent Contracts

### 5.1 Cross-Memory Entity References

- **SPEC-5.1.1**: An entity created via long-term memory MUST be referenceable in reasoning traces.
- **SPEC-5.1.2**: A full flow from conversation to entity creation to reasoning MUST be supported.
- **SPEC-5.1.3**: Entities created in one session MUST be visible when queried from another session.
- **SPEC-5.1.4**: Facts MUST be storable alongside entities they reference by name.
- **SPEC-5.1.5**: Preferences MUST be storable alongside related entities.
- **SPEC-5.1.6**: A reasoning trace MUST be creatable in the same session as conversation messages.

### 5.2 Entity Relationships

- **SPEC-5.2.1**: `add_relationship` MUST create a typed edge between two entities.
- **SPEC-5.2.2**: Related entities MUST be discoverable via `get_related_entities` from the source.
- **SPEC-5.2.3**: Multiple relationship types between entities MUST be supported.
- **SPEC-5.2.4**: `add_relationship` MUST return a relationship with a valid UUID ID.

### 5.3 Entity Merging

- **SPEC-5.3.1**: `merge_duplicate_entities` MUST combine two entities into one.
- **SPEC-5.3.2**: Merged entity MUST retain relationships from both source entities.

### 5.4 Similar Trace Search

- **SPEC-5.4.1**: `get_similar_traces` MUST return traces with similar tasks.
- **SPEC-5.4.2**: `get_similar_traces` MUST respect the `limit` parameter.
- **SPEC-5.4.3**: `get_similar_traces` on empty database MUST return empty list.

### 5.5 Multi-Agent Memory Sharing

- **SPEC-5.5.1**: Entities created in one session MUST be searchable from another session context.
- **SPEC-5.5.2**: Reasoning traces MUST be filterable by session, enabling per-agent isolation.
- **SPEC-5.5.3**: Conversations MUST be isolated per session while entities are shared across sessions.

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
| SPEC-1.1.5 | `test_schema::TestSchemaConversationCreation::test_conversation_id_is_valid_uuid` |
| SPEC-1.1.6 | `test_schema::TestSchemaConversationCreation::test_conversation_created_at_is_set` |
| SPEC-1.1.7 | `test_schema::TestSchemaConversationCreation::test_conversation_title_is_optional` |
| SPEC-1.1.8 | `test_schema::TestSchemaConversationCreation::test_conversation_reuses_same_id_across_messages` |
| SPEC-1.1.9 | `test_schema::TestSchemaSessionIsolation::test_sessions_have_different_conversation_ids` |
| SPEC-1.1.10 | `test_schema::TestSchemaMessageDeletion::test_deletion_does_not_affect_other_messages` |
| SPEC-1.1.11 | `test_schema::TestSchemaMessageProperties::test_message_has_id` |
| SPEC-1.1.12 | `test_schema::TestSchemaMessageProperties::test_message_has_role` |
| SPEC-1.1.13 | `test_schema::TestSchemaMessageProperties::test_message_has_content` |
| SPEC-1.1.14 | `test_schema::TestSchemaMessageProperties::test_message_has_timestamp` |
| SPEC-1.2.1 | `test_schema::TestSchemaEntityCreation::test_entity_created_with_required_fields` |
| SPEC-1.2.2 | `test_schema::TestSchemaEntityCreation::test_entity_id_is_valid_uuid` |
| SPEC-1.2.3–1.2.7 | `test_schema::TestSchemaEntityCreation::test_entity_*_type_schema` |
| SPEC-1.2.8 | `test_schema::TestSchemaEntityCreation::test_entity_created_at_is_set` |
| SPEC-1.3.1 | `test_schema::TestSchemaPreferenceCreation::test_preference_has_required_fields` |
| SPEC-1.3.2 | `test_schema::TestSchemaPreferenceCreation::test_preference_id_is_valid_uuid` |
| SPEC-1.4.1 | `test_schema::TestSchemaFactCreation::test_fact_has_required_fields` |
| SPEC-1.4.2 | `test_schema::TestSchemaFactCreation::test_fact_id_is_valid_uuid` |
| SPEC-2.1.1 | `test_short_term::TestAddMessage::test_add_message_returns_valid_message` |
| SPEC-2.1.2–2.1.4 | `test_short_term::TestAddMessage::test_add_message_*_role` |
| SPEC-2.1.5 | `test_short_term::TestAddMessage::test_add_message_with_metadata` |
| SPEC-2.1.6 | `test_short_term::TestAddMessage::test_add_message_creates_conversation_on_first_call` |
| SPEC-2.1.7 | `test_short_term::TestAddMessage::test_add_message_empty_content` |
| SPEC-2.1.8 | `test_short_term::TestAddMessage::test_add_message_long_content` |
| SPEC-2.1.9 | `test_short_term::TestAddMessage::test_add_message_unicode_content` |
| SPEC-2.1.10 | `test_short_term::TestAddMessage::test_add_message_special_characters` |
| SPEC-2.1.11 | `test_short_term::TestAddMessage::test_add_message_empty_metadata` |
| SPEC-2.1.12 | `test_short_term::TestAddMessage::test_add_message_nested_metadata` |
| SPEC-2.1.13 | `test_short_term::TestAddMessage::test_add_message_null_metadata_defaults_empty` |
| SPEC-2.1.14 | `test_short_term::TestAddMessage::test_add_message_uuid_format` |
| SPEC-2.1.15 | `test_short_term::TestAddMessage::test_add_message_timestamp_is_recent` |
| SPEC-2.1.16 | `test_short_term::TestAddMessage::test_add_message_rapid_succession` |
| SPEC-2.2.1 | `test_short_term::TestGetConversation::test_get_conversation_message_order` |
| SPEC-2.2.2 | `test_short_term::TestGetConversation::test_get_conversation_with_limit` |
| SPEC-2.2.3 | `test_short_term::TestGetConversation::test_get_conversation_empty_session` |
| SPEC-2.2.4 | `test_short_term::TestGetConversation::test_get_conversation_multiple_sessions_isolated` |
| SPEC-2.2.5 | `test_short_term::TestGetConversation::test_get_conversation_limit_exceeds_count` |
| SPEC-2.2.6 | `test_short_term::TestGetConversation::test_get_conversation_limit_one` |
| SPEC-2.2.7 | `test_short_term::TestGetConversation::test_get_conversation_preserves_content_fidelity` |
| SPEC-2.2.8 | `test_short_term::TestGetConversation::test_get_conversation_preserves_metadata` |
| SPEC-2.2.9 | `test_short_term::TestGetConversation::test_get_conversation_preserves_roles` |
| SPEC-2.2.10 | `test_short_term::TestGetConversation::test_get_conversation_twenty_messages_ordered` |
| SPEC-2.2.11 | `test_short_term::TestGetConversation::test_get_conversation_returns_valid_conversation_id` |
| SPEC-2.2.12 | `test_short_term::TestGetConversation::test_get_conversation_three_sessions_fully_isolated` |
| SPEC-2.3.1 | `test_short_term::TestSearchMessages::test_search_messages_finds_relevant` |
| SPEC-2.3.2 | `test_short_term::TestSearchMessages::test_search_messages_session_filter` |
| SPEC-2.3.3 | `test_short_term::TestSearchMessages::test_search_messages_respects_limit` |
| SPEC-2.3.4 | `test_short_term::TestSearchMessages::test_search_messages_no_results` |
| SPEC-2.3.5 | `test_short_term::TestSearchMessages::test_search_messages_limit_one` |
| SPEC-2.3.6 | `test_short_term::TestSearchMessages::test_search_messages_empty_database` |
| SPEC-2.3.7 | `test_short_term::TestSearchMessages::test_search_messages_across_sessions` |
| SPEC-2.4.1 | `test_short_term::TestListSessions::test_list_sessions_returns_all` |
| SPEC-2.4.2 | `test_short_term::TestListSessions::test_list_sessions_includes_message_count` |
| SPEC-2.4.3 | `test_short_term::TestListSessions::test_list_sessions_empty` |
| SPEC-2.4.4 | `test_short_term::TestListSessions::test_list_sessions_single_session` |
| SPEC-2.4.5 | `test_short_term::TestListSessions::test_list_sessions_respects_limit` |
| SPEC-2.4.6 | `test_short_term::TestListSessions::test_list_sessions_created_at_present` |
| SPEC-2.4.7 | `test_short_term::TestListSessions::test_list_sessions_message_count_after_delete` |
| SPEC-2.4.8 | `test_short_term::TestListSessions::test_list_sessions_multiple_sessions_independent_counts` |
| SPEC-2.5.1 | `test_short_term::TestDeleteMessage::test_delete_message_returns_true` |
| SPEC-2.5.2 | `test_short_term::TestDeleteMessage::test_delete_message_removes_from_conversation` |
| SPEC-2.5.3 | `test_short_term::TestDeleteMessage::test_delete_message_nonexistent_returns_false` |
| SPEC-2.5.4 | `test_short_term::TestDeleteMessage::test_delete_message_preserves_remaining_order` |
| SPEC-2.5.5 | `test_short_term::TestDeleteMessage::test_delete_first_message` |
| SPEC-2.5.6 | `test_short_term::TestDeleteMessage::test_delete_last_message` |
| SPEC-2.5.7 | `test_short_term::TestDeleteMessage::test_delete_middle_message` |
| SPEC-2.5.8 | `test_short_term::TestDeleteMessage::test_delete_all_messages_one_by_one` |
| SPEC-2.5.9 | `test_short_term::TestDeleteMessage::test_delete_same_message_twice` |
| SPEC-2.6.1 | `test_short_term::TestClearSession::test_clear_session_removes_all_messages` |
| SPEC-2.6.2 | `test_short_term::TestClearSession::test_clear_session_preserves_other_sessions` |
| SPEC-2.6.3 | `test_short_term::TestClearSession::test_clear_session_idempotent_on_empty` |
| SPEC-2.6.4 | `test_short_term::TestClearSession::test_clear_session_then_add_new_messages` |
| SPEC-2.6.5 | `test_short_term::TestClearSession::test_clear_session_multiple_sessions_selective` |
| SPEC-2.7.1 | `test_short_term::TestMessageChainStructure::test_messages_maintain_insertion_order` |
| SPEC-2.7.2 | `test_short_term::TestMessageChainStructure::test_timestamps_are_monotonically_increasing` |
| SPEC-2.7.3 | `test_short_term::TestMessageChainStructure::test_chain_with_single_message` |
| SPEC-2.7.4 | `test_short_term::TestMessageChainStructure::test_chain_with_two_messages` |
| SPEC-2.7.5 | `test_short_term::TestMessageChainStructure::test_chain_integrity_after_middle_delete` |
| SPEC-2.7.6 | `test_short_term::TestMessageChainStructure::test_large_chain_ordering` |
| SPEC-2.7.7 | `test_short_term::TestMessageChainStructure::test_mixed_roles_maintain_order` |
| SPEC-2.8.1 | `test_short_term::TestIdempotency::test_add_message_returns_unique_ids` |
| SPEC-2.8.2 | `test_short_term::TestIdempotency::test_duplicate_content_stored_separately` |
| SPEC-2.8.3 | `test_short_term::TestIdempotency::test_clear_session_is_idempotent` |
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

---

# Volume 5 — Hosted-Service Operations (Platinum Tier)

**Status:** Optional. Implementations that target the hosted Neo4j Agent
Memory Service at `https://memory.neo4jlabs.com/v1` SHOULD implement these
operations. The TCK Platinum tier validates them; implementations that omit
them remain Bronze/Silver/Gold compliant.

## 5.1 — Conversation Lifecycle

- **SPEC-5.1.1** `create_conversation(user_id, metadata?)` MUST return a
  Conversation with a fresh UUID `id`, the supplied `user_id`, and a
  `workspace_id` scoped to the API key.
- **SPEC-5.1.2** `list_conversations(limit?)` MUST return Conversations
  the API key has access to, newest-first.
- **SPEC-5.1.3** `delete_conversation(id)` MUST be idempotent.
- **SPEC-5.1.4** `bulk_add_messages(conversation_id, messages)` MUST cap
  input at 100 messages and return them in insertion order.

## 5.2 — Three-Tier Context

- **SPEC-5.2.1** `get_context(conversation_id)` MUST return three lists:
  `reflections`, `observations`, `recent_messages`.
- **SPEC-5.2.2** Observations and reflections are generated asynchronously;
  they MAY be empty for newly-created conversations.

## 5.3 — Entity Feedback, History, and Graph

- **SPEC-5.3.1** `set_entity_feedback(entity_id, user_score, confirmed)`
  MUST return `{id, updated: bool}`. `user_score` is in [0, 1].
- **SPEC-5.3.2** `get_entity_history(entity_id)` MUST return every
  cross-conversation mention of the entity.
- **SPEC-5.3.3** `merge_entities(source_id, target_id)` MUST leave a
  `SAME_AS` provenance link from the source to the target.
- **SPEC-5.3.4** `get_entity_graph()` MUST return a node-and-edge view
  suitable for visualization.

## 5.4 — Reasoning Provenance

- **SPEC-5.4.1** `record_step(conversation_id, reasoning, action_taken,
  result?)` MUST persist the step under the conversation.
- **SPEC-5.4.2** `explain_step(step_id)` MUST return the step's tool calls
  and the entities it influenced.
- **SPEC-5.4.3** `get_trace_by_conversation(conversation_id)` MUST return
  all steps and tool calls for the conversation.
- **SPEC-5.4.4** `get_entity_provenance(entity_id)` MUST return the
  reasoning chain that influenced the entity's creation.

## 5.5 — Cypher Console

- **SPEC-5.5.1** `cypher_query(cypher, params?)` MUST execute read-only
  queries. Write operations MUST be rejected.

| Spec ID | Test Reference |
|---------|----------------|
| SPEC-5.1.x | `test_platinum::TestConversationLifecycle::test_*` |
| SPEC-5.2.x | `test_platinum::TestContext::test_*` |
| SPEC-5.3.x | `test_platinum::TestEntityFeedbackAndGraph::test_*` |
| SPEC-5.4.x | `test_platinum::TestReasoningProvenance::test_*` |
| SPEC-5.5.x | `test_platinum::TestCypherConsole::test_*` |
