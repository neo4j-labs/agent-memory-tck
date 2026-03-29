"""FastAPI server for Lenny agent."""

from __future__ import annotations

import os
import uuid

from fastapi import FastAPI
from pydantic import BaseModel

from lenny.agent import LennyDeps, lenny_agent

app = FastAPI(title="Lenny - Podcast Research Agent")


class ResearchRequest(BaseModel):
    """Request to research a podcast topic."""

    transcript: str
    episode_title: str | None = None


class ResearchResponse(BaseModel):
    """Response from Lenny's research."""

    session_id: str
    result: str


@app.post("/research", response_model=ResearchResponse)
async def research(request: ResearchRequest) -> ResearchResponse:
    """Process a podcast transcript and extract entities."""
    session_id = f"lenny-{uuid.uuid4().hex[:8]}"
    deps = LennyDeps(
        memory_endpoint=os.getenv("MEMORY_ENDPOINT", "http://localhost:3001"),
        session_id=session_id,
    )

    prompt = f"Analyze this podcast transcript and extract all people, organizations, and key topics mentioned:\n\n"
    if request.episode_title:
        prompt += f"Episode: {request.episode_title}\n\n"
    prompt += request.transcript

    result = await lenny_agent.run(prompt, deps=deps)
    return ResearchResponse(session_id=session_id, result=result.data)


@app.get("/health")
async def health() -> dict:
    return {"status": "healthy", "agent": "lenny", "framework": "pydantic-ai"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8001")))
