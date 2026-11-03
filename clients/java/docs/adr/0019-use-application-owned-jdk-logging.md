---
status: accepted
---

# Let applications own the logging backend

> Amended by [ADR 0024](0024-reuse-application-http-clients.md): initialization
> and operation events use the logger `org.neo4j.agentmemory.MemoryClient`
> instead of `org.neo4j.agentmemory.JdkMemoryClient`.

The Java 17 client emits diagnostics through JDK `System.Logger` under `org.neo4j.agentmemory`, without a required runtime logging dependency or public logging configuration API. Applications select the backend and thresholds, including an optional SLF4J JDK platform bridge and Logback backend, so the core does not impose a logging stack on framework integrations. Initialization and operation summaries use `org.neo4j.agentmemory.JdkMemoryClient`. The implemented await helper uses `org.neo4j.agentmemory.AwaitSupport`; applications must configure logging before its first default-timeout call because its once-only fallback event is not replayed.

Configure the package logger `org.neo4j.agentmemory` at INFO for initialization and await configuration, or DEBUG for ordinary operation summaries, independently of a WARN root. Backend configuration does not guarantee delivery, and joining an operation future does not flush logging.

The [README logging section](../../README.md#blocking-helper-and-logging) supplies application-side bridge/backend dependencies and package configuration. [ADR 0020](0020-leave-operation-failure-severity-to-callers.md) records severity ownership and [ADR 0021](0021-observe-operations-without-exposing-content.md) records the implemented observation boundary and verification evidence.
