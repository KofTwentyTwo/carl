-- Copyright (C) 2026 KofTwentyTwo
CREATE VIEW carl_calendar_connection_view AS
 SELECT a.id,a.principal,a.title,a.evidence,a.revision,c.provider,c.calendar_identity,c.sync_state,c.last_success,c.last_attempt,c.failure_code
 FROM carl_access a JOIN carl_calendar_connection c ON c.record_id=a.id WHERE a.details;
