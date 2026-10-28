---
status: accepted
---

# Run conformance against the hosted service as a strict translator

The Java conformance server measures the Java client against the TCK through the HTTP bridge, and it does so against the hosted service only. The TypeScript, Go, C#, and R conformance jobs chain their client's bridge transport into `tck.bridge.reference_server`, but the Java client has no bridge transport under [ADR 0014](0014-keep-java-client-within-hosted-service-boundary.md), and adding one only to fit the harness would widen the client boundary for test convenience. The server is therefore configured exactly like an application, through `MemoryClient.create()`, and a run needs a Workspace API key.

The server is a strict translator. It renames and reshapes values between the snake_case bridge vocabulary and the client's domain, reorders service-ordered history into insertion order, and binds TCK Sessions to Conversations; it never emulates behavior the client or the service lacks. A field the client does not return is left out or sent as null, and a Bridge Method the client cannot carry answers HTTP 501 naming either a Client Gap or a Service Gap (see the [conformance glossary](../../conformance/GLOSSARY.md)). Emulation was rejected because it would certify the server rather than the client: fabricating timestamps, holding metadata in a side store, or hiding deleted message ids would raise the pass rate without the client doing anything.

All TCK data belongs to one fixed TCK user. Every Conversation the server creates carries that user ID, and `clear_all_data` deletes that user's memory, which keeps runs safe in a shared workspace and makes clearing a single service operation. Each Session's name is recorded in its Conversation's metadata under `tck.session`, so the binding lives on the service, survives server restarts, and lets `list_sessions` translate Conversations back into Session names; the server keeps only a cache. A no-op clear, as the TypeScript REST transport has, was rejected because results would depend on test order.

Because the hosted API has no equivalent for several Bronze and Silver Bridge Methods, such as message deletion, preferences, facts, and the TCK's Trace lifecycle, full Bronze or Silver compliance is out of reach by construction. Run the TCK directly against the conformance server and use its test results and shared compliance reporting, following the approach used by the other language clients. A Client Gap is planned client work, and a Service Gap lifts only when the service changes.

The local CI job also runs the Java HTTP server in an explicit proxy mode against
the Python reference bridge backed by Neo4j. This tests Java bridge HTTP
forwarding and the TCK harness without requiring a hosted API key. Proxy mode
does not validate the Java client, so hosted strict-translator results remain
the Java client's conformance evidence.
