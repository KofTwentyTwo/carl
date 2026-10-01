-- Copyright (C) 2026 KofTwentyTwo
-- Preserve all existing principal/detail checks; append the authoritative relationship currency.
CREATE OR REPLACE VIEW carl_portfolio_move_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,m.offer_id,m.offer_revision,m.as_of,m.capacity,m.minimum_amount,
 m.minimum_fraction,m.monthly_fee,m.first_payment,m.proposed_payment,m.reviewed_by,
 (o.revision<>m.offer_revision OR EXISTS(SELECT 1 FROM carl_portfolio_allocation x JOIN carl_record r ON r.id=x.account_id WHERE x.move_id=m.record_id AND r.revision<>x.account_revision)) AS source_stale,o.currency
 FROM carl_access a JOIN carl_portfolio_move m ON m.record_id=a.id
 JOIN carl_financing_offer_view o ON o.id=m.offer_id AND o.principal=a.principal
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_portfolio_allocation x WHERE x.move_id=m.record_id AND NOT EXISTS(SELECT 1 FROM carl_account_view v WHERE v.id=x.account_id AND v.principal=a.principal));
CREATE OR REPLACE VIEW carl_expense_settlement_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,s.expense_id,s.due_date,s.actual_id,s.amount,s.active,e.currency
 FROM carl_access a JOIN carl_expense_settlement s ON s.record_id=a.id
 JOIN carl_expense_view e ON e.id=s.expense_id AND e.principal=a.principal
 JOIN carl_expense_actual_view x ON x.id=s.actual_id AND x.principal=a.principal WHERE a.details;
CREATE OR REPLACE VIEW carl_rent_application_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,x.rent_due_id,x.split_id,x.version,x.component_id,x.amount,x.active,d.property_id,d.currency
 FROM carl_access a JOIN carl_rent_application x ON x.record_id=a.id
 JOIN carl_rent_due_view d ON d.id=x.rent_due_id AND d.principal=a.principal
 JOIN carl_rental_source_view s ON s.id=x.split_id AND s.principal=a.principal WHERE a.details;
