-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_report_claim (
 request_id uuid PRIMARY KEY REFERENCES carl_request(id),
 household_id bigint NOT NULL REFERENCES carl_household(id),
 permission_revision bigint NOT NULL
);
CREATE TABLE carl_report_claim_audience (
 request_id uuid NOT NULL REFERENCES carl_report_claim(request_id),
 member_id bigint NOT NULL REFERENCES carl_member(id),
 PRIMARY KEY(request_id,member_id)
);
CREATE TABLE carl_report_recovery (
 operation_id uuid PRIMARY KEY,
 request_id uuid NOT NULL REFERENCES carl_report_claim(request_id),
 actor_id bigint NOT NULL REFERENCES carl_member(id),
 evidence text NOT NULL CHECK(length(evidence) BETWEEN 1 AND 4000),
 outcome text NOT NULL CHECK(outcome IN ('COMPLETE','PARTIAL','FAILED','UNKNOWN')),
 artifact_id bigint REFERENCES carl_artifact(record_id),
 created_at timestamptz NOT NULL DEFAULT now()
);
-- Extend existing kinds without discarding independently introduced consumer kinds.
DO $$ DECLARE expression text; BEGIN
 SELECT pg_get_expr(conbin,conrelid) INTO expression FROM pg_constraint
 WHERE conrelid='carl_artifact'::regclass AND conname='carl_artifact_kind_check';
 ALTER TABLE carl_artifact DROP CONSTRAINT carl_artifact_kind_check;
 EXECUTE 'ALTER TABLE carl_artifact ADD CONSTRAINT carl_artifact_kind_check CHECK (' || expression || ' OR kind IN (''CALENDAR_REPORT'',''VENDOR_REPORT'',''BILL_COMPARISON''))';
END $$;
-- No audience is invented for pre-migration interrupted requests. They remain unqualified.
