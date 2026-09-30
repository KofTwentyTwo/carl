-- Copyright (C) 2026 KofTwentyTwo
CREATE TABLE carl_expense (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id), currency char(3) NOT NULL,
 cadence text NOT NULL CHECK(cadence IN ('ONCE','WEEKLY','BIWEEKLY','MONTHLY','QUARTERLY','ANNUAL')),
 first_due date NOT NULL,last_due date,base_amount numeric(20,4) CHECK(base_amount IS NULL OR base_amount>=0),
 kind text NOT NULL CHECK(kind IN ('EXPENSE','RESERVE_EARMARK')),
 basis text NOT NULL CHECK(basis IN ('COMMITTED','ESTIMATED','HYPOTHETICAL')),
 property_id bigint REFERENCES carl_property(record_id),CHECK(last_due IS NULL OR last_due>=first_due)
);
CREATE TABLE carl_expense_season (
 expense_id bigint NOT NULL REFERENCES carl_expense(record_id),month integer NOT NULL CHECK(month BETWEEN 1 AND 12),
 amount numeric(20,4) NOT NULL CHECK(amount>=0),PRIMARY KEY(expense_id,month)
);
CREATE TABLE carl_expense_actual (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),transaction_id bigint UNIQUE REFERENCES carl_transaction(record_id),
 transaction_revision bigint,paid_date date NOT NULL,currency char(3) NOT NULL,amount numeric(20,4) NOT NULL CHECK(amount>0),
 kind text NOT NULL CHECK(kind IN ('EXPENSE','RESERVE_EARMARK')),
 CHECK((transaction_id IS NULL)=(transaction_revision IS NULL))
);
CREATE TABLE carl_expense_settlement (
 record_id bigint PRIMARY KEY REFERENCES carl_record(id),expense_id bigint NOT NULL REFERENCES carl_expense(record_id),
 due_date date NOT NULL,actual_id bigint NOT NULL REFERENCES carl_expense_actual(record_id),
 amount numeric(20,4) NOT NULL CHECK(amount>0),active boolean NOT NULL DEFAULT true
);
CREATE VIEW carl_expense_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,e.currency,e.cadence,e.first_due,e.last_due,e.base_amount,e.kind,e.basis,e.property_id
 FROM carl_access a JOIN carl_expense e ON e.record_id=a.id WHERE a.details
 AND(e.property_id IS NULL OR EXISTS(SELECT 1 FROM carl_rental_property_view p WHERE p.id=e.property_id AND p.principal=a.principal));
CREATE VIEW carl_expense_actual_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,x.transaction_id,x.transaction_revision,x.paid_date,x.currency,x.amount,x.kind,
 (x.transaction_id IS NOT NULL AND(r.revision IS DISTINCT FROM x.transaction_revision OR t.amount IS DISTINCT FROM -x.amount OR t.effective_date IS DISTINCT FROM x.paid_date OR t.currency IS DISTINCT FROM x.currency)) AS source_stale
 FROM carl_access a JOIN carl_expense_actual x ON x.record_id=a.id
 LEFT JOIN carl_transaction_view t ON t.id=x.transaction_id AND t.principal=a.principal
 LEFT JOIN carl_record r ON r.id=x.transaction_id WHERE a.details AND(x.transaction_id IS NULL OR t.id IS NOT NULL);
CREATE VIEW carl_expense_settlement_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,s.expense_id,s.due_date,s.actual_id,s.amount,s.active
 FROM carl_access a JOIN carl_expense_settlement s ON s.record_id=a.id
 JOIN carl_expense_view e ON e.id=s.expense_id AND e.principal=a.principal
 JOIN carl_expense_actual_view x ON x.id=s.actual_id AND x.principal=a.principal WHERE a.details;
CREATE TABLE carl_cash_expense_selection (
 record_id bigint NOT NULL REFERENCES carl_cash_plan(record_id),expense_id bigint NOT NULL REFERENCES carl_expense(record_id),
 PRIMARY KEY(record_id,expense_id)
);
CREATE TABLE carl_cash_expense_actual_selection (
 record_id bigint NOT NULL REFERENCES carl_cash_plan(record_id),actual_id bigint NOT NULL REFERENCES carl_expense_actual(record_id),
 PRIMARY KEY(record_id,actual_id)
);
CREATE TRIGGER carl_expense_property_epoch AFTER UPDATE OF property_id ON carl_expense
 FOR EACH ROW WHEN(OLD.property_id IS DISTINCT FROM NEW.property_id) EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_expense_selection_epoch AFTER INSERT OR DELETE ON carl_cash_expense_selection FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_expense_actual_selection_epoch AFTER INSERT OR DELETE ON carl_cash_expense_actual_selection FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
-- Raw tables are not generic QQQ read/write targets. Services check source dependencies.
