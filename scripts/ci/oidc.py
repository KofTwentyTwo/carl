#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""Validate Carl signing claims without emitting the identity token."""

import base64
import json
import os

PROJECT = "ab70c257-7c86-45e2-b988-86003f49734d"
ORGANIZATION = "f0a10872-21a3-4373-940a-06233d826661"
DEFINITION = "70b80fd1-b928-5fbf-817d-75ab416ccc87"
ISSUER = "https://oidc.circleci.com"
IDENTITY = "https://circleci.com/api/v2/projects/" + PROJECT + "/pipeline-definitions/" + DEFINITION


def validate_claims(claims, environment):
    expected_ref = "refs/tags/" + environment["CIRCLE_TAG"] if environment.get("CIRCLE_TAG") else "refs/heads/" + environment["CIRCLE_BRANCH"]
    expected = {
        "iss": ISSUER, "aud": "sigstore", "oidc.circleci.com/org-id": ORGANIZATION,
        "oidc.circleci.com/project-id": PROJECT, "oidc.circleci.com/pipeline-definition-id": DEFINITION,
        "oidc.circleci.com/vcs-origin": "github.com/KofTwentyTwo/carl",
        "oidc.circleci.com/vcs-ref": expected_ref,
        "oidc.circleci.com/workflow-id": environment["CIRCLE_WORKFLOW_ID"],
        "oidc.circleci.com/ssh-rerun": False,
    }
    if environment.get("CIRCLE_PROJECT_ID") != PROJECT or any(claims.get(k) != v for k, v in expected.items()):
        raise ValueError("Carl signing requires the pinned project/pipeline, exact source/ref/workflow and a normal job")
    return ISSUER, IDENTITY


if __name__ == "__main__":
    token = os.environ["SIGSTORE_ID_TOKEN"].split(".")
    if len(token) != 3:
        raise ValueError("CircleCI identity token required")
    claims = json.loads(base64.urlsafe_b64decode(token[1] + "=" * (-len(token[1]) % 4)))
    validate_claims(claims, os.environ)
