"""Shared pytest fixtures + dotenv loading for the Python client suites.

Loads `.env` from the repo root if present so e2e tests can pick up
`MEMORY_API_KEY` locally. CI uses real env vars (set from the
`MEMORY_API_KEY` repo secret).
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

try:
    from dotenv import load_dotenv  # type: ignore[import-not-found]

    # Walk up from this file to find the first `.env` we can use.
    here = Path(__file__).resolve()
    for ancestor in [here.parent, *here.parents]:
        candidate = ancestor / ".env"
        if candidate.exists():
            load_dotenv(candidate, override=False)
            break
except ImportError:
    # python-dotenv is dev-only; it's fine to run without it in CI where
    # secrets come from the environment directly.
    pass


def _get_endpoint() -> str:
    return os.environ.get("MEMORY_ENDPOINT", "https://memory.neo4jlabs.com/v1")


def _get_api_key() -> str | None:
    key = os.environ.get("MEMORY_API_KEY")
    return key.strip() if key and key.strip() else None


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        "e2e: hits the live hosted service — skipped if MEMORY_API_KEY is unset",
    )
    config.addinivalue_line(
        "markers",
        "integration: in-process integration tests with a mocked transport",
    )
    config.addinivalue_line("markers", "unit: pure-unit tests — no I/O")


@pytest.fixture
def memory_endpoint() -> str:
    return _get_endpoint()


@pytest.fixture
def memory_api_key() -> str | None:
    return _get_api_key()
