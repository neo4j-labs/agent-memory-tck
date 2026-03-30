"""FastAPI server for Atlas orchestrator agent."""

from __future__ import annotations

import os
import uuid

from fastapi import FastAPI
from pydantic import BaseModel

from atlas.graph import atlas_graph

app = FastAPI(title="Atlas - Orchestrator Agent")


class SynthesisRequest(BaseModel):
    """Request for cross-agent knowledge synthesis."""

    query: str


class SynthesisResponse(BaseModel):
    """Response from Atlas's synthesis."""

    session_id: str
    synthesis: str
    entity_count: int
    trace_count: int


@app.post("/synthesize", response_model=SynthesisResponse)
async def synthesize(request: SynthesisRequest) -> SynthesisResponse:
    """Synthesize knowledge from all agents' memory contributions."""
    session_id = f"atlas-{uuid.uuid4().hex[:8]}"

    result = await atlas_graph.ainvoke(
        {
            "query": request.query,
            "session_id": session_id,
            "entities": [],
            "traces": [],
            "synthesis": "",
        }
    )

    return SynthesisResponse(
        session_id=session_id,
        synthesis=result["synthesis"],
        entity_count=len(result["entities"]),
        trace_count=len(result["traces"]),
    )


@app.get("/health")
async def health() -> dict:
    return {"status": "healthy", "agent": "atlas", "framework": "langgraph"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8004")))
