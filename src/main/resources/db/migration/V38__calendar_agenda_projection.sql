-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_calendar_connection ADD COLUMN coverage_from date, ADD COLUMN coverage_through date, ADD COLUMN collection_binding text;
ALTER TABLE carl_calendar_event ADD COLUMN snapshot_absent boolean NOT NULL DEFAULT false, ADD COLUMN observed_at timestamptz;
CREATE TABLE carl_calendar_series(connection_id bigint NOT NULL REFERENCES carl_calendar_connection(record_id),provider_uid text NOT NULL CHECK(length(provider_uid)<=500),stable_id uuid NOT NULL UNIQUE,PRIMARY KEY(connection_id,provider_uid));
CREATE OR REPLACE VIEW carl_calendar_connection_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,c.provider,c.calendar_identity,c.sync_state,c.last_success,c.last_attempt,c.failure_code,c.coverage_from,c.coverage_through
 FROM carl_access a JOIN carl_calendar_connection c ON c.record_id=a.id WHERE a.details;
CREATE OR REPLACE VIEW carl_calendar_view AS SELECT a.id,a.principal,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.title ELSE 'Busy' END AS title,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.evidence ELSE 'Event details restricted' END AS evidence,
 e.start_at,e.end_at,CASE WHEN a.details AND NOT e.free_busy_only THEN e.source_zone ELSE NULL END AS source_zone,e.all_day_start,e.all_day_end_exclusive,e.cancelled,e.transparent,
 e.observed_at AS last_success,CASE WHEN e.observed_at IS NULL THEN 'UNVERIFIED' WHEN e.observed_at<c.last_success THEN 'OLDER_SNAPSHOT' ELSE c.sync_state END AS sync_state,a.revision
 FROM carl_access a JOIN carl_calendar_event e ON e.record_id=a.id JOIN carl_calendar_connection c ON c.record_id=e.connection_id
 WHERE NOT e.snapshot_absent;
