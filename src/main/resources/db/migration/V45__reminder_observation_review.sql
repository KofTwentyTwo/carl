-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_reminder_observation (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 plan_id bigint NOT NULL REFERENCES carl_plan(record_id), step_id uuid NOT NULL REFERENCES carl_plan_step(id),
 plan_version integer NOT NULL, remote_hash text NOT NULL, remote_etag text NOT NULL,
 remote_state text NOT NULL CHECK(remote_state IN ('REMOTE_REPORTED_COMPLETE','NEEDS_ACTION','IN_PROCESS','CANCELLED')),
 reported_completed timestamptz, observed_at timestamptz NOT NULL DEFAULT now(), observed_by bigint NOT NULL REFERENCES carl_member(id),
 limitation text NOT NULL, review_state text NOT NULL DEFAULT 'PENDING' CHECK(review_state IN ('PENDING','ACCEPT_REPORTED_COMPLETE','DISMISS')),
 reviewed_by bigint REFERENCES carl_member(id), reviewed_at timestamptz, reviewed_version integer, review_note text,
 UNIQUE(step_id,remote_hash,plan_version)
);
CREATE VIEW carl_reminder_observation_view AS
 SELECT o.id,p.principal,p.title,o.plan_id,o.step_id,o.plan_version,o.remote_state,o.reported_completed,o.observed_at,
 o.limitation,o.review_state,o.reviewed_version,o.review_note
 FROM carl_plan_view p JOIN carl_reminder_observation o ON o.plan_id=p.id;
DO $$
DECLARE prior_definition text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO STRICT prior_definition FROM pg_constraint
 WHERE conrelid='carl_client_workflow'::regclass AND conname='carl_client_workflow_kind_check';
 ALTER TABLE carl_client_workflow DROP CONSTRAINT carl_client_workflow_kind_check;
 EXECUTE 'ALTER TABLE carl_client_workflow ADD CONSTRAINT carl_client_workflow_kind_check CHECK (' ||
 substring(prior_definition FROM 7) || ' OR kind IN (''calendar-plan'',''reminder-review''))';
END $$;
