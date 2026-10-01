-- Copyright (C) 2026 KofTwentyTwo
-- Profiles decorate authoritative property/account records; missing profiles remain UNKNOWN.
CREATE TABLE carl_home_profile (
 property_id bigint PRIMARY KEY REFERENCES carl_property(record_id),
 property_use text NOT NULL CHECK(property_use IN ('UNKNOWN','PRIMARY_RESIDENCE','RENTAL','SECOND_HOME','OTHER_REAL_ESTATE')),
 mortgage_as_of date, mortgage_principal numeric(20,4), annual_rate numeric(12,8), payment_amount numeric(20,4),
 first_payment date, maturity_date date, amortization_months integer,
 rate_kind text NOT NULL CHECK(rate_kind IN ('UNKNOWN','FIXED','VARIABLE')),
 escrow_amount numeric(20,4), fee_amount numeric(20,4), assumptions text NOT NULL, profile_evidence text NOT NULL,
 updated_by bigint NOT NULL REFERENCES carl_member(id), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(mortgage_principal IS NULL OR mortgage_principal>=0), CHECK(annual_rate IS NULL OR annual_rate BETWEEN 0 AND 10),
 CHECK(payment_amount IS NULL OR payment_amount>=0), CHECK(escrow_amount IS NULL OR escrow_amount>=0), CHECK(fee_amount IS NULL OR fee_amount>=0),
 CHECK(amortization_months IS NULL OR amortization_months BETWEEN 1 AND 1200),
 CHECK(first_payment IS NULL OR maturity_date IS NULL OR first_payment<=maturity_date)
);
CREATE TABLE carl_home_history (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, property_id bigint NOT NULL REFERENCES carl_property(record_id),
 property_revision bigint NOT NULL, actor_id bigint NOT NULL REFERENCES carl_member(id),
 asset_account_id bigint REFERENCES carl_account(record_id), debt_account_id bigint REFERENCES carl_account(record_id),
 snapshot text NOT NULL, evidence text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(property_id,property_revision)
);
CREATE VIEW carl_home_view AS
 SELECT p.*,coalesce(h.property_use,'UNKNOWN') AS property_use,h.mortgage_as_of,h.mortgage_principal,h.annual_rate,h.payment_amount,
 h.first_payment,h.maturity_date,h.amortization_months,coalesce(h.rate_kind,'UNKNOWN') AS rate_kind,h.escrow_amount,h.fee_amount,
 h.assumptions,h.profile_evidence,h.updated_by,h.updated_at
 FROM carl_rental_property_view p LEFT JOIN carl_home_profile h ON h.property_id=p.id;
CREATE VIEW carl_home_history_view AS
 SELECT h.id,p.principal,p.title,h.evidence,h.property_id,h.property_revision,h.actor_id,h.snapshot,h.created_at
 FROM carl_home_view p JOIN carl_home_history h ON h.property_id=p.id
 WHERE (h.asset_account_id IS NULL OR EXISTS(SELECT 1 FROM carl_account_view a WHERE a.id=h.asset_account_id AND a.principal=p.principal))
 AND (h.debt_account_id IS NULL OR EXISTS(SELECT 1 FROM carl_account_view a WHERE a.id=h.debt_account_id AND a.principal=p.principal));
