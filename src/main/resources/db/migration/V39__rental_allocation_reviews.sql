-- Copyright (C) 2026 KofTwentyTwo
-- Saved human review state is distinct from immutable source classifications.
CREATE TABLE carl_rental_review (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 transaction_id bigint NOT NULL REFERENCES carl_transaction(record_id), transaction_revision bigint NOT NULL,
 review_version bigint NOT NULL DEFAULT 1 CHECK(review_version>0),
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','APPLYING','APPLIED','UNKNOWN','FAILED')),
 classification_id bigint REFERENCES carl_rental_split(record_id), classification_version integer,
 child_request uuid, apply_evidence text, result_id bigint REFERENCES carl_rental_split(record_id),
 created_by bigint NOT NULL REFERENCES carl_member(id),
 CHECK((classification_id IS NULL)=(classification_version IS NULL)),
 CHECK(classification_version IS NULL OR classification_version>0),
 CHECK(state NOT IN ('APPLYING','UNKNOWN','APPLIED') OR (child_request IS NOT NULL AND apply_evidence IS NOT NULL)),
 CHECK((state='APPLIED')=(result_id IS NOT NULL))
);
CREATE TABLE carl_rental_review_component (
 id bigserial PRIMARY KEY, review_id bigint NOT NULL REFERENCES carl_rental_review(record_id),
 component_id text NOT NULL CHECK(length(component_id) BETWEEN 1 AND 150),
 kind text NOT NULL CHECK(kind IN ('RENT_RECEIPT','OPERATING_EXPENSE','DEBT_PRINCIPAL','DEBT_INTEREST','CAPITAL_EXPENDITURE','RESERVE_TRANSFER','DEPOSIT_RECEIPT','DEPOSIT_REFUND')),
 amount numeric(20,4) NOT NULL CHECK(amount>0), outside_fraction numeric(16,12) NOT NULL CHECK(outside_fraction BETWEEN 0 AND 1),
 UNIQUE(review_id,component_id)
);
CREATE TABLE carl_rental_review_share (
 id bigserial PRIMARY KEY, component_id bigint NOT NULL REFERENCES carl_rental_review_component(id) ON DELETE CASCADE,
 property_id bigint NOT NULL REFERENCES carl_property(record_id),
 fraction numeric(16,12) NOT NULL CHECK(fraction>0 AND fraction<=1), UNIQUE(component_id,property_id)
);
CREATE INDEX carl_rental_review_source ON carl_rental_review(transaction_id);
CREATE INDEX carl_rental_review_share_property ON carl_rental_review_share(property_id,component_id);
CREATE VIEW carl_rental_review_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,r.transaction_id,r.transaction_revision,r.review_version,r.state,
 r.classification_id,r.classification_version,r.result_id,t.amount AS source_amount,t.currency,t.effective_date AS source_date,
 (tr.revision<>r.transaction_revision) AS source_stale,
 (r.classification_id IS NOT NULL AND EXISTS(SELECT 1 FROM carl_rental_split s WHERE s.record_id=r.classification_id AND s.current_version<>r.classification_version)) AS classification_stale
 FROM carl_access a JOIN carl_rental_review r ON r.record_id=a.id
 JOIN carl_transaction_view t ON t.id=r.transaction_id AND t.principal=a.principal
 JOIN carl_record tr ON tr.id=r.transaction_id
 WHERE a.details
 AND (r.classification_id IS NULL OR EXISTS(SELECT 1 FROM carl_rental_source_view v WHERE v.id=r.classification_id AND v.principal=a.principal))
 AND (r.result_id IS NULL OR EXISTS(SELECT 1 FROM carl_rental_source_view v WHERE v.id=r.result_id AND v.principal=a.principal))
 AND NOT EXISTS(SELECT 1 FROM carl_rental_review_component c JOIN carl_rental_review_share s ON s.component_id=c.id
 WHERE c.review_id=r.record_id AND NOT EXISTS(SELECT 1 FROM carl_rental_property_view p WHERE p.id=s.property_id AND p.principal=a.principal));
CREATE VIEW carl_rental_review_component_view AS
 SELECT c.id,r.principal,r.title || ' / ' || c.component_id AS title,r.evidence,r.revision,c.review_id,
 c.component_id,c.kind,c.amount,c.outside_fraction,r.review_version,r.state,r.currency
 FROM carl_rental_review_view r JOIN carl_rental_review_component c ON c.review_id=r.id;
CREATE VIEW carl_rental_review_share_view AS
 SELECT s.id,r.principal,r.title || ' / ' || p.title AS title,r.evidence,r.revision,c.review_id,c.component_id,
 s.property_id,p.title AS property_label,s.fraction,r.review_version,r.state
 FROM carl_rental_review_component_view c JOIN carl_rental_review_view r ON r.id=c.review_id AND r.principal=c.principal
 JOIN carl_rental_review_share s ON s.component_id=c.id
 JOIN carl_rental_property_view p ON p.id=s.property_id AND p.principal=r.principal;
CREATE FUNCTION carl_rental_review_parent_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='carl_rental_review_component' THEN
  IF NEW.review_id IS DISTINCT FROM OLD.review_id THEN RAISE EXCEPTION 'Review component parent is immutable'; END IF;
 ELSIF TG_TABLE_NAME='carl_rental_review_share' THEN
  IF NEW.component_id IS DISTINCT FROM OLD.component_id THEN RAISE EXCEPTION 'Review share parent is immutable'; END IF;
 ELSE
  IF NEW.transaction_id IS DISTINCT FROM OLD.transaction_id OR NEW.transaction_revision IS DISTINCT FROM OLD.transaction_revision
   OR NEW.classification_id IS DISTINCT FROM OLD.classification_id OR NEW.classification_version IS DISTINCT FROM OLD.classification_version
  THEN RAISE EXCEPTION 'Reviewed source identity is immutable'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER carl_rental_review_parent BEFORE UPDATE ON carl_rental_review FOR EACH ROW EXECUTE FUNCTION carl_rental_review_parent_immutable();
CREATE TRIGGER carl_rental_review_component_parent BEFORE UPDATE ON carl_rental_review_component FOR EACH ROW EXECUTE FUNCTION carl_rental_review_parent_immutable();
CREATE TRIGGER carl_rental_review_share_parent BEFORE UPDATE ON carl_rental_review_share FOR EACH ROW EXECUTE FUNCTION carl_rental_review_parent_immutable();
CREATE FUNCTION carl_rental_review_dependency_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review bigint; household bigint;
BEGIN
 IF TG_TABLE_NAME='carl_rental_review_component' THEN
  review=coalesce(NEW.review_id,OLD.review_id);
 ELSE
  SELECT review_id INTO review FROM carl_rental_review_component WHERE id=coalesce(NEW.component_id,OLD.component_id);
 END IF;
 SELECT household_id INTO household FROM carl_record WHERE id=review;
 UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=household;
 RETURN coalesce(NEW,OLD);
END;
$$;
CREATE TRIGGER carl_rental_review_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_rental_review FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_rental_review_component_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_rental_review_component FOR EACH ROW EXECUTE FUNCTION carl_rental_review_dependency_epoch();
CREATE TRIGGER carl_rental_review_share_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_rental_review_share FOR EACH ROW EXECUTE FUNCTION carl_rental_review_dependency_epoch();
-- Dependency changes invalidate old shared conversation context, including direct privileged corrections.
-- Raw review tables have no generic QQQ privileges.
