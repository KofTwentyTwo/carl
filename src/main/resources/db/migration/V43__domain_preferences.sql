-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_preference_binding(
 record_id bigint PRIMARY KEY REFERENCES carl_preference(record_id), household_id bigint NOT NULL REFERENCES carl_household(id),
 scope text NOT NULL CHECK(scope IN('MEMBER','HOUSEHOLD')), member_id bigint REFERENCES carl_member(id),preference_key text NOT NULL,
 CHECK((scope='MEMBER')=(member_id IS NOT NULL)), UNIQUE NULLS NOT DISTINCT(household_id,scope,member_id,preference_key)
);
CREATE TABLE carl_preference_history(
 record_id bigint NOT NULL REFERENCES carl_preference(record_id),version bigint NOT NULL,member_id bigint NOT NULL REFERENCES carl_member(id),
 value text NOT NULL,evidence text NOT NULL,changed_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(record_id,version)
);
CREATE VIEW carl_preference_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,p.preference_key,p.value,b.scope,b.member_id,a.created_at,h.changed_at AS updated_at,h.member_id AS updated_by
 FROM carl_access a JOIN carl_preference p ON p.record_id=a.id JOIN carl_preference_binding b ON b.record_id=p.record_id
 JOIN carl_preference_history h ON h.record_id=p.record_id AND h.version=a.revision
 WHERE a.details AND (b.scope='HOUSEHOLD' OR b.member_id=a.member_id);
