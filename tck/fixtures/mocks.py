"""Mock components for TCK testing.

These mocks ensure deterministic, reproducible test behavior without
requiring external services (LLM providers, embedding APIs).
"""

import hashlib
from typing import Protocol, runtime_checkable


@runtime_checkable
class Embedder(Protocol):
    """Protocol for embedding providers used by the TCK."""

    @property
    def dimensions(self) -> int: ...

    async def embed(self, text: str) -> list[float]: ...

    async def embed_batch(self, texts: list[str]) -> list[list[float]]: ...


class MockEmbedder:
    """Deterministic embedder that generates reproducible embeddings from text hashes.

    Uses SHA256 to produce consistent 1536-dimensional vectors.
    The same text always produces the same embedding, making test
    assertions deterministic and reproducible across environments.
    """

    def __init__(self, dimensions: int = 1536):
        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    async def embed(self, text: str) -> list[float]:
        """Generate a deterministic embedding based on text hash."""
        h = hashlib.sha256(text.encode()).hexdigest()
        embedding = []
        for i in range(0, min(len(h), self._dimensions * 2), 2):
            if len(embedding) >= self._dimensions:
                break
            embedding.append(float(int(h[i : i + 2], 16)) / 255.0)
        while len(embedding) < self._dimensions:
            embedding.append(0.0)
        return embedding

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Generate embeddings for multiple texts."""
        return [await self.embed(t) for t in texts]
