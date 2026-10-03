-- Copyright (C) 2026 KofTwentyTwo
-- Household finance/history evidence is not verification of current terms.
CREATE TABLE carl_document_review (
 id uuid PRIMARY KEY, member_id bigint NOT NULL REFERENCES carl_member(id),
 permission_revision bigint NOT NULL, upload_reference text NOT NULL REFERENCES carl_upload(reference),
 input_digest char(64) NOT NULL, source_digest char(64) NOT NULL, original_name text NOT NULL, content_hash char(64) NOT NULL,
 title text NOT NULL, visibility text NOT NULL CHECK(visibility IN ('PRIVATE','FAMILY')),
 source_identity text NOT NULL, source_type text NOT NULL CHECK(source_type IN ('HISTORICAL_NOTE','SOURCE_DOCUMENT')),
 document_date date, as_of_date date, provenance text NOT NULL,
 extraction_status text NOT NULL CHECK(extraction_status IN ('TEXT_EXTRACTED','IMAGE_ONLY','ENCRYPTED','UNSUPPORTED','INVALID','PAGE_LIMIT','TEXT_LIMIT')),
 extracted_text text NOT NULL CHECK(length(extracted_text)<=100000), page_count integer,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE carl_document (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 source_identity text NOT NULL CHECK(length(source_identity) BETWEEN 1 AND 1000),
 source_type text NOT NULL CHECK(source_type IN ('HISTORICAL_NOTE','SOURCE_DOCUMENT')),
 document_date date, as_of_date date,
 state text NOT NULL DEFAULT 'SUPPLIED_UNVERIFIED' CHECK(state='SUPPLIED_UNVERIFIED'),
 source_digest char(64) NOT NULL, original_name text NOT NULL CHECK(length(original_name) BETWEEN 1 AND 200),
 media_type text NOT NULL, content_hash char(64) NOT NULL, original_content bytea NOT NULL CHECK(octet_length(original_content) BETWEEN 1 AND 20000000),
 extraction_status text NOT NULL CHECK(extraction_status IN ('TEXT_EXTRACTED','IMAGE_ONLY','ENCRYPTED','UNSUPPORTED','INVALID','PAGE_LIMIT','TEXT_LIMIT')),
 extracted_text text NOT NULL CHECK(length(extracted_text)<=100000), page_count integer,
 review_id uuid NOT NULL REFERENCES carl_document_review(id)
);
CREATE FUNCTION carl_document_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Original household evidence is immutable';
END;
$$;
CREATE TRIGGER carl_document_immutable BEFORE UPDATE ON carl_document FOR EACH ROW EXECUTE FUNCTION carl_document_immutable();
CREATE TRIGGER carl_document_review_immutable BEFORE UPDATE ON carl_document_review FOR EACH ROW EXECUTE FUNCTION carl_document_immutable();
CREATE TABLE carl_document_download (
 id uuid PRIMARY KEY, document_id bigint NOT NULL REFERENCES carl_document(record_id),
 requester_id bigint NOT NULL REFERENCES carl_member(id), permission_revision bigint NOT NULL
);
CREATE VIEW carl_document_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.visibility,a.created_at,a.revision,
 d.source_identity,d.source_type,d.document_date,d.as_of_date,d.state,d.content_hash,d.original_name,d.media_type,
 octet_length(d.original_content) AS byte_count,d.extraction_status,d.page_count,length(d.extracted_text) AS text_length
 FROM carl_access a JOIN carl_document d ON d.record_id=a.id WHERE a.details;
