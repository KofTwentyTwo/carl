-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_plan_export (
 id uuid PRIMARY KEY, plan_id bigint NOT NULL REFERENCES carl_plan(record_id), plan_version integer NOT NULL,
 requester_id bigint NOT NULL REFERENCES carl_member(id), permission_revision bigint NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content)<=4000000), created_at timestamptz NOT NULL DEFAULT now()
);
