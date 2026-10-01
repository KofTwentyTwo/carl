-- Copyright (C) 2026 KofTwentyTwo
CREATE OR REPLACE VIEW carl_calendar_view AS SELECT a.id,a.principal,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.title ELSE 'Busy' END AS title,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.evidence ELSE 'Event details restricted' END AS evidence,
 e.start_at,e.end_at,CASE WHEN a.details AND NOT e.free_busy_only THEN e.source_zone ELSE NULL END AS source_zone,e.all_day_start,e.all_day_end_exclusive,e.cancelled,e.transparent,
 c.last_success,c.sync_state,a.revision
 FROM carl_access a JOIN carl_calendar_event e ON e.record_id=a.id JOIN carl_calendar_connection c ON c.record_id=e.connection_id;

CREATE OR REPLACE VIEW carl_artifact_view AS SELECT a.id,a.principal,a.title,a.created_at,f.kind,f.version,f.period_start,f.period_end,f.facts,f.narrative,f.limitations,f.narration_state,f.status_label,
 EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE s.artifact_id=a.id AND s.source_revision<>r.revision) AS stale
 FROM carl_access a JOIN carl_artifact f ON f.record_id=a.id
 JOIN carl_household h ON h.id=a.household_id AND h.permission_revision=f.permission_revision
 JOIN carl_artifact_audience audience ON audience.artifact_id=a.id AND audience.member_id=a.member_id
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access source WHERE source.id=s.source_id AND source.principal=a.principal AND source.details) AND NOT EXISTS(SELECT 1 FROM carl_calendar_view calendar WHERE calendar.id=s.source_id AND calendar.principal=a.principal))
 AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_transaction t ON t.record_id=s.source_id WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access account WHERE account.id=t.account_id AND account.principal=a.principal AND account.details));
