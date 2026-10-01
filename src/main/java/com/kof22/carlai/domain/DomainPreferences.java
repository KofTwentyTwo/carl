/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Explicit presentation preferences never override domain permissions or execution policy. */
public final class DomainPreferences
{
   private static final Map<String, Set<String>> ALLOWED = Map.of("REPORT_DETAIL", Set.of("BRIEF", "STANDARD", "DETAILED"), "REPORT_PERIOD", Set.of("DAILY", "WEEKLY", "MONTHLY"));
   private final CarlService service;
   /** Shares Carl's trusted principal mapping and state. */
   public DomainPreferences(CarlService service)
   {
      this.service = service;
   }



   /** Stores an explicit self-owned or manager-authorized household choice with full history. */
   public long set(String principal, UUID request, String scope, String key, String value, String evidence)
   {
      if(!Set.of("MEMBER", "HOUSEHOLD").contains(scope) || !valid(scope, key, value))
      {
         throw new IllegalArgumentException("Only supported, explicit presentation preferences are allowed");
      }
      CarlService.bounded(evidence, 2000, "preference provenance");
      String digest = BillCsv.hash(CarlService.json(Map.of("scope", scope, "key", key, "value", value, "evidence", evidence)));
      return service.transaction(c ->
      {
         var initial = CarlService.member(c, principal);
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
         var actor = authorized(c, principal);
         if(actor.householdId() != initial.householdId())
         {
            throw new SecurityException("Membership changed");
         }
         if(scope.equals("HOUSEHOLD"))
         {
            CarlService.manager(c, principal, "SETTINGS");
         }
         Long prior = CarlService.request(c, actor, request, "DOMAIN_PREFERENCE", digest);
         if(prior != null)
         {
            BudgetRecords.require(c, principal, "carl_preference_view", prior);
            return prior;
         }
         Long owner = scope.equals("MEMBER") ? actor.id() : null;
         var existing = CarlService.rows(c, "SELECT p.record_id,r.revision FROM carl_preference_binding b JOIN carl_preference p ON p.record_id=b.record_id JOIN carl_record r ON r.id=p.record_id WHERE b.household_id=? AND b.scope=? AND b.member_id IS NOT DISTINCT FROM ? AND p.preference_key=?", actor.householdId(), scope, owner, key);
         long id;
         long version;
         if(existing.isEmpty())
         {
            id = CarlService.record(c, actor, "SETTINGS", scope.equals("MEMBER") ? "PRIVATE" : "FAMILY", key, evidence);
            version = 1;
            CarlService.execute(c, "INSERT INTO carl_preference(record_id,preference_key,value) VALUES(?,?,?)", id, key, value);
            CarlService.execute(c, "INSERT INTO carl_preference_binding(record_id,household_id,scope,member_id,preference_key) VALUES(?,?,?,?,?)", id, actor.householdId(), scope, owner, key);
         }
         else
         {
            id = CarlService.number(existing.getFirst(), "record_id");
            BudgetRecords.require(c, principal, "carl_preference_view", id);
            version = CarlService.number(existing.getFirst(), "revision") + 1;
            CarlService.execute(c, "UPDATE carl_preference SET value=? WHERE record_id=?", value, id);
            CarlService.execute(c, "UPDATE carl_record SET evidence=?,revision=revision+1 WHERE id=?", evidence, id);
         }
         CarlService.execute(c, "INSERT INTO carl_preference_history(record_id,version,member_id,value,evidence) VALUES(?,?,?,?,?)", id, version, actor.id(), value, evidence);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Explicit presentation preference; no authority or policy change");
         return id;
      });
   }



   /** Returns only current household choices and the caller's explicit overrides, with no invented defaults. */
   public Map<String, String> effective(String principal)
   {
      return service.transaction(c ->
      {
         var actor = authorized(c, principal);
         var rows = CarlService.rows(c, "SELECT preference_key,value,scope FROM carl_preference_view WHERE principal=? AND (scope='HOUSEHOLD' OR member_id=?) ORDER BY CASE WHEN scope='HOUSEHOLD' THEN 0 ELSE 1 END", principal, actor.id());
         var result = new LinkedHashMap<String, String>();
         for(var row : rows)
         {
            String key = row.get("preference_key").toString();
            String value = row.get("value").toString();
            if(valid(row.get("scope").toString(), key, value))
            {
               result.put(key, value);
            }
         }
         return Map.copyOf(result);
      });
   }



   private static boolean valid(String scope, String key, String value)
   {
      if(value == null || key == null)
      {
         return false;
      }
      if(ALLOWED.getOrDefault(key, Set.of()).contains(value))
      {
         return true;
      }
      if(!"MEMBER".equals(scope))
      {
         return false;
      }
      try
      {
         return switch(key)
         {
            case "DASHBOARD_FROM", "DASHBOARD_THROUGH" -> value.matches("\\d{4}-\\d{2}-\\d{2}") && LocalDate.parse(value).getYear() >= 1900 && LocalDate.parse(value).getYear() <= 2200;
            case "DASHBOARD_CURRENCY" -> value.matches("[A-Z]{3}") && Currency.getInstance(value).getDefaultFractionDigits() >= 0 && Currency.getInstance(value).getDefaultFractionDigits() <= 4;
            case "DASHBOARD_PLAN", "DASHBOARD_BALANCE" -> value.matches("[1-9][0-9]{0,17}");
            default -> false;
         };
      }
      catch(IllegalArgumentException invalid)
      {
         return false;
      }
   }



   private static CarlService.Member authorized(Connection c, String principal) throws SQLException
   {
      var actor = CarlService.member(c, principal);
      if(CarlService.rows(c, "SELECT member_id FROM carl_permission WHERE member_id=? AND domain='SETTINGS' AND details", actor.id()).size() != 1)
      {
         throw new SecurityException("Preference access unavailable");
      }
      return actor;
   }
}
