---
status: superseded by ADR-0024
---

# Use JDK HTTP without a transport extension

> Superseded by [ADR 0024](0024-reuse-application-http-clients.md): a
> published transport seam now lets applications supply a configured JDK,
> Spring `RestClient` or `WebClient`, or LangChain4j HTTP client, or their
> own transport.
> The decisions against automatic retries and a `MemoryClient` lifecycle
> method remain in force.

The initial Java client uses an unconfigured `java.net.http.HttpClient.newHttpClient()` internally and exposes no transport abstraction or public injection point, including no overload that accepts a configured JDK client. It adds no client-level connection timeout, executor, redirect policy, protocol preference, proxy, authenticator, retry policy, or request timeout; those policies belong to a future framework-owned client. Although a transport extension could let applications reuse existing HTTP infrastructure, choosing its level before implementing real Spring and LangChain4j integrations risks optimizing for low-level clients when those integrations may instead need framework-native clients such as Spring REST Client or the LangChain4j HTTP client. Tests exercise the client through a local HTTP server; a transport seam will be reconsidered only when concrete framework integrations establish its requirements.

The core performs no automatic retries, particularly of mutating POST requests: without an idempotency contract, a lost response leaves the write outcome ambiguous and replay can duplicate persisted data. Applications and integrations may apply an explicit retry policy outside the core. `MemoryClient` also exposes no lifecycle method; deterministic shutdown belongs to a lower-level implementation only when it owns resources requiring it, rather than becoming part of the domain interface preemptively.

[ADR 0018](0018-bound-waits-without-mutating-futures.md) bounds caller waiting without adding a transport deadline.
