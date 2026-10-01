--
-- Copyright (C) 2026 KofTwentyTwo
--

CREATE TABLE carl_financing_offer (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), kind text NOT NULL CHECK(kind IN ('CONSOLIDATION','REFINANCE','BALANCE_TRANSFER','PURCHASE_FINANCE')),
 currency char(3) NOT NULL, principal numeric(20,4) NOT NULL CHECK(principal>0), financed_fee numeric(20,4) NOT NULL CHECK(financed_fee>=0), cash_fee numeric(20,4) NOT NULL CHECK(cash_fee>=0),
 monthly_payment numeric(20,4) NOT NULL CHECK(monthly_payment>0), term_months integer NOT NULL CHECK(term_months BETWEEN 1 AND 600),
 initial_apr numeric(16,12) NOT NULL CHECK(initial_apr>=0), post_promo_apr numeric(16,12) NOT NULL CHECK(post_promo_apr>=0),
 promotion text NOT NULL CHECK(promotion IN ('NONE','TRUE_ZERO','DEFERRED_INTEREST')), promo_months integer NOT NULL CHECK(promo_months BETWEEN 0 AND 599),
 deferred_apr numeric(16,12) NOT NULL CHECK(deferred_apr>=0), allocation_confirmed boolean NOT NULL,
 evidence_class text NOT NULL CHECK(evidence_class IN ('HYPOTHETICAL','VERIFIED_TERMS')), as_of date NOT NULL, expires_on date NOT NULL,
 first_payment date NOT NULL, collateral_terms text NOT NULL, entered_by bigint NOT NULL REFERENCES carl_member(id)
);
CREATE VIEW carl_financing_offer_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,
 o.kind,o.currency,o.principal AS financed_principal,o.financed_fee,o.cash_fee,o.monthly_payment,o.term_months,
 o.initial_apr,o.post_promo_apr,o.promotion,o.promo_months,o.deferred_apr,o.allocation_confirmed,o.evidence_class,
 o.as_of,o.expires_on,o.first_payment,o.collateral_terms
 FROM carl_access a JOIN carl_financing_offer o ON o.record_id=a.id WHERE a.details;
