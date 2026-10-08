-- Copyright (C) 2026 KofTwentyTwo
-- Public-safe reason a client workflow ended FAILED or UNKNOWN; lowercase identifiers so new reasons need no migration.
ALTER TABLE carl_client_workflow ADD COLUMN failure_code text
 CHECK (failure_code IS NULL OR (status IN ('FAILED','UNKNOWN') AND failure_code ~ '^[a-z_]{1,32}$'));
