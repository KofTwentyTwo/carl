#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Render all overlays and enforce secret, migration-order and image boundaries."""

import argparse
import json
from pathlib import Path
import re
import subprocess

import yaml

ENVIRONMENTS = {
    "dev": ("carl-dev", "carl-dev.galaxy.direct"),
    "staging": ("carl-staging", "carl-staging.galaxy.direct"),
    "production": ("carl-prod", "carl.galaxy.direct"),
}


def validate(documents, environment, require_digests=False, backup_image=None):
    namespace, host = ENVIRONMENTS[environment]
    kinds = {document["kind"]: document for document in documents if document["kind"] != "Job"}
    jobs = {document["metadata"]["name"]: document for document in documents if document["kind"] == "Job"}
    if (len(kinds) + len(jobs) != len(documents) or set(kinds) != {"Deployment", "Ingress", "Service", "ServiceAccount"}
        or set(jobs) not in ({"carl-migrate"}, {"carl-migrate", "carl-backup"})):
        raise ValueError("Exact Carl workload/migration resource inventory required")
    if any(document["metadata"].get("namespace") != namespace for document in documents):
        raise ValueError("Environment namespace isolation required")
    for document in documents:
        if document.get("kind") in {"Secret", "ConfigMap"} or any(key in document for key in ("data", "stringData")):
            raise ValueError("Private configuration never belongs in public deployment source")
    ingress = kinds["Ingress"]["spec"]
    if ingress["rules"][0]["host"] != host or ingress["tls"][0]["hosts"] != [host]:
        raise ValueError("Exact environment HTTPS hostname required")
    deployment = kinds["Deployment"]
    job = jobs["carl-migrate"]
    annotations = job["metadata"].get("annotations", {})
    if (annotations.get("argocd.argoproj.io/hook") != "PreSync"
        or int(annotations.get("argocd.argoproj.io/sync-wave", "0")) >= int(deployment["metadata"]["annotations"]["argocd.argoproj.io/sync-wave"])
        or job["spec"]["backoffLimit"] > 2):
        raise ValueError("Bounded migration must succeed before application rollout")
    backup = jobs.get("carl-backup")
    if require_digests and backup is None:
        raise ValueError("Deployable private overlay requires a qualified backup before migration")
    if backup is not None:
        annotations = backup["metadata"].get("annotations", {})
        spec = backup["spec"]
        pod = spec["template"]["spec"]
        containers = pod["containers"]
        if (annotations.get("argocd.argoproj.io/hook") != "PreSync"
            or int(annotations.get("argocd.argoproj.io/sync-wave", "0")) >= int(job["metadata"]["annotations"]["argocd.argoproj.io/sync-wave"])
            or spec.get("backoffLimit", 99) > 2 or not 0 < spec.get("activeDeadlineSeconds", 0) <= 1800
            or pod.get("automountServiceAccountToken") is not False or len(containers) != 1):
            raise ValueError("Required backup must fail closed before migration")
        image = containers[0]["image"]
        if not re.fullmatch(r"ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}", image) or image.endswith("0" * 64):
            raise ValueError("Qualified existing backup image digest required")
        if require_digests and image != backup_image:
            raise ValueError("Backup image must match private recovery qualification")
        secrets = [volume["secret"] for volume in pod["volumes"] if "secret" in volume]
        if (not secrets or any(secret.get("secretName") != "carl-backup" or secret.get("optional") is not False for secret in secrets)
            or any("hostPath" in volume for volume in pod["volumes"])
            or pod.get("securityContext", {}).get("runAsNonRoot") is not True
            or containers[0].get("securityContext", {}).get("allowPrivilegeEscalation") is not False
            or pod.get("hostNetwork") or pod.get("hostPID") or pod.get("hostIPC")):
            raise ValueError("Backup needs its own isolated credentials and restricted workload")
    if kinds["ServiceAccount"].get("automountServiceAccountToken") is not False:
        raise ValueError("Carl does not need Kubernetes API credentials")
    for document, role in ((deployment, "application"), (job, "migrator")):
        pod = document["spec"]["template"]["spec"]
        if (pod.get("automountServiceAccountToken") is not False or pod.get("hostNetwork")
            or pod.get("hostPID") or pod.get("hostIPC") or pod.get("initContainers")):
            raise ValueError("No API token/host namespaces or unqualified startup helpers")
        context = pod["securityContext"]
        if context.get("runAsNonRoot") is not True or context.get("runAsUser") != 10001:
            raise ValueError("Non-root Carl user required")
        containers = pod["containers"]
        if len(containers) != 1:
            raise ValueError("Exactly one qualified role container required")
        container = containers[0]
        expected = "ghcr.io/koftwentytwo/carl" + ("-migrator" if role == "migrator" else "")
        image = container["image"]
        if require_digests:
            if not re.fullmatch(re.escape(expected) + r"@sha256:[a-f0-9]{64}", image) or image.endswith("0" * 64):
                raise ValueError("Real qualified immutable image digest required")
        elif image != expected + ":UNQUALIFIED":
            if not re.fullmatch(re.escape(expected) + r"@sha256:[a-f0-9]{64}", image):
                raise ValueError("Only explicit unqualified scaffold or immutable images allowed")
        security = container["securityContext"]
        if (security.get("allowPrivilegeEscalation") is not False or security.get("readOnlyRootFilesystem") is not True
            or security.get("privileged") or security.get("capabilities", {}).get("drop") != ["ALL"]):
            raise ValueError("Restricted read-only container required")
        name = "carl-runtime" if role == "application" else "carl-migration"
        secrets = [volume["secret"] for volume in pod["volumes"] if "secret" in volume]
        if len(secrets) != 1 or secrets[0]["secretName"] != name or secrets[0].get("optional") is not False:
            raise ValueError("Runtime and migration credentials must remain separate and mandatory")
        if any("hostPath" in volume for volume in pod["volumes"]):
            raise ValueError("No host filesystem access")
        expected_args = ["/run/secrets/runtime/agent.properties"] if role == "application" else ["migrate", "/run/secrets/migration/migration.properties"]
        if container.get("args") != expected_args or "command" in container or container.get("env") or container.get("envFrom"):
            raise ValueError("Use only the required private properties file, with no fixture/bootstrap startup")
    return documents


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("deployment/gitops"))
    parser.add_argument("--require-digests", choices=ENVIRONMENTS)
    parser.add_argument("--qualification", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.require_digests and not args.qualification:
        parser.error("Private recovery qualification is required for deployable overlays")
    qualification = json.loads(args.qualification.read_text()) if args.qualification else {}
    for environment in ENVIRONMENTS:
        rendered = subprocess.run(["kustomize", "build", str(args.root / "environments" / environment)], check=True, text=True, capture_output=True).stdout
        validate(list(yaml.safe_load_all(rendered)), environment, args.require_digests == environment, qualification.get("backupImage"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"status": "PASS", "environments": list(ENVIRONMENTS), "cluster": "NOT_QUALIFIED", "digestsRequiredFor": args.require_digests}, indent=2) + "\n")


if __name__ == "__main__":
    main()
