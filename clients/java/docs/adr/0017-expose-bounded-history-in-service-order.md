---
status: accepted
---

# Expose bounded recent history in service order

The consumer needs recent messages for restoration and previews, so `Conversation.messages(int limit)` sends one request for 1–200 messages and rejects out-of-range values synchronously with `IllegalArgumentException`; the existing `messages()` sends no limit and retains the documented service default of 50. Both return immutable snapshots in the service's documented newest-first order, preserving UUIDs, roles, content, and repeated messages without sorting, reversing, or deduplicating. `listConversations` likewise returns one service-selected result set using the supplied limit; automatic pagination and public cursor or offset types remain deferred until the hosted contract supports them.

Applications own chronological conversion, local model windows, title and preview formatting, and eviction. A bounded read does not trim or replace remote history, and a client-side timestamp sort cannot repair an unspecified service tie order or select recently active conversations omitted by the server. The equal-timestamp message ordering and latest-activity conversation selection requested in S1–S2 remain service-verification questions, not guarantees established by this ADR; the message limit range and default are prose-only contract constraints that schema validation cannot detect drifting.

Complements [ADR 0013](0013-validate-traffic-against-published-openapi.md) and [ADR 0015](0015-distinguish-missing-read-results-from-empty-memory.md).
