#!/usr/bin/env python3
#
# Copyright (C) 2026 KofTwentyTwo
#
"""Synthetic release-policy, provenance and fail-closed evidence regressions."""

import hashlib
import json
import os
import runpy
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY = runpy.run_path(str(ROOT / "scripts/release-policy.py"))
EVIDENCE = runpy.run_path(str(ROOT / "scripts/release-evidence.py"))
SHA = "1" * 40


class ReleaseTests(unittest.TestCase):
    def test_signed_tag_requires_trusted_key_and_exact_main_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / "signing"
            home.mkdir(mode=0o700)
            env = dict(
                os.environ,
                GNUPGHOME=str(home),
                GIT_CONFIG_GLOBAL=os.devnull,
                GIT_CONFIG_NOSYSTEM="1",
            )

            def command(*args):
                return subprocess.run(
                    args, cwd=root, env=env, check=True, capture_output=True, text=True
                ).stdout.strip()

            command(
                "gpg",
                "--batch",
                "--pinentry-mode",
                "loopback",
                "--passphrase",
                "",
                "--quick-generate-key",
                "Carl Synthetic Release <release@example.invalid>",
                "ed25519",
                "sign",
                "0",
            )
            fingerprint = next(
                line.split(":")[9]
                for line in command("gpg", "--with-colons", "--list-keys").splitlines()
                if line.startswith("fpr:")
            )
            command("git", "init", "-q")
            command("git", "config", "user.name", "Synthetic Release")
            command("git", "config", "user.email", "release@example.invalid")
            (root / "source.txt").write_text("synthetic immutable source")
            command("git", "add", "source.txt")
            command("git", "commit", "-qm", "synthetic")
            commit = command("git", "rev-parse", "HEAD")
            command("git", "update-ref", "refs/remotes/origin/main", commit)
            command(
                "git",
                "-c",
                "user.signingkey=" + fingerprint,
                "tag",
                "-s",
                "v0.1.0-rc.1",
                "-m",
                "Synthetic candidate",
            )
            (root / "config/release").mkdir(parents=True)
            (root / "config/release/trusted-signers.asc").write_text(
                command("gpg", "--armor", "--export", fingerprint)
            )
            POLICY["signed_tag"](root, "v0.1.0-rc.1", commit)
            command("git", "tag", "-a", "v0.1.0-rc.2", "-m", "Unsigned candidate")
            with self.assertRaises(ValueError):
                POLICY["signed_tag"](root, "v0.1.0-rc.2", commit)
            (root / "source.txt").write_text("different source")
            command("git", "commit", "-qam", "new source")
            with self.assertRaises(ValueError):
                POLICY["signed_tag"](
                    root, "v0.1.0-rc.1", command("git", "rev-parse", "HEAD")
                )

    def test_channels_and_untrusted_refs(self):
        for branch in ("main", "develop"):
            plan = POLICY["select"]("0.1.0", "push", "refs/heads/" + branch, SHA)
            self.assertEqual("snapshot", plan["channel"])
            self.assertIn(SHA, plan["tag"])
        for tag, channel in (("v0.1.0-rc.1", "rc"), ("v0.1.0", "stable")):
            self.assertEqual(
                channel,
                POLICY["select"](
                    "0.1.0", "workflow_dispatch", "refs/heads/main", SHA, tag
                )["channel"],
            )
        for event, ref, tag in (
            ("pull_request", "refs/heads/main", ""),
            ("push", "refs/heads/feature/x", ""),
            ("workflow_dispatch", "refs/heads/develop", "v0.1.0"),
            ("workflow_dispatch", "refs/heads/main", "v0.2.0"),
        ):
            with self.assertRaises(ValueError):
                POLICY["select"]("0.1.0", event, ref, SHA, tag)

    def test_invalid_versions(self):
        for value in (
            "01.0.0",
            "1.0",
            "1.0.0-rc.0",
            "1.0.0-rc.01",
            "1.0.0\nmalicious",
            "../1.0.0",
            "1.0.0+extra",
        ):
            with self.assertRaises(ValueError):
                POLICY["parse"](value)

    def test_semantic_change_classification(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "CHANGELOG.md").write_text(
                "## [0.1.1] - 2026-09-30\n\n### Added\n\n- A new feature.\n"
            )
            with self.assertRaises(ValueError):
                POLICY["notes"](root, "0.1.1", "0.1.0")
            (root / "CHANGELOG.md").write_text(
                "## [0.2.0] - 2026-09-30\n\n### Added\n\n- A new feature.\n"
            )
            self.assertIn("A new feature", POLICY["notes"](root, "0.2.0", "0.1.0"))

    def test_candidate_changes_only_consumer_version_and_requires_qualified_parent(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "config/release").mkdir(parents=True)
            (root / "config/release/foundation.json").write_text('{"version":"0.4.0"}')
            (root / "VERSION").write_text("0.1.0\n")
            pom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>com.kof22</groupId><artifactId>kof22-agent-parent</artifactId><version>0.4.0</version></parent><artifactId>carl-ai</artifactId><version>0.1.0-SNAPSHOT</version></project>'
            (root / "pom.xml").write_text(pom)
            POLICY["prepare"](root, "0.1.0-rc.1")
            result = ET.parse(root / "pom.xml").getroot()
            self.assertEqual(
                "0.4.0", result.findtext("m:parent/m:version", namespaces=POLICY["NS"])
            )
            self.assertEqual(
                "0.1.0-rc.1", result.findtext("m:version", namespaces=POLICY["NS"])
            )
            (root / "pom.xml").write_text(pom.replace("0.4.0", "0.4.0-SNAPSHOT"))
            with self.assertRaises(ValueError):
                POLICY["prepare"](root, "0.1.0")

    def fixture(self, root):
        for name in (
            "scripts",
            "config/release",
            "target/surefire-reports",
            "target/site/jacoco",
            "target/browser-evidence",
            "target/security",
            "target/agent/bin",
            "target/agent/lib",
            "m2",
        ):
            (root / name).mkdir(parents=True)
        shutil.copy(
            ROOT / "scripts/release-policy.py", root / "scripts/release-policy.py"
        )
        shutil.copy(
            ROOT / "scripts/verify-security.py", root / "scripts/verify-security.py"
        )
        (root / "target/release-preparation.json").write_text(
            '{"version":"0.1.0-rc.1","foundation":"0.4.0"}'
        )
        (root / "m2/parent.pom").write_text("Synthetic qualified Maven bytes")
        foundation = {
            "version": "0.4.0",
            "source": "2" * 40,
            "artifacts": {
                "parent.pom": hashlib.sha256(
                    (root / "m2/parent.pom").read_bytes()
                ).hexdigest()
            },
        }
        (root / "config/release/foundation.json").write_text(json.dumps(foundation))
        (root / "target/surefire-reports/TEST-synthetic.xml").write_text(
            '<testsuite tests="2" failures="0" errors="0" skipped="0"/>'
        )
        (root / "target/site/jacoco/jacoco.xml").write_text(
            '<report><counter type="LINE" covered="8" missed="2"/><counter type="BRANCH" covered="6" missed="4"/></report>'
        )
        (root / "target/browser-evidence/report.json").write_text(
            '{"status":"PASS","checks":["synthetic-authenticated-native-flow"]}'
        )
        scan = {
            "SchemaVersion": 2,
            "Results": [
                {
                    "Type": "jar",
                    "Packages": [{"Name": "synthetic", "Version": "1"}],
                    "Vulnerabilities": [],
                }
            ],
        }
        for name in ("dependencies", "image"):
            (root / "target/security" / (name + ".json")).write_text(json.dumps(scan))
        (root / "target/security/gitleaks.json").write_text("[]")
        (root / "target/agent/bin/agent").write_text("synthetic executable fixture")
        (root / "target/agent/lib/synthetic.jar").write_text("synthetic jar fixture")
        (root / "target/candidate-image.tar").write_text("synthetic image fixture")

    def seal(self, root):
        return EVIDENCE["seal"](
            root, root / "delivery", SHA, "KofTwentyTwo/carl", "123", root / "m2"
        )

    def test_seal_and_detect_changed_release_bytes_or_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            self.seal(root)
            result = EVIDENCE["verify"](root / "delivery", SHA, "0.1.0-rc.1", "rc")
            self.assertEqual(2, result["qualification"]["tests"])
            with self.assertRaises(ValueError):
                EVIDENCE["verify"](root / "delivery", "2" * 40)
            (root / "delivery/qualification-evidence.zip").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                EVIDENCE["verify"](root / "delivery", SHA)

    def test_failed_skipped_browser_scanner_or_dependency_evidence_cannot_release(self):
        mutations = (
            (
                "target/surefire-reports/TEST-synthetic.xml",
                '<testsuite tests="2" failures="0" errors="0" skipped="1"/>',
            ),
            (
                "target/browser-evidence/report.json",
                '{"status":"FAIL","checks":["one"]}',
            ),
            ("target/security/image.json", '{"SchemaVersion":2,"Results":[]}'),
            (
                "target/security/gitleaks.json",
                '[{"Description":"synthetic secret finding"}]',
            ),
            ("m2/parent.pom", "different dependency"),
            (
                "target/site/jacoco/jacoco.xml",
                '<report><counter type="LINE" covered="1" missed="9"/><counter type="BRANCH" covered="6" missed="4"/></report>',
            ),
        )
        for name, contents in mutations:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.fixture(root)
                (root / name).write_text(contents)
                with self.assertRaises(ValueError):
                    self.seal(root)


if __name__ == "__main__":
    unittest.main()
