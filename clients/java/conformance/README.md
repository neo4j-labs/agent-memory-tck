# Conformance server

The Java conformance server measures the client against the hosted service through the TCK HTTP bridge. It is a strict translator: it reshapes requests and responses without emulating missing client or service behavior. Bronze and Silver are the target; full compliance is out of reach on the hosted API. See [ADR 0026](../docs/adr/0026-run-conformance-against-the-hosted-service-as-a-strict-translator.md) and the [conformance glossary](GLOSSARY.md).

Run these commands from the repository root with Java 17 and Maven installed:

```sh
export MEMORY_API_KEY='<workspace-api-key>'
export MEMORY_ENDPOINT='https://memory.neo4jlabs.com/v1'
export TCK_BRIDGE_PORT=3001
make conformance-java
```

The equivalent launch command is:

```sh
mvn -f clients/java/pom.xml -q -pl conformance -am compile exec:java
```

`MEMORY_API_KEY` must be a Workspace API key for the target workspace. `MEMORY_ENDPOINT` is optional and defaults to `https://memory.neo4jlabs.com/v1` when absent or blank. `TCK_BRIDGE_PORT` is optional and defaults to `3001`.

**Every test deletes the `tck-java` user's memory in the target workspace.** Reserve that user for conformance runs. Sessions bind to that user's Conversations through the `tck.session` metadata key.

With the server running, use a second terminal at the repository root to run all 160 Bronze and Silver scenarios:

```sh
uv run --extra bridge pytest -m 'bronze or silver' \
  --bridge-url http://localhost:3001 \
  --json-report --json-report-file=java-conformance-results.json -v
```

If you change `TCK_BRIDGE_PORT`, use the same port in `--bridge-url`. Pytest reports the conformance results directly and exits nonzero when scenarios fail, including known gaps. The JSON report records the results for inspection or use with the shared TCK compliance reporter.

Some scenarios pass through defaults supplied by the bridge adapter, such as timestamps and empty metadata. Those passes do not demonstrate client support for the fields. Client Gaps require client or translation work; Service Gaps require a hosted service change. Gold and Platinum Bridge Methods are outside this server's scope and answer HTTP 404.

Message metadata is forwarded to the client and returned from service responses.
Absent, null, or empty metadata is accepted. The hosted contract supports only
string values: numeric, boolean, null, array, or object values within metadata
receive HTTP 501 naming the Service Gap before any service call. A non-object
metadata field receives HTTP 400. The string-only metadata scenario can pass;
the typed and nested metadata scenarios still require a service change.
