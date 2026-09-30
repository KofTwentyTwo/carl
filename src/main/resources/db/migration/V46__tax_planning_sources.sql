-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_tax_reference(
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),jurisdiction text NOT NULL CHECK(jurisdiction IN('US','ILLINOIS','RANDOLPH_COUNTY')),
 topic text NOT NULL CHECK(topic IN('RENTAL_RECORDS','OWNERSHIP_STRUCTURE','PROPERTY_ASSESSMENT')),
 source_url text NOT NULL,edition text NOT NULL,tax_year integer CHECK(tax_year BETWEEN 1900 AND 2200),
 published_on date,retrieved_at timestamptz NOT NULL,content_hash char(64),reviewed_on date,review_until date,review_evidence text,
 active boolean NOT NULL DEFAULT true,reviewed_by bigint NOT NULL REFERENCES carl_member(id),
 CHECK((reviewed_on IS NULL AND review_until IS NULL) OR (reviewed_on IS NOT NULL AND review_until>=reviewed_on))
);
CREATE TABLE carl_tax_alternative(
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),property_id bigint NOT NULL REFERENCES carl_property(record_id),
 structure_label text NOT NULL,current_title_evidence text,assumptions text NOT NULL,
 setup_cost numeric(20,4),annual_cost numeric(20,4),currency char(3) NOT NULL,
 professional_review text,created_by bigint NOT NULL REFERENCES carl_member(id)
);
CREATE TABLE carl_tax_alternative_reference(
 alternative_id bigint NOT NULL REFERENCES carl_tax_alternative(record_id),reference_id bigint NOT NULL REFERENCES carl_tax_reference(record_id),
 reference_revision bigint NOT NULL,PRIMARY KEY(alternative_id,reference_id)
);
CREATE VIEW carl_tax_reference_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,a.created_at,r.jurisdiction,r.topic,r.source_url,r.edition,r.tax_year,
 r.published_on,r.retrieved_at,r.content_hash,r.reviewed_on,r.review_until,r.review_evidence,r.active,r.reviewed_by
 FROM carl_access a JOIN carl_tax_reference r ON r.record_id=a.id WHERE a.details;
CREATE VIEW carl_tax_alternative_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,a.created_at,t.property_id,t.structure_label,t.current_title_evidence,t.assumptions,
 t.setup_cost,t.annual_cost,t.currency,t.professional_review,t.created_by
 FROM carl_access a JOIN carl_tax_alternative t ON t.record_id=a.id
 JOIN carl_tax_property_view p ON p.id=t.property_id AND p.principal=a.principal WHERE a.details
 AND NOT EXISTS(SELECT 1 FROM carl_tax_alternative_reference link WHERE link.alternative_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_tax_reference_view reference WHERE reference.id=link.reference_id AND reference.principal=a.principal));
-- Reference deactivation invalidates saved context as well as making new packets incomplete.
CREATE FUNCTION carl_tax_reference_access_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.active IS DISTINCT FROM NEW.active THEN
  UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=(SELECT household_id FROM carl_record WHERE id=NEW.record_id);
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER carl_tax_reference_epoch AFTER UPDATE OF active ON carl_tax_reference FOR EACH ROW EXECUTE FUNCTION carl_tax_reference_access_epoch();
