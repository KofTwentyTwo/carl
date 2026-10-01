#!/usr/bin/env python3
#
# Copyright (C) 2026 KofTwentyTwo
#
"""Seal verified executable artifacts; independently verify every delivered byte."""

import argparse
import hashlib
import json
import os
import runpy
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def tests(root):
    files = sorted((root / "target").glob("*surefire-reports/TEST-*.xml")) + sorted(
        (root / "target").glob("failsafe-reports/TEST-*.xml")
    )
    total = 0
    if not files:
        raise ValueError("Actual test XML is required")
    for path in files:
        suite = ET.parse(path).getroot()
        if suite.tag != "testsuite" or any(
            int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")
        ):
            raise ValueError("Every mandatory test must pass without skips")
        total += int(suite.get("tests", "0"))
    if total < 1:
        raise ValueError("Empty test evidence")
    report = ET.parse(root / "target/site/jacoco/jacoco.xml").getroot()
    for kind, floor in (("LINE", 0.80), ("BRANCH", 0.60)):
        values = report.findall("counter[@type='" + kind + "']")
        if len(values) != 1:
            raise ValueError("Whole-application coverage evidence required")
        covered = int(values[0].get("covered", "0"))
        missed = int(values[0].get("missed", "0"))
        if covered + missed == 0 or covered / (covered + missed) < floor:
            raise ValueError("Consumer coverage floor not met")
    return total


def seal(root, destination, source, repository, run, maven_repository=None):
    preparation = json.loads((root / "target/release-preparation.json").read_text())
    version = preparation["version"]
    policy = runpy.run_path(str(root / "scripts/release-policy.py"))
    _, channel, _ = policy["parse"](version)
    foundation = json.loads((root / "config/release/foundation.json").read_text())
    if foundation["version"] != preparation["foundation"]:
        raise ValueError("Foundation qualification baseline does not match candidate")
    maven_repository = maven_repository or Path.home() / ".m2/repository"
    for name, expected in foundation["artifacts"].items():
        path = maven_repository / name
        if (
            Path(name).is_absolute()
            or ".." in Path(name).parts
            or not path.is_file()
            or digest(path) != expected
        ):
            raise ValueError(
                "Resolved foundation artifact differs from qualified publication"
            )
    if (
        not __import__("re").fullmatch(r"[a-f0-9]{40}", source)
        or repository != "KofTwentyTwo/carl"
        or not str(run).isdigit()
    ):
        raise ValueError("Exact source, repository and workflow run required")
    total = tests(root)
    browser = json.loads((root / "target/browser-evidence/report.json").read_text())
    if (
        browser.get("status") != "PASS"
        or not browser.get("checks")
        or len(browser["checks"]) != len(set(browser["checks"]))
    ):
        raise ValueError("Actual packaged browser evidence must pass")
    docked = json.loads((root / "target/docked-chat-browser-evidence/docked-chat-report.json").read_text())
    if (
        docked.get("status") != "PASS"
        or not docked.get("checks")
        or len(docked["checks"]) != len(set(docked["checks"]))
        or docked.get("apiResponsesMocked") is not False
        or docked.get("liveModel") is not False
        or docked.get("expectedOutcome") != "UNKNOWN"
        or docked.get("distributionUnchanged") is not True
    ):
        raise ValueError("Actual packaged docked-chat evidence must pass")
    widths = {item.get("viewport", {}).get("width") for item in docked.get("runs", [])}
    if not any(isinstance(width, int) and width >= 1000 for width in widths) or not any(
        isinstance(width, int) and 0 < width <= 500 for width in widths
    ):
        raise ValueError("Actual desktop and narrow docked-chat runs are required")
    actual_distribution = {
        str(path.relative_to(root / "target/agent")): digest(path)
        for path in sorted((root / "target/agent").rglob("*")) if path.is_file()
    }
    if docked.get("distributionFileHashes") != actual_distribution:
        raise ValueError("Docked-chat evidence differs from the exact candidate distribution")
    scans = runpy.run_path(str(root / "scripts/verify-security.py"))
    packages = {
        name: scans["validate_trivy"](
            json.loads((root / "target/security" / (name + ".json")).read_text())
        )
        for name in ("dependencies", "image")
    }
    if json.loads((root / "target/security/gitleaks.json").read_text()) != []:
        raise ValueError("A clean actual source secret scan is required")
    distribution = root / "target/agent"
    if not (distribution / "bin/agent").is_file() or not list(
        distribution.rglob("*.jar")
    ):
        raise ValueError("Native executable distribution is missing")
    if any(path.is_symlink() for path in distribution.rglob("*")):
        raise ValueError("Release distribution must not contain symbolic links")
    image = root / "target/candidate-image.tar"
    if not image.is_file() or image.stat().st_size == 0:
        raise ValueError("Scanned OCI image archive is missing")
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(
        destination / ("carl-ai-" + version + ".tar.gz"), "w:gz"
    ) as archive:
        archive.add(distribution, arcname="carl-ai")
    with tarfile.open(
        destination / ("carl-ai-" + version + "-image.tar.gz"), "w:gz"
    ) as archive:
        archive.add(image, arcname="image.tar")
    evidence = []
    for pattern in (
        "*surefire-reports/TEST-*.xml",
        "failsafe-reports/TEST-*.xml",
        "security/*.json",
        "site/jacoco/jacoco.xml",
        "browser-evidence/*",
        "docked-chat-browser-evidence/*.json",
        "docked-chat-browser-evidence/*.png",
        "release-preparation.json",
    ):
        evidence.extend(
            path for path in (root / "target").glob(pattern) if path.is_file()
        )
    with zipfile.ZipFile(
        destination / "qualification-evidence.zip", "w", zipfile.ZIP_DEFLATED
    ) as archive:
        for path in sorted(set(evidence)):
            if path.is_symlink():
                raise ValueError("Evidence cannot be a symbolic link")
            archive.write(path, path.relative_to(root / "target"))
    manifest = {
        "schema": 1,
        "product": "Carl AI",
        "repository": repository,
        "source": source,
        "version": version,
        "channel": channel,
        "workflowRun": str(run),
        "preparation": preparation,
        "foundation": foundation,
        "qualification": {
            "tests": total,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "browserChecks": browser["checks"],
            "dockedChatChecks": docked["checks"],
            "inventoriedJavaPackages": packages,
            "liveProviders": "NOT_QUALIFIED",
            "deployment": "NOT_REQUESTED",
        },
        "files": {
            path.name: {"sha256": digest(path), "bytes": path.stat().st_size}
            for path in sorted(destination.iterdir())
        },
    }
    (destination / "delivery-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )
    return manifest


def verify(directory, source=None, version=None, channel=None):
    manifest = json.loads((directory / "delivery-manifest.json").read_text())
    if (
        manifest.get("schema") != 1
        or manifest.get("product") != "Carl AI"
        or manifest.get("repository") != "KofTwentyTwo/carl"
    ):
        raise ValueError("Unexpected release identity")
    for key, expected in (
        ("source", source),
        ("version", version),
        ("channel", channel),
    ):
        if expected is not None and manifest.get(key) != expected:
            raise ValueError("Release " + key + " does not match requested promotion")
    qualification = manifest.get("qualification", {})
    if (
        qualification.get("tests", 0) < 1
        or any(qualification.get(key) != 0 for key in ("failures", "errors", "skipped"))
        or not qualification.get("browserChecks")
        or not qualification.get("dockedChatChecks")
        or len(qualification["dockedChatChecks"]) != len(set(qualification["dockedChatChecks"]))
    ):
        raise ValueError("Missing passing mandatory qualification")
    files = manifest.get("files", {})
    expected_files = {
        "carl-ai-" + manifest["version"] + ".tar.gz",
        "carl-ai-" + manifest["version"] + "-image.tar.gz",
        "qualification-evidence.zip",
    }
    if set(files) != expected_files:
        raise ValueError("Incomplete or unexpected release artifact set")
    for name, expected in files.items():
        path = directory / name
        if (
            Path(name).name != name
            or path.is_symlink()
            or not path.is_file()
            or path.stat().st_size != expected.get("bytes")
            or digest(path) != expected.get("sha256")
        ):
            raise ValueError("Release artifact failed identity/digest verification")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seal", "verify"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--source", default=os.getenv("GITHUB_SHA"))
    parser.add_argument("--version")
    parser.add_argument("--channel")
    args = parser.parse_args()
    if args.action == "seal":
        seal(
            args.root,
            args.directory,
            args.source,
            os.environ["GITHUB_REPOSITORY"],
            os.environ["GITHUB_RUN_ID"],
        )
    else:
        verify(args.directory, args.source, args.version, args.channel)


if __name__ == "__main__":
    main()
