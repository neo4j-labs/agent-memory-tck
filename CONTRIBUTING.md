# Contributing to neo4j-agent-memory TCK

Thank you for your interest in contributing to the neo4j-agent-memory Technology Compatibility Kit!

## How to Contribute

### Reporting Issues

- Use GitHub Issues to report bugs or suggest improvements.
- Include the TCK version, your adapter implementation details, and relevant error output.

### Adding Test Scenarios

1. Identify a behavioral requirement not covered by existing tests.
2. Add a SPEC clause to `SPEC.md` with a unique identifier.
3. Write the test in the appropriate file under `tck/tests/v1/`.
4. Add a scenario ID to `tck/registry/scenario_ids.yaml`.
5. Run the registry validator: `python -m tck.registry.validator`
6. Submit a pull request.

### Implementing an Adapter

See [docs/writing-an-adapter.md](docs/writing-an-adapter.md) for a step-by-step guide.

### Submitting a Certification

See [docs/certification.md](docs/certification.md) for how to submit your compliance report.

## Development Setup

```bash
# Clone the repository
git clone https://github.com/neo4j-labs/agent-memory-tck.git
cd agent-memory-tck

# Install with uv
uv sync --extra dev

# Run linting
uv run ruff check .
uv run ruff format --check .

# Run tests (requires an adapter)
uv run pytest -m bronze -v

# Validate scenario registry
uv run python -m tck.registry.validator
```

## Code Style

- Python: Follow ruff defaults (line length 100).
- TypeScript: Strict mode, no `any` in public API.
- Go: Standard `gofmt`, pass `go vet` and `staticcheck`.

## Scenario ID Rules

- Once published, a scenario ID is **never reassigned**.
- Format: `SCN-{TIER}-{NUMBER}` (e.g., `SCN-B-042`)
- Tiers: `B` (Bronze), `S` (Silver), `G` (Gold)

## Pull Request Guidelines

1. One logical change per PR.
2. Include tests for new behavior.
3. Update `SPEC.md` if adding behavioral requirements.
4. Update `scenario_ids.yaml` for new tests.
5. Ensure CI passes.
6. **For user-visible changes to a published client**, add a changeset:
   ```bash
   npx changeset
   ```
   This drops a Markdown file into `.changeset/` describing the change
   and the appropriate bump (patch / minor / major). A bot aggregates
   pending changesets into a "Version Packages" PR. For 0.x clients
   (e.g., `@neo4j-labs/agent-memory`), breaking changes can ship as a
   `minor` bump — but the CHANGELOG callout is mandatory.

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 License.
