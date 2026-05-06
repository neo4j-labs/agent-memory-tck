"""Python client for the Neo4j Agent Memory Service.

Two transports ship in-box:

- BridgeTransport: TCK bridge protocol (POST /{snake_case_method}).
- RestTransport: hosted REST API at https://memory.neo4jlabs.com/v1.

Construction picks the transport from the endpoint shape (REST when /v1).
"""

from .client import MemoryClient
from .errors import (
    AuthenticationError,
    ConnectionError as MemoryConnectionError,
    MemoryError,
    NotSupportedError,
    TransportError,
)
from .types import (
    AccessTokenPair,
    AgentStep,
    AgentStepExplanation,
    ApiKey,
    BulkMessageInput,
    Conversation,
    ConversationContext,
    ConversationTrace,
    CypherResult,
    Entity,
    EntityFeedbackResult,
    EntityGraph,
    EntityHistory,
    EntityMergeResult,
    EntityProvenance,
    Fact,
    Message,
    MessageRole,
    Observation,
    Preference,
    ReasoningStep,
    ReasoningTrace,
    Reflection,
    Relationship,
    SessionInfo,
    ToolCall,
    ToolCallStatus,
    ToolStats,
)

__version__ = "0.1.0"
__all__ = [
    "MemoryClient",
    "MemoryError",
    "TransportError",
    "AuthenticationError",
    "MemoryConnectionError",
    "NotSupportedError",
    "AccessTokenPair",
    "AgentStep",
    "AgentStepExplanation",
    "ApiKey",
    "BulkMessageInput",
    "Conversation",
    "ConversationContext",
    "ConversationTrace",
    "CypherResult",
    "Entity",
    "EntityFeedbackResult",
    "EntityGraph",
    "EntityHistory",
    "EntityMergeResult",
    "EntityProvenance",
    "Fact",
    "Message",
    "MessageRole",
    "Observation",
    "Preference",
    "ReasoningStep",
    "ReasoningTrace",
    "Reflection",
    "Relationship",
    "SessionInfo",
    "ToolCall",
    "ToolCallStatus",
    "ToolStats",
]
