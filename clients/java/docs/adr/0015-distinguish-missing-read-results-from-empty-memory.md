---
status: accepted
---

# Distinguish missing read results from empty memory

Conversation-list responses must contain a non-null `conversations` array, and message-read responses must contain a non-null `messages` array; a missing or null array fails with `ResponseDecodingException`, while an explicit empty array succeeds. Normalizing both cases to empty would make an incompatible service response look like a conversation or workspace with no stored memory. Optional context collections—reflections, observations, and recent messages—may be missing or null and normalize to empty instead, so `ConversationContext` always provides all three collections.

Returned collections are immutable snapshots, with `ConversationContext` defensively copying its inputs, and preserve the service's element order. Callers cannot mutate client-owned values through those collections, and message order retains its domain meaning.

Refines the deferred validation boundary in [ADR 0003](0003-optional-internal-json-adapters.md); bounded history reads are covered by [ADR 0017](0017-expose-bounded-history-in-service-order.md).
