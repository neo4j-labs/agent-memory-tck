---
status: accepted
---

# Release from the POM revision

GitHub Actions releases `org.neo4j:agent-memory-client` as a patch release whenever a merge to `main` changes the client's main sources. One CI-friendly `revision` property in `clients/java/pom.xml` owns the version, and it is always a `-SNAPSHOT` between releases. A release strips the suffix, tags the released commit `java-vX.Y.Z`, and commits the next patch snapshot to `main`. The tag is derived from the POM, never the other way round, so a minor or major release is a pull request that edits `revision`, for example to `0.2.0-SNAPSHOT`, and the next qualifying merge releases `0.2.0`. A tag-triggered release like the TypeScript client's `ts-v*` was rejected because every change would wait for someone to bump and tag it by hand. Deriving the next version from the latest tag was rejected because it leaves no place to ask for a minor or major release.

Maven plugins do the release work, so the workflow has no scripted release logic. `maven-release-plugin` computes the versions, verifies the build, commits, tags, and pushes. It updates the `revision` property rather than a literal `<version>`. `jreleaser-maven-plugin`, bound to a `release` profile of the client POM, creates the GitHub Release from the tag, and generates the checksums and the notes. Scripting the same steps in the workflow was rejected, and so was a tool that derives versions from tags, which has no place for a minor or major bump.

Only changes under `memory-client/src/main` start a release. Tests, test dependencies, dependency versions, build configuration, and documentation do not release by themselves; they ship with the next source change, or with a manual run, which always releases. The path filter replaces any comparison with the previous tag. Comparing each build with the previous release's jar would answer "did the artifact change" exactly, but it was rejected as too much machinery for changes that are rare and can wait.

The GitHub side ends at a GitHub Release that carries the jar, the sources and javadoc jars, the flattened POM, and checksums. Publishing to Maven Central, signing, and verification against the hosted service belong to a separate CI that holds those credentials. This repository only keeps the artifact valid for Central: the client POM carries the required metadata, and `flatten-maven-plugin` resolves `${revision}` and publishes the POM without the `agent-memory-java` parent. The parent and `agent-memory-conformance` are never released.

## Consequences

- The tagged commit holds the release version, so a release is rebuilt by building its tag without extra flags. The other CI can do the same instead of reusing the GitHub Release assets.
- Each release adds two `github-actions[bot]` commits to `main`, a release commit and a bump commit, pushed with the workflow's own `GITHUB_TOKEN`. That push starts no further workflow runs, and it works only while `main` has no branch protection or ruleset that blocks the bot. The commits also touch `memory-client/pom.xml`, which is outside the trigger path.
- The release commit, the tag, and the bump commit are pushed one after another, not atomically. A merge that lands during the run fails the first push before anything is published. A merge that lands in the seconds between pushes leaves `main` on a release version until a pull request restores the `-SNAPSHOT`.
- A queued run releases whatever `main` holds when it starts, so a change already released by the previous run can produce a second patch release with the same content.
- The release notes and the checksum file follow JReleaser's conventions: the file is `SHA256SUMS.sha256`, and it still verifies with `sha256sum -c`.
