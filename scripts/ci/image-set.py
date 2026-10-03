#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Seal CI evidence and reject unqualified or mismatched GitOps promotions."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import runpy
import xml.etree.ElementTree as ET

SHA = re.compile(r"[a-f0-9]{64}")
SOURCE = re.compile(r"[a-f0-9]{40}")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}")
ENVIRONMENTS = {
    "dev": ("snapshot", "carl-dev", "carl-dev.galaxy.direct"),
    "staging": ("rc", "carl-staging", "carl-staging.galaxy.direct"),
    "production": ("stable", "carl-prod", "carl.galaxy.direct"),
}
GATES = {
    "foundation": "target/foundation-evidence/report.json",
    "tests": "target/site/jacoco/jacoco.xml",
    "browser": "target/browser-evidence/report.json",
    "dashboard": "target/dashboard-browser-evidence/report.json",
    "talk": "target/talk-browser-evidence/report.json",
    "dockedChat": "target/docked-chat-browser-evidence/docked-chat-report.json",
    "qbits": "target/qbits-browser-evidence/qbits-report.json",
    "migration": "target/migration-evidence/report.json",
    "sourceScan": "target/security/gitleaks.json",
    "dependenciesScan": "target/security/dependencies.json",
    "applicationScan": "target/security/image.json",
    "migratorScan": "target/security/migrator.json",
    "applicationSBOM": "target/security/application-sbom.json",
    "migratorSBOM": "target/security/migrator-sbom.json",
    "manifests": "target/gitops-validation.json",
}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate(manifest):
    if (manifest.get("schema") != 1 or manifest.get("repository") != "KofTwentyTwo/carl"
        or not SOURCE.fullmatch(str(manifest.get("source", "")))
        or not SHA.fullmatch(str(manifest.get("distributionSha256", "")))
        or not manifest.get("workflow")):
        raise ValueError("Exact Carl source/distribution/workflow identity required")
    policy = runpy.run_path(str(Path(__file__).resolve().parents[1] / "release-policy.py"))
    if policy["parse"](manifest.get("version", ""))[1] != manifest.get("channel"):
        raise ValueError("Image-set semantic version/channel mismatch")
    for field, expected in (("gates", set(GATES)), ("images", {"application", "migrator"})):
        if not isinstance(manifest.get(field), dict) or set(manifest[field]) != expected:
            raise ValueError("Incomplete " + field)
    if any(not SHA.fullmatch(str(value)) for value in manifest["gates"].values()):
        raise ValueError("Hashed passing gate evidence required")
    dependencies = manifest.get("dependencyClosure")
    if not isinstance(dependencies, dict) or not dependencies:
        raise ValueError("Actual dependency closure required")
    for name, value in dependencies.items():
        if (not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts
            or not name.endswith(".jar") or not SHA.fullmatch(str(value))):
            raise ValueError("Invalid dependency closure")
    for role, image in manifest["images"].items():
        expected = "ghcr.io/koftwentytwo/carl" + ("-migrator" if role == "migrator" else "")
        if (not isinstance(image, dict) or set(image) != {"name", "digest"}
            or image["name"] != expected or not DIGEST.fullmatch(str(image["digest"]))):
            raise ValueError("Immutable reviewed application/migrator image pair required")
    if manifest.get("qualification") != {"ciData": "SYNTHETIC_ONLY", "deployment": "NOT_QUALIFIED"}:
        raise ValueError("CI must neither contain private data nor claim deployed acceptance")
    return manifest


def qualify(manifest, qualification, environment, rc=None):
    validate(manifest)
    channel, namespace, host = ENVIRONMENTS[environment]
    if manifest["channel"] != channel:
        raise ValueError("Release channel cannot promote to this environment")
    expected = {
        "schema": 1, "environment": environment, "namespace": namespace, "host": host,
        "data": "RETAINED_PRIVATE", "identity": "VERIFIED", "backupRestore": "PASS",
        "databaseIsolation": "PASS", "workflowQualification": "PASS",
        "source": manifest["source"], "distributionSha256": manifest["distributionSha256"],
        "images": manifest["images"],
    }
    if any(qualification.get(key) != value for key, value in expected.items()):
        raise ValueError("Current private deployment qualification does not match the exact candidate")
    expires = datetime.fromisoformat(qualification.get("expiresAt", "").replace("Z", "+00:00"))
    if expires.tzinfo is None or expires <= datetime.now(timezone.utc):
        raise ValueError("Environment qualification expired or lacks an explicit timezone")
    if channel == "stable":
        if rc is None:
            raise ValueError("Stable requires a previously qualified RC image set")
        validate(rc)
        if (rc["channel"] != "rc" or not rc["version"].startswith(manifest["version"] + "-rc.")
            or any(rc[key] != manifest[key] for key in ("source", "distributionSha256", "dependencyClosure", "images", "gates"))):
            raise ValueError("Stable must promote the preceding qualified RC bytes without rebuilding")


def seal(root, images, plan, workflow):
    release = runpy.run_path(str(root / "scripts/release-evidence.py"))
    release["tests"](root)
    foundation_policy = runpy.run_path(str(root / "scripts/ci/foundation.py"))
    baseline_path = foundation_policy["baseline_path"](root, plan["channel"])
    foundation = json.loads(baseline_path.read_text())
    parent = ET.parse(root / "pom.xml").getroot().findtext(
        "{http://maven.apache.org/POM/4.0.0}parent/{http://maven.apache.org/POM/4.0.0}version")
    if (foundation.get("version") != parent or not foundation.get("qualificationRun")
        or not foundation.get("artifacts") or not SHA.fullmatch(str(foundation.get("evidenceZipSha256", "")))):
        raise ValueError("Current parent requires matching reviewed upstream foundation qualification")
    proof = json.loads((root / GATES["foundation"]).read_text())
    if (proof.get("status") != "PASS" or proof.get("version") != parent
        or proof.get("baselinePath") != str(baseline_path.relative_to(root))
        or proof.get("baselineSha256") != digest(baseline_path)
        or proof.get("qualificationRun") != foundation["qualificationRun"]
        or proof.get("resolvedArtifacts") != foundation["artifacts"]
        or proof.get("resolvedImmutableArtifacts") != {actual: foundation["artifacts"][logical]
            for logical, actual in foundation.get("immutableArtifactPaths", {}).items()}
        or proof.get("resolution") != "FRESH_AUTHENTICATED_MAVEN"):
        raise ValueError("Fresh foundation resolution evidence must match the reviewed qualification")
    security = runpy.run_path(str(root / "scripts/verify-security.py"))
    for gate, relative in GATES.items():
        path = root / relative
        if gate == "tests":
            continue
        report = json.loads(path.read_text())
        if gate == "sourceScan":
            if report != []:
                raise ValueError("Source secret scan must be clean")
        elif gate.endswith("Scan"):
            security["validate_trivy"](report)
        elif gate.endswith("SBOM"):
            if report.get("bomFormat") != "CycloneDX" or not report.get("components"):
                raise ValueError("Actual nonempty image SBOM required")
        elif report.get("status") != "PASS":
            raise ValueError("Mandatory gate did not pass: " + gate)
        elif gate in {"browser", "dashboard", "talk", "dockedChat", "qbits"}:
            checks = report.get("checks", [])
            if not checks or len(checks) != len(set(checks)):
                raise ValueError("Missing actual browser checks: " + gate)
        elif gate == "migration":
            if any(report.get(key) is not True for key in (
                "fresh", "retained", "repeat", "checksumRejection", "permissionIsolation", "interruptionRetry")):
                raise ValueError("Full PostgreSQL migration/retained-data qualification required")
            if report.get("packagedDistributionMatched") is not True:
                raise ValueError("Actual consumer SQL/changelog must match the qualified image distribution")
        elif gate == "manifests" and report.get("environments") != list(ENVIRONMENTS):
            raise ValueError("Every GitOps environment must be validated")
    distribution = root / "target/agent"
    files = {str(path.relative_to(distribution)): digest(path) for path in sorted(distribution.rglob("*")) if path.is_file()}
    if not files or any(path.is_symlink() for path in distribution.rglob("*")):
        raise ValueError("Real immutable executable distribution required")
    for name, expected in foundation["artifacts"].items():
        if name.endswith(".jar") and not name.endswith("-tests.jar") and expected not in files.values():
            raise ValueError("Packaged foundation differs from the fresh reviewed artifacts")
    docked = json.loads((root / GATES["dockedChat"]).read_text())
    if (docked.get("distributionUnchanged") is not True or docked.get("apiResponsesMocked") is not False
        or docked.get("distributionFileHashes") != files):
        raise ValueError("Browser tests must use this exact immutable distribution")
    tests = sorted((root / "target").glob("*surefire-reports/TEST-*.xml")) + sorted((root / "target").glob("failsafe-reports/TEST-*.xml"))
    gates = {gate: digest(root / path) for gate, path in GATES.items()}
    gates["tests"] = hashlib.sha256(json.dumps({str(path.relative_to(root)): digest(path) for path in tests} | {GATES["tests"]: gates["tests"]}, sort_keys=True).encode()).hexdigest()
    manifest = {
        "schema": 1, "repository": "KofTwentyTwo/carl", "source": plan["source"],
        "version": plan["version"], "channel": plan["channel"], "workflow": workflow,
        "distributionSha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
        "dependencyClosure": {name: value for name, value in files.items() if name.endswith(".jar")},
        "images": images, "gates": gates,
        "qualification": {"ciData": "SYNTHETIC_ONLY", "deployment": "NOT_QUALIFIED"},
    }
    return validate(manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    seal_parser = commands.add_parser("seal")
    seal_parser.add_argument("--images", type=Path, required=True)
    seal_parser.add_argument("--plan", type=Path, required=True)
    seal_parser.add_argument("--workflow", required=True)
    seal_parser.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("manifest", type=Path)
    verify.add_argument("--qualification", type=Path)
    verify.add_argument("--environment", choices=ENVIRONMENTS)
    verify.add_argument("--rc", type=Path)
    args = parser.parse_args()
    if args.command == "seal":
        manifest = seal(Path.cwd(), json.loads(args.images.read_text()), json.loads(args.plan.read_text()), args.workflow)
        args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    else:
        manifest = validate(json.loads(args.manifest.read_text()))
        if args.qualification or args.environment:
            if not args.qualification or not args.environment:
                parser.error("Qualification and environment are required together")
            qualify(manifest, json.loads(args.qualification.read_text()), args.environment,
                    json.loads(args.rc.read_text()) if args.rc else None)


if __name__ == "__main__":
    main()
