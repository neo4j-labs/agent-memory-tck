import pytest


@pytest.fixture(autouse=True)
async def _check_adapter_and_clean():
    yield
