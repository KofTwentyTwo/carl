-- Copyright (C) 2026 KofTwentyTwo
CREATE VIEW carl_member_view AS
 SELECT member.id,actor.principal,member.label AS title,member.active
 FROM carl_member actor JOIN carl_member member ON member.household_id=actor.household_id
 WHERE actor.active AND member.active;
