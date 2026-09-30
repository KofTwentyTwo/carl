-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_debt_terms ADD COLUMN principal_balance numeric(20,4) CHECK(principal_balance>=0);
ALTER TABLE carl_debt_terms ADD COLUMN balance_as_of date;
ALTER TABLE carl_debt_terms ADD COLUMN minimum_fraction numeric(12,10) NOT NULL DEFAULT 0 CHECK(minimum_fraction>=0 AND minimum_fraction<=1);
CREATE VIEW carl_debt_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,c.currency,d.principal_balance,d.balance_as_of,d.minimum_amount,d.minimum_fraction,d.evidence AS terms_evidence
 FROM carl_access a JOIN carl_account c ON c.record_id=a.id JOIN carl_debt_terms d ON d.account_id=a.id WHERE a.details;
