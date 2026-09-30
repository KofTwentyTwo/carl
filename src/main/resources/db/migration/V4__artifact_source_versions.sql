-- Copyright (C) 2026 KofTwentyTwo
CREATE OR REPLACE VIEW carl_vendor_view AS SELECT a.id,a.principal,a.title,a.evidence,v.category,v.contact,v.contact_verified,a.revision
 FROM carl_access a JOIN carl_vendor v ON v.record_id=a.id WHERE a.details;
CREATE OR REPLACE VIEW carl_work_view AS SELECT a.id,a.principal,a.title,a.evidence,w.vendor_id,w.status,w.assigned_member,w.follow_up,w.commitment_evidence,a.revision
 FROM carl_access a JOIN carl_work_item w ON w.record_id=a.id WHERE a.details;
CREATE OR REPLACE VIEW carl_calendar_view AS SELECT a.id,a.principal,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.title ELSE 'Busy' END AS title,
 CASE WHEN a.details AND NOT e.free_busy_only THEN a.evidence ELSE 'Event details restricted' END AS evidence,
 e.start_at,e.end_at,e.source_zone,e.all_day_start,e.all_day_end_exclusive,e.cancelled,e.transparent,
 c.last_success,c.sync_state,a.revision
 FROM carl_access a JOIN carl_calendar_event e ON e.record_id=a.id JOIN carl_calendar_connection c ON c.record_id=e.connection_id;
