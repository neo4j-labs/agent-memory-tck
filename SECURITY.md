# Security policy

## Reporting a vulnerability

If you believe you've found a security issue in `neo4j-agent-memory-tck`
or any of the language clients (`@neo4j-labs/agent-memory`,
`agent-memory-go`, `Neo4j.AgentMemory`, `neo4j-agent-memory-client`,
`neo4j.memory`), please report it privately. Do **not** open a public
GitHub issue.

Primary channel: GitHub Private Security Advisories.

1. Go to https://github.com/neo4j-labs/agent-memory-tck/security/advisories
2. Click "Report a vulnerability."
3. Fill out the form — we coordinate with you in private.

Backup channel: email `security@neo4j.com` with a description of the
issue and steps to reproduce. Reference this repository in the subject
line.

## What to include

- The package(s) and version(s) affected.
- A minimal proof-of-concept or reproduction steps.
- Your assessment of severity and impact.
- Any disclosure timeline you'd like us to honor.

## Our response

- We'll acknowledge receipt within 5 business days.
- We'll work with you on a fix and a coordinated disclosure timeline.
- Once a fix is released, we'll credit you in the advisory (unless you
  prefer to remain anonymous).

## Scope

In scope:

- Code in this repository (TCK core, language clients, demo agents,
  workflows).
- Published packages: `@neo4j-labs/agent-memory`, `agent-memory-go`,
  `Neo4j.AgentMemory`, `neo4j-agent-memory-client`, `neo4j.memory`.

Out of scope (please report to the relevant project directly):

- The hosted Neo4j Agent Memory Service at `memory.neo4jlabs.com` —
  report to `security@neo4j.com`.
- Dependencies — report to the upstream maintainer.
- Generic Neo4j product vulnerabilities — report via Neo4j's product
  security channel.

## Labs disclaimer

This is a Neo4j Labs project. There are no SLAs around response time or
fix availability, but we take security reports seriously and will
respond to coordinated disclosures as quickly as our maintenance bandwidth
allows.
