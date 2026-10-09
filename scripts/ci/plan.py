#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Reuse the existing signed source/semantic-version policy from CircleCI."""

import argparse
import json
import os
from pathlib import Path
import runpy
import subprocess

root = Path.cwd()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--record-only", action="store_true")
args = parser.parse_args()
policy = runpy.run_path(str(root / "scripts/release-policy.py"))
source = os.environ["CIRCLE_SHA1"]
tag = os.environ.get("CIRCLE_TAG", "")
if tag:
    subprocess.run(["git", "fetch", "--tags", "origin", "+refs/heads/main:refs/remotes/origin/main"], check=True)
    subprocess.run(["python3", "scripts/release-policy.py", "--event", "workflow_dispatch",
                    "--ref", "refs/heads/main", "--commit", source, "--tag", tag,
                    "--output", "target/circleci-plan.json"], check=True)
    plan = json.loads((root / "target/circleci-plan.json").read_text())
    if not args.record_only:
        policy["prepare"](root, plan["version"])
else:
    branch = os.environ.get("CIRCLE_BRANCH", "")
    if branch in {"main", "develop"}:
        plan = policy["select"]((root / "VERSION").read_text().strip(), "push", "refs/heads/" + branch, source)
    else:
        plan = {"version": (root / "VERSION").read_text().strip() + "-SNAPSHOT", "channel": "snapshot", "source": source, "tag": ""}
if policy["git"](root, "rev-parse", "HEAD") != source:
    raise ValueError("CircleCI source must match the exact checkout")
output = root / "target/circleci-plan.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n")
