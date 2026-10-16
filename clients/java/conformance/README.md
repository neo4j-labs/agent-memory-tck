# Conformance server

The Java conformance server measures the client against the hosted service through the TCK HTTP bridge. It is a strict translator: it reshapes requests and responses without emulating missing client or service behavior. Bronze and Silver are the target; full compliance is out of reach on the hosted API. See [ADR 0026](docs/adr/0026-run-conformance-against-the-hosted-service-as-a-strict-translator.md) and the [conformance glossary](conformance/GLOSSARY.md).

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

With the server running, use a second terminal at the repository root to run all 160 Bronze and Silver scenarios and then check the report against the reviewed [Ledger](conformance/ledger.yaml):

```sh
uv run --extra bridge pytest -m 'bronze or silver' \
  --bridge-url http://localhost:3001 \
  --json-report --json-report-file=java-conformance-results.json -v
uv run python -I clients/java/conformance/check_ledger.py check java-conformance-results.json
```

If you change `TCK_BRIDGE_PORT`, use the same port in `--bridge-url`. Known gaps make pytest exit nonzero; run the Ledger check even when pytest fails. The check fails for a regression in a `pass` scenario, a missing Bronze or Silver Ledger entry, or an unknown scenario ID. It reports gap scenarios that pass without failing.

The reviewed Ledger records 51 `pass`, 46 `client-gap`, and 63 `service-gap` scenarios, with identical outcomes across three hosted runs. Six scenarios pass only through bridge defaults and remain Client Gaps: SCN-B-014, SCN-B-027, SCN-B-037, SCN-B-039, SCN-B-041, and SCN-B-085. Those passes do not demonstrate client support. Client Gaps require client or translation work; Service Gaps require a hosted service change. Gold and Platinum Bridge Methods are outside this server's scope and answer HTTP 404.
