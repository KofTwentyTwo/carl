-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_debt_payment_profile (
 account_id bigint PRIMARY KEY REFERENCES carl_debt_terms(account_id),
 as_of date NOT NULL, first_payment_date date NOT NULL,
 current_payment numeric(20,4) NOT NULL CHECK(current_payment>=0),
 proposed_payment numeric(20,4) NOT NULL CHECK(proposed_payment>=0),
 rollover text NOT NULL CHECK(rollover IN ('AVALANCHE','SNOWBALL','MINIMUM_ONLY')),
 evidence text NOT NULL CHECK(length(evidence) BETWEEN 1 AND 4000),
 updated_by bigint NOT NULL REFERENCES carl_member(id), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(first_payment_date>=as_of AND first_payment_date<=as_of+interval '1 month')
);
