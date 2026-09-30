-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_manual_transaction_origin(
 transaction_id bigint PRIMARY KEY REFERENCES carl_transaction(record_id), request_id uuid NOT NULL UNIQUE,
 member_id bigint NOT NULL REFERENCES carl_member(id), account_id bigint NOT NULL REFERENCES carl_account(record_id),
 effective_date date NOT NULL, amount numeric(20,4) NOT NULL, currency char(3) NOT NULL,
 classification text NOT NULL, category text NOT NULL, evidence text NOT NULL, entered_at timestamptz NOT NULL DEFAULT now()
);
CREATE OR REPLACE VIEW carl_budget_view AS SELECT a.id,a.principal,a.title,a.evidence,b.category,b.period_start,b.period_end,b.amount,b.currency,a.revision
 FROM carl_access a JOIN carl_budget b ON b.record_id=a.id WHERE a.details;
CREATE VIEW carl_manual_transaction_view AS SELECT t.*,o.member_id AS entered_by,o.entered_at,o.evidence AS original_evidence,o.amount AS original_amount,o.classification AS original_classification
 FROM carl_transaction_view t JOIN carl_manual_transaction_origin o ON o.transaction_id=t.id;
