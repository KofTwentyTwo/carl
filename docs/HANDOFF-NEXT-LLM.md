# Carl AI takeover handoff — October 2, 2026

**STOPPED at the owner's request.** Implementation is paused for independent review by another LLM. Preserve all local changes and isolated candidates. Do not treat this handoff, earlier checked checklists, controlled fixtures or a running URL as proof that Carl is complete. The owner wants a real clerk, accountant and advisor that can compose reusable tools to inspect, correct and plan over the same authoritative records shown in QQQ.

**Later on October 2:** the uncommitted work described below is preserved as commit `9bd782e`
(`feature/codex-wip-2026-10-02`). A local `mvn verify` of `9bd782e` against foundation main `d0a11bf`
passes 566 tests; this supersedes the failing 566-test run recorded below. The owner chat still fails
([Carl#34](https://github.com/KofTwentyTwo/carl/issues/34)); its fixes are in local branches pending push.
Paths to private files are described, not given; the owner holds them.

## Start here

Read AGENTS.md, REQUIREMENTS.md, FINANCIAL-PLANNING-REQUIREMENTS.md, DECISIONS.md, SESSION-STATE.md, TODO.md and ACCEPTANCE.md. Carl is the application; the foundation supplies reusable infrastructure. Consumer checkout is the owner's local Carl clone, branch `feature/cicd-liquibase-gitops`, HEAD `5414d7acde7f5d7556dc52544ee0e4bd2ab3bc52`, with substantial uncommitted work. Foundation's original checkout is still an older branch; do not mistake it for published main. Do not reset, stash, remove worktrees or commit the failing candidate merely to make the tree look clean. Source and evidence were preserved in the private owner-held bundle (not for Git, CI or publication).

## Running application versus latest source

- Actual QA URL: https://localhost:8443. Anonymous metadata returns401. Actual local owner OIDC, PostgreSQL, Anthropic, OpenSearch and Artemis are configured. Private credentials and records are in the owner's private local data directory; never print or publish them. Authentik and live calendar/reminder work are owner-deferred.
- Runtime is the sealed 563-test distribution in the owner's private runtime directory; app SHA256 `adf707224e4ae0c84511bc21c787aa9d3e2b8f5f8327cfd872fa3dc89d9aad9b`. Database is **Flyway domainV62/coreV8**, not Liquibase. The candidate fixes below are not running.
- Real imports, original-document custody, selected/all-authorized transaction CSV exports and selected browser flows have scoped evidence. Metadata enumeration of150 processes is not execution acceptance of150 processes. Missing account identities/bucket relationships, current debt terms, budget/reserve and rental evidence block whole financial priming. Preserve uncertainty and ask for the missing facts.
- Owner's ordinary monthly-spending chat is **FAIL**: UNKNOWN, no saved answer/artifact, nine successful authorized reads and recorded provider usage. The complex document-to-plan followup also remains UNKNOWN. Preserve original request identities; do not automatically replay submissions.

## Current source and exact checks

- Bounded readiness retrieval is integrated in ConversationReads, CarlConversation and CarlReadinessConversationTest. Actual controlled SDK/PostgreSQL RED exhausted ten iterations; focused GREEN uses two reads/three model turns,50 focused tests pass. This does not qualify normal owner chat.
- Latest full `spotless:apply clean verify` against matching published foundation51 artifacts: **566 tests,1 failure,9 errors,0 skips, BUILD FAILURE**. Only CarlConversationHttpTest failed: one503/unavailable assertion and nine migration-inspection errors. Full log is preserved. Subsequent isolated `-Dtest=CarlConversationHttpTest test` passes29/29. Cross-suite/static QQQ connection state or container lifecycle is a hypothesis, not established cause. No full-green rerun or new package followed.
- Four development CI files are integrated: canonical nine foundation artifact hashes and immutable timestamped paths pinned to published main `d0a11bf387b2a4a6b25a5feb5c34d17d8aa2aad3`, hosted run36974782652.31 Python tests,22 Node checks, shell/CircleCI validation pass. Stable release baseline0.4.1 is unchanged. No hosted Carl green image/GitOps rollout is claimed.
- Full failed log: `evidence/carl-readiness-retrieval-published-foundation-full-verify.log` in the private owner-held bundle. Isolated PASS: `evidence/carl-published-foundation-http-failure-diagnostic.log`. A later focused run overwrote the HTTP XML; retain the full failed log as independent evidence.

## Isolated candidates — review before integration

1. **Spending aggregate, NOT integrated:** `evidence/carl-spending-read-final-bundle/manifest.json` and narrow.patch. Four guarded files;6 new tests;50 focused tests/style pass. Actual controlled SDK RED paginates into context exhaustion; GREEN uses one audited read/two turns. Aggregates all currently permitted rows deterministically, preserves currencies, uncertain classifications/credits and confirmed transfer evidence. No invented ownership or higher budgets. Verify baseline hashes before applying; a full suite would contain572 tests if this is the only added change.
2. **Dock Check Status, NOT integrated/published:** `evidence/chat-status/HANDOFF.md` and handoff-file-guards.json. Component RED reproduces missing acknowledgement and stale response overwrite; GREEN15/15. Full frontend1985 tests/types/lint/audit/export pass. Actual disposable PostgreSQL/native baseline browser confirms exact saved-request GET returns200 UNKNOWN but lacks acknowledgement. Candidate packaged browser GREEN and complete foundation gates are unrun. **Security followup:** superseded same-owner/current-generation401/403 must still clear private state; add that regression before adoption. Candidate UI SHA `08e8170f1414bdad4dc44277bf1b2d9c9664a0b9704f8828120d6113fb47cc87` is unpublished.
3. **Generic governed local actions, design research only:** see foundation#60. Current READ-only Carl bindings and Slack-dependent shared WRITE approval cannot provide the required single-owner local clerk behavior. Do not bypass ToolGate, manufacture Slack, execute raw SQL, hide writes as reads or infer access from an owner label. Reuse narrow caller-aware create/update/link/classify/plan services with current permissions, revisions, idempotency, attribution and truthful unknown outcomes. Separate explicit local standing authority/native review from external financial actions.
4. **Published-foundation matching package:** `evidence/carl-foundation51-matching-qualification-bundle` and saved package archive. Independently scoped563-test frozen source, matching nine artifact identities, actual PostgreSQL/Liquibase transition checks and273-package zero HIGH/CRITICAL scan pass. This package predates new readiness/spending/status fixes and was never adopted privately. Archive-recovered signed published bytes do not prove this machine's fresh authenticated Maven access; that returned403.
5. **Upstream date defaults/Next RC:** saved current-clean-handoff.json and guarded three-file patch. PR32 and draft RC metadata PR33 remain unmerged/unpublished; hosted matrices37003996901 and37004311277 were in_progress at pause. Recheck actual state and exact heads. QQQ4.1.0-RC.1 and Next1.0.0-RC.11 are published; RC12 candidate is not a release. Another reviewer is active upstream; preserve their changes.

## Migration and delivery holds

Do not transition the private DB until a matching current candidate passes all required gates and backups/restore/roles are checked. `DatabaseBootstrap migrate` uses db.username/password literally; the explicit migrate configuration must use the existing migrator identity. `provision` credential substitution does not apply to migrate. Admin-owned Liquibase history breaks expected role grants. Preserve canonical Flyway history and versioned domain/core migration evidence.

CircleCI + Munitor, GHCR, private carl-CD, existing app-of-apps and automatic dev/RC/prod promotion are owner-selected. Existing PostgreSQL with isolated databases/roles, galaxy.direct URLs and named approved identities are required. No qualified Carl image set or Argo deployment exists. Authentik is deferred; approved test-user names and production policy are outstanding. OpenSearch's current image has82 HIGH/CRITICAL findings and remains a deployment hold. Do not weaken release/security gates.

## GitHub ownership and status

- Carl#33: [clerk/accountant/advisor parent](https://github.com/KofTwentyTwo/carl/issues/33).
- Carl#34: [normal native chat and status](https://github.com/KofTwentyTwo/carl/issues/34).
- Carl#28–31 have detailed current comments for retrieval, actions, bulk classification and documents. Carl#3/#5/#6 retain data/Liquibase/CI holds.
- Foundation#60: [generic standalone local action authority](https://github.com/KofTwentyTwo/kof22-agent-foundation/issues/60).
- Foundation#61: [dock status refresh/acknowledgement](https://github.com/KofTwentyTwo/kof22-agent-foundation/issues/61).
- Keep consumer business work in Carl; no GitHub Project is needed. Do not close issues from isolated tests or mocks. Public issues omit private financial identities and records.

## Suggested next review, after owner resumes

Independently inspect implementation and these claims. Diagnose the full-suite failure, review/integrate the guarded spending read, qualify shared status fixes including revocation, then test an actual owner UI → real model → deterministic facts → displayed and persisted result. Design and qualify generic local authority upstream before enabling conversational corrections. An owner-confirmed rental-mortgage relationship must be handled through reusable capabilities; do not invent ownership, current terms or principal/interest/escrow splits. Reconcile original sources and current authoritative records, then prove the same corrected records through QQQ. Complete hosted CI/Liquibase/packaging separately. No additional implementation, inference requests, migration, merge or release was performed during this pause cleanup.
