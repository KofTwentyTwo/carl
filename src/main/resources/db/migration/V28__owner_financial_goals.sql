-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_financial_goal (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),
 goal_type text NOT NULL CHECK(goal_type IN ('DEBT_FREEDOM','RENTAL_TAX','INVESTMENT','OTHER')),
 priority integer NOT NULL CHECK(priority BETWEEN 1 AND 100), selected_by bigint NOT NULL REFERENCES carl_member(id),
 investment_stage_selected boolean NOT NULL DEFAULT false, horizon_months integer CHECK(horizon_months BETWEEN 1 AND 600),
 risk_context text, currency char(3), reserve_need numeric(20,4) CHECK(reserve_need>=0),
 tax_context_supplied boolean NOT NULL DEFAULT false, holdings_complete boolean NOT NULL DEFAULT false, costs_complete boolean NOT NULL DEFAULT false,
 context_evidence text, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE VIEW carl_financial_goal_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,
 g.goal_type,g.priority,g.investment_stage_selected,g.horizon_months,g.risk_context,g.currency,g.reserve_need,
 g.tax_context_supplied,g.holdings_complete,g.costs_complete,g.context_evidence,g.updated_at
 FROM carl_access a JOIN carl_financial_goal g ON g.record_id=a.id WHERE a.details;
