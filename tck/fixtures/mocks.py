"""Mock components for TCK testing.

These mocks ensure deterministic, reproducible test behavior without
requiring external services (LLM providers, embedding APIs).
"""

import hashlib
from typing import Protocol, runtime_checkable

from tck.fixtures.data import SEARCH_QUERY, UNRELATED_SEARCH_MESSAGE

_SEARCH_VECTOR_COMPONENTS = {SEARCH_QUERY: 0, UNRELATED_SEARCH_MESSAGE: 1}


@runtime_checkable
class Embedder(Protocol):
    """Protocol for embedding providers used by the TCK."""

    @property
    def dimensions(self) -> int: ...

    async def embed(self, text: str) -> list[float]: ...

    async def embed_batch(self, texts: list[str]) -> list[list[float]]: ...


class MockEmbedder:
    """Deterministic embedder with controlled vectors for the message-search fixture.

    SEARCH_QUERY and UNRELATED_SEARCH_MESSAGE use orthogonal unit vectors.
    Their Neo4j cosine similarity is 0.5, below the default threshold of 0.7;
    an identical query and message score 1.0. Other text uses SHA256 as before.
    Vectors have 1536 dimensions by default and require at least two dimensions.
    """

    def __init__(self, dimensions: int = 1536):
        if dimensions < 2:
            raise ValueError("MockEmbedder requires at least two dimensions")
        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    async def embed(self, text: str) -> list[float]:
        """Return a controlled search vector or a deterministic text-hash vector."""
        if text in _SEARCH_VECTOR_COMPONENTS:
            embedding = [0.0] * self._dimensions
            embedding[_SEARCH_VECTOR_COMPONENTS[text]] = 1.0
            return embedding

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
