---
status: accepted
---

# Validate client traffic against the published OpenAPI contract

The Java client's HTTP behavior is verified against the Swagger 2.0 contract the hosted service publishes, rather than against hand-written expectations. A copy of that contract is vendored into the repository so the unit suite stays offline and deterministic. Every exchange a test records is validated in both directions — path, method, parameters, request body, the declared security header, content types, and response status — before the test completes. On top of the contract, the suite rejects any request property the operation does not declare: the contract never forbids additional properties, so a misspelled property name would otherwise validate cleanly forever. Each operation the client maps has its own stub and its own test, so one operation's contract can be read in one place.

Response bodies are synthesized from each operation's declared success schema, with every documented property populated, rather than hand-written or recorded from the service. Where the contract is silent, conventions fill the gap: it gives identifiers and timestamps no format and leaves the message role and tool-call status of responses unenumerated, so identifiers are UUIDs, timestamps are ISO-8601 instants, and role and status take the values the client binds. These conventions record where the client relies on observed service behavior rather than on the contract.

The vendored copy is refreshed by hand during HTTP work and may lag the published one; nothing fails merely because the two differ. Instead, the same per-operation tests run a second time against the contract the configured service publishes now, so an upstream change fails the build only when it breaks an operation this client calls, while additive changes pass. That live pass runs only where an API key for the service is configured, the same switch as the end-to-end tests against the hosted service, even though the contract itself is public: one switch decides where live checks run, and builds without a key, such as release and conformance, stay independent of the service. It is skipped when the contract cannot be fetched, and fails when the service returns something that is not a contract or no longer declares an operation the client calls.

## Considered Options

- **Validating from the validator library's test-server hook.** The hook runs after the response has already returned to the client, so a check at the end of each test saw an empty report and validation silently gated nothing.
- **An overlay file carrying the conventions above.** Rejected as machinery: the conventions are few, they belong beside the code that needs them, and the vendored contract stays exactly what the service published.
- **A byte-for-byte drift check between the vendored and published contracts.** It failed on every additive upstream change, including operations this client never calls, and proved nothing about the client itself.

## Consequences

- The suite proves conformance, not correctness. It cannot see constraints the contract states only in prose — notably that a message read without a limit is capped at the service default of 50 — and its response validation is close to tautological, because fixtures are generated from the schema they are checked against. Its real response-side value is that the client decodes a fully populated documented shape without failing.
- Value-level assertions stay in focused tests, including the ADR 0012 guarantee that absent optional properties are omitted from requests: the contract cannot express it, since an omitted optional property and a present one are equally legal.
- The live pass and the end-to-end tests against the hosted service complement each other: the end-to-end tests catch behavior the contract cannot express, and the live pass catches what lenient request binding on the service hides, such as a renamed request property the service silently ignores.
