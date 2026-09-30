-- Copyright (C) 2026 KofTwentyTwo
CREATE VIEW carl_balance_selection_view AS
 SELECT a.id,a.principal,a.title,a.evidence,'ACCOUNT'::text AS source_type,a.currency,a.as_of AS observed_on
 FROM carl_account_view a
 UNION ALL
 SELECT p.id,p.principal,p.title,p.evidence,'PROPERTY'::text AS source_type,p.currency,p.valuation_date AS observed_on
 FROM carl_rental_property_view p;
CREATE VIEW carl_rental_baseline_selection_view AS
 SELECT f.id,f.principal,concat('Rental report ',f.period_start,' through ',f.period_end,' (#',f.id,')') AS title,
 f.period_start,f.period_end,f.created_at,f.status_label
 FROM carl_artifact_view f
 WHERE f.kind='FINANCIAL_PLAN' AND NOT f.stale AND f.facts::jsonb->>'calculationVersion'='rental-cash-v1';
