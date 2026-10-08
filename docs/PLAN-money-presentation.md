# Consistent money presentation

Use QQQ decimal metadata and native field display behaviors for currency-aware
display values. Keep original BigDecimal values for sorting, calculations and
machine-readable import/export. Declare money separately from APRs and shares.
Use the same grouped symbol/currency presentation in dashboards, comparisons,
human report views, copy/download and PDF outputs. Right-align monetary columns,
headers and standalone financial figures; keep narrative text naturally aligned.
Missing amounts/currencies remain explicit. Never convert currencies or round
away significant source precision merely to change presentation.

1. [x] Add failing display/dashboard/PDF regressions.
2. [x] Implement shared monetary presentation and native metadata behavior; expose
   currency from existing permission-scoped relationships where omitted.
3. [x] Correct shared Next numeric alignment through the supported foundation patch.
4. [x] Run required Java/frontend checks and packaged browser/visual verification.
5. [x] Record exact evidence and update handoffs; retain existing integration holds.

Completed with [exact qualification](evidence/2026-10-01-money-presentation-qualification.json):419 current Java results,117 final browser checks, unchanged coverage floors, and matching frozen distribution/image. The former synthetic preview was `localhost:57132` (closed; not live); model/hosted/release/production holds remain separate.
