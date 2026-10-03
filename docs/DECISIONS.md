# Decisions

## October 1 — local QA, model access and visual direction

The owner defers Authentik and calendar/reminder integration while requesting a functioning local QA application. A dedicated password-protected localhost login is authorized. Actual local OIDC authentication uses a separate identity database and verified subject; it does not impersonate the import actor or qualify the future internet deployment. The owner permits Anthropic to process the financial records needed to answer questions using the existing personal credential. Household report/calendar display uses America/Chicago. All imported accounts are USD; unknown ownership, economic account identity and bucket relationships remain unresolved.

The owner selects the Carl local sign-in design for the main application: charcoal surfaces, a subdued geometric background, white text and blue accents. Apply it through native QQQ/Next theme extension points and matching Carl dashboard/chat styling while preserving readable financial tables and visible keyboard focus. Track implementation in Carl issue27. This is a consumer branding decision, not a new foundation-wide appearance requirement.

## October1 — current real-data and real-service instructions

The owner requires actual functional application flows and authorizes supplied real Monarch exports as private local test/application data. Do not commit the files, derived private records, account assessments or confirmed private facts. Ask for missing facts and retain confirmed answers with provenance in the protected private dataset. All exported accounts are confirmed USD. Source account identities and parent/bucket relationships require evidence; do not invent or silently merge them. These instructions supersede earlier synthetic-only local data guidance. Existing prohibitions on external financial/vendor mutations and public disclosure remain.

The fixture-backed preview is stopped. Historical fixture checks are controlled evidence only, not live product completion. Current delivery decisions remain in SPEC-cicd-gitops.md; real implementation and rollout qualification are unfinished.

| Decision | Current outcome |
| --- | --- |
| D-01 | Confirmed public KofTwentyTwo/carl; display Carl AI. Identifiers carl-ai and com.kof22.carlai. Immutable release baseline0.4.1 is independently remotely qualified at b23f89a44647ddc8fd0f3f0894b159ae823638b7/run36703216841. On October 1 the development parent 0.5.0-SNAPSHOT passed the final 408-test/113-browser RC8 qualification; earlier gates retain their historical identities. Protected foundation PR17/main1464b10 is independently published and qualified in run36878751071; see [signed RC8 development evidence](evidence/2026-10-01-foundation-pr17-rc8-publication.json). Hosted private-package credentials and a matching immutable release remain separate holds. |
| D-02 | Private conversations by default, explicitly shared family chats. Actual family membership, verified principal mapping, record/field grants and report audiences unresolved; synthetic fixtures only. |
| D-03 | Implement manual entry and documented CSV import with synthetic records. Real bill sources/documents unresolved. |
| D-04 | Superseded: owner now selects shared Synology CalDAV calendar/reminders with standing read/write plan-maintenance authority. Actual endpoint/version/collections/credentials and live qualification pending; see D-22 and CALENDAR-REMINDERS.md. |
| D-05 | Manual/supplied vendor correspondence for baseline; no mailbox connection authorized. |
| D-06 | Native QQQ administration and controlled conversation invocation; secure family/iPhone API is released in foundation 0.4.0 and integrated into Carl with private/default and explicit shared scope. Controlled HTTP qualification passes; live identity/client deployment remains separate. No native mobile app in this release. |
| D-07 | Explicit synthetic test zone America/Chicago and separate USD/EUR totals; these are not production household settings. |
| D-08 | Foundation Anthropic adapter selected for development. Owner authorizes the existing personal1Password CLAUDE_API token for synthetic preview conversations; CLI access is blocked by dismissed authorization prompts. Real family data handling, budget and retention decisions remain unresolved. |
| D-09 | Local/package qualification only. No production hosting, identity provider, operator or recovery target selected. |
| D-10 | Scheduled generation/delivery excluded until cadence/audience/channel selected. On-demand reports required. |

The requirements baseline is preserved verbatim. This log records subsequent owner decisions without editing its historical text. Public repository creation and implementation were explicitly requested; neither authorizes real family data, live provider calls, or deployment.

## September 29 financial-planning scope clarification

- D-11: Owner made household financial understanding/planning a central Carl responsibility: debts, assets, cash flow, budgets, freeing cash, reducing debt, paying off credit cards, rental houses, taxes and structure. Include it in the current build. The original section 2 debt-optimization exclusion is superseded by [the financial addendum](FINANCIAL-PLANNING-REQUIREMENTS.md); the preserved baseline remains unchanged.
- D-12: Owner confirmed all relevant locations as Randolph County / Chester, Illinois, United States. This establishes jurisdiction context only, not tax year, filing status, legal ownership, entity elections, rates or other personal financial facts.
- The existing no-external-action boundary remains: analysis/plans/drafts, no payments, filings, transfers, applications or commitments. Real-data/provider access remains separately unresolved.

## Initial transaction source

D-13: Owner can manually download all transactions from Monarch and authorized local inspection of `~/Downloads/Transactions_*` examples containing real data. Implement this export as the initial transaction ingestion route; see [Monarch import contract](MONARCH-IMPORT.md). Preserve opaque transaction IDs and scope deduplication across overlapping files. Currency mapping remains unresolved because the export has no currency column. This local inspection authorization does not authorize real-data publication, live account access or model-provider uploads.

D-14: Owner supplied manual Monarch balance-history and updated transaction exports and authorized local inspection. Support `Date,Balance,Account` balance imports alongside the transaction format. Conflicting balances under a source-label/date require explicit reconciliation; CSV order never resolves them. Real account identity/type/currency mappings remain to be confirmed.

D-15: Owner requires an easy repeated UI workflow for the two Monarch Settings exports. Native QQQ `Import from Monarch` must upload, preview/reconcile, apply reviewed changes and retain history with saved mappings. No command-line-only completion. FIN-13 and MON-11 through MON-15 are current scope.

D-16: Owner requires complete debt what-if strategies (new loans, refinancing, 0%/other transfers, snowball) and execution support. Internal planning/checklists/progress reconciliation are in scope; external submission/financial action boundary awaits the focused owner decision. D-17: Owner requires practical family-client purchase guidance: supported budget and payment-method recommendation, exemplified by kitchen tables/chairs. Added FIN-14 through FIN-16 and FAT-11 through FAT-17.

D-18: Owner clarified interactive planning and human execution: Carl documents plans/tasks, produces reports/PDFs/calendar entries, records updates and revises plans together with the family. This resolves financial execution as human-led; no financial application/payment/transfer action is introduced. D-19: The earlier calendar-output question is resolved and superseded by D-22: writable shared Synology calendar/reminders. D-20: Expenses including power, gas and maintenance are explicit core inputs. D-21: Credit availability must not establish purchase affordability; the agent must explain debt/cash-flow consequences and can recommend delaying/saving without judgment. Public fixtures contain no personal financial narrative.


D-22: Owner confirms shared calendar and reminders hosted on Synology, with read/write access and authority for Carl to update them as needed. This supersedes Apple iCloud and calendar-read-only requirements only for the designated planning workspace. No repeated per-entry approval is required; finance/vendor actions remain outside scope. Actual provider/client compatibility and secret configuration need live qualification.

D-23: Owner's primary mission order is debt reduction, improved rental/tax structure, then investment planning. Use explicit agreed goals and stage milestones, without inventing a debt-free prerequisite or investment risk profile. FIN-20 / FAT-23 capture the investment-planning boundary; no trades or money movement.


## September30 — running preview and native conversation

Owner confirms the currently served preview is insufficient: it lacks the requested grouped navigation/current frontend and has no visible way to interact with Carl. Add native “Talk to Carl” using Carl's existing conversational workflow and authoritative state, with private-by-default conversations and explicit authorized family sharing. Use the latest qualified Next UI RC and the separately approved exact QQQ4.1 development snapshot while no4.1RC is published. The replacement preview at `localhost:64503` (closed; not live) used qualified Next UI1.0.0-RC.7 with grouped branded navigation, dashboards, Quick Search/ESB and native Talk. Its five running Talk checks pass offline. The existing personal1Password token is selected and authorized for synthetic evaluation, but CLI access remains blocked; an offline receipt is not a live model answer. Preserve financial/vendor authority boundaries and the separate standing Synology workspace authority.


## October1 — current synthetic preview

The frozen008af67 preview is `localhost:51051` (closed; not live), with the previous64503 preview retained. Native Talk passes five offline/privacy checks; six current navigation/record checks and four selected dashboard-fact checks pass, alongside two acknowledged ESB events with no dead letters. The model remains disconnected pending access to the already-selected personal token. Real household/model/Synology policies and account identities are not inferred. See [running evidence](evidence/2026-10-01-current-running-preview.json).


## Repository and issue ownership — October 1

The owner confirms that the agent foundation is reusable infrastructure for all agents, while Carl is an independent consumer application. Carl application features, domain data/imports, branding, financial workflows and app-specific defects belong in the `KofTwentyTwo/carl` issue tracker. Foundation issues contain only reusable agent infrastructure. The owner explicitly does not require GitHub Projects. A consumer reproducing a shared runtime defect may supply evidence, but its business implementation does not move upstream.
