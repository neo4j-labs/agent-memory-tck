"""Deterministic test data factories for TCK tests.

All IDs and values are predictable so compliance reports are
reproducible and comparable across test runs.
"""

# Deterministic session IDs for test isolation
SESSION_A = "tck-session-alpha"
SESSION_B = "tck-session-beta"
SESSION_C = "tck-session-gamma"

# Edge-case test strings
LONG_CONTENT = "x" * 10_000  # 10K character string
UNICODE_CONTENT = "Hello \u4e16\u754c \U0001f30d \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4 \u2603\ufe0f \u2764\ufe0f\u200d\U0001f525"
SPECIAL_CHARS_CONTENT = "Line1\nLine2\tTabbed \"quoted\" 'single' back\\slash"
EMPTY_CONTENT = ""

# Nested metadata for round-trip tests
NESTED_METADATA = {
    "source": "test",
    "priority": "high",
    "tags": ["memory", "tck", "bronze"],
    "nested": {"level2": {"level3": "deep_value"}},
    "count": 42,
    "active": True,
}

# Entity test data
ENTITIES = [
    {"name": "Alice Johnson", "type": "PERSON", "description": "Software engineer at Acme Corp"},
    {"name": "Bob Smith", "type": "PERSON", "description": "Project manager"},
    {"name": "Acme Corp", "type": "ORGANIZATION", "description": "Technology company"},
    {"name": "San Francisco", "type": "LOCATION", "description": "City in California"},
    {"name": "Product Launch", "type": "EVENT", "description": "Annual product launch event"},
]

# Preference test data
PREFERENCES = [
    {
        "category": "language",
        "preference": "Prefers Python over JavaScript",
        "context": "programming",
    },
    {"category": "communication", "preference": "Prefers async communication", "context": "work"},
    {"category": "food", "preference": "Vegetarian diet", "context": "dietary restrictions"},
]

# Fact test data
FACTS = [
    {"subject": "Alice Johnson", "predicate": "WORKS_AT", "object": "Acme Corp"},
    {"subject": "Acme Corp", "predicate": "LOCATED_IN", "object": "San Francisco"},
    {"subject": "Bob Smith", "predicate": "MANAGES", "object": "Product Launch"},
]

# Message sequences for conversation tests
CONVERSATION_MESSAGES = [
    {"role": "user", "content": "Hello, I'm working on the agent memory project."},
    {"role": "assistant", "content": "I can help with the agent memory project. What do you need?"},
    {"role": "user", "content": "I need to add entity extraction for people and organizations."},
    {
        "role": "assistant",
        "content": "Entity extraction can identify people like Alice and organizations like Acme Corp from text.",
    },
    {"role": "user", "content": "Great, let's start with the Person entity type."},
]

# Reasoning trace test data
TRACE_TASK = "Find information about Alice Johnson's role at Acme Corp"

TRACE_STEPS = [
    {
        "thought": "I need to search the knowledge graph for Alice Johnson.",
        "action": "search_entities",
        "observation": "Found entity: Alice Johnson (PERSON)",
    },
    {
        "thought": "Now I need to find her relationships.",
        "action": "get_related_entities",
        "observation": "Alice Johnson WORKS_AT Acme Corp",
    },
    {
        "thought": "I have enough information to answer.",
        "action": "compose_response",
        "observation": "Response composed successfully.",
    },
]

TOOL_CALLS = [
    {
        "tool_name": "search_entities",
        "arguments": {"query": "Alice Johnson", "limit": 5},
        "result": {"entities": [{"name": "Alice Johnson", "type": "PERSON"}]},
        "status": "success",
        "duration_ms": 150,
    },
    {
        "tool_name": "get_related_entities",
        "arguments": {"entity_id": "placeholder", "depth": 1},
        "result": {"relationships": [{"type": "WORKS_AT", "target": "Acme Corp"}]},
        "status": "success",
        "duration_ms": 200,
    },
]
