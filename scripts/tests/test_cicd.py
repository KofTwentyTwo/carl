#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Negative promotion regressions; synthetic evidence never qualifies a deployment."""

import copy
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class PromotionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = runpy.run_path(str(ROOT / "scripts/ci/image-set.py"))

    def manifest(self):
        return {
            "schema": 1,
            "repository": "KofTwentyTwo/carl",
            "source": "a" * 40,
            "channel": "snapshot",
            "version": "0.1.0-SNAPSHOT",
            "workflow": "pipeline-123",
            "distributionSha256": "b" * 64,
            "dependencyClosure": {"lib/core.jar": "c" * 64},
            "images": {
                "application": {"name": "ghcr.io/koftwentytwo/carl", "digest": "sha256:" + "d" * 64},
                "migrator": {"name": "ghcr.io/koftwentytwo/carl-migrator", "digest": "sha256:" + "e" * 64},
            },
            "gates": {key: "f" * 64 for key in self.policy["GATES"]},
            "qualification": {"ciData": "SYNTHETIC_ONLY", "deployment": "NOT_QUALIFIED"},
        }

    def deployment(self, environment="dev"):
        return {
            "schema": 1,
            "environment": environment,
            "namespace": "carl-" + ("prod" if environment == "production" else environment),
            "host": {"dev": "carl-dev.galaxy.direct", "staging": "carl-staging.galaxy.direct", "production": "carl.galaxy.direct"}[environment],
            "data": "RETAINED_PRIVATE",
            "identity": "VERIFIED",
            "backupRestore": "PASS",
            "databaseIsolation": "PASS",
            "workflowQualification": "PASS",
            "source": "a" * 40,
            "distributionSha256": "b" * 64,
            "images": copy.deepcopy(self.manifest()["images"]),
            "expiresAt": "2099-01-01T00:00:00Z",
        }

    def test_complete_snapshot_promotes_only_to_dev(self):
        self.policy["validate"](self.manifest())
        self.policy["qualify"](self.manifest(), self.deployment(), "dev")
        with self.assertRaises(ValueError):
            self.policy["qualify"](self.manifest(), self.deployment("staging"), "staging")

    def test_missing_gate_or_mutable_image_is_rejected(self):
        for key in self.policy["GATES"]:
            manifest = self.manifest()
            del manifest["gates"][key]
            with self.assertRaises(ValueError):
                self.policy["validate"](manifest)
        manifest = self.manifest()
        manifest["images"]["migrator"]["digest"] = "latest"
        with self.assertRaises(ValueError):
            self.policy["validate"](manifest)

    def test_bad_scan_or_test_inventory_is_rejected(self):
        for value in ({}, [], "", "PASS"):
            manifest = self.manifest()
            manifest["dependencyClosure"] = value
            with self.assertRaises(ValueError):
                self.policy["validate"](manifest)

    def test_private_qualification_must_match_both_images_and_exact_source(self):
        for field in ("source", "distributionSha256", "images", "host", "namespace"):
            qualification = self.deployment()
            qualification[field] = "different"
            with self.assertRaises(ValueError):
                self.policy["qualify"](self.manifest(), qualification, "dev")

    def test_fixture_identity_and_expired_evidence_never_qualify(self):
        for field, value in (("data", "SYNTHETIC_ONLY"), ("identity", "FIXTURE"),
                             ("backupRestore", "PENDING"), ("databaseIsolation", "FAIL"),
                             ("workflowQualification", "PENDING"), ("expiresAt", "2020-01-01T00:00:00Z")):
            qualification = self.deployment()
            qualification[field] = value
            with self.assertRaises(ValueError):
                self.policy["qualify"](self.manifest(), qualification, "dev")

    def test_stable_requires_the_same_qualified_rc_image_set(self):
        manifest = self.manifest()
        manifest.update(channel="stable", version="0.1.0")
        qualification = self.deployment("production")
        with self.assertRaises(ValueError):
            self.policy["qualify"](manifest, qualification, "production")
        rc = copy.deepcopy(manifest)
        rc.update(channel="rc", version="0.1.0-rc.1")
        self.policy["qualify"](manifest, qualification, "production", rc)
        rc["images"]["migrator"]["digest"] = "sha256:" + "1" * 64
        with self.assertRaises(ValueError):
            self.policy["qualify"](manifest, qualification, "production", rc)


if __name__ == "__main__":
    unittest.main()
