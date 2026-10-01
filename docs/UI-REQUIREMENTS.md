# Carl AI navigation, branding and financial dashboards

Confirmed owner direction, September 30, 2026. This supplements the original requirements without changing financial execution authority. Carl remains one agent application with native QQQ administration, authoritative services and PostgreSQL state.

## Product experience

Carl AI is the visible product name. Use Carl's identity in the header, browser titles, sign-in, navigation, empty/error states and report/PDF headings. Keep third-party attribution in the license/about information. Use the native Next UI sample's app grouping, sections, icons and contextual actions; avoid one long list of tables and processes. Stable record/process identities and existing direct URLs remain usable.

The main dashboards follow the financial questions represented in Monarch: Cash Flow, Balance Sheet, Financial Plan and Progress, and an income/expense Sankey. They display Carl's actual authorized records and deterministic calculations. A decorative fixture-only dashboard does not satisfy this scope.

## Navigation and action placement

| Group | Main views | Actions belong here |
| --- | --- | --- |
| Overview | Financial summary, data freshness and next plan steps | Open the relevant dashboard or task |
| Money | Cash Flow, Balance Sheet, accounts/debts, transactions, budgets, bills and recurring expenses | Review/classify transactions, reconcile imports, correct local records |
| Plans | Financial Plan and Progress, goals, scenarios, assigned steps and check-ins | Compare what-ifs, select a draft plan, agree explicitly, assign, report progress, revise |
| Properties and Tax | Rental properties/units, rent and operating results, tax evidence | Allocate/review rental records, stress-test, assemble evidence and conditional alternatives |
| Calendar and Reminders | Agenda, conflicts, plan entries and reminder observations | Refresh, maintain the designated shared workspace, review reported completion |
| Vendors | Directory, work items and draft history | Maintain records and prepare/review/copy drafts |
| Documents and Data | Reports/PDFs, source evidence, Monarch import preview/outcomes | Upload the two Monarch files, review/apply reconciliation, retrieve protected outputs |
| Settings | Permitted household preferences, membership and connection health | Manage authorized local settings; operational details remain operator-oriented |

Related actions appear beside the applicable view or record and in its app-home section. No new global list of every operation. Distinguish drafts, human agreement, reported completion, observed evidence and external calendar synchronization. Never label a financial action paid, transferred or completed merely because a reminder checkbox changed.

## Dashboard rules

Cash Flow uses a selected reporting interval, household display zone and explicit currency. Show classified income, ordinary expenses, debt principal/interest, capital movements and net cash change with clear definitions. Separate borrowing and asset-sale proceeds from ordinary income. Exclude explicitly matched internal transfer pairs from income/expense totals. Refunds retain their direction and source classification. Unclassified rows and partial source coverage are visible exceptions; amount sign alone is not classification.

Balance Sheet uses an explicit as-of date, permitted account/property selections and source freshness. Assets, liabilities and net worth retain exact decimals and currency. Avoid counting a rental property's value twice through its linked asset account, or its debt twice through linked liabilities. Missing/conflicting observations are gaps, not zero. Do not equate net worth, available credit or a stale balance with spendable cash.

Financial Plan and Progress shows the selected plan/version, explicit goals, dated assumptions, assigned steps and next actions. Display projected outcomes separately from observed account/transaction evidence and human reports. A revised source or goal marks dependent results stale and offers an explicit regeneration/rebase path. Show debt principal, interest and fees independently of uncertain investment returns; do not present a guaranteed net-return optimizer.

The Sankey reconciles exact authorized amounts over the selected interval, uses a bounded number of category nodes and retains an accessible numeric table. Transfers, refunds, debt service, capital movement, surplus/deficit and unclassified coverage must remain explainable. Negative values must not be silently clamped to zero; they require a truthful direction or explicit exception. The chart is another view of the same figures, not a second calculation implementation. Use native supported widget/HTML rendering and sanitization; no separate frontend service or unsanitized imported markup.

Every dashboard states interval/as-of, currency, visible scope, generation time, freshness and missing sources. Partial access is labeled as accessible records, never the whole household. A missing or failing source produces an actionable partial/empty state. Drill-downs, widget requests, search, saved outputs and exports recheck current record/field access. Shared scope is an explicit intersection, never a union of private permissions.

## Foundation and QBits

Qualify exact published QQQ 4.1 development snapshot artifacts because a 4.1 RC is not yet published; pair them with the latest qualified Next UI RC. September30 recheck identifies1.0.0-RC.5; its qualification is pending. Record source revisions, per-module timestamped versions, hashes and actual resolution/build evidence. Neither a snapshot nor a local build is labeled a published RC/stable release.

Quick Search must use the actual QBit with a bounded current-permission scope. Indexed private labels, snippets, highlights and global counts must not escape authorization. Rehydrate permitted results from authoritative Carl services and recheck access on opening a record. ESB must use its actual supported module/runtime lifecycle and controlled broker tests. Caller identity, permissions and destinations do not come from message payloads. Domain workflows retain idempotency and the financial/vendor action boundary.

## Required evidence

- UX-01: native grouped navigation places every visible domain record/action predictably; direct URLs and permissions remain valid.
- UX-02: Carl branding is visible before/after login, on browser titles/icons and in documents; no QQQ Admin product title remains.
- UX-03: Cash Flow deterministic fixtures cover exact currency totals, transfers, refunds, borrowing, principal/interest/capital and unclassified coverage.
- UX-04: Balance Sheet fixtures cover explicit as-of selections, property/account double counting, liabilities, conflicts, freshness and missing values.
- UX-05: plan dashboard fixtures distinguish projected, reported and observed outcomes and current/stale versions.
- UX-06: Sankey node/link totals agree with the accessible table for surplus, deficit and refund cases, without negative clamping or mixed currencies.
- UX-07: authenticated real HTTP/PostgreSQL tests deny guessed widget/record IDs, cross-member data, stale grants, search leaks and protected downloads after revocation.
- UX-08: actual packaged browser evidence checks desktop/narrow dashboards, group navigation, contextual actions, filters, empty/error states and accessible chart/table behavior.
- UX-09: actual Quick Search QBit/OpenSearch and ESB/broker fixtures prove indexing/search scope, lifecycle, retry and consumer wiring in foundation and Carl.
- UX-10: complete quality/build/security gates pass on the final integrated source; remote publication, live qualification and deployment retain separate statuses.
- UX-11: an obvious native “Talk to Carl” entry point accepts questions/follow-ups through the same conversational capabilities and persisted records as the family API. Private conversations are the default; explicit sharing fixes an authorized audience. Current identity/access is rechecked, excluded actions remain unavailable, and an unconfigured/failed model is shown truthfully. Actual native UI-to-domain-to-QQQ and access-negative evidence is required; a decorative form or API documentation alone does not pass.

Status: requirements confirmed and implemented with controlled packaged/browser evidence. Original RC7 qualification covers branded grouped desktop/narrow dashboards, default signed-in Overview, Talk and actual QBits. The current386-test build additionally has32 packaged checks and a [new running preview](evidence/2026-10-01-current-running-preview.json) with fresh navigation, selected dashboard figures, Talk privacy and two acknowledged ESB events. Live model/household acceptance and hosted delivery remain BLOCKED. The isolated slice evidence below is historical.


## Native dashboard slice evidence (2026-09-30)

Qualified in an isolated consumer copy against the exact signed foundation 0.4.1 cache and pinned QQQ 4.0.0; no original source, provider, financial execution or shared frontend change. The final `clean verify` completed with 352 tests, zero failures/errors/skips. JaCoCo covered 9782/11132 lines and 5706/8319 branches, meeting the consumer floors. This is fixture qualification, not live household acceptance.

- UX-01: actual nested native applications group Overview, Money, Plans, Properties and Tax, Calendar and Reminders, Vendors, Documents and Data, and Settings. Existing table/process IDs and `/app/<name>` routes remain. QQQ 4.0 sections contain leaf records/actions; app nesting supplies the sidebar hierarchy.
- UX-02: consumer metadata sets Carl AI title/company, SVG logo/icon and blue accent. Actual authenticated native metadata returns Carl branding. Packaged login, document title/favicon, desktop/narrow navigation and landing-route screenshots remain pending.
- UX-03/06: Cash Flow and its Sankey share one permission-filtered exact-decimal snapshot with explicit inclusive dates, currency, zone, coverage gaps, classification and transfer rules. CASH-kind movements are distinct from spending across all account kinds. Non-cash ledger movements remain in a separate exact table. Matched CASH-to-CASH transfers are excluded only when both legs are accessible; bank-to-card/loan/investment cash legs remain and allocation stays unknown. Mixed-kind fixtures reconcile cash out 350.40, cash refund 20.10, net movement -330.30, classified spending 1000.25, all-kind refunds 45.35 and net spending 954.90 USD. Refunds, capital/debt costs, unknown classification, partial pairs, surplus, deficit, zero and aggregated categories are explicit. Charts draw at most eight transaction groups per side plus the net balancing node; all amounts stay in the accessible table.
- UX-04: Balance Sheet reads an explicitly selected saved `selected-balance-sheet-v1` output, preserving as-of/age limit, property/account duplication treatment, currency separation, signed liabilities, excluded missing values, gaps and stale labels. Viewing it creates no artifact. Native source selection links to the existing account/property review process.
- UX-05: Plan and Progress reads the selected accessible current plan, its version/tasks, current explicit goal priorities, saved qualified projections, agreed expectations and selected observed-effect receipts. Human check-ins and numerical observation matches cannot establish payment, debt reduction or execution. Report-time and current plan-version matches are separate; changed source facts retain explicit regenerate/rebase guidance.
- UX-07: real synthetic PostgreSQL tests exercise private-account dependencies, exact audience intersection, cross-household denial, guessed saved IDs, revoked finance permissions and unchanged state on dashboard reads. Actual signed JWT/local JWKS native HTTP tests render real cash/Sankey facts, reject invalid filters and anonymous callers, escape imported markup, hide private-member amounts, and deny all four widgets after revocation before loading selection labels.
- UX-06/08 sanitizer support: a renderer preview was run through the actual RC4 `sanitizeHtml` implementation. Accessible SVG role/label, exact amounts, three tables and inert native links survived; script/form/control/event markup was absent. This is sanitizer execution, not packaged browser acceptance. Browser visuals and narrow-screen behavior still require review.

Implementation bounds: inclusive interval at most 366 days; at most 100000 accessible transactions, 1000 directional categories, 100 plan tasks/expectations/observations/goals; facts at most 256 KiB and rendered HTML at most 512 KiB. Required native dropdowns replace script/forms; no second frontend server or framework was introduced. Financial dashboard access rechecks verified identity, current membership and FINANCE details before selection labels and after rendering; no financial dashboard fact cache exists.

Remaining UX-08/09/10 work: packaged RC4 browser checks and default signed-in landing hook, source-integrated full gate, approved foundation/QQQ snapshot upgrade, real Quick Search/ESB QBit wiring and fixture qualification. QQQ 4.0 has no consumer home-screen/default-app metadata API; RC4 `/` redirects to generic `/app`, while these widgets are native app dashboards under `/app/carlAI`, `/app/carlOverview`, `/app/carlMoney` and `/app/carlPlanning`. No new UI/QBit live or remote-release acceptance is claimed.
