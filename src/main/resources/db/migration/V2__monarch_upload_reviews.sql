-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_upload (
 reference text PRIMARY KEY, member_id bigint NOT NULL REFERENCES carl_member(id),
 contents bytea NOT NULL CHECK(octet_length(contents)<=20000000), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE carl_monarch_mapping (
 member_id bigint NOT NULL REFERENCES carl_member(id), source_label text NOT NULL,
 account_id bigint NOT NULL REFERENCES carl_account(record_id), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(member_id,source_label)
);
CREATE TABLE carl_import_review (
 id uuid PRIMARY KEY, member_id bigint NOT NULL REFERENCES carl_member(id),
 transaction_reference text REFERENCES carl_upload(reference), balance_reference text REFERENCES carl_upload(reference),
 status text NOT NULL CHECK(status IN ('PREVIEW','NEEDS_REVIEW','PARTIAL','COMPLETE')),
 result text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(transaction_reference IS NOT NULL OR balance_reference IS NOT NULL)
);
CREATE TABLE carl_balance_resolution (
 review_id uuid NOT NULL REFERENCES carl_import_review(id), source_label text NOT NULL,
 observation_date date NOT NULL, selected_logical_row integer NOT NULL CHECK(selected_logical_row>=2),
 member_id bigint NOT NULL REFERENCES carl_member(id), reason text NOT NULL,
 PRIMARY KEY(review_id,source_label,observation_date)
);
CREATE VIEW carl_import_review_view AS SELECT r.id::text AS id,m.principal,'Monarch import ' || r.created_at AS title,
 r.status,r.result,r.created_at FROM carl_import_review r JOIN carl_member m ON m.id=r.member_id AND m.active;
