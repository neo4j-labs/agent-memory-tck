# neo4j-agent-memory TCK

Technology Compliance Kit for [neo4j-agent-memory](https://github.com/neo4j-labs/agent-memory) implementations.

The TCK provides a formal specification, executable test suite, and compliance framework that enables any implementation to verify conformance with the neo4j-agent-memory data model and behavioral contracts.

## Compliance Tiers

| Tier | Scope | Requirements |
|------|-------|--------------|
| **Bronze** | Schema + Short-Term Memory | All REQUIRED node types, relationships, and short-term memory behavioral tests pass |
| **Silver** | All Three Memory Primitives | Bronze + Long-Term Memory + Reasoning Memory MUST tests pass |
| **Gold** | Full Specification | Silver + cross-memory integration + SHOULD clause tests pass |

## Quick Start

### Install

```bash
pip install neo4j-agent-memory-tck
```

### Write an Adapter

Implement the `BaseAdapter` interface for your system:

```python
from tck.adapters.base_adapter import BaseAdapter

class MyAdapter(BaseAdapter):
    async def setup(self) -> None: ...
    async def add_message(self, session_id, role, content, **kwargs): ...
    # ... implement required methods for your target tier
```

### Run Tests

```bash
# Run Bronze tier tests
pytest -m bronze --adapter my_package:MyAdapter

# Run all tiers
pytest -m "bronze or silver or gold" --adapter my_package:MyAdapter

# Generate compliance report
tck report results.json --output report.html
```

## Certification Badges

```markdown
![Bronze Certified](https://img.shields.io/badge/agent--memory--tck-Bronze-F97316?logo=neo4j)
![Silver Certified](https://img.shields.io/badge/agent--memory--tck-Silver-22C55E?logo=neo4j)
![Gold Certified](https://img.shields.io/badge/agent--memory--tck-Gold-6366F1?logo=neo4j)
```

## License

Apache 2.0 — See [LICENSE](LICENSE).
