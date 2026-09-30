-- Copyright (C) 2026 KofTwentyTwo
DO $$
DECLARE prior_definition text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO STRICT prior_definition FROM pg_constraint
 WHERE conrelid='carl_client_workflow'::regclass AND conname='carl_client_workflow_kind_check';
 ALTER TABLE carl_client_workflow DROP CONSTRAINT carl_client_workflow_kind_check;
 EXECUTE 'ALTER TABLE carl_client_workflow ADD CONSTRAINT carl_client_workflow_kind_check CHECK (' ||
 substring(prior_definition FROM 7) || ' OR kind IN (''balance-sheet'',''rental-stress-report''))';
END $$;
