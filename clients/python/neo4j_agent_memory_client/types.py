"""Canonical types — snake_case throughout (matches the TCK Pydantic models)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

MessageRole = Literal["user", "assistant", "system"]
ToolCallStatus = Literal[
    "pending", "success", "failure", "error", "timeout", "cancelled"
]


@dataclass
class Message:
    id: str
    role: str
    content: str
    timestamp: str = ""
    embedding: list[float] | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    conversation_id: str | None = None


@dataclass
class Conversation:
    id: str
    session_id: str = ""
    messages: list[Message] = field(default_factory=list)
    title: str | None = None
    created_at: str = ""
    updated_at: str | None = None
    workspace_id: str | None = None
    user_id: str | None = None
    metadata: dict[str, Any] | None = None


@dataclass
class SessionInfo:
    session_id: str
    message_count: int = 0
    created_at: str = ""
    updated_at: str | None = None


@dataclass
class Observation:
    id: str
    conversation_id: str
    content: str
    window_start: str | None = None
    window_end: str | None = None
    created_at: str = ""


@dataclass
class Reflection:
    id: str
    conversation_id: str
    content: str
    created_at: str = ""


@dataclass
class ConversationContext:
    reflections: list[Reflection] = field(default_factory=list)
    observations: list[Observation] = field(default_factory=list)
    recent_messages: list[Message] = field(default_factory=list)


@dataclass
class BulkMessageInput:
    role: str
    content: str
    metadata: dict[str, Any] | None = None


@dataclass
class EntityRelationshipRef:
    id: str
    type: str
    target_id: str
    target_name: str | None = None
    properties: dict[str, Any] | None = None


@dataclass
class Entity:
    id: str
    name: str
    type: str
    subtype: str | None = None
    description: str | None = None
    embedding: list[float] | None = None
    canonical_name: str | None = None
    created_at: str = ""
    updated_at: str | None = None
    confidence: float | None = None
    source_stage: str | None = None
    relationships: list[EntityRelationshipRef] | None = None


@dataclass
class EntityMention:
    conversation_id: str
    message_id: str | None
    content: str
    timestamp: str


@dataclass
class EntityHistory:
    entity_id: str
    mentions: list[EntityMention] = field(default_factory=list)


@dataclass
class EntityGraphNode:
    id: str
    name: str
    type: str


@dataclass
class EntityGraphEdge:
    id: str
    source: str
    target: str
    type: str


@dataclass
class EntityGraph:
    nodes: list[EntityGraphNode] = field(default_factory=list)
    edges: list[EntityGraphEdge] = field(default_factory=list)


@dataclass
class EntityFeedbackResult:
    id: str
    updated: bool


@dataclass
class EntityMergeResult:
    source_id: str
    target_id: str
    status: str


@dataclass
class Preference:
    id: str
    category: str
    preference: str
    context: str | None = None
    embedding: list[float] | None = None


@dataclass
class Fact:
    id: str
    subject: str
    predicate: str
    object: str
    embedding: list[float] | None = None


@dataclass
class Relationship:
    id: str
    source_id: str
    target_id: str
    relationship_type: str
    properties: dict[str, Any] | None = None


@dataclass
class ToolCall:
    id: str
    tool_name: str
    arguments: dict[str, Any] = field(default_factory=dict)
    result: Any = None
    status: str = "success"
    duration_ms: int | None = None
    error: str | None = None


@dataclass
class ReasoningStep:
    id: str
    trace_id: str = ""
    step_number: int = 0
    thought: str | None = None
    action: str | None = None
    observation: str | None = None
    tool_calls: list[ToolCall] = field(default_factory=list)


@dataclass
class ReasoningTrace:
    id: str
    session_id: str = ""
    task: str = ""
    steps: list[ReasoningStep] = field(default_factory=list)
    outcome: str | None = None
    success: bool | None = None
    started_at: str = ""
    completed_at: str | None = None


@dataclass
class ToolStats:
    name: str
    total_calls: int = 0
    successful_calls: int = 0
    failed_calls: int = 0
    success_rate: float = 0.0
    avg_duration_ms: float | None = None


@dataclass
class AgentStep:
    id: str
    conversation_id: str
    reasoning: str
    action_taken: str
    result: str | None = None
    created_at: str = ""


@dataclass
class AgentStepExplanation:
    id: str
    conversation_id: str
    reasoning: str
    action_taken: str
    result: str | None = None
    created_at: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    influenced_entities: list[Entity] = field(default_factory=list)


@dataclass
class ConversationTrace:
    conversation_id: str
    steps: list[AgentStep] = field(default_factory=list)
    tool_calls: list[ToolCall] = field(default_factory=list)


@dataclass
class EntityProvenance:
    entity_id: str
    steps: list[AgentStep] = field(default_factory=list)


@dataclass
class CypherResult:
    columns: list[str] = field(default_factory=list)
    rows: list[list[Any]] = field(default_factory=list)
    stats: dict[str, Any] | None = None


@dataclass
class ApiKey:
    id: str
    label: str
    workspace_id: str
    created_at: str
    scopes: list[str] = field(default_factory=list)
    expires_at: str | None = None
    key: str | None = None


@dataclass
class AccessTokenPair:
    access_token: str
    refresh_token: str
    expires_in: int
