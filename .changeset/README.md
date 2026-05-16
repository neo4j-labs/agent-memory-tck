# Changesets

Hello and welcome! This folder collects "changesets" — one Markdown file
per user-visible change. When a PR introduces a change worth a CHANGELOG
entry, the contributor runs:

```bash
npx changeset
```

…which asks which package(s) the change affects, what kind of bump it
warrants (patch / minor / major), and a short summary. The result is a
file in this directory.

On merge to `main`, a bot opens a "Version Packages" PR that aggregates
all pending changesets into version bumps + CHANGELOG entries. When
that PR merges, tagged releases are cut and published to npm.

For the TypeScript client at `@neo4j-labs/agent-memory` (Beta /
0.x), breaking changes can ship as a `minor` bump. CHANGELOG callouts
are required for anything externally observable.

Full docs: https://github.com/changesets/changesets
