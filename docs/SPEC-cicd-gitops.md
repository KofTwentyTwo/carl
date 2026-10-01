# Carl CI/CD, Liquibase and GitOps design

October 1, 2026 — owner decisions incorporated; design review pending.

Carl remains one deployable agent application, including its domain model,
reasoning, native QQQ interface and family API. CircleCI/Munitor will verify its
source and publish an immutable image set to GHCR. A private
`KofTwentyTwo/carl-CD` repository will hold Kustomize environments registered in
the existing `k8s-app-of-apps` configuration. Argo CD will reconcile qualified
image digests automatically for development, RC/staging and production. The
first rollout uses only the fictional household and approved Authentik testers.

| Environment | Namespace on k8s-prod | Authenticated hostname |
| --- | --- | --- |
| Development | carl-dev | carl-dev.galaxy.direct |
| RC/staging | carl-staging | carl-staging.galaxy.direct |
| Production | carl-prod | carl.galaxy.direct |

## Migration and image boundaries

The foundation will supply shared Liquibase execution, history validation and a
tested transition from the retained Flyway core/domain histories. Existing
consumers retain a supported legacy path. Existing SQL migrations remain
immutable; adoption verifies the actual applied history before recording
equivalent Liquibase changesets. Fresh databases and retained databases must
arrive at the same validated schema and data. New DDL and versioned data changes
use append-only changesets and explicit preconditions. Runtime credentials only
validate; a separate migration role performs schema/data upgrades.

Publish the Carl application and one-shot migrator images from the same tested
source/dependency closure. Use existing backup infrastructure where it meets
Carl's isolation/recovery requirements; add a dedicated backup image only where
that infrastructure requires it. The migration job and backup mechanism use
isolated Carl databases/credentials and protected storage. Argo CD ordering must
stop application rollout if migration or required backup fails. Updates of a
retained database are incremental; dataset initialization is separately bounded
and restricted to the explicitly configured synthetic test deployment.

## CI and automatic promotion

Reuse the existing CircleCI contexts: `ghcr` (`GHCR_TOKEN`, `GHCR_USERNAME`),
`github` (`GITHUB_TOKEN`) and `security` (`NVD_API_KEY`). Their existence and
variable names were verified through the authenticated API; values were not
returned. Prove actual private Maven resolution and GHCR/GitOps permissions in
the new pipeline. Preserve the full Java21/Maven lifecycle, foundation100% core
and adapter coverage, Carl80% line/60% branch floors, copyright/style/convergence
checks, real PostgreSQL tests, browser/privacy workflows and scans. Munitor's
ordinary build command alone is insufficient: its inspected build script skips
tests. Reuse supported pipeline extension points or qualify a minimal upstream
Munitor extension to carry the complete gates.

Build once, scan and attest each image, then promote the tested digests and their
matching migration image. Validate every GitOps overlay and publish release
evidence linking source, dependencies, images and test results. Automatic
promotion follows successful gates; immutable RC/stable dependency and release
checks remain part of those gates. Current snapshot dependencies do not establish
an RC/stable release. Replace duplicate GitHub publishing only after the
CircleCI path is qualified. Next UI RC9 was published during this work; qualify
it through the existing foundation patch/packaging process before adopting it.

## Services, identity and rollout qualification

Reuse the cluster's existing PostgreSQL/operator and Authentik services with
dedicated Carl databases, migrator/runtime/reader roles and a dedicated identity
client. Resolve their actual endpoints and secret references from the existing
configuration and cluster. Private GitOps contains deployment-specific settings;
public source contains configuration contracts and synthetic fixtures. Access is
denied unless a verified principal belongs to the explicit approved tester
mapping. The owner will supply tester usernames; record that mapping privately.

Before reporting cluster readiness, prove authenticated ingress/logout, actual
QQQ records and domain workflows, health/metrics, isolated database permissions,
schema/data upgrade and interruption/retry behavior, persistent state, and
backup/restore with the agreed recovery procedure. The configured cluster API
and recorded cluster VIP are currently unreachable from this Mac; restore an
approved management route before live service inspection or rollout acceptance.
Model connectivity remains a separate qualification using the already selected
credential and synthetic data. The localhost preview's Alice/Bob fixture login
stays limited to its local test transport.

## Delivery sequence and evidence

1. Qualify shared Liquibase support and retained/fresh database transition in the foundation.
2. Add Carl changelogs, migrator/seed boundaries and requirement-linked PostgreSQL tests.
3. Qualify the complete Munitor/CircleCI build, dependency resolution, image set and provenance.
4. Create private carl-CD, validate overlays/Argo registration and test promotion/failure handling.
5. Qualify the authorized synthetic rollout, identity, shared services, persistence and recovery.

Track source merge, pipeline verification, image publication and cluster
qualification separately. Acceptance requires real gate and deployed behavior
evidence; fixture-only or rendered-manifest checks cannot establish live rollout.
