"""Scenario ID registry validator.

Validates that:
1. All test functions have a corresponding scenario ID in the registry.
2. All scenario IDs in the registry map to existing test functions.
3. No duplicate scenario IDs exist.

Usage:
    python -m tck.registry.validator
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

import yaml


def load_registry(registry_path: Path) -> dict:
    """Load the scenario ID registry from YAML."""
    with open(registry_path) as f:
        return yaml.safe_load(f) or {}


def _extract_marker_tier(decorators: list[ast.expr]) -> str | None:
    """Extract bronze/silver/gold tier from pytest.mark decorators."""
    for dec in decorators:
        if isinstance(dec, ast.Attribute) and dec.attr in ("bronze", "silver", "gold"):
            if isinstance(dec.value, ast.Attribute) and dec.value.attr == "mark":
                return dec.attr
    return None


def collect_test_ids(tests_dir: Path) -> set[str]:
    """Collect all test function IDs from the test files using AST parsing."""
    test_ids = set()

    for py_file in tests_dir.rglob("test_*.py"):
        module_name = py_file.stem
        tree = ast.parse(py_file.read_text())

        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef) and node.name.startswith("Test"):
                class_name = node.name
                for item in node.body:
                    if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                        if item.name.startswith("test_"):
                            test_id = f"{module_name}::{class_name}::{item.name}"
                            test_ids.add(test_id)

    return test_ids


def collect_test_tiers(tests_dir: Path) -> dict[str, str]:
    """Map test_id -> tier from pytest.mark decorators on test classes."""
    tiers: dict[str, str] = {}

    for py_file in tests_dir.rglob("test_*.py"):
        module_name = py_file.stem
        tree = ast.parse(py_file.read_text())

        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef) and node.name.startswith("Test"):
                class_tier = _extract_marker_tier(node.decorator_list)
                if class_tier:
                    for item in node.body:
                        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                            if item.name.startswith("test_"):
                                test_id = f"{module_name}::{node.name}::{item.name}"
                                tiers[test_id] = class_tier

    return tiers


def validate(registry_path: Path, tests_dir: Path) -> list[str]:
    """Validate the registry against the test suite. Returns list of errors."""
    errors = []

    registry = load_registry(registry_path)
    test_ids = collect_test_ids(tests_dir)

    # Check for duplicate scenario IDs (should not happen in YAML keys, but verify)
    seen_ids = set()
    for scn_id in registry:
        if scn_id in seen_ids:
            errors.append(f"Duplicate scenario ID: {scn_id}")
        seen_ids.add(scn_id)

    # Check all registry entries point to existing tests
    registry_test_ids = set()
    for scn_id, entry in registry.items():
        test_id = entry.get("test_id", "")
        registry_test_ids.add(test_id)
        if test_id not in test_ids:
            errors.append(f"{scn_id}: test_id '{test_id}' not found in test suite")

    # Check all tests have a registry entry
    unregistered = test_ids - registry_test_ids
    for test_id in sorted(unregistered):
        errors.append(f"Test '{test_id}' has no scenario ID in registry")

    # Check tier consistency between registry YAML and pytest.mark decorators
    test_tiers = collect_test_tiers(tests_dir)
    for scn_id, entry in registry.items():
        test_id = entry.get("test_id", "")
        yaml_tier = entry.get("tier", "")
        code_tier = test_tiers.get(test_id, "")
        if yaml_tier and code_tier and yaml_tier != code_tier:
            errors.append(f"{scn_id}: registry tier '{yaml_tier}' != code marker '{code_tier}'")

    return errors


def main():
    """CLI entry point."""
    root = Path(__file__).parent.parent.parent
    registry_path = root / "tck" / "registry" / "scenario_ids.yaml"
    tests_dir = root / "tck" / "tests"

    if not registry_path.exists():
        print(f"ERROR: Registry file not found: {registry_path}")
        sys.exit(1)

    errors = validate(registry_path, tests_dir)

    if errors:
        print(f"Registry validation FAILED with {len(errors)} error(s):\n")
        for error in errors:
            print(f"  - {error}")
        sys.exit(1)
    else:
        # Count entries
        registry = load_registry(registry_path)
        bronze = sum(1 for v in registry.values() if v.get("tier") == "bronze")
        silver = sum(1 for v in registry.values() if v.get("tier") == "silver")
        gold = sum(1 for v in registry.values() if v.get("tier") == "gold")
        print(f"Registry validation PASSED: {len(registry)} scenarios")
        print(f"  Bronze: {bronze}  Silver: {silver}  Gold: {gold}")
        sys.exit(0)


if __name__ == "__main__":
    main()
