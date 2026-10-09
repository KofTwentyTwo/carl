#!/usr/bin/env python3
# Copyright (C) 2026 KofTwentyTwo
"""CI identity and complete publication policy regressions; synthetic tests only."""

import copy
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReadinessTest(unittest.TestCase):
    def test_sigstore_requires_exact_carl_identity_audience_ref_and_normal_job(self):
        policy = runpy.run_path(str(ROOT / 'scripts/ci/oidc.py'))
        environment = {'CIRCLE_PROJECT_ID': policy['PROJECT'], 'CIRCLE_WORKFLOW_ID': 'workflow', 'CIRCLE_BRANCH': 'develop'}
        claims = {'iss': policy['ISSUER'], 'aud': 'sigstore', 'oidc.circleci.com/org-id': policy['ORGANIZATION'],
                  'oidc.circleci.com/project-id': policy['PROJECT'], 'oidc.circleci.com/pipeline-definition-id': policy['DEFINITION'],
                  'oidc.circleci.com/vcs-origin': 'github.com/KofTwentyTwo/carl', 'oidc.circleci.com/vcs-ref': 'refs/heads/develop',
                  'oidc.circleci.com/workflow-id': 'workflow', 'oidc.circleci.com/ssh-rerun': False}
        self.assertEqual((policy['ISSUER'], policy['IDENTITY']), policy['validate_claims'](claims, environment))
        for key in claims:
            bad = copy.deepcopy(claims)
            bad[key] = True if key.endswith('ssh-rerun') else 'different'
            with self.assertRaises(ValueError, msg=key):
                policy['validate_claims'](bad, environment)
        claims['oidc.circleci.com/vcs-ref'] = 'refs/tags/v0.1.0-rc.1'
        environment['CIRCLE_TAG'] = 'v0.1.0-rc.1'
        policy['validate_claims'](claims, environment)

    def test_snapshot_publication_can_never_substitute_for_stable_baseline(self):
        policy = runpy.run_path(str(ROOT / 'scripts/ci/foundation.py'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config/ci').mkdir(parents=True)
            (root / 'config/release').mkdir(parents=True)
            (root / 'config/ci/foundation.json').write_text('{}')
            self.assertEqual(root / 'config/ci/foundation.json', policy['baseline_path'](root, 'snapshot'))
            for channel in ['rc', 'stable']:
                self.assertEqual(root / 'config/release/foundation.json', policy['baseline_path'](root, channel))
            version = '0.5.0-SNAPSHOT'
            (root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><version>' + version + '</version></parent></project>')
            artifacts = {}
            immutable_paths = {}
            for module, suffixes in {'core': ['pom', 'jar', 'tests.jar'], 'ui': ['pom', 'jar'], 'qqq': ['pom', 'jar', 'tests.jar'], 'parent': ['pom']}.items():
                artifact = 'kof22-agent-' + module
                for suffix in suffixes:
                    name = 'com/kof22/' + artifact + '/' + version + '/' + artifact + '-' + version + ('-tests.jar' if suffix == 'tests.jar' else '.' + suffix)
                    file = root / 'm2' / name
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(b'SYNTHETIC POLICY TEST ONLY')
                    artifacts[name] = hashlib.sha256(file.read_bytes()).hexdigest()
                    actual = str(Path(name).parent / Path(name).name.replace('-SNAPSHOT', '-20261002.072846-7'))
                    immutable_paths[name] = actual
                    (root / 'm2' / actual).write_bytes(file.read_bytes())
            baseline = {'version': version, 'qualificationRun': 123, 'evidenceZipSha256': 'a' * 64, 'artifacts': artifacts,
                        'qualificationScope': 'DEVELOPMENT_ONLY', 'channel': 'snapshot', 'immutableArtifactPaths': immutable_paths}
            (root / 'config/ci/foundation.json').write_text(json.dumps(baseline))
            result = policy['qualify'](root, root / 'm2', 'snapshot')
            self.assertEqual('config/ci/foundation.json', result['baselinePath'])
            self.assertEqual({immutable_paths[k]: v for k, v in artifacts.items()}, result['resolvedImmutableArtifacts'])
            first = next(iter(immutable_paths))
            immutable_file = root / 'm2' / immutable_paths[first]
            original = immutable_file.read_bytes()
            immutable_file.write_bytes(b'DIFFERENT TIMESTAMPED PUBLICATION')
            with self.assertRaises(ValueError):
                policy['qualify'](root, root / 'm2', 'snapshot')
            immutable_file.write_bytes(original)
            published_path = immutable_paths[first]
            immutable_paths[first] = first
            (root / 'config/ci/foundation.json').write_text(json.dumps(baseline))
            with self.assertRaises(ValueError):
                policy['qualify'](root, root / 'm2', 'snapshot')
            immutable_paths[first] = published_path
            (root / 'config/ci/foundation.json').write_text(json.dumps(baseline))
            for channel in ['rc', 'stable']:
                (root / 'config/release/foundation.json').write_text(json.dumps(baseline))
                with self.assertRaises(ValueError):
                    policy['qualify'](root, root / 'm2', channel)
            removed = artifacts.pop(next(iter(artifacts)))
            (root / 'config/ci/foundation.json').write_text(json.dumps(baseline))
            with self.assertRaises(ValueError):
                policy['qualify'](root, root / 'm2', 'snapshot')
            self.assertTrue(removed)


if __name__ == '__main__':
    unittest.main()
