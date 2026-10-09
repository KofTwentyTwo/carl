#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Reject unsafe or incomplete manifests before Argo registration/promotion."""

import copy
from pathlib import Path
import runpy
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class GitOpsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = runpy.run_path(str(ROOT / "scripts/ci/validate-gitops.py"))
        output = subprocess.run(["kustomize", "build", str(ROOT / "deployment/gitops/environments/dev")], check=True, text=True, capture_output=True).stdout
        cls.documents = list(yaml.safe_load_all(output))

    def test_all_environments_render_and_remain_unqualified(self):
        for environment in self.policy["ENVIRONMENTS"]:
            output = subprocess.run(["kustomize", "build", str(ROOT / "deployment/gitops/environments" / environment)], check=True, text=True, capture_output=True).stdout
            documents = list(yaml.safe_load_all(output))
            self.policy["validate"](documents, environment)
            with self.assertRaises(ValueError):
                self.policy["validate"](documents, environment, True)

    def test_namespace_credentials_and_startup_changes_are_rejected(self):
        for mutation in ("namespace", "credential", "seed", "api", "root", "hook", "privileged"):
            documents = copy.deepcopy(self.documents)
            deployment = next(value for value in documents if value["kind"] == "Deployment")
            job = next(value for value in documents if value["kind"] == "Job")
            pod = deployment["spec"]["template"]["spec"]
            if mutation == "namespace": deployment["metadata"]["namespace"] = "carl-prod"
            elif mutation == "credential": pod["volumes"][0]["secret"]["secretName"] = "carl-migration"
            elif mutation == "seed": pod["containers"][0]["command"] = ["fixture-seed"]
            elif mutation == "api": pod["automountServiceAccountToken"] = True
            elif mutation == "root": pod["securityContext"]["runAsUser"] = 0
            elif mutation == "hook": job["metadata"]["annotations"]["argocd.argoproj.io/hook"] = "PostSync"
            elif mutation == "privileged": pod["containers"][0]["securityContext"]["privileged"] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.policy["validate"](documents, "dev")

    def test_only_a_complete_immutable_image_pair_is_deployable(self):
        documents = copy.deepcopy(self.documents)
        for document in documents:
            if document["kind"] in {"Deployment", "Job"}:
                container = document["spec"]["template"]["spec"]["containers"][0]
                container["image"] = container["image"].split(":", 1)[0] + "@sha256:" + "a" * 64
        with self.assertRaises(ValueError):
            self.policy["validate"](documents, "dev", True)
        job = next(value for value in documents if value["kind"] == "Job")
        backup = copy.deepcopy(job)
        backup["metadata"]["name"] = "carl-backup"
        backup["metadata"]["annotations"]["argocd.argoproj.io/sync-wave"] = "-30"
        backup["spec"]["template"]["spec"]["volumes"][0]["secret"]["secretName"] = "carl-backup"
        backup_image = "ghcr.io/koftwentytwo/qualified-backup@sha256:" + "b" * 64
        backup["spec"]["template"]["spec"]["containers"][0]["image"] = backup_image
        documents.append(backup)
        self.policy["validate"](documents, "dev", True, backup_image)
        job["spec"]["template"]["spec"]["containers"][0]["image"] = "ghcr.io/koftwentytwo/carl-migrator:latest"
        with self.assertRaises(ValueError):
            self.policy["validate"](documents, "dev", True)

    def test_circleci_publishes_after_full_verification_and_stable_reuses_rc(self):
        config = yaml.safe_load((ROOT / ".circleci/config.yml").read_text())
        for workflow in ("development", "release-candidate"):
            jobs = config["workflows"][workflow]["jobs"]
            publish = next(value["publish"] for value in jobs if "publish" in value)
            promote = next(value["promote"] for value in jobs if "promote" in value)
            self.assertEqual(["verify"], publish["requires"])
            self.assertEqual(["publish"], promote["requires"])
        steps = config["jobs"]["stable"]["steps"]
        commands = [value["run"]["command"] for value in steps if isinstance(value, dict) and "run" in value]
        self.assertIn("bash scripts/ci/stable.sh", commands)
        self.assertFalse(any("images.sh build" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
