<!-- Copyright (C) 2026 KofTwentyTwo -->

# Selected balance sheets and rental stress

Carl uses the existing authoritative accounts, dated balance observations, rental properties and protected rental cash reports. These workflows create local reports and attributed hypothetical inputs; they cannot change a lender account, make a payment, perform repairs or establish tax treatment.

## Assets and liabilities

Choose the as-of date, maximum observation age in days, selected account IDs and each selected property's valuation source. A property estimate replaces its linked asset-account balance; a linked-account selection counts that account once and adds no separate property amount. Selecting a linked asset separately does not add it again. Linked mortgages are included once across selected properties, using their account ownership, even when multiple properties share the mortgage. Property ownership is not automatically applied to legal debt liability. The property/account links and ownership are current supplied context, not reconstructed historical ownership.

For each account Carl selects all observations on the latest date on or before as-of. Matching amounts with different bases can support the same figure; conflicting amounts have no inferred precedence and remain unresolved. Future observations do not replace earlier observations. Missing balances, property values, ownership, and absent chosen links are explicit gaps, never invented zeroes. A property estimate after as-of is excluded. Stale observed estimates remain in known-source totals with explicit age warnings. All totals remain partial selected-source figures with currencies separate; liquidity is not disposable cash or purchase affordability.

Each ownership amount is rounded half-up once at the currency's native precision. Signed account values remain signed, and repeated linked accounts do not multiply their contribution. No exchange rate is inferred. Saved reports retain source revisions and the permission epoch. Changes show staleness or deny retrieval where access has changed.

## Rental stress replay

First generate a source-linked rental cash report. Then record immutable human assumptions for one property in that report: expected whole-property rent over exactly that report interval, vacancy fraction, additional one-off repair amount, additional APR fraction, allocated constant whole-property mortgage principal, its exact balance date, and explanatory evidence. Explicit zero disables an individual shock. The scenario retains the human author and the protected baseline; making it family-visible cannot broaden the baseline's audience.

The scenario replays the selected historical interval once. Vacancy impact equals supplied expected rent multiplied by vacancy fraction. The repair is an explicit added cash cost. Additional simple interest equals allocated principal × additional APR × actual interval days / 365, rounded to currency precision. Allocation cannot exceed the resolved same-currency negative balance observed on the exact selected date. The saved result carries that observation’s ID, amount, date, basis and supplied evidence, alongside the source-account revision. This is a constant-principal interest stress assumption, not an amortizing-payment quote, observed lender rate reset, refinance comparison or future prediction.

Carl subtracts those three rounded components from baseline cash after reserve transfers. Ownership results use the source-calculated owned baseline, round each adverse component at the supplied property fraction, sum them, then subtract that sum. They remain proportional reporting attribution, not a promised distribution or verified liability allocation. Baseline source coverage stays partial. Unknown source facts require review; no tax savings, legal structure, complete household result or actual financial action is asserted.

A stale rental baseline must be regenerated. Revoking any baseline source or changing audience permissions blocks access according to the same Carl artifact rules. The native input/review processes and explicit family workflow use these services; ordinary read tools do not silently create reports or scenarios.

## Qualification

The integrated PostgreSQL and native HTTP fixtures use synthetic records only. They cover duplicate asset/mortgage suppression, currency separation, historical selection, conflicting observations, null facts, stale sources, private/shared audience denial, revoked artifact retrieval, bounded principal, stable retries and penny conservation. Final299-test consumer and31-check packaged browser gates pass, including native mixed-source selection. Hosted release remains separately blocked; no real-family data, provider qualification or deployment follows from these fixtures.
