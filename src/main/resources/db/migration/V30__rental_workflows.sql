-- Copyright (C) 2026 KofTwentyTwo
-- Consumer-owned source classifications are immutable versions, not edits to imported evidence.
ALTER TABLE carl_property ALTER COLUMN ownership_share DROP NOT NULL;
ALTER TABLE carl_property ADD COLUMN building_basis numeric(20,4) CHECK(building_basis IS NULL OR building_basis>=0);
ALTER TABLE carl_property ADD CONSTRAINT carl_property_nonnegative_values CHECK
 ((market_value IS NULL OR market_value>=0) AND (acquisition_basis IS NULL OR acquisition_basis>=0) AND (land_basis IS NULL OR land_basis>=0));
CREATE TABLE carl_rental_split (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 transaction_id bigint NOT NULL UNIQUE REFERENCES carl_transaction(record_id),
 current_version integer NOT NULL CHECK(current_version>0)
);
CREATE TABLE carl_rental_split_version (
 split_id bigint NOT NULL REFERENCES carl_rental_split(record_id), version integer NOT NULL CHECK(version>0),
 transaction_revision bigint NOT NULL, source_amount numeric(20,4) NOT NULL, source_date date NOT NULL,
 currency char(3) NOT NULL, input_hash char(64) NOT NULL, evidence text NOT NULL,
 created_by bigint NOT NULL REFERENCES carl_member(id), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(split_id,version)
);
CREATE TABLE carl_rental_component (
 split_id bigint NOT NULL, version integer NOT NULL, component_id text NOT NULL CHECK(length(component_id) BETWEEN 1 AND 150),
 kind text NOT NULL CHECK(kind IN ('RENT_RECEIPT','OPERATING_EXPENSE','DEBT_PRINCIPAL','DEBT_INTEREST','CAPITAL_EXPENDITURE','RESERVE_TRANSFER','DEPOSIT_RECEIPT','DEPOSIT_REFUND')),
 amount numeric(20,4) NOT NULL CHECK(amount>0), outside_fraction numeric(16,12) NOT NULL CHECK(outside_fraction>=0 AND outside_fraction<=1),
 PRIMARY KEY(split_id,version,component_id), FOREIGN KEY(split_id,version) REFERENCES carl_rental_split_version(split_id,version)
);
CREATE TABLE carl_rental_allocation (
 split_id bigint NOT NULL, version integer NOT NULL, component_id text NOT NULL,
 property_id bigint NOT NULL REFERENCES carl_property(record_id), fraction numeric(16,12) NOT NULL CHECK(fraction>0 AND fraction<=1),
 PRIMARY KEY(split_id,version,component_id,property_id),
 FOREIGN KEY(split_id,version,component_id) REFERENCES carl_rental_component(split_id,version,component_id)
);
CREATE TABLE carl_rent_due (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), property_id bigint NOT NULL REFERENCES carl_property(record_id),
 unit_id bigint REFERENCES carl_rental_unit(record_id), due_date date NOT NULL, amount numeric(20,4) CHECK(amount IS NULL OR amount>=0)
);
CREATE TABLE carl_rent_application (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), rent_due_id bigint NOT NULL REFERENCES carl_rent_due(record_id),
 split_id bigint NOT NULL, version integer NOT NULL, component_id text NOT NULL,
 amount numeric(20,4) NOT NULL CHECK(amount>0), active boolean NOT NULL DEFAULT true,
 created_by bigint NOT NULL REFERENCES carl_member(id),
 FOREIGN KEY(split_id,version,component_id) REFERENCES carl_rental_component(split_id,version,component_id)
);
CREATE INDEX carl_rental_allocation_property ON carl_rental_allocation(property_id,split_id,version);
CREATE INDEX carl_rent_due_property ON carl_rent_due(property_id,due_date);
CREATE INDEX carl_rent_application_receipt ON carl_rent_application(split_id,version,component_id) WHERE active;
CREATE INDEX carl_rent_application_due ON carl_rent_application(rent_due_id) WHERE active;

CREATE VIEW carl_rental_property_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,p.locality,p.ownership_share,p.asset_account_id,p.debt_account_id,
 p.legal_owner,p.market_value,p.valuation_date,p.currency,p.acquisition_date,p.acquisition_basis,p.land_basis,p.building_basis,p.basis_evidence
 FROM carl_access a JOIN carl_property p ON p.record_id=a.id WHERE a.details
 AND (p.asset_account_id IS NULL OR EXISTS(SELECT 1 FROM carl_account_view av WHERE av.id=p.asset_account_id AND av.principal=a.principal))
 AND (p.debt_account_id IS NULL OR EXISTS(SELECT 1 FROM carl_account_view dv WHERE dv.id=p.debt_account_id AND dv.principal=a.principal));
CREATE VIEW carl_rental_unit_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,u.property_id,u.unit_label,u.scheduled_rent,u.currency,u.lease_start,u.lease_end,u.occupancy
 FROM carl_access a JOIN carl_rental_unit u ON u.record_id=a.id WHERE a.details
 AND EXISTS(SELECT 1 FROM carl_rental_property_view p WHERE p.id=u.property_id AND p.principal=a.principal);
CREATE VIEW carl_rental_source_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,s.transaction_id,s.current_version,
 v.source_amount,v.source_date,v.currency,v.transaction_revision,v.input_hash,
 (v.transaction_revision<>tr.revision) AS source_stale
 FROM carl_access a JOIN carl_rental_split s ON s.record_id=a.id
 JOIN carl_rental_split_version v ON v.split_id=s.record_id AND v.version=s.current_version
 JOIN carl_record tr ON tr.id=s.transaction_id WHERE a.details
 AND EXISTS(SELECT 1 FROM carl_transaction_view t WHERE t.id=s.transaction_id AND t.principal=a.principal)
 AND NOT EXISTS(SELECT 1 FROM carl_rental_allocation x WHERE x.split_id=s.record_id AND x.version=s.current_version
 AND NOT EXISTS(SELECT 1 FROM carl_rental_property_view p WHERE p.id=x.property_id AND p.principal=a.principal));
CREATE VIEW carl_rent_due_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,d.property_id,d.unit_id,d.due_date,d.amount,p.currency
 FROM carl_access a JOIN carl_rent_due d ON d.record_id=a.id
 JOIN carl_rental_property_view p ON p.id=d.property_id AND p.principal=a.principal WHERE a.details
 AND (d.unit_id IS NULL OR EXISTS(SELECT 1 FROM carl_rental_unit_view u WHERE u.id=d.unit_id AND u.principal=a.principal));
CREATE VIEW carl_rent_application_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,x.rent_due_id,x.split_id,x.version,x.component_id,x.amount,x.active,d.property_id
 FROM carl_access a JOIN carl_rent_application x ON x.record_id=a.id
 JOIN carl_rent_due_view d ON d.id=x.rent_due_id AND d.principal=a.principal
 JOIN carl_rental_source_view s ON s.id=x.split_id AND s.principal=a.principal WHERE a.details;
CREATE VIEW carl_rental_allocation_view AS
 SELECT s.id,s.principal,s.current_version,c.component_id,c.kind,c.amount,x.property_id,x.fraction,c.outside_fraction
 FROM carl_rental_source_view s JOIN carl_rental_component c ON c.split_id=s.id AND c.version=s.current_version
 JOIN carl_rental_allocation x ON x.split_id=c.split_id AND x.version=c.version AND x.component_id=c.component_id;

-- Relinking a property's source accounts changes access dependencies and invalidates saved scopes.
CREATE TRIGGER carl_property_asset_epoch AFTER UPDATE OF asset_account_id ON carl_property
 FOR EACH ROW WHEN(OLD.asset_account_id IS DISTINCT FROM NEW.asset_account_id) EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_property_debt_epoch AFTER UPDATE OF debt_account_id ON carl_property
 FOR EACH ROW WHEN(OLD.debt_account_id IS DISTINCT FROM NEW.debt_account_id) EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_rental_dependency_epoch AFTER UPDATE OF current_version ON carl_rental_split
 FOR EACH ROW WHEN(OLD.current_version IS DISTINCT FROM NEW.current_version) EXECUTE FUNCTION carl_bump_permission_epoch();
-- Raw/version tables have no generic QQQ write or read grant; administration uses scoped views/processes.
