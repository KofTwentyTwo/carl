-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_identity (
 issuer text NOT NULL, subject text NOT NULL, member_id bigint NOT NULL REFERENCES carl_member(id),
 PRIMARY KEY(issuer,subject), CHECK(length(issuer)<=2000), CHECK(length(subject)<=2000)
);
CREATE TABLE carl_client_workflow (
 request_id uuid PRIMARY KEY, conversation_id uuid NOT NULL, requester_id bigint NOT NULL REFERENCES carl_member(id),
 kind text NOT NULL CHECK(kind IN ('report','draft')), audience text NOT NULL, permission_revision bigint NOT NULL,
 input_digest char(64) NOT NULL, status text NOT NULL CHECK(status IN ('PENDING','COMPLETE','PARTIAL','FAILED','UNKNOWN')),
 artifact_id bigint REFERENCES carl_artifact(record_id), created_at timestamptz NOT NULL DEFAULT now()
);
