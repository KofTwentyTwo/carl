-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_client_workflow ADD COLUMN plan_id bigint REFERENCES carl_plan(record_id);
ALTER TABLE carl_client_workflow ADD COLUMN plan_result text CHECK(plan_result IS NULL OR length(plan_result)<=524288);
ALTER TABLE carl_client_workflow ADD COLUMN export_id uuid REFERENCES carl_plan_export(id);
ALTER TABLE carl_client_workflow DROP CONSTRAINT carl_client_workflow_kind_check;
ALTER TABLE carl_client_workflow ADD CONSTRAINT carl_client_workflow_kind_check CHECK(kind IN ('report','draft','debt-comparison','purchase-assessment','offer-comparison','rental-report','tax-packet','plan-create','plan-step','plan-agree','plan-check-in','plan-export'));
