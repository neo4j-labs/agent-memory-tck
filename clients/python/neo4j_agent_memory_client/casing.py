"""snake_case ↔ camelCase translation for wire payloads."""

from __future__ import annotations

import re
from typing import Any

_SNAKE_RE = re.compile(r"_([a-z0-9])")
_CAMEL_RE = re.compile(r"([A-Z])")


def _camel_key(s: str) -> str:
    return _SNAKE_RE.sub(lambda m: m.group(1).upper(), s)


def _snake_key(s: str) -> str:
    return _CAMEL_RE.sub(lambda m: "_" + m.group(1).lower(), s)


def snake_to_camel(value: Any) -> Any:
    if isinstance(value, dict):
        return {_camel_key(k): snake_to_camel(v) for k, v in value.items()}
    if isinstance(value, list):
        return [snake_to_camel(v) for v in value]
    return value


def camel_to_snake(value: Any) -> Any:
    if isinstance(value, dict):
        return {_snake_key(k): camel_to_snake(v) for k, v in value.items()}
    if isinstance(value, list):
        return [camel_to_snake(v) for v in value]
    return value
