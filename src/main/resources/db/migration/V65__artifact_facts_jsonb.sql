-- Copyright (C) 2026 KofTwentyTwo
-- Issue #15: store report/plan facts as typed jsonb. carl_artifact_view is the only view reading the column; it is detached,
-- the column converts in place (invalid JSON aborts the whole migration), and the view keeps exposing text so dependent
-- views and readers keep their column types. JSON selectors read the typed base column through an expression index.
CREATE OR REPLACE VIEW carl_artifact_view AS SELECT a.id,a.principal,a.title,a.created_at,f.kind,f.version,f.period_start,f.period_end,NULL::text AS facts,f.narrative,f.limitations,f.narration_state,f.status_label,
 EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE s.artifact_id=a.id AND s.source_revision<>r.revision) AS stale
 FROM carl_access a JOIN carl_artifact f ON f.record_id=a.id
 JOIN carl_household h ON h.id=a.household_id AND h.permission_revision=f.permission_revision
 JOIN carl_artifact_audience audience ON audience.artifact_id=a.id AND audience.member_id=a.member_id
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access source WHERE source.id=s.source_id AND source.principal=a.principal AND source.details) AND NOT EXISTS(SELECT 1 FROM carl_calendar_view calendar WHERE calendar.id=s.source_id AND calendar.principal=a.principal))
 AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_transaction t ON t.record_id=s.source_id WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access account WHERE account.id=t.account_id AND account.principal=a.principal AND account.details));
ALTER TABLE carl_artifact ALTER COLUMN facts TYPE jsonb USING facts::jsonb;
CREATE OR REPLACE VIEW carl_artifact_view AS SELECT a.id,a.principal,a.title,a.created_at,f.kind,f.version,f.period_start,f.period_end,f.facts::text AS facts,f.narrative,f.limitations,f.narration_state,f.status_label,
 EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE s.artifact_id=a.id AND s.source_revision<>r.revision) AS stale
 FROM carl_access a JOIN carl_artifact f ON f.record_id=a.id
 JOIN carl_household h ON h.id=a.household_id AND h.permission_revision=f.permission_revision
 JOIN carl_artifact_audience audience ON audience.artifact_id=a.id AND audience.member_id=a.member_id
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access source WHERE source.id=s.source_id AND source.principal=a.principal AND source.details) AND NOT EXISTS(SELECT 1 FROM carl_calendar_view calendar WHERE calendar.id=s.source_id AND calendar.principal=a.principal))
 AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_transaction t ON t.record_id=s.source_id WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access account WHERE account.id=t.account_id AND account.principal=a.principal AND account.details));
CREATE INDEX carl_artifact_calculation_version ON carl_artifact((facts->>'calculationVersion'));
CREATE OR REPLACE VIEW carl_rental_baseline_selection_view AS
 SELECT f.id,f.principal,concat('Rental report ',f.period_start,' through ',f.period_end,' (#',f.id,')') AS title,
 f.period_start,f.period_end,f.created_at,f.status_label
 FROM carl_artifact_view f JOIN carl_artifact selection ON selection.record_id=f.id
 WHERE f.kind='FINANCIAL_PLAN' AND NOT f.stale AND selection.facts->>'calculationVersion'='rental-cash-v1';
