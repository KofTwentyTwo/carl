#!/usr/bin/env python3
#
# Copyright (C) 2026 KofTwentyTwo
#

"""Fail closed on empty artifact scans, scanner errors and unreviewed middleware regressions."""

import argparse
from collections import Counter
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def validate_trivy(report):
    if (
        not isinstance(report, dict)
        or report.get("SchemaVersion") != 2
        or not isinstance(report.get("Results"), list)
    ):
        raise ValueError("Missing or unsupported Trivy report")
    count = 0
    for result in report["Results"]:
        if not isinstance(result, dict):
            raise ValueError("Invalid scan result")
        packages = result.get("Packages", [])
        if not isinstance(packages, list) or any(
            not isinstance(p, dict)
            or any(
                not isinstance(p.get(k), str) or not p[k].strip()
                for k in ("Name", "Version")
            )
            for p in packages
        ):
            raise ValueError("Malformed package inventory")
        if result.get("Type") == "jar":
            count += len(packages)
        findings = result.get("Vulnerabilities", [])
        if not isinstance(findings, list):
            raise ValueError("Malformed vulnerability list")
        for finding in findings:
            if not isinstance(finding, dict) or finding.get("Severity") not in {
                "UNKNOWN",
                "LOW",
                "MEDIUM",
                "HIGH",
                "CRITICAL",
            }:
                raise ValueError("Malformed vulnerability record")
            if finding.get("Severity") in {"HIGH", "CRITICAL"}:
                raise ValueError("HIGH/CRITICAL dependency or image finding")
    if count == 0:
        raise ValueError("Scan did not inventory any Java packages")
    return count


def validate_baseline(findings, baseline):
    for key, current in findings.items():
        previous = baseline.get(key)
        if (
            not previous
            or not previous.get("reason")
            or current["count"] > previous["count"]
            or current["source_sha256"] != previous["source_sha256"]
        ):
            raise ValueError(
                "Unreviewed static-analysis finding or changed baseline source: " + key
            )


def middleware_findings(module):
    counts = Counter()
    spot = ET.parse(module / "target/spotbugsXml.xml").getroot()
    if spot.tag != "BugCollection" or spot.find("FindBugsSummary") is None:
        raise ValueError("Invalid SpotBugs report")
    summary = spot.find("FindBugsSummary")
    classes = {
        str(p.relative_to(module / "target/classes").with_suffix("")).replace("/", ".")
        for p in (module / "target/classes").rglob("*.class")
    }
    analyzed = [c.get("class") for c in summary.iter("ClassStats")]
    if (
        not classes
        or set(analyzed) != classes
        or len(analyzed) != len(classes)
        or int(summary.get("total_classes", "0")) != len(classes)
    ):
        raise ValueError("SpotBugs class inventory does not cover compiled middleware")
    benchmark = module / "target/pmd-benchmark.txt"
    parser_counts = re.findall(
        r"^Parser\s+[0-9.]+\s+[0-9.]+\s+([0-9]+)\s*$",
        benchmark.read_text() if benchmark.is_file() else "",
        re.M,
    )
    sources = list((module / "src/main/java").rglob("*.java"))
    if not sources or parser_counts != [str(len(sources))]:
        raise ValueError("PMD parser inventory does not cover middleware source files")
    errors = spot.find("Errors")
    if errors is None or any(
        int(errors.get(key, "0")) for key in ("errors", "missingClasses")
    ):
        raise ValueError("SpotBugs analysis was incomplete")
    for finding in spot.findall("BugInstance"):
        source = finding.find("SourceLine")
        if source is None or not source.get("sourcepath"):
            raise ValueError("SpotBugs finding lacks source identity")
        counts["spotbugs:" + finding.get("type") + ":" + source.get("sourcepath")] += 1
    pmd = ET.parse(module / "target/pmd.xml").getroot()
    if not pmd.tag.endswith("pmd") or any(
        child.tag.endswith(("error", "suppressedviolation")) for child in pmd
    ):
        raise ValueError("PMD analysis was incomplete or suppressed")
    for file in pmd:
        if not file.tag.endswith("file"):
            continue
        absolute = Path(file.get("name"))
        relative = absolute.relative_to((module / "src/main/java").resolve())
        for finding in file:
            counts["pmd:" + finding.get("rule") + ":" + str(relative)] += 1
    return {
        key: {
            "count": count,
            "source_sha256": hashlib.sha256(
                (module / "src/main/java" / key.split(":", 2)[2]).read_bytes()
            ).hexdigest(),
        }
        for key, count in sorted(counts.items())
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trivy", type=Path, action="append", default=[])
    parser.add_argument("--middleware", type=Path)
    parser.add_argument(
        "--baseline",
        type=Path,
        default=Path("config/security/middleware-baseline.json"),
    )
    args = parser.parse_args()
    if not args.trivy and not args.middleware:
        parser.error("At least one security report is required")
    for path in args.trivy:
        count = validate_trivy(json.loads(path.read_text()))
        print(f"{path}: {count} Java packages, no HIGH/CRITICAL findings")
    if args.middleware:
        findings = middleware_findings(args.middleware.resolve())
        validate_baseline(findings, json.loads(args.baseline.read_text())["findings"])
        print(
            f"Middleware regression gate passed; {sum(v['count'] for v in findings.values())} reviewed baseline findings remain"
        )


if __name__ == "__main__":
    main()
