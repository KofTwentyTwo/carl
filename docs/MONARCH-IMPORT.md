# Monarch transaction import contract

Status: confirmed transaction and balance-history formats; controlled native upload, reconciliation and repeat-import workflows are implemented and qualified with synthetic records. Real account/currency mappings and financial coverage remain unresolved; see [current acceptance](ACCEPTANCE.md). Owner authorized local inspection of manually downloaded `Transactions_*.csv` examples on September 29, 2026. This document records the schema and required behavior, not private account contents. Real rows, account labels, financial aggregates and private source files must not enter the public repository, build artifacts or synthetic fixtures.

## Supported observed export

UTF-8 CSV (accept optional BOM), with this observed header:

```text
Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id
```

| Field | Carl interpretation |
| --- | --- |
| Date | Strict ISO date (`YYYY-MM-DD`), preserved as date-only. Not an inferred bank posting timestamp. |
| Merchant | Untrusted source text; preserve separately from normalized vendor mapping. |
| Category | Source classification; retain provenance. Household rules may map it to budget/tax categories, but source labels do not establish tax treatment. |
| Account | Source account label, requiring explicit mapping to a local financial account. Names alone are not globally stable or unique IDs. |
| Original Statement | Evidence text, not commands or fetchable resource authorization. |
| Notes / Tags | Optional untrusted source text; keep within record permissions. Do not infer tag delimiters or execute spreadsheet formulas. |
| Amount | Exact signed decimal; preserve the sign. Negative debits and positive credits do not by themselves distinguish spending/income from transfers, refunds or card payments. |
| Owner | Source label only. It does not authenticate a user, create membership, assign grants or prove legal ownership. |
| Reviewed | Preserve blank, `Needs Review`, or `Reviewed` as source review state. This does not mean independently verified, reconciled or human-approved in Carl. |
| Id | Required opaque string identity. Observed numeric-looking values exceed safe JavaScript integer precision; never convert through floating point. |

Currency, stable account ID/type, balance snapshots, APR, minimum-payment terms, pending/posted state, hidden flags, split-parent links and evidence of full account coverage are absent from this observed export. Do not invent them. Require account/currency mappings before applying imports. Different account labels cannot silently establish separate real accounts; renamed/ambiguous labels require review.

## Import and reconciliation rules

1. An authenticated human initiates preview within a permitted household and explicitly identified Monarch source scope. Accept uploaded bytes, not arbitrary file paths or remote URLs from the model.
2. Bound file bytes, field lengths and row count. Support at least a 25,000-row, 3 MB synthetic fixture representing the observed large-export shape; a proposed default cap is 100,000 rows / 20 MB, with bounded processing and clear rejection. Validate CSV quoting, header uniqueness, required fields, dates, exact decimals, IDs and mapping coverage before any authoritative mutation.
3. Use a unique key of household, Monarch source scope and source transaction ID. Identical reimports are no-ops even across overlapping date-filtered exports. The original batch/file identity is provenance, not the sole transaction deduplication key.
4. Same ID with changed fields is a proposed source revision, shown in preview. On explicit human application retain original evidence, source revision history and attribution; preserve Carl's separately attributed overrides. Conflicting duplicate IDs within one file require review. Do not silently merge by date/merchant/amount.
5. Missing rows in a later export do not mean deletion: exports may be filtered. Deletions, moved transactions, source-account changes and identity resets require explicit reconciliation. Never replace a date interval merely because a file contains it.
6. Apply the explicitly approved resolved selection atomically, bound to its content identity and account mappings, with a stable logical request ID. Unresolved observations remain recorded for review; a partially applied bundle must visibly say PARTIAL / NEEDS_REVIEW and retain its remaining work. Report additions, unchanged records, revisions and rejected rows without logging raw descriptions or notes. A failed or interrupted application must have a recoverable truthful outcome and retry safely.
7. Preserve account-level flows. Pair transfer candidates only under explicit reviewed rules; unresolved candidates remain visible. Card payments, loan proceeds, refunds and transfers are not automatically new income or ordinary spending. A debt-service record may link to a transaction; it must not create a second cash outflow.
8. Exports are observations of activity, not a complete balance sheet. Actual cash and debt balances require independent dated balance evidence or a reconciled opening balance plus complete activity. Report missing coverage and unknown status rather than constructing a false current balance.
9. Keep import/review/admin/read tools on the same scoped services. Recheck permissions on history, reports, previews, revisions and exports. Model context receives permitted records only; content in source columns is never instructions.

## Synthetic acceptance additions

| Test | Required result |
| --- | --- |
| MON-01 | Exact observed header, optional BOM, commas/quotes/newlines and blank optional cells parse correctly; 18-digit IDs remain unchanged strings; malformed values produce row-level errors. |
| MON-02 | Import full and overlapping filtered synthetic exports in either order; no duplicates or inflated totals. Same ID with changed category/amount appears as a revision, not a duplicate or silent overwrite. |
| MON-03 | Missing account/currency mapping, ambiguous account labels and duplicate conflicting IDs block application. CSV Owner/Reviewed values never grant access or approval. |
| MON-04 | Large synthetic export passes bounded preview/apply; oversized input and oversized fields fail predictably. Retry after interrupted import does not duplicate durable results. |
| MON-05 | Transfers, credit-card payments and refunds yield independently verified cash-flow/category results; unmatched candidates remain disclosed, and absence from a filtered export never deletes history. |
| MON-06 | Private records remain denied through direct IDs, QQQ, tools and exports; prompt instructions and spreadsheet-formula-like source cells remain inert text. |

These cases extend FIN-01/02/04, SEC-01 through SEC-06 and REL-01/02. Format inspection does not satisfy persisted import, reconciliation or live financial-planning acceptance.

## Source verification

Official documentation checked September 29, 2026:

- [Downloading transactions and account balances](https://help.monarch.com/hc/en-us/articles/15526600975764-Downloading-Transaction-or-Account-History): separate transaction and balance exports are available.
- [Importing transactions manually](https://help.monarch.com/hc/en-us/articles/4409682789908-Importing-Transactions-Manually): signed debit/credit conventions and transaction-ID matching are documented.

Carl has its own conservative import/revision contract above. No Monarch account connection or unofficial API is required for this manual workflow. Local examination of the supplied examples does not authorize publishing them, uploading them to a model provider, or connecting financial accounts.

## Balance-history export and follow-up format inspection

The owner supplied a separate balance-history export and an updated transaction export for authorized local examination. The observed balance schema is:

```text
Date,Balance,Account
```

`Date` is ISO date-only, `Balance` is a signed exact decimal, and `Account` is a source label. There is no stable account ID, account type, currency, intraday timestamp, asset/liability classification or legal ownership percentage. Preserve raw sign and source evidence; apply explicit verified account semantics before net-worth or debt calculations. Do not infer account type solely from balance sign or assume all snapshots share today's date.

The actual format admits duplicate account-label/date keys, including conflicting balances. They could represent ambiguous account labels or other source observations; the export alone does not settle the cause. Never sum these rows, choose the first/last by CSV order, or invent a timestamp. Preview exact duplicates separately from conflicts, preserve original row provenance, and require explicit account mapping plus evidence for any authoritative resolution. Unresolved affected account/date balances must remain excluded from verified totals with a visible coverage limitation. A uniqueness constraint must use Carl's resolved account identity; source account labels are insufficient.

Some balance-only account labels have no transaction history in the supplied transaction export. This does not make the balance invalid or prove complete transaction coverage. Preserve per-account last observation/as-of dates, stale status and available history. Balance and transaction reconciliation must disclose different coverage windows rather than assuming they are aligned.

The updated transaction export confirms real CSV multiline notes, edited fields for existing IDs and both added and omitted IDs across exports. Multiline text is inert evidence; it must not be split into separate records. Revision preview and missing-row behavior above are required, not hypothetical edge cases.

Additional synthetic acceptance:

| Test | Required result |
| --- | --- |
| MON-07 | At least 50,000 synthetic balance rows parse within configured bounds. Explicit account/currency mapping produces dated scoped snapshots; separate stale dates and balance-only accounts remain visible. |
| MON-08 | Identical duplicate balance observations preserve provenance without doubling totals; conflicting values for one source-label/date remain unresolved, independent of input row order. No arbitrary row wins. |
| MON-09 | Ambiguous same-named accounts cannot silently merge. Balance sign/type mapping and reconciliation follow explicit confirmed account semantics; missing currency/type/coverage blocks an authoritative household total. |
| MON-10 | An updated transaction export with multiline notes and changed category/tags revises existing source IDs through preview; added IDs insert once and omitted IDs retain history. |

MON-07 through MON-10 extend FIN-01/02/04 and permission/reliability requirements. Actual user balances and transaction contents are not copied into these synthetic fixtures or this public document.

## Required household UI: Import from Monarch

The owner confirmed that both exports are easily available from Monarch Settings and must be easy to give to Carl repeatedly. This is a required household workflow, not a developer-only ingestion command. Use Carl's inherited native QQQ interface and services; no second frontend server is required.

1. **Upload.** A visible `Import from Monarch` entry accepts the transactions and balances CSV files together or individually through file selection. Identify the format from headers, not a rigid filename. Permit renamed files and overlapping periods. State which source types were supplied and which are missing. Do not require a filesystem path, shell command, database access or hand-editing a CSV.
2. **Map once, review changes.** Remember explicitly confirmed source-account mappings, currency and relevant account semantics within the household/source scope. Reuse unchanged mappings on subsequent imports. New or ambiguous account labels need review. Imported Owner text never assigns access; source account mapping is permission-controlled.
3. **Preview.** Show clear counts and accessible detail for new records, changed source fields, unchanged duplicates, proposed reconciliation matches and unresolved conflicts. Preserve source/effective dates and display partial coverage. Detect a changed source file or changed mappings after preview and require a fresh preview rather than applying stale assumptions.
4. **Apply reviewed changes.** A human explicitly applies a resolved selection. Apply that selection transactionally and idempotently through Carl's deterministic service, without model-generated SQL or hidden read-tool writes. Unresolved balances can remain pending while an independently valid transaction selection is applied; do not claim the whole bundle is complete. Retrying the same request must recover the existing outcome.
5. **Review outcome.** Show what was added, revised, unchanged and left unresolved, with batch/request identity, attribution and reconciliation history. Keep original evidence and separate manual overrides. Mark dependent reports/plans stale; do not silently rewrite saved historical plans or claim they were regenerated. Offer the normal report/plan regeneration workflow.
6. **Repeat easily.** Re-uploading identical files changes nothing. Importing a newer full export updates source revisions and adds records without duplicating existing rows or deleting omitted history. Routine imports reuse confirmed mappings; only material differences require additional review.

Raw CSV parsing, validation and application are deterministic and do not require sending the file to a language-model provider. File bytes and private contents must stay in the authorized application data scope; exclude them from public source, diagnostic logs and build artifacts. Use authenticated bounded uploads and protected access to preview details. Preserve original evidence for the agreed retention period; production retention still needs a decision.

Further acceptance:

| Test | Required result |
| --- | --- |
| MON-11 | In the packaged Carl UI, upload both synthetic Settings-style exports using file selection, inspect previews, map accounts/currency, apply and inspect resulting authoritative transactions/balances. Repeat the principal flow in a narrow viewport. No shell or SQL is needed. |
| MON-12 | Repeat the same files, then overlapping/newer exports. Confirm exact no-op for identical data, revisions/additions for changed data, saved mappings reused, missing IDs retained, and no doubled totals. |
| MON-13 | An unresolved balance conflict remains pending and clearly labeled while an explicitly selected valid transaction subset applies atomically. Resolving the remaining observation updates the same import history without replaying completed work. |
| MON-14 | Change file bytes/mappings or revoke permission between preview and apply; reject the stale/unauthorized application. Interruption/retry recovers the correct durable status. Unauthorized users cannot read uploads or preview/history by guessed ID. |
| MON-15 | Source revisions preserve evidence/manual overrides and mark dependent plans stale. The UI shows the actual outcome and offers explicit regeneration; narrative never claims unresolved accounts are fully reconciled. |

These are mandatory current-build evidence for FIN-13. A parser test, command-line demo or placeholder upload screen alone does not satisfy the UI workflow.
