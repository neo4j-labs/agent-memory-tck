---
status: accepted
---

# Use client-owned exceptions

Asynchronous client operations fail through a small public hierarchy rooted at `MemoryClientException`: `MemoryServiceException` represents non-success HTTP responses, `ResponseDecodingException` represents invalid or incompatible success payloads, and `MissingJsonCodecException` reports at construction time that Jackson 3 is unavailable. `MemoryServiceException` carries the client operation, status code, immutable response headers, content type, and a bounded response-body excerpt; the client does not invent status-specific subclasses until the hosted contract gives statuses stable domain semantics. Network failures are wrapped once in `MemoryClientException` with the JDK exception retained as the cause. This prevents JDK HTTP and Jackson implementation details from becoming the caller's stable error contract while preserving their diagnostic information.
