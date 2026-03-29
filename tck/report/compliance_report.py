"""Compliance report generator for TCK test results.

Reads pytest JSON report output and generates a structured compliance
report with tier classification and pass/fail breakdown.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

import tck

# Tier definitions: name, required markers, pass threshold
TIERS = {
    "bronze": {
        "name": "Bronze",
        "description": "Schema compliance + Short-term memory",
        "markers": {"bronze"},
        "threshold": 1.0,
    },
    "silver": {
        "name": "Silver",
        "description": "All three memory types (Short-term + Long-term + Reasoning)",
        "markers": {"bronze", "silver"},
        "threshold": 1.0,
    },
    "gold": {
        "name": "Gold",
        "description": "Full specification including SHOULD clauses and cross-memory",
        "markers": {"bronze", "silver", "gold"},
        "threshold": 0.8,
    },
}


def classify_test_tier(test: dict) -> str | None:
    """Determine which tier a test belongs to based on its markers."""
    markers = {m.get("name", m) if isinstance(m, dict) else m for m in test.get("markers", [])}
    if "gold" in markers:
        return "gold"
    if "silver" in markers:
        return "silver"
    if "bronze" in markers:
        return "bronze"
    return None


def compute_tier_results(tests: list[dict]) -> dict:
    """Compute pass/fail/skip counts per tier."""
    tier_results = {
        tier: {"total": 0, "passed": 0, "failed": 0, "skipped": 0, "errors": 0} for tier in TIERS
    }

    for test in tests:
        tier = classify_test_tier(test)
        if tier is None:
            continue

        outcome = test.get("outcome", "unknown")
        tier_results[tier]["total"] += 1

        if outcome == "passed":
            tier_results[tier]["passed"] += 1
        elif outcome == "failed":
            tier_results[tier]["failed"] += 1
        elif outcome in ("skipped", "xfailed"):
            tier_results[tier]["skipped"] += 1
        else:
            tier_results[tier]["errors"] += 1

    return tier_results


def determine_achieved_tier(tier_results: dict) -> str | None:
    """Determine the highest tier achieved based on pass rates."""
    achieved = None

    for tier_name in ["bronze", "silver", "gold"]:
        results = tier_results[tier_name]
        tier_def = TIERS[tier_name]

        # Check all required lower tiers pass at 100%
        all_required_pass = True
        for required_marker in tier_def["markers"]:
            req_results = tier_results[required_marker]
            if req_results["total"] == 0:
                all_required_pass = False
                break

            testable = req_results["total"] - req_results["skipped"]
            if testable == 0:
                continue

            pass_rate = req_results["passed"] / testable
            # Bronze and silver require 100%, gold requires threshold
            required_rate = 1.0 if required_marker != "gold" else tier_def["threshold"]
            if pass_rate < required_rate:
                all_required_pass = False
                break

        if all_required_pass and results["total"] > 0:
            achieved = tier_name

    return achieved


def generate_report(
    pytest_report_path: str,
    *,
    implementation_name: str = "unknown",
    implementation_version: str = "unknown",
) -> dict:
    """Generate a compliance report from pytest JSON report output."""
    with open(pytest_report_path) as f:
        raw = json.load(f)

    tests = raw.get("tests", [])
    tier_results = compute_tier_results(tests)
    achieved = determine_achieved_tier(tier_results)

    # Build per-test detail
    test_details = []
    for test in tests:
        tier = classify_test_tier(test)
        test_details.append(
            {
                "nodeid": test.get("nodeid", ""),
                "outcome": test.get("outcome", "unknown"),
                "tier": tier,
                "duration": test.get("duration", 0),
                "message": test.get("call", {}).get("longrepr", "") if test.get("call") else "",
            }
        )

    report = {
        "implementation": implementation_name,
        "version": implementation_version,
        "tck_version": tck.__version__,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "tier_achieved": achieved,
        "tiers": {
            tier_name: {
                **results,
                "pass_rate": (results["passed"] / max(results["total"] - results["skipped"], 1)),
                "status": "PASS" if determine_tier_status(tier_name, results) else "FAIL",
            }
            for tier_name, results in tier_results.items()
        },
        "summary": {
            "total_tests": len(tests),
            "total_passed": sum(r["passed"] for r in tier_results.values()),
            "total_failed": sum(r["failed"] for r in tier_results.values()),
            "total_skipped": sum(r["skipped"] for r in tier_results.values()),
        },
        "tests": test_details,
    }

    return report


def determine_tier_status(tier_name: str, results: dict) -> bool:
    """Check if a tier passes its requirements."""
    if results["total"] == 0:
        return False
    testable = results["total"] - results["skipped"]
    if testable == 0:
        return True
    pass_rate = results["passed"] / testable
    threshold = TIERS[tier_name]["threshold"]
    return pass_rate >= threshold


def render_html_report(report: dict, output_path: str) -> None:
    """Render the compliance report as HTML."""
    template_dir = Path(__file__).parent / "templates"
    env = Environment(loader=FileSystemLoader(str(template_dir)))
    template = env.get_template("report.html.jinja")

    html = template.render(report=report, tiers=TIERS)
    Path(output_path).write_text(html)


def main():
    """CLI entry point for compliance report generation."""
    parser = argparse.ArgumentParser(
        description="Generate TCK compliance report from pytest results"
    )
    parser.add_argument(
        "pytest_report",
        help="Path to pytest JSON report file (from --json-report)",
    )
    parser.add_argument(
        "--name",
        default="unknown",
        help="Implementation name",
    )
    parser.add_argument(
        "--version",
        default="unknown",
        help="Implementation version",
    )
    parser.add_argument(
        "--output",
        "-o",
        default="compliance_report.json",
        help="Output path for JSON report",
    )
    parser.add_argument(
        "--html",
        default=None,
        help="Output path for HTML report (optional)",
    )

    args = parser.parse_args()

    report = generate_report(
        args.pytest_report,
        implementation_name=args.name,
        implementation_version=args.version,
    )

    # Write JSON report
    with open(args.output, "w") as f:
        json.dump(report, f, indent=2)
    print(f"JSON report written to {args.output}")

    # Write HTML report if requested
    if args.html:
        render_html_report(report, args.html)
        print(f"HTML report written to {args.html}")

    # Print summary
    achieved = report["tier_achieved"]
    tier_label = TIERS[achieved]["name"] if achieved else "None"
    print(f"\nCompliance Tier Achieved: {tier_label}")
    for tier_name, results in report["tiers"].items():
        status = results["status"]
        passed = results["passed"]
        total = results["total"]
        print(f"  {TIERS[tier_name]['name']:8s}: {status} ({passed}/{total} passed)")

    sys.exit(0 if achieved else 1)


if __name__ == "__main__":
    main()
