"""Lenny — Podcast research agent using PydanticAI.

Lenny is the primary entity builder in the multi-agent demo.
It processes podcast transcripts, extracts entities (people,
organizations, topics), and stores them in the shared Neo4j graph.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from pydantic_ai import Agent

from lenny.tools import extract_entities_tool, search_knowledge_tool


@dataclass
class LennyDeps:
    """Dependencies injected into Lenny's agent context."""

    memory_endpoint: str = os.getenv("MEMORY_ENDPOINT", "http://localhost:3001")
    session_id: str = "lenny-default"


SYSTEM_PROMPT = """\
You are Lenny, a podcast research agent. Your job is to:
1. Analyze podcast transcripts and episode descriptions
2. Extract key entities: people (guests, hosts), organizations, topics
3. Store entities and relationships in the shared knowledge graph
4. Record facts about what was discussed

When you extract entities, always use the extract_entities tool.
When looking up existing knowledge, use search_knowledge.

Namespace: demo-polyglot
Agent: lenny
"""

lenny_agent = Agent(
    "openai:gpt-4o-mini",
    deps_type=LennyDeps,
    system_prompt=SYSTEM_PROMPT,
)

lenny_agent.tool(extract_entities_tool)
lenny_agent.tool(search_knowledge_tool)
