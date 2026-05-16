"""Error hierarchy."""


class MemoryError(Exception):
    """Base error for all memory client failures."""


class TransportError(MemoryError):
    """HTTP/network-level failure."""

    def __init__(self, message: str, status_code: int | None = None, body: object = None):
        super().__init__(message)
        self.status_code = status_code
        self.body = body


class ConnectionError(MemoryError):
    """Failed to connect to the backend."""


class AuthenticationError(MemoryError):
    """Bearer token rejected (401/403)."""


class NotFoundError(MemoryError):
    """Resource not found (404)."""


class ValidationError(MemoryError):
    """Input failed validation."""


class NotSupportedError(MemoryError):
    """The transport cannot fulfil this method (e.g. REST has no equivalent)."""
