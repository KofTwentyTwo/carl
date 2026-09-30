/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/** Explicit source precedence over authorized accounts and properties; never a completeness claim. */
public final class BalanceSheets
{
   /** A linked asset is represented by exactly one chosen valuation. */
   public enum Valuation
   {
      PROPERTY_ESTIMATE, LINKED_ACCOUNT
   }



   /** The human-selected property valuation source. */
   public record PropertyChoice(long property, Valuation valuation)
   {
   }
   private final CarlService service;
   private final java.time.Clock clock;
   /** Uses the same Carl database and permission model. */
   public BalanceSheets(CarlService service)
   {
      this(service, java.time.Clock.systemUTC());
   }



   /** Configurable clock keeps synthetic date boundaries reproducible. */
   public BalanceSheets(CarlService service, java.time.Clock clock)
   {
      this.service = service;
      this.clock = java.util.Objects.requireNonNull(clock);
   }



   /** Saves a bounded, dated, source-linked selected balance sheet. */
   public long report(CarlService.Scope scope, UUID request, LocalDate asOf, int maximumAgeDays, List<Long> accounts, List<PropertyChoice> properties)
   {
      date(asOf);
      if(asOf.isAfter(LocalDate.now(clock)))
      {
         throw new IllegalArgumentException("Future dates do not establish an observed balance sheet");
      }
      if(maximumAgeDays < 0 || maximumAgeDays > 3660 || accounts == null || properties == null || accounts.size() > 100 || properties.size() > 100 || accounts.isEmpty() && properties.isEmpty()
         || accounts.stream().anyMatch(id -> id == null || id <= 0) || accounts.stream().distinct().count() != accounts.size()
         || properties.stream().anyMatch(p -> p == null || p.property() <= 0 || p.valuation() == null) || properties.stream().map(PropertyChoice::property).distinct().count() != properties.size())
      {
         throw new IllegalArgumentException("Choose unique bounded sources, valuation policies and an explicit age limit");
      }
      String digest = BillCsv.hash(CarlService.json(Map.of("operation", "BALANCE_SHEET", "asOf", asOf, "age", maximumAgeDays, "accounts", accounts, "properties", properties)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         requireArtifact(scope, prior);
         return prior;
      }
      var restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         var snapshot = service.transaction(c ->
         {
            long epoch = lock(c, scope);
            var sources = new LinkedHashMap<Long, Long>();
            var selectedAccounts = new LinkedHashSet<>(accounts);
            var suppressed = new LinkedHashSet<Long>();
            var rows = new ArrayList<Map<String, Object>>();
            var gaps = new ArrayList<String>();
            var totals = new LinkedHashMap<String, BigDecimal>();
            var liquidity = new LinkedHashMap<String, BigDecimal>();
            for(var choice : properties)
            {
               var property = permitted(c, scope, "carl_rental_property_view", choice.property(), sources);
               String currency = property.get("currency").toString();
               Long asset = nullableId(property.get("asset_account_id"));
               Long debt = nullableId(property.get("debt_account_id"));
               if(asset != null)
               {
                  selectedAccounts.add(asset);
               }
               if(debt != null)
               {
                  selectedAccounts.add(debt);
               }
               var item = new LinkedHashMap<String, Object>();
               item.put("property", choice.property());
               item.put("title", property.get("title"));
               item.put("currency", currency);
               item.put("valuationPolicy", choice.valuation());
               item.put("linkedAssetAccount", asset);
               item.put("linkedDebtAccount", debt);
               item.put("evidence", property.get("evidence"));
               var ownership = (BigDecimal) property.get("ownership_share");
               item.put("ownershipFraction", ownership);
               if(ownership == null)
               {
                  gaps.add("Property " + choice.property() + " ownership is unknown; no property ownership amount inferred.");
               }
               if(choice.valuation() == Valuation.LINKED_ACCOUNT)
               {
                  if(asset == null)
                  {
                     gaps.add("Property " + choice.property() + " has no linked asset account for the chosen policy.");
                  }
                  item.put("treatment", "Linked asset account counted once using its supplied account ownership; no additional property amount.");
                  if(asset != null)
                  {
                     var account = permitted(c, scope, "carl_account_view", asset, sources);
                     if(ownership == null || ownership.compareTo((BigDecimal) account.get("ownership_share")) != 0)
                     {
                        gaps.add("Property " + choice.property() + " and linked account ownership differ or are unknown; account attribution is not property equity.");
                     }
                  }
               }
               else
               {
                  if(asset != null)
                  {
                     suppressed.add(asset);
                  }
                  BigDecimal value = (BigDecimal) property.get("market_value");
                  LocalDate observed = property.get("valuation_date") == null ? null : LocalDate.parse(property.get("valuation_date").toString());
                  item.put("suppliedPropertyValue", value);
                  item.put("valuationDate", observed);
                  BigDecimal owned = null;
                  if(value == null || observed == null)
                  {
                     gaps.add("Property " + choice.property() + " valuation missing; excluded, not zero.");
                  }
                  else if(observed.isAfter(asOf))
                  {
                     gaps.add("Property " + choice.property() + " valuation is after asOf; excluded, not backdated.");
                  }
                  else
                  {
                     if(ChronoUnit.DAYS.between(observed, asOf) > maximumAgeDays)
                     {
                        gaps.add("Property " + choice.property() + " valuation exceeds the selected age limit; retained as a stale observed estimate.");
                     }
                     if(ownership != null)
                     {
                        owned = rounded(value.multiply(ownership), currency);
                        totals.merge(currency, owned, BigDecimal::add);
                     }
                  }
                  item.put("ownedPropertyValue", owned);
                  item.put("treatment", "Supplied property estimate takes precedence; linked asset account suppressed even if this estimate is unresolved.");
               }
               rows.add(item);
            }
            var accountRows = new ArrayList<Map<String, Object>>();
            for(long id : selectedAccounts)
            {
               var account = permitted(c, scope, "carl_account_view", id, sources);
               var item = new LinkedHashMap<String, Object>();
               for(String key : List.of("id", "title", "kind", "currency", "ownership_share", "liquid", "evidence"))
               {
                  item.put(key, account.get(key));
               }
               if(suppressed.contains(id))
               {
                  item.put("treatment", "EXCLUDED_DUPLICATE_PROPERTY_ASSET");
                  accountRows.add(item);
                  continue;
               }
               var balance = balance(c, id, asOf);
               item.put("observations", balance);
               BigDecimal amount = resolved(balance);
               BigDecimal owned = null;
               if(amount == null)
               {
                  gaps.add("Account " + id + " has missing or conflicting dated balances; excluded, not zero.");
               }
               else
               {
                  LocalDate observed = LocalDate.parse(balance.getFirst().get("as_of").toString());
                  if(ChronoUnit.DAYS.between(observed, asOf) > maximumAgeDays)
                  {
                     gaps.add("Account " + id + " balance exceeds the selected age limit; retained as a stale observation.");
                  }
                  String currency = account.get("currency").toString();
                  owned = rounded(amount.multiply((BigDecimal) account.get("ownership_share")), currency);
                  totals.merge(currency, owned, BigDecimal::add);
                  if(Boolean.TRUE.equals(account.get("liquid")))
                  {
                     liquidity.merge(currency, owned, BigDecimal::add);
                  }
               }
               item.put("ownedSignedBalance", owned);
               item.put("treatment", "Counted once using signed source balance and supplied account ownership.");
               accountRows.add(item);
            }
            var facts = new LinkedHashMap<String, Object>();
            facts.put("calculationVersion", "selected-balance-sheet-v1");
            facts.put("asOf", asOf);
            facts.put("maximumAgeDays", maximumAgeDays);
            facts.put("properties", rows);
            facts.put("accounts", accountRows);
            facts.put("knownSelectedNetWorthByCurrency", totals);
            facts.put("knownSelectedLiquidBalancesByCurrency", liquidity);
            facts.put("gaps", gaps);
            facts.put("scope", "Partial explicitly selected authorized sources only; not complete household net worth. Liabilities retain their signed source amounts, shared mortgages count once, and no currency conversion occurs.");
            facts.put("temporalScope", "Dated values on or before asOf with current supplied ownership and links; not reconstructed historical ownership. Stale observations remain visibly labeled; missing amounts are excluded, not zero.");
            return new Snapshot(epoch, facts, sources);
         });
         return save(scope, request, asOf, asOf, snapshot, digest, "Incomplete selected balance sheet — review source coverage and gaps");
      }
      finally
      {
         restore.run();
      }
   }

   record Snapshot(long epoch, Map<String, Object> facts, Map<Long, Long> sources)
   {
   }
   long save(CarlService.Scope scope, UUID request, LocalDate from, LocalDate through, Snapshot snapshot, String digest, String label)
   {
      String facts = CarlService.json(snapshot.facts());
      if(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
      {
         throw new IllegalArgumentException("Selected result exceeds 1 MiB; narrow the scope");
      }
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", from, through, facts, "", "NOT_REQUESTED", "Conditional scoped evidence only; no completeness, disposable cash, tax treatment or financial action is established.", snapshot.sources(), null, label, digest, snapshot.epoch());
   }



   void requireArtifact(CarlService.Scope scope, long id)
   {
      for(String p : scope.audience())
      {
         service.artifact(p, id);
      }
   }



   static long lock(Connection c, CarlService.Scope scope) throws SQLException
   {
      var before = CarlService.member(c, scope.principal());
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", before.householdId());
      var actor = CarlService.member(c, scope.principal());
      for(String p : scope.audience())
      {
         if(CarlService.member(c, p).householdId() != actor.householdId() || actor.householdId() != before.householdId())
         {
            throw new SecurityException("Audience unavailable");
         }
      }
      return actor.permissionRevision();
   }



   static Map<String, Object> permitted(Connection c, CarlService.Scope scope, String view, long id, Map<Long, Long> sources) throws SQLException
   {
      if(!List.of("carl_rental_property_view", "carl_account_view", "carl_rental_shock_view", "carl_artifact_view").contains(view))
      {
         throw new IllegalArgumentException("Unknown source type");
      }
      Map<String, Object> result = null;
      for(String p : scope.audience())
      {
         var rows = CarlService.rows(c, "SELECT v.*,r.revision AS source_revision FROM " + view + " v JOIN carl_record r ON r.id=v.id WHERE v.principal=? AND v.id=?", p, id);
         if(rows.size() != 1)
         {
            throw new SecurityException("Selected source unavailable to intended audience");
         }
         result = rows.getFirst();
      }
      if(result == null)
      {
         throw new SecurityException("Explicit audience required");
      }
      sources.put(id, CarlService.number(result, "source_revision"));
      result.remove("principal");
      return result;
   }



   static List<Map<String, Object>> balance(Connection c, long account, LocalDate asOf) throws SQLException
   {
      return CarlService.rows(c, "SELECT id,as_of,amount,basis,evidence FROM carl_balance WHERE account_id=? AND as_of=(SELECT max(as_of) FROM carl_balance WHERE account_id=? AND as_of<=?) ORDER BY basis,id LIMIT 5", account, account, asOf);
   }



   static BigDecimal resolved(List<Map<String, Object>> observations)
   {
      if(observations.isEmpty())
      {
         return null;
      }
      BigDecimal value = (BigDecimal) observations.getFirst().get("amount");
      return observations.stream().allMatch(r -> value.compareTo((BigDecimal) r.get("amount")) == 0) ? value : null;
   }



   static BigDecimal rounded(BigDecimal amount, String currency)
   {
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Currency precision unsupported");
      }
      return amount.setScale(scale, RoundingMode.HALF_UP);
   }



   static Long nullableId(Object value)
   {
      return value == null ? null : ((Number) value).longValue();
   }



   static void date(LocalDate date)
   {
      if(date == null || date.getYear() < 1900 || date.getYear() > 2200)
      {
         throw new IllegalArgumentException("Explicit date between 1900 and 2200 required");
      }
   }
}
