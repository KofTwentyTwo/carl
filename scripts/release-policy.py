#!/usr/bin/env python3
#
# Copyright (C) 2026 KofTwentyTwo
#
"""Validate Carl semantic versions and exact-source signed promotion policy."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(SNAPSHOT|rc\.([1-9][0-9]*)))?"
)


def parse(value):
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError("Expected X.Y.Z, X.Y.Z-rc.N or X.Y.Z-SNAPSHOT")
    return (
        tuple(int(match[i]) for i in (1, 2, 3)),
        "snapshot" if match[4] == "SNAPSHOT" else "rc" if match[4] else "stable",
        int(match[5] or 0),
    )


def select(target, event, ref, commit, tag=""):
    if parse(target)[1] != "stable" or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Unqualified upcoming version and exact commit required")
    if event == "push" and ref in ("refs/heads/main", "refs/heads/develop"):
        return {
            "version": target + "-SNAPSHOT",
            "channel": "snapshot",
            "tag": "snapshot-" + ref.rsplit("/", 1)[1] + "-" + commit,
            "source": commit,
        }
    if (
        event == "workflow_dispatch"
        and ref == "refs/heads/main"
        and tag.startswith("v")
    ):
        numbers, channel, _ = parse(tag[1:])
        if numbers != parse(target)[0] or channel == "snapshot":
            raise ValueError("Promotion tag must match VERSION and be RC/stable")
        return {"version": tag[1:], "channel": channel, "tag": tag, "source": commit}
    raise ValueError(
        "Only main/develop pushes or signed main RC/stable dispatches publish"
    )


def git(root, *args, env=None):
    return subprocess.run(
        ["git", *args], cwd=root, env=env, check=True, text=True, capture_output=True
    ).stdout.strip()


def notes(root, target, previous=None):
    content = (root / "CHANGELOG.md").read_text()
    match = re.search(
        r"^## \[" + re.escape(target) + r"\] - (\d{4}-\d{2}-\d{2})\n(.*?)(?=^## |\Z)",
        content,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        raise ValueError("VERSION needs one dated changelog section")
    date.fromisoformat(match[1])
    kinds = {x.lower() for x in re.findall(r"^### (\w+)$", match[2], re.MULTILINE)}
    if (
        not kinds
        or not kinds
        <= {"added", "changed", "fixed", "security", "breaking", "documentation"}
        or not re.search(r"^[-*] .+", match[2], re.MULTILINE)
    ):
        raise ValueError("Classified substantive release notes required")
    if previous:
        old = parse(previous)[0]
        minimum = (
            (old[0] + 1, 0, 0)
            if "breaking" in kinds and old[0]
            else (old[0], old[1] + 1, 0)
            if kinds & {"breaking", "added", "changed"}
            else (old[0], old[1], old[2] + 1)
        )
        if parse(target)[0] < minimum:
            raise ValueError(
                "Changelog classification requires a larger semantic version"
            )
        new = parse(target)[0]
        if (new[0] != old[0] and new[1:] != (0, 0)) or (new[1] != old[1] and new[2]):
            raise ValueError("Major and minor bumps reset lower version components")
    return match.group(0).strip()


def signed_tag(root, tag, commit):
    if (
        git(root, "rev-parse", tag + "^{commit}") != commit
        or git(root, "rev-parse", "origin/main") != commit
    ):
        raise ValueError(
            "Signed promotion must use the exact current protected main source"
        )
    if "-----BEGIN PGP SIGNATURE-----" not in git(root, "cat-file", "-p", tag):
        raise ValueError("Annotated OpenPGP signed tag required")
    with tempfile.TemporaryDirectory(prefix="carl-release-trust-") as directory:
        os.chmod(directory, 0o700)
        env = dict(os.environ, GNUPGHOME=directory)
        subprocess.run(
            [
                "gpg",
                "--batch",
                "--import",
                str(root / "config/release/trusted-signers.asc"),
            ],
            env=env,
            check=True,
            capture_output=True,
        )
        git(root, "-c", "gpg.program=gpg", "verify-tag", tag, env=env)


def prepare(root, version):
    parse(version)
    path = root / "pom.xml"
    original = path.read_bytes()
    tree = ET.fromstring(original)
    direct = tree.find("m:version", NS)
    target = (root / "VERSION").read_text().strip()
    if (
        direct is None
        or direct.text != target + "-SNAPSHOT"
        or parse(version)[0] != parse(target)[0]
    ):
        raise ValueError(
            "Candidate must replace only the aligned consumer snapshot version"
        )
    parent = tree.findtext("m:parent/m:version", namespaces=NS)
    qualified = json.loads((root / "config/release/foundation.json").read_text())
    if parent != qualified["version"]:
        raise ValueError(
            "Consumer parent must match the reviewed remote foundation qualification baseline"
        )
    if parse(version)[1] in ("rc", "stable") and (
        parent is None or parse(parent)[1] != "stable"
    ):
        raise ValueError("RC/stable require a qualified stable foundation release pin")
    direct.text = version
    ET.register_namespace("", NS["m"])
    ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
    ET.ElementTree(tree).write(path, encoding="utf-8", xml_declaration=True)
    output = root / "target/release-preparation.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "version": version,
                "foundation": parent,
                "sourcePomSha256": hashlib.sha256(original).hexdigest(),
                "candidatePomSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "transformation": "consumer project version only; parent and dependencies unchanged",
            },
            indent=2,
        )
        + "\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--prepare")
    parser.add_argument("--event", default=os.getenv("GITHUB_EVENT_NAME"))
    parser.add_argument("--ref", default=os.getenv("GITHUB_REF"))
    parser.add_argument("--commit", default=os.getenv("GITHUB_SHA"))
    parser.add_argument("--tag", default="")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if args.prepare:
        prepare(args.root, args.prepare)
        return
    target = (args.root / "VERSION").read_text().strip()
    plan = select(target, args.event, args.ref, args.commit, args.tag)
    if git(args.root, "rev-parse", "HEAD") != plan["source"]:
        raise ValueError("Checkout differs from claimed workflow source")
    tags = git(args.root, "tag", "--list", "v*").splitlines()
    versions = []
    for tag in tags:
        try:
            versions.append((tag, parse(tag[1:])))
        except ValueError:
            continue
    stable = [
        tag[1:] for tag, value in versions if value[1] == "stable" and tag != args.tag
    ]
    previous = max(stable, key=lambda x: parse(x)[0], default=None)
    plan["notes"] = notes(args.root, target, previous)
    plan["rcTag"] = ""
    if plan["channel"] != "snapshot":
        signed_tag(args.root, args.tag, args.commit)
        current = parse(plan["version"])
        earlier = [
            value[2]
            for tag, value in versions
            if value[0] == current[0] and value[1] == "rc" and tag != args.tag
        ]
        if current[1] == "rc" and current[2] != max(earlier, default=0) + 1:
            raise ValueError("RC numbers must be consecutive and immutable")
        if current[1] == "stable":
            if not earlier:
                raise ValueError("Stable promotion requires a qualified RC")
            plan["rcTag"] = "v" + target + "-rc." + str(max(earlier))
            if git(args.root, "rev-parse", plan["rcTag"] + "^{commit}") != args.commit:
                raise ValueError("Stable source must equal the preceding RC source")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(plan, indent=2) + "\n")
    if args.github_output:
        with args.github_output.open("a") as output:
            for key in ("version", "channel", "tag", "source", "rcTag"):
                output.write(key + "=" + plan[key] + "\n")


if __name__ == "__main__":
    main()
