-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_cash_plan (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), currency char(3) NOT NULL,
 from_date date NOT NULL, through_date date NOT NULL,
 opening_cash numeric(20,4) NOT NULL, reserve_floor numeric(20,4) NOT NULL CHECK(reserve_floor>=0),
 discretionary_cap numeric(20,4) NOT NULL CHECK(discretionary_cap>=0),
 balances_resolved boolean NOT NULL, obligations_covered boolean NOT NULL, scope_complete boolean NOT NULL,
 reserve_confirmed boolean NOT NULL, selected_plans_included boolean NOT NULL, income_supported boolean NOT NULL,
 CHECK(through_date>=from_date AND through_date-from_date<366)
);
CREATE TABLE carl_cash_event (
 id uuid PRIMARY KEY, plan_id bigint NOT NULL REFERENCES carl_cash_plan(record_id),
 event_date date NOT NULL, signed_amount numeric(20,4) NOT NULL, title text NOT NULL,
 evidence text NOT NULL, created_by bigint NOT NULL REFERENCES carl_member(id)
);
CREATE VIEW carl_cash_plan_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,
 p.currency,p.from_date,p.through_date,p.opening_cash,p.reserve_floor,p.discretionary_cap,
 p.balances_resolved,p.obligations_covered,p.scope_complete,p.reserve_confirmed,p.selected_plans_included,p.income_supported
 FROM carl_access a JOIN carl_cash_plan p ON p.record_id=a.id WHERE a.details;
