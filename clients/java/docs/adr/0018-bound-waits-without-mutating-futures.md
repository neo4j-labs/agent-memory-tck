---
status: accepted
---

# Bound synchronous waits without mutating futures

Synchronous consumers use the static `MemoryClient.await(future)` and `MemoryClient.await(future, Duration)` helpers, while every remote operation continues to return `CompletableFuture`. A helper can wait on a client operation or application-composed future without constructing a client or duplicating the domain surface in a synchronous facade. Its timeout measures only time spent waiting: timeout and interruption neither cancel nor complete the supplied future, retry the operation, or impose an HTTP deadline, so other consumers can still obtain its eventual result and a timed-out write may already have been applied.

Waiting returns the original value, including null for a successful void operation, and unwraps only `ExecutionException` and `CompletionException` wrappers with causes. Client exceptions, other runtime exceptions, cancellation, and errors retain their original identity; checked failures become causes of `MemoryClientException`. A wait timeout or interruption also becomes a `MemoryClientException` cause, and only interruption of the actual wait restores the current thread's interrupt flag.

An explicit positive duration, representable in nanoseconds as a `long`, bypasses default configuration. Otherwise `NAMS_AWAIT_TIMEOUT_SECONDS` is resolved lazily once per loaded library, accepting trimmed positive whole seconds and falling back to 30 seconds for absent, blank, or invalid values rather than failing class initialization. That fallback is reported once at INFO for absence or WARNING for invalid input, giving all callers a shared bounded default without repeated configuration noise; independent class loaders retain independent defaults.

Extends [ADR 0001](0001-completablefuture-for-java-client.md) and preserves the transport boundary in [ADR 0005](0005-use-jdk-http-without-a-transport-extension.md).
