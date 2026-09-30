-- Copyright (C) 2026 KofTwentyTwo
-- Existing artifacts receive epoch zero and remain unavailable until regenerated.
ALTER TABLE carl_artifact ADD COLUMN permission_revision bigint NOT NULL DEFAULT 0;
CREATE OR REPLACE FUNCTION carl_bump_permission_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_household bigint; new_household bigint;
BEGIN
 IF TG_TABLE_NAME='carl_member' OR TG_TABLE_NAME='carl_record' THEN
  IF TG_OP<>'INSERT' THEN old_household=OLD.household_id; END IF;
  IF TG_OP<>'DELETE' THEN new_household=NEW.household_id; END IF;
 ELSIF TG_TABLE_NAME IN ('carl_permission','carl_identity') THEN
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_member WHERE id=OLD.member_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_member WHERE id=NEW.member_id; END IF;
 ELSIF TG_TABLE_NAME='carl_artifact_audience' THEN
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_record WHERE id=OLD.artifact_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_record WHERE id=NEW.artifact_id; END IF;
 ELSE
  IF TG_TABLE_NAME='carl_grant' AND TG_OP='INSERT' AND EXISTS(SELECT 1 FROM carl_artifact f JOIN carl_record r ON r.id=f.record_id WHERE f.record_id=NEW.record_id AND r.created_at=transaction_timestamp()) THEN RETURN NEW; END IF;
  IF TG_OP<>'INSERT' THEN SELECT household_id INTO old_household FROM carl_record WHERE id=OLD.record_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT household_id INTO new_household FROM carl_record WHERE id=NEW.record_id; END IF;
 END IF;
 UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=old_household OR id=new_household;
 RETURN coalesce(NEW,OLD);
END;
$$;
CREATE TRIGGER carl_identity_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_identity FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_record_delete_epoch BEFORE DELETE ON carl_record FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_record_household_epoch AFTER UPDATE OF household_id ON carl_record FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_transaction_account_epoch AFTER UPDATE OF account_id ON carl_transaction FOR EACH ROW WHEN(OLD.account_id IS DISTINCT FROM NEW.account_id) EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_calendar_details_epoch AFTER UPDATE OF free_busy_only ON carl_calendar_event FOR EACH ROW WHEN(OLD.free_busy_only IS DISTINCT FROM NEW.free_busy_only) EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_artifact_audience_epoch AFTER UPDATE OR DELETE ON carl_artifact_audience FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE OR REPLACE VIEW carl_artifact_view AS SELECT a.id,a.principal,a.title,a.created_at,f.kind,f.version,f.period_start,f.period_end,f.facts,f.narrative,f.limitations,f.narration_state,f.status_label,
 EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_record r ON r.id=s.source_id WHERE s.artifact_id=a.id AND s.source_revision<>r.revision) AS stale
 FROM carl_access a JOIN carl_artifact f ON f.record_id=a.id
 JOIN carl_household h ON h.id=a.household_id AND h.permission_revision=f.permission_revision
 JOIN carl_artifact_audience audience ON audience.artifact_id=a.id AND audience.member_id=a.member_id
 WHERE a.details AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access source WHERE source.id=s.source_id AND source.principal=a.principal AND source.details))
 AND NOT EXISTS(SELECT 1 FROM carl_artifact_source s JOIN carl_transaction t ON t.record_id=s.source_id WHERE s.artifact_id=a.id AND NOT EXISTS
 (SELECT 1 FROM carl_access account WHERE account.id=t.account_id AND account.principal=a.principal AND account.details));
