---
status: accepted
---

# Observe operation outcomes without exposing content

> Amended by [ADR 0024](0024-reuse-application-http-clients.md): events use the
> logger `org.neo4j.agentmemory.MemoryClient`, and initialization names the
> selected transport. Request ID lookup ignores header-name case for every
> transport. LangChain4j non-success responses carry no headers, so their
> events omit `requestId`. A transport's synchronous failure now completes
> the future exceptionally in the transport phase.

> Amended by [ADR 0025](0025-select-jackson-major-at-runtime.md): initialization
> also names the selected JSON codec as `json=jackson3` or `json=jackson2`.

The implemented logging records selected operation metadata rather than payloads: a local call ID shared across client instances in the loaded client classes (not a durable or distributed identifier) correlates start and terminal events, with elapsed time, outcome, observed HTTP status, result counts, and an optional service request ID. Operation events exclude URLs; configuration fallback warnings and initialization log the selected base URL as refined by [ADR 0023](0023-build-validated-client-configuration.md). All events exclude credentials, resource IDs, content, response excerpts, and exception messages or stack traces; failures expose only their phase and unwrapped exception class name. Service request IDs must match `[A-Za-z0-9._:-]{1,128}`, allowing bounded correlation metadata without arbitrary header dumps, and formatted log text remains a diagnostic convention rather than a public event API.

Shared request helpers supply a closure to `ClientLogging.call(operation, action)`. The logging helper starts the operation, invokes the closure synchronously exactly once, observes its original returned future, and records and rethrows synchronous failures without changing their identity. The closure only supplies phase and response metadata; it does not manage logging lifecycle. Observation uses a side-effect callback whose dependent future is ignored, preserving future identity and cancellation behavior. Start timing at shared request-helper entry, include POST encoding, and report success only after status validation, decoding, and domain conversion; DELETE needs only status validation. Record one terminal outcome even when a cancelled future's exchange continues, and correlate through captured operation state rather than thread-local context; cancellation describes the caller's future outcome, not confirmed transport cancellation, and success does not assert extraction or enrichment readiness.

Formatting and backend calls guard against ordinary `RuntimeException` failures so diagnostics cannot change client results, without swallowing fatal errors or recursively logging a broken logger. Returning the original future means a caller may observe completion before logging finishes, and changing logger thresholds can suppress either event in a pair; these limits are preferable to inserting a new completion or cancellation boundary for observability.

Request ID selection uses the first available `x-request-id`, `request-id`, or `x-amzn-requestid` response header, in that order; an invalid selected value is omitted. Synchronous validation before shared request-helper entry produces no operation event. Counts describe decoded results, not service readiness.

[ADR 0019](0019-use-application-owned-jdk-logging.md) records the backend choice; [ADR 0020](0020-leave-operation-failure-severity-to-callers.md) records the accepted severity policy. The [README](../../README.md#blocking-helper-and-logging) documents fields and package configuration.

Verification on 2026-09-29: the final local regression command `mvn -f clients/java/pom.xml -pl memory-client -am test -Dmaven.compiler.source=17 -Dmaven.compiler.target=17` passed 136 tests with zero failures, errors, or skips (Java 17.0.20.1, Maven 3.8.7; command-only compiler overrides support its older default compiler plugin). An isolated temporary Maven application resolved the README's `slf4j-jdk-platform-logging:2.0.20` and `logback-classic:1.5.38` using `mvn dependency:build-classpath -Dmdep.outputFile=classpath.txt`. Its Java 17 probe used the exact README XML with an absolute `logback.configurationFile` path and a 20-second process deadline: package INFO/root WARN emitted `probe-initialized` (INFO) and `probe-invalid-setting` (WARN) once each and suppressed `probe-completed` (DEBUG). A fresh JVM changing only the package threshold to DEBUG emitted all three exactly once. This verifies routing and inheritance independently of mock logger tests, not guaranteed event delivery. The probe and dependencies remained outside the repository and core POM; POMs and public declarations are unchanged from the execution baseline. Hosted credential-gated checks were not run and are not represented by these local results.
