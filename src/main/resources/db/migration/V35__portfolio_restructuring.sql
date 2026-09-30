-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_portfolio_move (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), offer_id bigint NOT NULL REFERENCES carl_financing_offer(record_id),
 offer_revision bigint NOT NULL, as_of date NOT NULL, capacity numeric(20,4) NOT NULL CHECK(capacity>0),
 minimum_amount numeric(20,4) NOT NULL CHECK(minimum_amount>=0), minimum_fraction numeric(16,12) NOT NULL CHECK(minimum_fraction BETWEEN 0 AND 1),
 monthly_fee numeric(20,4) NOT NULL CHECK(monthly_fee>=0), first_payment date NOT NULL,
 proposed_payment numeric(20,4) NOT NULL CHECK(proposed_payment>=0), reviewed_by bigint NOT NULL REFERENCES carl_member(id),
 CHECK(minimum_amount>0 OR minimum_fraction>0), CHECK(first_payment>=as_of)
);
CREATE TABLE carl_portfolio_allocation (
 move_id bigint NOT NULL REFERENCES carl_portfolio_move(record_id), account_id bigint NOT NULL REFERENCES carl_account(record_id),
 account_revision bigint NOT NULL, amount numeric(20,4) NOT NULL CHECK(amount>0), PRIMARY KEY(move_id,account_id)
);
CREATE VIEW carl_portfolio_move_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,m.offer_id,m.offer_revision,m.as_of,m.capacity,m.minimum_amount,
 m.minimum_fraction,m.monthly_fee,m.first_payment,m.proposed_payment,m.reviewed_by,
 (o.revision<>m.offer_revision OR EXISTS(SELECT 1 FROM carl_portfolio_allocation x JOIN carl_record r ON r.id=x.account_id WHERE x.move_id=m.record_id AND r.revision<>x.account_revision)) AS source_stale
 FROM carl_access a JOIN carl_portfolio_move m ON m.record_id=a.id
 JOIN carl_financing_offer_view o ON o.id=m.offer_id AND o.principal=a.principal
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_portfolio_allocation x WHERE x.move_id=m.record_id AND NOT EXISTS(SELECT 1 FROM carl_account_view v WHERE v.id=x.account_id AND v.principal=a.principal));
-- Move records and allocations are immutable reviewed assumptions; corrections create a new move.
-- Only narrow application services write these tables; generic QQQ CRUD is unavailable.
