-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_rental_shock (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 baseline_artifact_id bigint NOT NULL REFERENCES carl_artifact(record_id),
 property_id bigint NOT NULL REFERENCES carl_property(record_id),
 expected_rent numeric(20,4) NOT NULL CHECK(expected_rent>=0),
 vacancy_fraction numeric(12,10) NOT NULL CHECK(vacancy_fraction>=0 AND vacancy_fraction<=1),
 repair_amount numeric(20,4) NOT NULL CHECK(repair_amount>=0),
 additional_annual_rate numeric(12,10) NOT NULL CHECK(additional_annual_rate>=0 AND additional_annual_rate<=5),
 allocated_principal numeric(20,4) NOT NULL CHECK(allocated_principal>=0),
 balance_as_of date NOT NULL, assumptions text NOT NULL CHECK(length(assumptions) BETWEEN 1 AND 4000),
 created_by bigint NOT NULL REFERENCES carl_member(id)
);
CREATE VIEW carl_rental_shock_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,s.baseline_artifact_id,s.property_id,s.expected_rent,s.vacancy_fraction,s.repair_amount,s.additional_annual_rate,s.allocated_principal,s.balance_as_of,s.assumptions,s.created_by,p.currency
 FROM carl_access a JOIN carl_rental_shock s ON s.record_id=a.id
 JOIN carl_rental_property_view p ON p.id=s.property_id AND p.principal=a.principal
 JOIN carl_artifact_view f ON f.id=s.baseline_artifact_id AND f.principal=a.principal
 WHERE a.details;
CREATE INDEX carl_balance_dated_lookup ON carl_balance(account_id,as_of DESC);
