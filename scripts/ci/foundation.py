#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Bind fresh Maven resolution to the reviewed upstream foundation qualification."""

import hashlib
import json
import os
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def baseline_path(root, channel):
    # An independently reviewed published snapshot can qualify dev, never RC/stable.
    development = root / "config/ci/foundation.json"
    return development if channel == "snapshot" and development.is_file() else root / "config/release/foundation.json"


def qualify(root, repository, channel):
    path = baseline_path(root, channel)
    baseline = json.loads(path.read_text())
    parent = ET.parse(root / "pom.xml").getroot().findtext(
        "{http://maven.apache.org/POM/4.0.0}parent/{http://maven.apache.org/POM/4.0.0}version")
    if (baseline.get("version") != parent or not baseline.get("qualificationRun")
        or not baseline.get("artifacts") or not re.fullmatch(r"[a-f0-9]{64}", str(baseline.get("evidenceZipSha256", "")))):
        raise ValueError("Current parent requires matching reviewed upstream publication: " + str(path.relative_to(root)))
    if channel in {"rc", "stable"} and parent.endswith("-SNAPSHOT"):
        raise ValueError("RC/stable require a qualified stable foundation release")
    inventory = set()
    for module, suffixes in {"core": ("pom", "jar", "tests.jar"), "ui": ("pom", "jar"),
                             "qqq": ("pom", "jar", "tests.jar"), "parent": ("pom",)}.items():
        artifact = "kof22-agent-" + module
        for suffix in suffixes:
            filename = artifact + "-" + parent + ("-tests.jar" if suffix == "tests.jar" else "." + suffix)
            inventory.add("com/kof22/" + artifact + "/" + parent + "/" + filename)
    if set(baseline["artifacts"]) != inventory:
        raise ValueError("The complete nine-artifact foundation publication is required")
    immutable_paths = baseline.get("immutableArtifactPaths", {})
    if path == root / "config/ci/foundation.json":
        if (baseline.get("qualificationScope") != "DEVELOPMENT_ONLY" or baseline.get("channel") != "snapshot"
            or set(immutable_paths) != inventory or len(set(immutable_paths.values())) != 9):
            raise ValueError("Development snapshots require all nine reviewed immutable publication paths")
        for logical, actual in immutable_paths.items():
            # Only the filename's snapshot suffix changes, never the module/version directory.
            directory, filename = logical.rsplit("/", 1)
            expected = re.escape(directory + "/" + filename.split("-SNAPSHOT", 1)[0]) + r"-\d{8}\.\d{6}-[1-9]\d*" + re.escape(filename.split("-SNAPSHOT", 1)[1])
            if not isinstance(actual, str) or not re.fullmatch(expected, actual):
                raise ValueError("Invalid immutable snapshot publication path")
    resolved = {}
    for name, expected in baseline["artifacts"].items():
        if Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError("Invalid qualified Maven artifact path")
        with (repository / name).open("rb") as source:
            resolved[name] = hashlib.file_digest(source, "sha256").hexdigest()
        if resolved[name] != expected:
            raise ValueError("Fresh foundation artifact differs from reviewed publication")
    immutable = {}
    for logical, actual in immutable_paths.items():
        with (repository / actual).open("rb") as source:
            immutable[actual] = hashlib.file_digest(source, "sha256").hexdigest()
        if immutable[actual] != resolved[logical]:
            raise ValueError("Fresh immutable snapshot identity differs from reviewed publication")
    return {
        "status": "PASS", "version": parent, "qualificationRun": baseline["qualificationRun"],
        "baselinePath": str(path.relative_to(root)), "baselineSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "resolvedArtifacts": resolved, "resolvedImmutableArtifacts": immutable,
        "resolution": "FRESH_AUTHENTICATED_MAVEN",
    }


def main():
    root = Path.cwd()
    plan = json.loads((root / "target/circleci-plan.json").read_text())
    output = root / "target/foundation-evidence/report.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('{"status":"RUNNING"}\n')
    try:
        report = qualify(root, Path(os.environ["MAVEN_REPO"]), plan["channel"])
    except (ValueError, OSError):
        output.write_text('{"status":"FAIL","reason":"REVIEWED_PUBLICATION_OR_FRESH_RESOLUTION_MISSING"}\n')
        raise
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
