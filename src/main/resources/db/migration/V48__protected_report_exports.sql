-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_report_export (
 id uuid PRIMARY KEY, artifact_id bigint NOT NULL REFERENCES carl_artifact(record_id),
 requester_id bigint NOT NULL REFERENCES carl_member(id), permission_revision bigint NOT NULL,
 format text NOT NULL CHECK(format IN ('PDF','TEXT')), snapshot_digest char(64) NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content)<=4000000), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX carl_report_export_artifact ON carl_report_export(artifact_id,requester_id);
-- No generic QQQ data route or raw-table reader grant; storage rechecks the artifact and sources.
