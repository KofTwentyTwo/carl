-- Copyright (C) 2026 KofTwentyTwo
CREATE VIEW carl_calendar_operation_view AS SELECT o.request_id AS id,p.principal,p.title,
 m.plan_id,m.step_id,m.component,o.plan_version,o.action,o.status,o.diagnostic,o.updated_at,
 m.last_success,m.read_state,m.last_read_success,m.retired
 FROM carl_plan_view p JOIN carl_calendar_mapping m ON m.plan_id=p.id JOIN carl_calendar_operation o ON o.step_id=m.step_id;
