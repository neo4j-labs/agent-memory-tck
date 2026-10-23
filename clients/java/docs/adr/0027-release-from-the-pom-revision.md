---
status: accepted
---

# Release from the POM revision

GitHub Actions releases `org.neo4j:agent-memory-client` as a patch release whenever a merge to `main` changes the client's main sources. One CI-friendly `revision` property in `clients/java/pom.xml` owns the version, and it is always a `-SNAPSHOT` between releases. A release strips the suffix, tags the released commit `java-vX.Y.Z`, and commits the next patch snapshot to `main`. The tag is derived from the POM, never the other way round, so a minor or major release is a pull request that edits `revision`, for example to `0.2.0-SNAPSHOT`, and the next qualifying merge releases `0.2.0`. A tag-triggered release like the TypeScript client's `ts-v*` was rejected because every change would wait for someone to bump and tag it by hand. Deriving the next version from the latest tag was rejected because it leaves no place to ask for a minor or major release.

Only changes under `memory-client/src/main` start a release. Tests, test dependencies, dependency versions, build configuration, and documentation do not release by themselves; they ship with the next source change, or with a manual run that forces a release. Comparing each build with the previous release's jar would answer "did the artifact change" exactly, but it was rejected as too much machinery for changes that are rare and can wait.

The GitHub side ends at a GitHub Release that carries the jar, the sources and javadoc jars, the flattened POM, and checksums. Publishing to Maven Central, signing, and verification against the hosted service belong to a separate CI that holds those credentials. This repository only keeps the artifact valid for Central: the client POM carries the required metadata, and `flatten-maven-plugin` resolves `${revision}` and publishes the POM without the `agent-memory-java` parent. The parent and `agent-memory-conformance` are never released.

## Consequences

- The tagged commit still reads `-SNAPSHOT`. Rebuilding a release means building its tag with `-Drevision=X.Y.Z`, and the other CI must follow the same rule when it rebuilds instead of reusing the GitHub Release assets.
- Each release adds one `github-actions[bot]` commit to `main`, pushed with the workflow's own `GITHUB_TOKEN`. That push starts no further workflow runs, and it works only while `main` has no branch protection or ruleset that blocks the bot.
