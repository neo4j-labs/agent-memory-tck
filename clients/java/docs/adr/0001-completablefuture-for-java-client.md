# Use CompletableFuture for the Java client API

The Java 17 core client exposes only asynchronous operations, returning `CompletableFuture<T>` or `CompletableFuture<Void>`. This matches the JDK HTTP client's native return type, retains its discoverable best-effort cancellation path, and avoids a runtime dependency; the initial implementation adds no bespoke cancellation propagation, while synchronous and framework-reactive facades remain optional downstream adapters.

[ADR 0018](0018-bound-waits-without-mutating-futures.md) adds static `MemoryClient.await(...)` helpers for synchronous consumers without changing operation return types or introducing a second client facade.
