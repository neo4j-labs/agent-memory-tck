---
status: superseded by ADR-0005
---

# Expose a minimal verb-shaped HTTP transport

The Java client exposes an asynchronous HTTP transport extension interface so applications can reuse the HTTP client already carrying their observability and environment configuration, while `MemoryClient.create(...)` supplies a vanilla JDK adapter by default. Following the verb-oriented shape of RabbitMQ Hop rather than a general request executor, the interface has explicit GET, POST, and DELETE operations returning `CompletableFuture` values and exchanging URIs, pass-through headers, and raw byte bodies and responses; NAMS endpoint mapping, authentication, JSON processing, and response interpretation remain inside the memory client. A blocking adapter owns its execution and offloading strategy rather than causing the memory client to hide blocking work on an implicit executor.

Detailed normalization of non-success statuses, timeouts, and adapter-native failures remains provisional until a real Spring or LangChain4j integration exercises the seam. The initial transport response must preserve the HTTP status, headers, and raw body so that this later evaluation does not begin from information loss.

Applications inject a ready transport adapter instance rather than a transport factory, allowing an adapter to wrap the HTTP client already constructed and configured by the application. A `MemoryClient` builder accepts that instance; the existing `MemoryClient.create(apiKey)` convenience path constructs the default JDK adapter.

The JDK adapter supports both constructing a vanilla `java.net.http.HttpClient` and wrapping an existing configured instance. Injection does not transfer ownership of an application-provided HTTP client, and the transport interface does not impose a lifecycle method in this initial design.

Each operation returns a transport-owned response containing the status code, multi-value response headers, and a non-null raw byte body. Outbound headers use a single-value map because the initial NAMS requests require only one value per header; adapters pass those values to their underlying client without interpreting NAMS semantics.
