-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_calendar_mapping ADD CONSTRAINT carl_calendar_plan_fk FOREIGN KEY(plan_id) REFERENCES carl_plan(record_id);
ALTER TABLE carl_calendar_mapping ADD CONSTRAINT carl_calendar_step_fk FOREIGN KEY(step_id) REFERENCES carl_plan_step(id);
