-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_plan (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), source_artifact_id bigint NOT NULL REFERENCES carl_artifact(record_id),
 version integer NOT NULL DEFAULT 1 CHECK(version>0), state text NOT NULL CHECK(state IN ('DRAFT','AGREED','RETIRED')),
 created_by bigint NOT NULL REFERENCES carl_member(id), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE carl_plan_step (
 id uuid PRIMARY KEY, plan_id bigint NOT NULL REFERENCES carl_plan(record_id), title text NOT NULL CHECK(length(title) BETWEEN 1 AND 500),
 assignee_id bigint NOT NULL REFERENCES carl_member(id), due_date date NOT NULL, location text NOT NULL,
 dependency_id uuid REFERENCES carl_plan_step(id), status text NOT NULL CHECK(status IN ('TODO','REPORTED_COMPLETE','VERIFIED_COMPLETE','BLOCKED')),
 checkin text NOT NULL DEFAULT '', evidence_record_id bigint REFERENCES carl_record(id), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE carl_plan_version (
 plan_id bigint NOT NULL REFERENCES carl_plan(record_id), version integer NOT NULL,
 actor_id bigint NOT NULL REFERENCES carl_member(id), reason text NOT NULL, snapshot text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(plan_id,version)
);
CREATE VIEW carl_plan_view AS
 SELECT a.id,a.principal,a.title,a.evidence,p.source_artifact_id,p.version,p.state,p.updated_at,f.stale AS source_stale
 FROM carl_access a JOIN carl_plan p ON p.record_id=a.id
 JOIN carl_artifact_view f ON f.id=p.source_artifact_id AND f.principal=a.principal WHERE a.details;
CREATE VIEW carl_plan_step_view AS
 SELECT s.id,p.principal,s.title,s.plan_id,s.assignee_id,s.due_date,s.location,s.dependency_id,s.status,s.checkin,s.evidence_record_id,s.updated_at
 FROM carl_plan_view p JOIN carl_plan_step s ON s.plan_id=p.id;
CREATE OR REPLACE FUNCTION carl_bump_permission_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_household bigint; new_household bigint;
BEGIN
 IF TG_TABLE_NAME='carl_member' OR TG_TABLE_NAME='carl_record' THEN
  IF TG_OP<>'INSERT' THEN old_household=OLD.household_id; END IF;
  IF TG_OP<>'DELETE' THEN new_household=NEW.household_id; END IF;
 ELSIF TG_TABLE_NAME IN ('carl_permission','carl_identity') THEN
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_member WHERE id=OLD.member_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_member WHERE id=NEW.member_id; END IF;
 ELSIF TG_TABLE_NAME='carl_artifact_audience' THEN
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_record WHERE id=OLD.artifact_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_record WHERE id=NEW.artifact_id; END IF;
 ELSE
  IF TG_TABLE_NAME='carl_grant' AND TG_OP='INSERT' AND EXISTS(SELECT 1 FROM carl_record r WHERE r.id=NEW.record_id AND r.created_at=transaction_timestamp()) THEN RETURN NEW; END IF;
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_record WHERE id=OLD.record_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_record WHERE id=NEW.record_id; END IF;
 END IF;
 UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=old_household OR id=new_household;
 RETURN coalesce(NEW,OLD);
END;
$$;
