--
-- Copyright (C) 2026 KofTwentyTwo
--

ALTER TABLE carl_client_workflow DROP CONSTRAINT carl_client_workflow_kind_check;
ALTER TABLE carl_client_workflow ADD CONSTRAINT carl_client_workflow_kind_check CHECK(kind IN ('report','draft','debt-comparison','purchase-assessment','offer-comparison'));
