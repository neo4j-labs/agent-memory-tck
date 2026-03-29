"""Atlas — LangGraph orchestrator that reads all agents' reasoning traces.

Atlas coordinates Lenny (research), Scout (search), and Forge (enrichment)
by reading their reasoning traces and synthesizing cross-agent knowledge.
"""

from __future__ import annotations

import os
from typing import Annotated, TypedDict

import httpx
from langgraph.graph import StateGraph, END


MEMORY_ENDPOINT = os.getenv("MEMORY_ENDPOINT", "http://localhost:3001")


class AtlasState(TypedDict):
    """State for the Atlas orchestrator graph."""

    query: str
    session_id: str
    entities: list[dict]
    traces: list[dict]
    synthesis: str


async def _memory_call(method: str, params: dict) -> dict | list:
    """Call the memory service."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{MEMORY_ENDPOINT}/{method}", json=params, timeout=30
        )
        if resp.status_code == 204:
            return {}
        return resp.json()


async def gather_entities(state: AtlasState) -> AtlasState:
    """Gather all entities from the shared knowledge graph."""
    results = await _memory_call("search_entities", {
        "query": state["query"],
        "limit": 20,
    })

    entities = []
    if isinstance(results, list):
        for e in results:
            entities.append({
                "name": e.get("name", ""),
                "type": e.get("type", ""),
                "description": e.get("description", ""),
            })

    return {**state, "entities": entities}


async def gather_traces(state: AtlasState) -> AtlasState:
    """Gather reasoning traces from all agents."""
    results = await _memory_call("list_traces", {"limit": 50})

    traces = []
    if isinstance(results, list):
        for t in results:
            traces.append({
                "id": t.get("id", ""),
                "session_id": t.get("session_id", ""),
                "task": t.get("task", ""),
                "outcome": t.get("outcome", ""),
                "success": t.get("success"),
            })

    return {**state, "traces": traces}


async def synthesize(state: AtlasState) -> AtlasState:
    """Synthesize findings from all agents into a summary."""
    session_id = state["session_id"]

    # Record Atlas's own reasoning trace
    trace = await _memory_call("start_trace", {
        "session_id": session_id,
        "task": f"Synthesize knowledge for: {state['query']}",
    })

    step = await _memory_call("add_step", {
        "trace_id": trace.get("id", ""),
        "thought": f"Found {len(state['entities'])} entities and {len(state['traces'])} traces",
        "action": "synthesize",
    })

    # Build synthesis
    entity_summary = ", ".join(
        f"{e['name']} ({e['type']})" for e in state["entities"][:10]
    ) or "No entities found"

    agent_activities = {}
    for t in state["traces"]:
        sid = t.get("session_id", "unknown")
        agent = sid.split("-")[0] if "-" in sid else sid
        agent_activities.setdefault(agent, []).append(t.get("task", ""))

    activity_lines = []
    for agent, tasks in agent_activities.items():
        activity_lines.append(f"  {agent}: {len(tasks)} task(s)")

    synthesis = (
        f"Knowledge synthesis for '{state['query']}':\n"
        f"Entities: {entity_summary}\n"
        f"Agent activity:\n" + "\n".join(activity_lines)
    )

    # Complete the trace
    trace_id = trace.get("id", "")
    if trace_id:
        await _memory_call("record_tool_call", {
            "step_id": step.get("id", ""),
            "tool_name": "synthesize",
            "arguments": {"query": state["query"]},
            "status": "success",
        })
        await _memory_call("complete_trace", {
            "trace_id": trace_id,
            "outcome": synthesis[:200],
            "success": True,
        })

    # Store synthesis as a message
    await _memory_call("add_message", {
        "session_id": session_id,
        "role": "assistant",
        "content": synthesis,
    })

    return {**state, "synthesis": synthesis}


def build_graph() -> StateGraph:
    """Build the Atlas orchestrator graph."""
    graph = StateGraph(AtlasState)

    graph.add_node("gather_entities", gather_entities)
    graph.add_node("gather_traces", gather_traces)
    graph.add_node("synthesize", synthesize)

    graph.set_entry_point("gather_entities")
    graph.add_edge("gather_entities", "gather_traces")
    graph.add_edge("gather_traces", "synthesize")
    graph.add_edge("synthesize", END)

    return graph.compile()


atlas_graph = build_graph()
