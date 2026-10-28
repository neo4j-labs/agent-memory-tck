# Represent an optional conversation user explicitly

> Superseded in part by [ADR 0012](0012-store-nullable-domain-state-directly.md): `CreateConversation` now stores a nullable `String`, while the live `Conversation.userId()` method still returns `Optional<String>`.

`CreateConversation` and the live `Conversation` expose their optional user association as `Optional<String>` rather than a nullable string. Convenience constructors support creation with or without a known user, normalize null or blank input to absence, and the internal REST mapping omits an absent `userId` property; no user-update operation is exposed because the hosted API has no such route.
