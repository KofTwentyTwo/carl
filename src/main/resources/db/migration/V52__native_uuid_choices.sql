-- Copyright (C) 2026 KofTwentyTwo
-- Native QQQ STRING keys require JDBC text values for possible-value label translation.
CREATE VIEW carl_native_plan_step_view AS
 SELECT id::text AS id,principal,title,plan_id,assignee_id,due_date,location,dependency_id::text AS dependency_id,status,checkin,evidence_record_id,updated_at
 FROM carl_plan_step_view;
CREATE VIEW carl_native_calendar_operation_view AS
 SELECT id::text AS id,principal,title,plan_id,step_id::text AS step_id,component,plan_version,action,status,diagnostic,updated_at,last_success,read_state,last_read_success,retired
 FROM carl_calendar_operation_view;
