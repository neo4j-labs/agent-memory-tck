---
status: accepted
---

# Preserve conversation metadata on live handles

Conversation list and detail responses expose different information, so the existing live `Conversation` carries their available metadata, timestamps, title, first-message snippet, and message count without introducing a separate public summary model. Its UUID and operations remain available, while the detail fields describe the response snapshot and require a fresh read after remote changes. This preserves useful service data and the established domain handle without fabricating timestamps, treating a missing count as zero, or presenting the first-message snippet as the latest-message preview.

Conversation metadata is an immutable `Map<String, String>`, empty when absent; nullable scalar state follows [ADR 0012](0012-store-nullable-domain-state-directly.md), with optional views on the live handle. `title()` prefers the supplied list title and otherwise reads `metadata.title`, preserving the original metadata separately rather than generating display text. `CreateConversation` accepts this string map alongside optional `userId`, omits empty metadata on the wire, and retains its no-argument and user-only constructors; conversation UUIDs remain service-generated, and no title-update operation is introduced for this use case.

The requirements allowed either a summary or enriched conversation model; the selected shape is implemented by [Conversation](../../memory-client/src/main/java/org/neo4j/agentmemory/conversation/Conversation.java) and [CreateConversation](../../memory-client/src/main/java/org/neo4j/agentmemory/conversation/CreateConversation.java).
