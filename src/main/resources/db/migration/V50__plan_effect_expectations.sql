-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_plan_effect (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 plan_id bigint NOT NULL REFERENCES carl_plan(record_id), plan_version integer NOT NULL CHECK(plan_version>0),
 step_id uuid NOT NULL REFERENCES carl_plan_step(id), step_title text NOT NULL,
 effect_kind text NOT NULL CHECK(effect_kind IN ('CASH_PAYMENT','TRANSFER','PRINCIPAL_REDUCTION')),
 account_id bigint NOT NULL REFERENCES carl_account(record_id), currency char(3) NOT NULL,
 expected_amount numeric(20,4) NOT NULL CHECK(expected_amount>0),
 period_start date NOT NULL, period_end date NOT NULL CHECK(period_end>=period_start),
 opening_principal numeric(20,4), opening_date date, opening_evidence text,
 actor_id bigint NOT NULL REFERENCES carl_member(id), rationale text NOT NULL CHECK(length(rationale) BETWEEN 1 AND 2000)
);
CREATE VIEW carl_plan_effect_view AS
 SELECT a.id,a.principal,a.title,a.evidence,e.plan_id,e.plan_version,e.step_id,e.step_title,e.effect_kind,
 e.account_id,e.currency,e.expected_amount,e.period_start,e.period_end,e.opening_principal,e.opening_date,e.opening_evidence,e.actor_id,e.rationale,
 p.version AS current_plan_version,p.state AS current_plan_state,p.source_stale
 FROM carl_access a JOIN carl_plan_effect e ON e.record_id=a.id
 JOIN carl_plan_view p ON p.id=e.plan_id AND p.principal=a.principal
 JOIN carl_account_view account ON account.id=e.account_id AND account.principal=a.principal WHERE a.details;
CREATE FUNCTION carl_plan_effect_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Plan expectations are immutable; create a new attributed expectation'; END;
$$;
CREATE TRIGGER carl_plan_effect_no_update BEFORE UPDATE ON carl_plan_effect FOR EACH ROW EXECUTE FUNCTION carl_plan_effect_immutable();
