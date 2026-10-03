-- Copyright (C) 2026 KofTwentyTwo
-- Source observations may be stored before their economic identity is qualified.
ALTER TABLE carl_account DROP CONSTRAINT carl_account_kind_check;
ALTER TABLE carl_account ADD CONSTRAINT carl_account_kind_check
 CHECK(kind IN ('CASH','CREDIT_CARD','LOAN','INVESTMENT','OTHER_ASSET','UNCLASSIFIED'));
ALTER TABLE carl_account ALTER COLUMN liquid DROP NOT NULL;
ALTER TABLE carl_account ALTER COLUMN ownership_share DROP NOT NULL;
ALTER TABLE carl_account ADD COLUMN review_state text NOT NULL DEFAULT 'CONFIRMED'
 CHECK(review_state IN ('CONFIRMED','NEEDS_REVIEW'));
ALTER TABLE carl_account ADD CONSTRAINT carl_account_qualification_check CHECK(
 (review_state='CONFIRMED' AND kind<>'UNCLASSIFIED' AND liquid IS NOT NULL AND ownership_share IS NOT NULL)
 OR (review_state='NEEDS_REVIEW' AND kind='UNCLASSIFIED' AND liquid IS NULL AND ownership_share IS NULL));
CREATE OR REPLACE VIEW carl_account_view AS
 SELECT a.id,a.principal,a.title,a.evidence,c.kind,c.currency,c.institution,c.liquid,c.ownership_share,
 b.amount AS balance,b.as_of,b.basis,c.review_state
 FROM carl_access a JOIN carl_account c ON c.record_id=a.id
 LEFT JOIN LATERAL (SELECT * FROM carl_balance b WHERE b.account_id=c.record_id ORDER BY as_of DESC,id DESC LIMIT 1) b ON true
 WHERE a.details;
