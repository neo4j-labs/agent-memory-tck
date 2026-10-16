"""Check or seed the Java conformance Ledger from a pytest-json-report."""

import argparse
import json
from pathlib import Path
import re
import sys

import yaml

REGISTRY = Path(__file__).resolve().parents[3] / "tck/registry/scenario_ids.yaml"
DEFAULT_LEDGER = Path(__file__).resolve().with_name("ledger.yaml")
BRIDGE_DEFAULTS = {"SCN-B-014", "SCN-B-027", "SCN-B-037", "SCN-B-039", "SCN-B-041", "SCN-B-085"}
STATUSES = {"pass", "client-gap", "service-gap"}


class InputError(ValueError):
    """An input file cannot be interpreted safely."""


def read_yaml(path):
    with path.open(encoding="utf-8") as stream:
        value = yaml.safe_load(stream)
    if not isinstance(value, dict):
        raise InputError(f"{path}: expected a mapping")
    return value


def read_registry():
    registry = read_yaml(REGISTRY)
    return {
        scenario: entry["test_id"]
        for scenario, entry in registry.items()
        if entry["tier"] in {"bronze", "silver"}
    }, set(registry)


def read_report(path):
    with path.open(encoding="utf-8") as stream:
        report = json.load(stream)
    if not isinstance(report, dict) or not isinstance(report.get("tests"), list):
        raise InputError("report must contain a tests array")
    results = {}
    for test in report["tests"]:
        if not isinstance(test, dict):
            raise InputError("report tests must be objects")
        nodeid, outcome = test.get("nodeid"), test.get("outcome")
        if not isinstance(nodeid, str) or not isinstance(outcome, str):
            raise InputError("each test needs string nodeid and outcome fields")
        if outcome not in {"passed", "failed", "skipped", "error", "xfailed", "xpassed"}:
            raise InputError(f"unknown outcome: {outcome}")
        parts = nodeid.split("::")
        if len(parts) < 2 or not parts[0].endswith(".py"):
            raise InputError(f"invalid test nodeid: {nodeid}")
        test_id = "::".join([Path(parts[0]).stem, *parts[1:]])
        if test_id in results:
            raise InputError(f"duplicate test result: {test_id}")
        for phase in ("setup", "call"):
            if phase in test and not isinstance(test[phase], dict):
                raise InputError(f"{nodeid}: {phase} must be an object")
        results[test_id] = test
    return results


def failure_text(test):
    setup = test.get("setup", {})
    phase = setup if setup.get("outcome") == "failed" else test.get("call", {})
    text = phase.get("longrepr", "")
    return text if isinstance(text, str) else json.dumps(text, ensure_ascii=False)


def seed_entry(scenario, test):
    if test["outcome"] == "passed":
        if scenario in BRIDGE_DEFAULTS:
            return {"status": "client-gap", "note": "passes only through a bridge default"}
        return {"status": "pass"}
    failure = failure_text(test)
    for kind in ("service", "client"):
        match = re.search(rf"{kind} gap:\s*([a-z_][a-z_0-9]*)", failure)
        if match:
            return {"status": f"{kind}-gap", "note": match[1]}
    first_line = failure.splitlines()[0] if failure else test["outcome"]
    return {"status": "client-gap", "note": f"needs triage: {first_line}"}


def seed(path, registry, results, force):
    missing = sorted(set(registry.values()) - results.keys())
    if missing:
        raise InputError(f"cannot seed an incomplete report: {len(missing)} scenarios missing")
    if path.exists() and not force:
        raise InputError(f"Ledger already exists: {path}; use --force to overwrite")
    chunks = []
    for scenario, test_id in sorted(registry.items()):
        chunks.append(f"# {test_id}\n")
        chunks.append(
            yaml.safe_dump({scenario: seed_entry(scenario, results[test_id])}, sort_keys=False)
        )
        chunks.append("\n")
    with path.open("w" if force else "x", encoding="utf-8") as stream:
        stream.write("".join(chunks))
    print(f"Seeded {len(registry)} scenarios in {path}")
    return 0


def check(path, registry, known_ids, results):
    ledger = read_yaml(path)
    for scenario, entry in ledger.items():
        if not isinstance(scenario, str) or not isinstance(entry, dict):
            raise InputError("Ledger entries must map scenario IDs to objects")
        if not isinstance(entry.get("status"), str) or entry["status"] not in STATUSES:
            raise InputError(f"{scenario}: invalid or missing status")
        if "note" in entry and not isinstance(entry["note"], str):
            raise InputError(f"{scenario}: note must be a string")
    violations = []
    for scenario in sorted(ledger.keys() - known_ids):
        violations.append(f"unknown scenario: {scenario}")
    for scenario, test_id in sorted(registry.items()):
        if scenario not in ledger:
            violations.append(f"{scenario}: missing from Ledger")
            continue
        test = results.get(test_id)
        passed = test is not None and test["outcome"] == "passed"
        if ledger[scenario]["status"] == "pass" and not passed:
            outcome = test["outcome"] if test else "missing result"
            violations.append(f"{scenario}: regression ({outcome}) — {test_id}")
        elif ledger[scenario]["status"] != "pass" and passed:
            print(f"Unexpected pass: {scenario} — {test_id}")
    for violation in violations:
        print(violation, file=sys.stderr)
    print(f"Checked {len(registry)} scenarios: {len(violations)} violations")
    return 1 if violations else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("check", "seed"):
        subparser = commands.add_parser(command)
        subparser.add_argument("report", type=Path)
        subparser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
        if command == "seed":
            subparser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)
    try:
        registry, known_ids = read_registry()
        results = read_report(args.report)
        if args.command == "seed":
            return seed(args.ledger, registry, results, args.force)
        return check(args.ledger, registry, known_ids, results)
    except (OSError, ValueError, yaml.YAMLError) as error:
        print(f"Bad input: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
