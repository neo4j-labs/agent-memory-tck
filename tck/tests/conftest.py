"""TCK test configuration and shared fixtures.

Implementers must override the `adapter` fixture in their own conftest.py
to provide a concrete BaseAdapter instance for their system.

Example:

    # my_project/tests/conftest.py
    import pytest
    from my_project.adapters import MyAdapter

    @pytest.fixture(scope="session")
    async def adapter():
        adapter = MyAdapter(uri="bolt://localhost:7687")
        await adapter.setup()
        yield adapter
        await adapter.teardown()

For cross-language testing via the HTTP bridge:

    pytest -m bronze --bridge-url http://localhost:3001
"""

from uuid import uuid4

import pytest

from tck.fixtures.mocks import MockEmbedder

# Sentinel to detect when no adapter has been configured
_NO_ADAPTER = object()


def pytest_configure(config):
    """Register TCK markers."""
    config.addinivalue_line("markers", "bronze: Bronze tier - schema and short-term memory")
    config.addinivalue_line("markers", "silver: Silver tier - all three memory types")
    config.addinivalue_line(
        "markers", "gold: Gold tier - SHOULD clauses and cross-memory integration"
    )


@pytest.fixture(scope="session")
def adapter(request):
    """Provide a BaseAdapter implementation.

    If --bridge-url is set, uses HTTPBridgeAdapter. Otherwise, override
    this fixture in your own conftest.py.
    """
    bridge_url = request.config.getoption("--bridge-url", default=None)

    if bridge_url:
        from tck.adapters.http_bridge import HTTPBridgeAdapter

        return HTTPBridgeAdapter(bridge_url)
    return _NO_ADAPTER


@pytest.fixture(autouse=True)
async def _check_adapter_and_clean(adapter):
    """Skip tests if no adapter is configured, otherwise clean state."""
    if adapter is _NO_ADAPTER:
        pytest.skip(
            "No adapter configured. Provide an 'adapter' fixture in your conftest.py, "
            "or use --bridge-url to test against an HTTP conformance server. "
            "See tck/tests/conftest.py or docs/writing-an-adapter.md for examples."
        )
    await adapter.clear_all_data()
    yield


@pytest.fixture
def session_id() -> str:
    """Generate a unique session ID for test isolation."""
    return f"tck-{uuid4()}"


@pytest.fixture
def mock_embedder() -> MockEmbedder:
    """Provide the TCK MockEmbedder for deterministic embeddings."""
    return MockEmbedder()
