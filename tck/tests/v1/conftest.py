"""Wire the ReferenceAdapter for v1 TCK tests when not using a bridge."""

import pytest

from tck.reference.adapter import ReferenceAdapter


@pytest.fixture(scope="session")
async def adapter(request):
    bridge_url = request.config.getoption("--bridge-url", default=None)
    if bridge_url:
        from tck.adapters.http_bridge import HTTPBridgeAdapter

        bridge = HTTPBridgeAdapter(bridge_url)
        await bridge.setup()
        yield bridge
        await bridge.teardown()
    else:
        ref = ReferenceAdapter()
        await ref.setup()
        yield ref
        await ref.teardown()
