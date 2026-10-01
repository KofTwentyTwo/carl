-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_calendar_mapping (
  step_id uuid PRIMARY KEY,
  plan_id bigint NOT NULL,
  collection_key text NOT NULL,
  component text NOT NULL CHECK(component IN ('VEVENT','VTODO')),
  etag text,
  published_hash text,
  retired boolean NOT NULL DEFAULT false,
  last_success timestamptz,
  observed_calendar text,
  observed_at timestamptz,
  last_read_success timestamptz,
  read_state text,
  read_diagnostic text
);
CREATE TABLE carl_calendar_operation (
  request_id uuid PRIMARY KEY,
  step_id uuid NOT NULL REFERENCES carl_calendar_mapping(step_id),
  plan_version integer NOT NULL CHECK(plan_version > 0),
  action text NOT NULL CHECK(action IN ('PUBLISH','RETIRE')),
  input_hash text NOT NULL,
  desired_calendar text NOT NULL,
  authority_revision text NOT NULL,
  actor text NOT NULL,
  status text NOT NULL CHECK(status IN ('PENDING','UNKNOWN','COMPLETE','CONFLICT')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  diagnostic text
);
CREATE INDEX carl_calendar_operation_resource ON carl_calendar_operation(step_id,updated_at);
-- No generic QQQ reader grants: expose permission-rechecked service results only.
