-- Copyright (C) 2026 KofTwentyTwo
CREATE FUNCTION carl_artifact_audience_insert_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM carl_record WHERE id=NEW.artifact_id AND created_at=transaction_timestamp()) THEN
  UPDATE carl_household SET permission_revision=permission_revision+1 WHERE id=(SELECT household_id FROM carl_record WHERE id=NEW.artifact_id);
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER carl_artifact_audience_insert_epoch AFTER INSERT ON carl_artifact_audience FOR EACH ROW EXECUTE FUNCTION carl_artifact_audience_insert_epoch();
