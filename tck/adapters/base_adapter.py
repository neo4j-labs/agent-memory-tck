"""Base adapter interface and TCK data models.

Implementations must subclass BaseAdapter and provide a concrete implementation
for each abstract method corresponding to their target compliance tier.
"""

from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class MessageRole(str, Enum):
    """Message role in a conversation."""

    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class ToolCallStatus(str, Enum):
    """Status of a tool call."""

    PENDING = "pending"
    SUCCESS = "success"
    FAILURE = "failure"
    ERROR = "error"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"


# ---------------------------------------------------------------------------
# TCK Data Models — implementation-agnostic Pydantic models
# ---------------------------------------------------------------------------


class TCKMessage(BaseModel):
    """A single message in a conversation."""

    id: UUID
    role: str
    content: str
    timestamp: datetime
    embedding: list[float] | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class TCKConversation(BaseModel):
    """A conversation (session) containing messages."""

    id: UUID
    session_id: str
    messages: list[TCKMessage] = Field(default_factory=list)
    title: str | None = None
    created_at: datetime
    updated_at: datetime | None = None


class TCKSessionInfo(BaseModel):
    """Summary information about a session."""

    session_id: str
    message_count: int = 0
    created_at: datetime
    updated_at: datetime | None = None


class TCKEntity(BaseModel):
    """A named entity in the knowledge graph."""

    id: UUID
    name: str
    type: str
    subtype: str | None = None
    description: str | None = None
    embedding: list[float] | None = None
    canonical_name: str | None = None
    created_at: datetime


class TCKPreference(BaseModel):
    """A user preference."""

    id: UUID
    category: str
    preference: str
    context: str | None = None
    embedding: list[float] | None = None


class TCKFact(BaseModel):
    """A subject-predicate-object fact triple."""

    id: UUID
    subject: str
    predicate: str
    object: str
    embedding: list[float] | None = None


class TCKRelationship(BaseModel):
    """A typed relationship between two entities."""

    id: UUID
    source_id: UUID
    target_id: UUID
    relationship_type: str
    properties: dict[str, Any] = Field(default_factory=dict)


class TCKReasoningTrace(BaseModel):
    """A complete reasoning trace for a task."""

    id: UUID
    session_id: str
    task: str
    steps: list["TCKReasoningStep"] = Field(default_factory=list)
    outcome: str | None = None
    success: bool | None = None
    started_at: datetime
    completed_at: datetime | None = None


class TCKReasoningStep(BaseModel):
    """A step in the agent's reasoning process."""

    id: UUID
    trace_id: UUID
    step_number: int
    thought: str | None = None
    action: str | None = None
    observation: str | None = None
    tool_calls: list["TCKToolCall"] = Field(default_factory=list)


class TCKToolCall(BaseModel):
    """A single tool invocation."""

    id: UUID
    tool_name: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    result: Any = None
    status: ToolCallStatus = ToolCallStatus.PENDING
    duration_ms: int | None = None
    error: str | None = None


class TCKToolStats(BaseModel):
    """Aggregated statistics for a tool."""

    name: str
    total_calls: int = 0
    successful_calls: int = 0
    failed_calls: int = 0
    success_rate: float = 0.0
    avg_duration_ms: float | None = None


# ---------------------------------------------------------------------------
# Volume 5 / Platinum tier — hosted-service operations
# ---------------------------------------------------------------------------


class TCKObservation(BaseModel):
    """Auto-generated message-window summary."""

    id: UUID
    conversation_id: UUID
    content: str
    window_start: datetime | None = None
    window_end: datetime | None = None
    created_at: datetime


class TCKReflection(BaseModel):
    """Higher-level insight derived from observations."""

    id: UUID
    conversation_id: UUID
    content: str
    created_at: datetime


class TCKConversationContext(BaseModel):
    """Three-tier conversational context."""

    reflections: list[TCKReflection] = Field(default_factory=list)
    observations: list[TCKObservation] = Field(default_factory=list)
    recent_messages: list[TCKMessage] = Field(default_factory=list)


class TCKEntityMention(BaseModel):
    """One mention of an entity inside a conversation."""

    conversation_id: UUID
    message_id: UUID | None = None
    content: str
    timestamp: datetime


class TCKEntityHistory(BaseModel):
    """All cross-conversation mentions of one entity."""

    entity_id: UUID
    mentions: list[TCKEntityMention] = Field(default_factory=list)


class TCKEntityGraphNode(BaseModel):
    id: UUID
    name: str
    type: str


class TCKEntityGraphEdge(BaseModel):
    id: UUID
    source: UUID
    target: UUID
    type: str


class TCKEntityGraph(BaseModel):
    nodes: list[TCKEntityGraphNode] = Field(default_factory=list)
    edges: list[TCKEntityGraphEdge] = Field(default_factory=list)


class TCKEntityFeedbackResult(BaseModel):
    id: UUID
    updated: bool


class TCKEntityMergeResult(BaseModel):
    source_id: UUID
    target_id: UUID
    status: str


class TCKAgentStep(BaseModel):
    """Hosted-service flat reasoning step (per conversation)."""

    id: UUID
    conversation_id: UUID
    reasoning: str
    action_taken: str
    result: str | None = None
    created_at: datetime


class TCKAgentStepExplanation(TCKAgentStep):
    """Detailed step explanation with tool calls + influenced entities."""

    tool_calls: list[TCKToolCall] = Field(default_factory=list)
    influenced_entities: list[TCKEntity] = Field(default_factory=list)


class TCKConversationTrace(BaseModel):
    """Hosted: flat reasoning trace for one conversation."""

    conversation_id: UUID
    steps: list[TCKAgentStep] = Field(default_factory=list)
    tool_calls: list[TCKToolCall] = Field(default_factory=list)


class TCKEntityProvenance(BaseModel):
    """Reasoning chain that influenced an entity."""

    entity_id: UUID
    steps: list[TCKAgentStep] = Field(default_factory=list)


class TCKCypherResult(BaseModel):
    """Read-only Cypher query result."""

    columns: list[str] = Field(default_factory=list)
    rows: list[list[Any]] = Field(default_factory=list)
    stats: dict[str, Any] | None = None


class TCKBulkMessageInput(BaseModel):
    """One message inside a bulk add."""

    role: str
    content: str
    metadata: dict[str, Any] | None = None


# ---------------------------------------------------------------------------
# BaseAdapter — the interface implementations must satisfy
# ---------------------------------------------------------------------------


class BaseAdapter(ABC):
    """Abstract base adapter for TCK compliance testing.

    Implementations must subclass this and provide concrete implementations
    for the abstract methods. Methods are grouped by compliance tier:

    - Bronze: setup/teardown, short-term memory methods
    - Silver: long-term memory + reasoning memory methods
    - Gold: cross-memory and advanced methods (have default NotImplementedError)
    """

    # --- Lifecycle ---

    @abstractmethod
    async def setup(self) -> None:
        """Initialize the implementation (connect, create schema, etc.)."""

    @abstractmethod
    async def teardown(self) -> None:
        """Clean up resources (close connections, etc.)."""

    @abstractmethod
    async def clear_all_data(self) -> None:
        """Delete all data for test isolation. Called before each test."""

    # --- Short-Term Memory (Bronze) ---

    @abstractmethod
    async def add_message(
        self,
        session_id: str,
        role: str,
        content: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> TCKMessage:
        """Add a message to a session. Creates the session if it doesn't exist."""

    @abstractmethod
    async def get_conversation(
        self,
        session_id: str,
        *,
        limit: int | None = None,
    ) -> TCKConversation:
        """Retrieve a conversation by session ID with its messages in order."""

    @abstractmethod
    async def search_messages(
        self,
        query: str,
        *,
        session_id: str | None = None,
        limit: int = 10,
        threshold: float = 0.7,
    ) -> list[TCKMessage]:
        """Search messages by semantic similarity."""

    @abstractmethod
    async def list_sessions(
        self,
        *,
        limit: int = 100,
    ) -> list[TCKSessionInfo]:
        """List all sessions."""

    @abstractmethod
    async def delete_message(self, message_id: UUID) -> bool:
        """Delete a specific message. Returns True if deleted, False if not found."""

    @abstractmethod
    async def clear_session(self, session_id: str) -> None:
        """Delete all data for a specific session."""

    # --- Long-Term Memory (Silver) ---

    @abstractmethod
    async def add_entity(
        self,
        name: str,
        entity_type: str,
        *,
        description: str | None = None,
    ) -> TCKEntity:
        """Create or update an entity in the knowledge graph."""

    @abstractmethod
    async def add_preference(
        self,
        category: str,
        preference: str,
        *,
        context: str | None = None,
    ) -> TCKPreference:
        """Store a user preference."""

    @abstractmethod
    async def add_fact(
        self,
        subject: str,
        predicate: str,
        obj: str,
    ) -> TCKFact:
        """Store a subject-predicate-object fact triple."""

    @abstractmethod
    async def search_entities(
        self,
        query: str,
        *,
        limit: int = 10,
    ) -> list[TCKEntity]:
        """Search entities by semantic similarity."""

    @abstractmethod
    async def search_preferences(
        self,
        query: str,
        *,
        category: str | None = None,
        limit: int = 10,
    ) -> list[TCKPreference]:
        """Search preferences by semantic similarity."""

    @abstractmethod
    async def get_entity_by_name(self, name: str) -> TCKEntity | None:
        """Look up an entity by exact name. Returns None if not found."""

    @abstractmethod
    async def get_related_entities(
        self,
        entity_id: UUID,
        *,
        relationship_type: str | None = None,
        depth: int = 1,
    ) -> list[TCKEntity]:
        """Get entities related to the given entity."""

    # --- Reasoning Memory (Silver) ---

    @abstractmethod
    async def start_trace(
        self,
        session_id: str,
        task: str,
    ) -> TCKReasoningTrace:
        """Start a new reasoning trace for a task."""

    @abstractmethod
    async def add_step(
        self,
        trace_id: UUID,
        *,
        thought: str | None = None,
        action: str | None = None,
        observation: str | None = None,
    ) -> TCKReasoningStep:
        """Add a reasoning step to a trace."""

    @abstractmethod
    async def record_tool_call(
        self,
        step_id: UUID,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        result: Any = None,
        status: ToolCallStatus = ToolCallStatus.SUCCESS,
        duration_ms: int | None = None,
        error: str | None = None,
    ) -> TCKToolCall:
        """Record a tool call within a reasoning step."""

    @abstractmethod
    async def complete_trace(
        self,
        trace_id: UUID,
        *,
        outcome: str | None = None,
        success: bool | None = None,
    ) -> TCKReasoningTrace:
        """Complete a reasoning trace with outcome."""

    @abstractmethod
    async def get_trace_with_steps(self, trace_id: UUID) -> TCKReasoningTrace | None:
        """Get a full reasoning trace including steps and tool calls."""

    @abstractmethod
    async def list_traces(
        self,
        *,
        session_id: str | None = None,
        limit: int = 100,
    ) -> list[TCKReasoningTrace]:
        """List reasoning traces, optionally filtered by session."""

    @abstractmethod
    async def get_tool_stats(
        self,
        tool_name: str | None = None,
    ) -> list[TCKToolStats]:
        """Get aggregated tool usage statistics."""

    # --- Gold Tier (optional — default raises NotImplementedError) ---

    async def add_relationship(
        self,
        source_id: UUID,
        target_id: UUID,
        relationship_type: str,
        *,
        properties: dict[str, Any] | None = None,
    ) -> TCKRelationship:
        """Create a typed relationship between two entities."""
        raise NotImplementedError("Gold tier: add_relationship")

    async def merge_duplicate_entities(
        self,
        source_id: UUID,
        target_id: UUID,
        *,
        canonical_name: str | None = None,
    ) -> TCKEntity:
        """Merge two duplicate entities into one."""
        raise NotImplementedError("Gold tier: merge_duplicate_entities")

    async def get_similar_traces(
        self,
        task: str,
        *,
        limit: int = 5,
        success_only: bool = True,
    ) -> list[TCKReasoningTrace]:
        """Find reasoning traces similar to a given task description."""
        raise NotImplementedError("Gold tier: get_similar_traces")

    # --- Platinum Tier (Volume 5 — hosted-service operations) ---
    # All optional. Implementations that don't expose these features
    # should leave the NotImplementedError defaults in place; the TCK
    # Platinum tests will skip them.

    async def create_conversation(
        self,
        user_id: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> TCKConversation:
        """Create a new conversation (hosted REST API)."""
        raise NotImplementedError("Platinum tier: create_conversation")

    async def list_conversations(
        self,
        *,
        limit: int | None = None,
    ) -> list[TCKConversation]:
        """List all conversations the API key can access."""
        raise NotImplementedError("Platinum tier: list_conversations")

    async def get_conversation_metadata(self, conversation_id: UUID) -> TCKConversation:
        """Fetch conversation metadata (without messages)."""
        raise NotImplementedError("Platinum tier: get_conversation_metadata")

    async def delete_conversation(self, conversation_id: UUID) -> None:
        """Delete a conversation and all its messages."""
        raise NotImplementedError("Platinum tier: delete_conversation")

    async def get_context(self, conversation_id: UUID) -> TCKConversationContext:
        """Three-tier context: reflections + observations + recent messages."""
        raise NotImplementedError("Platinum tier: get_context")

    async def bulk_add_messages(
        self,
        conversation_id: UUID,
        messages: list[TCKBulkMessageInput],
    ) -> list[TCKMessage]:
        """Bulk-add up to 100 messages in one request."""
        raise NotImplementedError("Platinum tier: bulk_add_messages")

    async def get_observations(
        self,
        conversation_id: UUID,
        *,
        limit: int | None = None,
    ) -> list[TCKObservation]:
        """Auto-generated message-window summaries."""
        raise NotImplementedError("Platinum tier: get_observations")

    async def get_reflections(
        self,
        conversation_id: UUID,
    ) -> list[TCKReflection]:
        """Higher-level reflections derived from observations."""
        raise NotImplementedError("Platinum tier: get_reflections")

    async def list_entities(
        self,
        *,
        type: str | None = None,
        limit: int | None = None,
    ) -> list[TCKEntity]:
        """List entities, optionally filtered by type."""
        raise NotImplementedError("Platinum tier: list_entities")

    async def get_entity(self, entity_id: UUID) -> TCKEntity:
        """Fetch one entity (with relationships) by id."""
        raise NotImplementedError("Platinum tier: get_entity")

    async def update_entity(
        self,
        entity_id: UUID,
        *,
        name: str | None = None,
        description: str | None = None,
    ) -> TCKEntity:
        """Update an entity's name and/or description."""
        raise NotImplementedError("Platinum tier: update_entity")

    async def delete_entity(self, entity_id: UUID) -> None:
        """Delete an entity and its relationships."""
        raise NotImplementedError("Platinum tier: delete_entity")

    async def set_entity_feedback(
        self,
        entity_id: UUID,
        *,
        user_score: float,
        confirmed: bool,
    ) -> TCKEntityFeedbackResult:
        """Score an entity 0-1 and optionally mark it human-confirmed."""
        raise NotImplementedError("Platinum tier: set_entity_feedback")

    async def get_entity_history(self, entity_id: UUID) -> TCKEntityHistory:
        """All cross-conversation mentions of this entity."""
        raise NotImplementedError("Platinum tier: get_entity_history")

    async def merge_entities(
        self,
        source_id: UUID,
        target_id: UUID,
    ) -> TCKEntityMergeResult:
        """Merge source into target via the hosted REST endpoint (creates SAME_AS)."""
        raise NotImplementedError("Platinum tier: merge_entities")

    async def get_entity_graph(self) -> TCKEntityGraph:
        """Full-graph view of every entity + edge."""
        raise NotImplementedError("Platinum tier: get_entity_graph")

    async def explain_step(self, step_id: UUID) -> TCKAgentStepExplanation:
        """Detailed step explanation: tool calls + influenced entities."""
        raise NotImplementedError("Platinum tier: explain_step")

    async def get_trace_by_conversation(
        self,
        conversation_id: UUID,
    ) -> TCKConversationTrace:
        """Hosted: flat reasoning trace (steps + tool calls) for a conversation."""
        raise NotImplementedError("Platinum tier: get_trace_by_conversation")

    async def get_entity_provenance(
        self,
        entity_id: UUID,
    ) -> TCKEntityProvenance:
        """Reasoning chain that influenced an entity."""
        raise NotImplementedError("Platinum tier: get_entity_provenance")

    async def record_step(
        self,
        *,
        conversation_id: UUID,
        reasoning: str,
        action_taken: str,
        result: str | None = None,
    ) -> TCKAgentStep:
        """Record one reasoning step under a conversation (hosted REACT model)."""
        raise NotImplementedError("Platinum tier: record_step")

    async def list_steps(self, conversation_id: UUID) -> list[TCKAgentStep]:
        """List all reasoning steps for one conversation."""
        raise NotImplementedError("Platinum tier: list_steps")

    async def cypher_query(
        self,
        cypher: str,
        params: dict[str, Any] | None = None,
    ) -> TCKCypherResult:
        """Execute a read-only Cypher query."""
        raise NotImplementedError("Platinum tier: cypher_query")
