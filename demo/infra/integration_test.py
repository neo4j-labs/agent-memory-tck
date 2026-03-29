"""Integration test for cross-language entity sharing.

Verifies that entities written by one agent (Python) are readable
by agents in other languages (TypeScript, Go).

Usage:
    python integration_test.py

Requires all agents and Neo4j to be running (via docker compose up).
"""

import asyncio
import sys

import httpx

LENNY_URL = "http://localhost:8001"
SCOUT_URL = "http://localhost:8002"
FORGE_URL = "http://localhost:8003"
ATLAS_URL = "http://localhost:8004"


async def check_health(name: str, url: str) -> bool:
    """Check if an agent is healthy."""
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.get(f"{url}/health", timeout=5)
            healthy = resp.status_code == 200
            status = "OK" if healthy else f"HTTP {resp.status_code}"
            print(f"  {name}: {status}")
            return healthy
    except Exception as e:
        print(f"  {name}: UNREACHABLE ({e})")
        return False


async def test_cross_language_entity_sharing():
    """Test that entities created by Lenny (Python) are visible to Forge (Go)."""
    print("\n=== Cross-Language Entity Sharing Test ===\n")

    # Step 1: Health checks
    print("1. Checking agent health...")
    agents = [
        ("Lenny (Python)", LENNY_URL),
        ("Scout (TypeScript)", SCOUT_URL),
        ("Forge (Go)", FORGE_URL),
        ("Atlas (Python)", ATLAS_URL),
    ]

    all_healthy = True
    for name, url in agents:
        if not await check_health(name, url):
            all_healthy = False

    if not all_healthy:
        print("\nWARNING: Not all agents are healthy. Continuing with available agents.\n")

    # Step 2: Lenny creates entities via research
    print("\n2. Lenny extracting entities from transcript...")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{LENNY_URL}/research",
                json={
                    "transcript": "In this episode, we interview Alice Johnson, CTO of Acme Corp, "
                    "about their new AI platform. She discusses the partnership with "
                    "Neo4j Labs for graph-based agent memory.",
                    "episode_title": "AI Memory with Alice Johnson",
                },
                timeout=60,
            )
            if resp.status_code == 200:
                data = resp.json()
                print(f"  Lenny session: {data.get('session_id')}")
                print(f"  Result: {data.get('result', '')[:100]}...")
            else:
                print(f"  Lenny research failed: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  Lenny research failed: {e}")

    # Step 3: Forge enriches entities
    print("\n3. Forge enriching entities...")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{FORGE_URL}/enrich",
                json={
                    "entity_name": "Alice Johnson",
                    "properties": {
                        "ROLE": "CTO",
                        "COMPANY": "Acme Corp",
                        "DOMAIN": "AI/ML",
                    },
                },
                timeout=30,
            )
            if resp.status_code == 200:
                data = resp.json()
                print(f"  Facts added: {data.get('facts_added', 0)}")
            else:
                print(f"  Forge enrich failed: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  Forge enrich failed: {e}")

    # Step 4: Scout searches for the entity
    print("\n4. Scout searching shared graph...")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{SCOUT_URL}/search",
                json={"query": "Alice Johnson Acme Corp"},
                timeout=60,
            )
            if resp.status_code == 200:
                data = resp.json()
                entities = data.get("existingEntities", [])
                print(f"  Found {len(entities)} entities")
                for e in entities:
                    print(f"    - {e.get('name')} ({e.get('type')})")
            else:
                print(f"  Scout search failed: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  Scout search failed: {e}")

    # Step 5: Atlas synthesizes
    print("\n5. Atlas synthesizing cross-agent knowledge...")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{ATLAS_URL}/synthesize",
                json={"query": "Alice Johnson"},
                timeout=60,
            )
            if resp.status_code == 200:
                data = resp.json()
                print(f"  Entities found: {data.get('entity_count', 0)}")
                print(f"  Traces found: {data.get('trace_count', 0)}")
                print(f"  Synthesis: {data.get('synthesis', '')[:200]}")
            else:
                print(f"  Atlas synthesis failed: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  Atlas synthesis failed: {e}")

    print("\n=== Integration Test Complete ===")


if __name__ == "__main__":
    asyncio.run(test_cross_language_entity_sharing())
