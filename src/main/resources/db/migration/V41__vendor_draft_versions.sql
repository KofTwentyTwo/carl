-- Copyright (C) 2026 KofTwentyTwo
-- Work details inherit their vendor's current permissions in every native/tool route.
CREATE OR REPLACE VIEW carl_work_view AS SELECT a.id,a.principal,a.title,a.evidence,w.vendor_id,w.status,w.assigned_member,w.follow_up,w.commitment_evidence,a.revision
 FROM carl_access a JOIN carl_work_item w ON w.record_id=a.id
 JOIN carl_vendor_view v ON v.id=w.vendor_id AND v.principal=a.principal WHERE a.details;
CREATE TABLE carl_draft_chain (
 root_id bigint PRIMARY KEY REFERENCES carl_artifact(record_id),
 head_id bigint NOT NULL UNIQUE REFERENCES carl_artifact(record_id),version integer NOT NULL CHECK(version BETWEEN 1 AND 1000)
);
CREATE TABLE carl_draft_revision (
 artifact_id bigint PRIMARY KEY REFERENCES carl_artifact(record_id),root_id bigint NOT NULL REFERENCES carl_draft_chain(root_id),
 parent_id bigint UNIQUE REFERENCES carl_artifact(record_id),version integer NOT NULL CHECK(version BETWEEN 1 AND 1000),
 edited_by bigint NOT NULL REFERENCES carl_member(id),reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 4000),
 human_edited boolean NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(root_id,version)
);
CREATE VIEW carl_draft_revision_view AS
 SELECT a.*,d.root_id,d.parent_id,d.edited_by,d.reason,d.human_edited,d.created_at AS edited_at
 FROM carl_artifact_view a JOIN carl_draft_revision d ON d.artifact_id=a.id WHERE a.kind='VENDOR_DRAFT';
CREATE TABLE carl_vendor_draft_export (
 id uuid PRIMARY KEY,artifact_id bigint NOT NULL REFERENCES carl_artifact(record_id),
 requester_id bigint NOT NULL REFERENCES carl_member(id),permission_revision bigint NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content)<=500000),created_at timestamptz NOT NULL DEFAULT now()
);
-- No generic writes or public download view. Domain services reauthorize every historical version/export.
