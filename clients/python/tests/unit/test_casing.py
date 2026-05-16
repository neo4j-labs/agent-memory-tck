"""Unit tests for snake_case ↔ camelCase translation."""

from __future__ import annotations

import pytest

from neo4j_agent_memory_client.casing import camel_to_snake, snake_to_camel


@pytest.mark.unit
class TestSnakeToCamel:
    def test_top_level_keys(self):
        assert snake_to_camel({"user_id": "x"}) == {"userId": "x"}

    def test_already_camel_unchanged(self):
        assert snake_to_camel({"userId": "x"}) == {"userId": "x"}

    def test_nested_dict(self):
        assert snake_to_camel({"outer_key": {"inner_key": 1}}) == {
            "outerKey": {"innerKey": 1}
        }

    def test_list_of_dicts(self):
        assert snake_to_camel([{"a_b": 1}, {"c_d": 2}]) == [{"aB": 1}, {"cD": 2}]

    def test_primitives_passthrough(self):
        assert snake_to_camel("hello") == "hello"
        assert snake_to_camel(42) == 42
        assert snake_to_camel(None) is None
        assert snake_to_camel(True) is True

    def test_empty_dict(self):
        assert snake_to_camel({}) == {}

    def test_underscore_with_digit(self):
        assert snake_to_camel({"v1_test": 1}) == {"v1Test": 1}


@pytest.mark.unit
class TestCamelToSnake:
    def test_top_level_keys(self):
        assert camel_to_snake({"userId": "x"}) == {"user_id": "x"}

    def test_already_snake_unchanged(self):
        assert camel_to_snake({"user_id": "x"}) == {"user_id": "x"}

    def test_nested(self):
        assert camel_to_snake({"outerKey": {"innerKey": 1}}) == {
            "outer_key": {"inner_key": 1}
        }

    def test_list(self):
        assert camel_to_snake([{"aB": 1}]) == [{"a_b": 1}]

    def test_roundtrip(self):
        original = {"user_id": "alice", "metadata": {"is_active": True, "tags": ["a", "b"]}}
        assert camel_to_snake(snake_to_camel(original)) == original
