# Store nullable domain state directly

Public records store semantic domain types directly in their components, including nullable `String`, `Duration`, and `Instant` values when the service may omit a value. Their generated component accessors therefore expose the complete record state transparently. Where an `Optional` view is useful to callers, the record adds an explicit `getXxx()` method that returns `Optional.ofNullable(component)`; `Optional` is a return-boundary type, not part of the record's state or canonical constructor.

The same storage rule applies to non-record domain objects. Existing live-object methods such as `Conversation.userId()`, `ReasoningStep.result()`, and `ReasoningStep.createdAt()` continue returning `Optional` for source compatibility, but construct that value from raw nullable fields on each call.

The internal HTTP mapper remains responsible for transport presence. It uses the optional-view methods when deciding whether to include `userId`, `result`, `output`, and `durationMs`; absent properties remain omitted from JSON. No public model requires Jackson annotations or a JSON-specific representation.

Changing a record component from `Optional<T>` to `T` intentionally changes its canonical constructor and generated accessor. This pre-1.0 client accepts that compatibility break so records remain transparent data carriers and do not store a transport/container abstraction as state.

This decision supersedes ADR 0009 for `CreateConversation` record state, preserves its decision for the live `Conversation.userId()` return type, and refines ADR 0010 by keeping tool-call output opaque while storing it as a nullable `String`.
