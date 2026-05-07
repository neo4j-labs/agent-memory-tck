"""Provenance tagging for e2e tests.

Every conversation, entity, and reasoning step the e2e suite creates gets
tagged with metadata that traces it back to:

    - the language client that ran the test  (tck_client)
    - the specific test that created it      (tck_test)
    - the GitHub Actions run that triggered it (tck_run_id, tck_run_attempt)
    - the commit SHA + branch                (tck_sha, tck_branch)
    - the suite start time                   (tck_started_at)
    - the hostname / runner                  (tck_host)

Querying provenance after the fact (with workspace-admin scope, via Cypher):

    # All e2e data from a single CI run, regardless of language:
    MATCH (c:Conversation) WHERE c.metadata.tck_run_id = '12345' RETURN c

    # Every entity tagged by the python suite:
    MATCH (e:Entity) WHERE e.description STARTS WITH '[tck:python' RETURN e

    # All reasoning steps the test harness emitted:
    MATCH (s:AgentStep) WHERE s.reasoning STARTS WITH 'TCK e2e' RETURN s
"""

from __future__ import annotations

import json
import os
import socket
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any


CLIENT_NAME = "python"


@lru_cache(maxsize=1)
def run_info() -> dict[str, str]:
    """Process-level metadata that's identical for every test in this run."""
    sha = (os.environ.get("GITHUB_SHA") or "local")[:7]
    return {
        "tck_client": CLIENT_NAME,
        "tck_run_id": os.environ.get("GITHUB_RUN_ID") or "local",
        "tck_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT") or "1",
        "tck_workflow": os.environ.get("GITHUB_WORKFLOW") or "local",
        "tck_sha": sha,
        "tck_branch": os.environ.get("GITHUB_REF_NAME") or "local",
        "tck_started_at": datetime.now(timezone.utc).isoformat(),
        "tck_host": os.environ.get("RUNNER_NAME") or socket.gethostname(),
    }


def metadata_for(test_name: str, **extra: Any) -> dict[str, Any]:
    """Per-test metadata: run_info plus the test name and any extras.

    Renamed from `test_metadata` to avoid pytest collecting helpers prefixed
    with `test_` as test cases.
    """
    return {**run_info(), "tck_test": test_name, **extra}


def tag_description(test_name: str, description: str) -> str:
    """Prefix an entity description with a searchable provenance tag."""
    info = run_info()
    return f"[tck:{info['tck_client']}:{info['tck_run_id']}:{test_name}] {description}"


def provenance_reasoning(test_name: str, phase: str = "setup") -> str:
    """Reasoning text used when recording a provenance step on a conversation."""
    info = run_info()
    return (
        f"TCK e2e test {phase}: {test_name} "
        f"[client={info['tck_client']}, run={info['tck_run_id']}, "
        f"sha={info['tck_sha']}, branch={info['tck_branch']}]"
    )


def provenance_result(test_name: str, **extra: Any) -> str:
    """JSON-encoded metadata blob suitable for the `result` field of a step."""
    return json.dumps(metadata_for(test_name, **extra), separators=(",", ":"))
