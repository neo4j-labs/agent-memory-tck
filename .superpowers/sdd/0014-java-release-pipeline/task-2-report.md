# Task 2 report: attach sources and Javadoc jars

## Changes

Updated `clients/java/memory-client/pom.xml` only: appended the Maven Source Plugin 3.4.0 (`attach-sources`, `jar-no-fork`) and Maven Javadoc Plugin 3.12.0 (`attach-javadocs`, `jar`) after flatten. Javadoc is configured with `doclint` `all,-missing`.

The temporary `{@link DoesNotExist}` was removed from `MemoryClient.java`; no Java source or test file remains changed.

## Commands and results

1. `mvn -q -f clients/java/pom.xml -Drevision=9.9.9 -pl memory-client -am package -DskipTests`
   - Exit 0.
   - Produced `memory-client/target/agent-memory-client-9.9.9.jar` (104,718 bytes), `agent-memory-client-9.9.9-sources.jar` (39,536 bytes), and `agent-memory-client-9.9.9-javadoc.jar` (315,174 bytes).
   - This package command explicitly skips tests (`-DskipTests`).

2. Doclint proof: temporarily added `See {@link DoesNotExist}.` to the `MemoryClient` Javadoc. Cleared the stale Javadoc output with `mvn -q -f clients/java/pom.xml -Drevision=9.9.9 -pl memory-client -am clean`, then ran the same package command above.
   - Exit 1 as required.
   - Javadoc reported `MemoryClient.java:22: error: reference not found`, then Maven reported `BUILD FAILURE` in `maven-javadoc-plugin:3.12.0:jar` (`attach-javadocs`).
   - Reverted the temporary edit. Re-ran the package command successfully to restore and verify all three artifacts.
   - A first proof attempt without clearing stale Javadoc output returned success; deleting generated output made the proof exercise Javadoc generation against the temporary source change.

3. `mvn -f clients/java/pom.xml -pl memory-client -am test`
   - `BUILD SUCCESS`; Maven reported no failures or errors. The focused run summaries included 31 tests in `MemoryClientLoggingTest`, 24 in `JsonCodecTest`, and all other executed suites passing.

4. `git diff --check`
   - Exit 0; no whitespace errors.

## Skipped checks

- The packaging invocation used `-DskipTests`; its tests were intentionally skipped. The separate client test command above ran successfully.
- Whole-build `verify`, hosted integration tests, and workflow lint were not run; they are outside the requested Task 2 command sequence.

## Self-review

- The plugin declarations match the brief values and are located after flatten.
- The commit contains the requested POM change and this report; the temporary Java-source change has been reverted.
- The pre-existing untracked `clients/java/docs/adr/0027-release-from-the-pom-revision.md` is user work and is not included.

## Concerns

None. The Javadoc proof must run after stale generated Javadoc output is cleared; the successful final package run verified the documented sources build normally.
