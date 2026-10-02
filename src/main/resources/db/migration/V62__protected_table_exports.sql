-- Copyright (C) 2026 KofTwentyTwo
-- Immutable dated CSV snapshots; current membership and source access are checked at retrieval.
CREATE TABLE carl_table_export (
 id uuid PRIMARY KEY, requester_id bigint NOT NULL REFERENCES carl_member(id),
 household_id bigint NOT NULL REFERENCES carl_household(id), permission_revision bigint NOT NULL,
 source_table text NOT NULL, selection_scope text NOT NULL CHECK(selection_scope IN ('SELECTED','ALL_AUTHORIZED')),
 input_digest char(64) NOT NULL, source_digest char(64) NOT NULL,
 source_ids text[] NOT NULL CHECK(cardinality(source_ids)<=50000),
 source_revision bigint NOT NULL, as_of timestamptz NOT NULL,
 row_count integer NOT NULL CHECK(row_count BETWEEN 0 AND 50000),
 content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 20000000),
 content_hash char(64) NOT NULL,
 text_policy text NOT NULL CHECK(text_policy='SPREADSHEET_SAFE_TEXT_PREFIX_V1'),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(requester_id,household_id) REFERENCES carl_member(id,household_id)
);
CREATE FUNCTION carl_table_export_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Dated table export snapshots are immutable';
END;
$$;
CREATE TRIGGER carl_table_export_immutable BEFORE UPDATE ON carl_table_export FOR EACH ROW EXECUTE FUNCTION carl_table_export_immutable();
CREATE VIEW carl_table_export_view AS
 SELECT e.id::text AS id,m.principal,concat(e.source_table,' / ',e.selection_scope,' / ',e.as_of) AS title,
 e.source_table,e.selection_scope,e.as_of,e.row_count,octet_length(e.content) AS byte_count,
 e.content_hash,e.text_policy,e.created_at,(e.source_revision<>h.revision) AS household_changed
 FROM carl_table_export e JOIN carl_member m ON m.id=e.requester_id AND m.household_id=e.household_id AND m.active
 JOIN carl_household h ON h.id=e.household_id AND h.permission_revision=e.permission_revision;
