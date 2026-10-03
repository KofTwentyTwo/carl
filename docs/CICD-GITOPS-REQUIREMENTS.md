# CI/CD and GitOps delivery requirements

Status: delivery decisions confirmed; tester names pending, October 1, 2026. The owner requests a complete
pipeline patterned after the Kof22 website: tested application and migration
images, Liquibase support for schema and versioned data changes, and Argo CD
delivery to the local Kubernetes infrastructure. Prepare a concrete design and
resolve the decisions below before implementation or cluster rollout.

The inspected website checkouts use CircleCI/Munitor, GHCR, supplemental migration
and backup images, a separate Website-CD Kustomize repository, and the existing
k8s-app-of-apps environment applications. Reference revisions are Website-Backend
`fc197e4` and Website-CD `1615724`; this is local inspection provenance. Carl
currently uses GitHub Actions, one application image, and separate Flyway core
and domain histories. Shared migration/runtime validation belongs in the
foundation; Carl owns its domain changes and application deployment settings.

| Decision | Confirmed choice or remaining question |
| --- | --- |
| CI engine | CircleCI/Munitor, matching the website |
| GitOps ownership | Private KofTwentyTwo/carl-CD, GHCR and existing app-of-apps |
| Liquibase boundary | Liquibase for core and domain, with a tested retained-data/history transition |
| Promotion | Automatic promotion of dev, RC/staging and production after their gates pass |
| Destinations | k8s-prod; carl-dev/carl-staging/carl-prod; carl-dev.galaxy.direct, carl-staging.galaxy.direct and carl.galaxy.direct |
| PostgreSQL | Existing PostgreSQL service; isolated databases and migrator/runtime/reader roles; discover service identity |
| Authentication | Existing Authentik with dedicated Carl client; named family/test accounts; username list pending |
| Credentials | Reuse website contexts ghcr/github/security; authenticated API confirms their variable names; verify package/publication permissions in CI |
| First rollout | Initial synthetic test deployment; no real family records or account connections |

Acceptance must prove fresh dependency resolution, the existing quality gates,
actual PostgreSQL schema/data upgrades and repeated application, preservation of
retained records and access controls, and failure handling. Publish a traceable
image set from the tested source, with immutable digests, scans and provenance.
Keep migration credentials separate from the validating runtime. Qualify GitOps
rendering, migration-before-application ordering, health, authenticated ingress,
operational visibility and recovery/promotion procedures for the selected scope.
Recorded source merges, published images and successful cluster rollout are
separate outcomes. The existing localhost synthetic preview remains a development
fixture with its model disconnected.

The [concrete delivery design](SPEC-cicd-gitops.md) incorporates these choices.
