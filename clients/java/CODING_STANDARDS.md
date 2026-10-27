# Java client coding standards

## Design and implementation

Use [GLOSSARY.md](GLOSSARY.md) for shared domain language. Read the ADRs for every affected concern below and follow their supersession and refinement notes. Record durable design decisions in ADRs; plans and research remain ephemeral working notes.

### API and concurrency

- **Packages and operation contract:** [capability packages and complete MemoryClient seam (0022)](docs/adr/0022-organize-packages-around-memory-operations.md).

- **Client configuration:** [immutable validated settings and base URL initialization logging (0023)](docs/adr/0023-build-validated-client-configuration.md).

- **API boundaries:** [live domain handles (0002)](docs/adr/0002-domain-oriented-java-memory-client.md), [integration-owned orchestration (0007)](docs/adr/0007-keep-agent-workflow-orchestration-outside-core.md), [hosted service, conformance, and framework boundaries (0014)](docs/adr/0014-keep-java-client-within-hosted-service-boundary.md), refined by [hosted strict-translator conformance (0026)](docs/adr/0026-run-conformance-against-the-hosted-service-as-a-strict-translator.md).
- **Async and blocking:** [operation return types (0001)](docs/adr/0001-completablefuture-for-java-client.md), [bounded waits, exception identity, and future preservation (0018)](docs/adr/0018-bound-waits-without-mutating-futures.md).

### Models

- **State and identity:** [nullable storage and optional views (0012)](docs/adr/0012-store-nullable-domain-state-directly.md), refining 0009–0010; [open entity types (0008)](docs/adr/0008-keep-entity-types-open.md), [opaque tool payloads (0010)](docs/adr/0010-keep-tool-call-payloads-opaque.md), [UUID equality (0011)](docs/adr/0011-use-domain-identity-equality.md).
- **Collections and history:** [required read arrays and optional collections (0015)](docs/adr/0015-distinguish-missing-read-results-from-empty-memory.md), [metadata and display snapshots (0016)](docs/adr/0016-preserve-conversation-metadata-on-live-handles.md), [bounded reads in service order (0017)](docs/adr/0017-expose-bounded-history-in-service-order.md).

Expose collections directly as immutable maps or lists, using empty values for optional absence.

### HTTP and failures

For transport, codecs, dependencies, or failures: [application HTTP clients through a published seam (0024)](docs/adr/0024-reuse-application-http-clients.md), superseding 0005, which superseded 0004; [internal codecs and optional dependencies (0003)](docs/adr/0003-optional-internal-json-adapters.md), refined by [runtime Jackson 3 or Jackson 2 selection (0025)](docs/adr/0025-select-jackson-major-at-runtime.md); [client-owned exceptions (0006)](docs/adr/0006-use-client-owned-exceptions.md).

For HTTP changes, follow [0013](docs/adr/0013-validate-traffic-against-published-openapi.md): validate recorded exchanges synchronously against the [vendored contract](memory-client/src/test/resources/openapi.json), retaining focused behavioral assertions alongside schema checks, and re-vendor it from the published document when it lags. Give every client operation its own `MemoryClientContractTest` test, which `LiveContractIT` reruns against the published contract, and call it on the live service in `HostedServiceIT`.

### Logging

For logging configuration, severity, or events: [application-owned JDK logging (0019)](docs/adr/0019-use-application-owned-jdk-logging.md), [caller-owned failure severity (0020)](docs/adr/0020-leave-operation-failure-severity-to-callers.md), [operation observations and content exclusion (0021)](docs/adr/0021-observe-operations-without-exposing-content.md).

### Build and release

- **Versions and releases:** [POM-owned revision and source-triggered GitHub Releases (0027)](docs/adr/0027-release-from-the-pom-revision.md).

## Verification and delivery

For client changes, run `mvn -f clients/java/pom.xml -pl memory-client -am test` from the repository root.

For hosted verification or source delivery, follow [README.md](README.md#dependency-and-source-delivery); report skipped hosted checks separately from passing tests.
