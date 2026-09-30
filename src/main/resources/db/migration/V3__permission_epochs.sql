-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_household ADD COLUMN permission_revision bigint NOT NULL DEFAULT 1;
CREATE FUNCTION carl_bump_permission_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE household bigint;
BEGIN
 IF TG_TABLE_NAME='carl_member' THEN
  household=coalesce(NEW.household_id,OLD.household_id);
 ELSIF TG_TABLE_NAME='carl_record' THEN
  IF NEW.visibility IS NOT DISTINCT FROM OLD.visibility AND NEW.owner_id=OLD.owner_id AND NEW.domain=OLD.domain THEN RETURN NEW; END IF;
  household=NEW.household_id;
 ELSIF TG_TABLE_NAME='carl_permission' THEN
  SELECT household_id INTO household FROM carl_member WHERE id=coalesce(NEW.member_id,OLD.member_id);
 ELSE
  IF TG_OP='INSERT' AND EXISTS(SELECT 1 FROM carl_artifact WHERE record_id=NEW.record_id) THEN RETURN NEW; END IF;
  SELECT household_id INTO household FROM carl_record WHERE id=coalesce(NEW.record_id,OLD.record_id);
 END IF;
 UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=household;
 RETURN coalesce(NEW,OLD);
END;
$$;
CREATE TRIGGER carl_member_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_member FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_permission_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_permission FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_grant_epoch AFTER INSERT OR UPDATE OR DELETE ON carl_grant FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
CREATE TRIGGER carl_record_epoch AFTER UPDATE OF visibility,owner_id,domain ON carl_record FOR EACH ROW EXECUTE FUNCTION carl_bump_permission_epoch();
