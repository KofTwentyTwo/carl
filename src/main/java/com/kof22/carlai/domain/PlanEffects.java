/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Immutable human expectations compared with selected observations, never execution authority. */
public final class PlanEffects
{
   /** Payment is cash movement; principal needs separately evidenced dated terms. */
   public enum Kind
   {
      CASH_PAYMENT, TRANSFER, PRINCIPAL_REDUCTION
   }
   private final CarlService service;
   private final java.time.Clock clock;
   /** Uses the authoritative Carl services and permission checks. */
   public PlanEffects(CarlService service)
   {
      this(service, java.time.Clock.systemUTC());
   }



   /** Injectable clock uses the household's configured date boundary for observed records. */
   public PlanEffects(CarlService service, java.time.Clock clock)
   {
      this.service = service;
      this.clock = java.util.Objects.requireNonNull(clock);
   }



   /** Native callers derive the immutable plan audience from protected persisted state. */
   public CarlService.Scope scope(String principal, long plan)
   {
      return service.transaction(c ->
      {
         source(c, CarlService.Scope.privateFor(principal), "carl_plan_view", plan, new LinkedHashMap<>());
         var rows = CarlService.rows(c, "SELECT m.principal FROM carl_artifact_audience a JOIN carl_plan p ON p.source_artifact_id=a.artifact_id JOIN carl_member m ON m.id=a.member_id AND m.active WHERE p.record_id=?", plan);
         var audience = new LinkedHashSet<String>();
         for(var row : rows)
         {
            audience.add(row.get("principal").toString());
         }
         var scope = new CarlService.Scope(principal, audience);
         authorizePlan(c, scope, plan, new LinkedHashMap<>());
         return scope;
      });
   }



   /** Resolves the stored expectation's plan audience for native review without caller-supplied recipients. */
   public CarlService.Scope expectationScope(String principal, long expectation)
   {
      long plan = service.transaction(c -> CarlService.number(source(c, CarlService.Scope.privateFor(principal), "carl_plan_effect_view", expectation, new LinkedHashMap<>()), "plan_id"));
      return scope(principal, plan);
   }



   /** Records only explicit expectations for the current agreed plan/task. */
   public long expect(CarlService.Scope scope, UUID request, long plan, int version, UUID step, Kind kind, long account, BigDecimal amount, LocalDate from, LocalDate through, String reason)
   {
      if(amount == null || step == null || kind == null || from == null || through == null || from.getYear() < 1900 || through.getYear() > 2200 || through.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, through) > 3660)
      {
         throw new IllegalArgumentException("Bounded explicit expectation required");
      }
      CarlService.bounded(reason, 2000, "Expectation rationale");
      String digest = BillCsv.hash(CarlService.json(Map.of("plan", plan, "version", version, "step", step, "kind", kind, "account", account, "amount", amount, "from", from, "through", through, "reason", reason, "audience", scope.audience().stream().sorted().toList())));
      return service.transaction(c ->
      {
         lock(c, scope, true);
         var actor = CarlService.manager(c, scope.principal(), "FINANCE");
         var sources = new LinkedHashMap<Long, Long>();
         var current = authorizePlan(c, scope, plan, sources);
         Long prior = CarlService.request(c, actor, request, "PLAN_EFFECT", digest);
         if(prior != null)
         {
            source(c, scope, "carl_plan_effect_view", prior, sources);
            return prior;
         }
         if(CarlService.number(current, "version") != version || !"AGREED".equals(current.get("state")) || Boolean.TRUE.equals(current.get("source_stale")))
         {
            throw new IllegalArgumentException("Use a current agreed plan with current sources");
         }
         var tasks = CarlService.rows(c, "SELECT title FROM carl_plan_step WHERE id=? AND plan_id=?", step, plan);
         if(tasks.size() != 1)
         {
            throw new IllegalArgumentException("Task does not belong to plan");
         }
         var a = source(c, scope, "carl_account_view", account, sources);
         String currency = a.get("currency").toString();
         int scale = Currency.getInstance(currency).getDefaultFractionDigits();
         if(amount == null || amount.signum() <= 0 || amount.precision() > 18 || Math.abs((long) amount.scale()) > 10 || scale < 0 || scale > 4)
         {
            throw new IllegalArgumentException("Positive exact currency amount required");
         }
         BigDecimal exactAmount = amount.setScale(scale, RoundingMode.UNNECESSARY);
         Map<String, Object> opening = null;
         if(kind == Kind.PRINCIPAL_REDUCTION)
         {
            opening = source(c, scope, "carl_debt_view", account, sources);
            if(opening.get("principal_balance") == null || opening.get("balance_as_of") == null || !LocalDate.parse(opening.get("balance_as_of").toString()).equals(from) || opening.get("terms_evidence").toString().isBlank() || LocalDate.parse(opening.get("balance_as_of").toString()).isAfter(LocalDate.now(clock.withZone(actor.zone()))))
            {
               throw new IllegalArgumentException("Opening principal statement dated exactly at window start and evidence required");
            }
         }
         long id = CarlService.record(c, actor, "FINANCE", "PRIVATE", "Plan expectation: " + kind, reason);
         CarlService.execute(c, "INSERT INTO carl_plan_effect(record_id,plan_id,plan_version,step_id,step_title,effect_kind,account_id,currency,expected_amount,period_start,period_end,opening_principal,opening_date,opening_evidence,actor_id,rationale) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, plan, version, step, tasks.getFirst().get("title"), kind.name(), account, currency, exactAmount, from, through, opening == null ? null : opening.get("principal_balance"), opening == null ? null : LocalDate.parse(opening.get("balance_as_of").toString()), opening == null ? null : opening.get("terms_evidence"), actor.id(), reason);
         for(String principal : scope.audience())
         {
            CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", id, CarlService.member(c, principal).id());
         }
         CarlService.complete(c, request, id, "COMPLETE", "Human expectation saved; no task state or external action changed");
         return id;
      });
   }



   /** Saves a deterministic comparison from explicit selected records; no automatic transaction matching. */
   public long compare(CarlService.Scope scope, UUID request, long expectation, List<Long> transactionIds, String evidence)
   {
      if(transactionIds == null || transactionIds.size() > 100 || transactionIds.stream().anyMatch(id -> id == null || id <= 0) || transactionIds.stream().distinct().count() != transactionIds.size())
      {
         throw new IllegalArgumentException("Select at most100 distinct transactions");
      }
      CarlService.bounded(evidence, 2000, "Observation selection evidence");
      String digest = BillCsv.hash(CarlService.json(Map.of("expectation", expectation, "transactions", transactionIds, "evidence", evidence)));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         for(String p : scope.audience())
         {
            service.artifact(p, prior);
         }
         return prior;
      }
      var restore = CarlService.nestedDeadline(java.time.Duration.ofSeconds(30));
      try
      {
         Snapshot snapshot = service.transaction(c ->
         {
            long epoch = lock(c, scope, false);
            LocalDate observedThrough = LocalDate.now(clock.withZone(CarlService.member(c, scope.principal()).zone()));
            var sources = new LinkedHashMap<Long, Long>();
            var expected = source(c, scope, "carl_plan_effect_view", expectation, sources);
            var plan = authorizePlan(c, scope, CarlService.number(expected, "plan_id"), sources);
            source(c, scope, "carl_account_view", CarlService.number(expected, "account_id"), sources);
            var rows = new ArrayList<Map<String, Object>>();
            for(long id : transactionIds)
            {
               var observation = source(c, scope, "carl_transaction_view", id, sources);
               if(LocalDate.parse(observation.get("effective_date").toString()).isAfter(observedThrough))
               {
                  throw new IllegalArgumentException("Future-dated transactions are not observed financial movement");
               }
               rows.add(observation);
            }
            Kind kind = Kind.valueOf(expected.get("effect_kind").toString());
            var gaps = new ArrayList<String>();
            BigDecimal observed = null;
            Map<String, Object> closing = null;
            if(kind == Kind.PRINCIPAL_REDUCTION)
            {
               closing = source(c, scope, "carl_debt_view", CarlService.number(expected, "account_id"), sources);
               if(!rows.isEmpty())
               {
                  gaps.add("Selected payments/total balance movements cannot establish principal reduction; they are context only.");
               }
               LocalDate closingDate = closing.get("balance_as_of") == null ? null : LocalDate.parse(closing.get("balance_as_of").toString());
               if(closingDate != null && !closingDate.isAfter(observedThrough) && closing.get("principal_balance") != null && closingDate.isAfter(LocalDate.parse(expected.get("opening_date").toString())) && within(closingDate, expected) && !closing.get("terms_evidence").toString().isBlank())
               {
                  observed = ((BigDecimal) expected.get("opening_principal")).subtract((BigDecimal) closing.get("principal_balance"));
               }
               else
               {
                  gaps.add("A later dated human-evidenced principal statement within the expectation window is missing; total imported balances are not principal evidence.");
               }
            }
            else
            {
               observed = movement(rows, expected, kind, gaps);
            }
            boolean versionMatches = CarlService.number(plan, "version") == CarlService.number(expected, "plan_version") && "AGREED".equals(plan.get("state"));
            if(!versionMatches)
            {
               gaps.add("Plan version/state changed; expectation remains immutable historical evidence and is not current agreement.");
               observed = null;
            }
            if(Boolean.TRUE.equals(plan.get("source_stale")))
            {
               gaps.add("Plan source facts changed since agreement; this is an observation comparison, not a renewed affordability or planning conclusion.");
            }
            BigDecimal difference = observed == null ? null : observed.subtract((BigDecimal) expected.get("expected_amount"));
            var facts = new LinkedHashMap<String, Object>();
            facts.put("calculationVersion", "plan-effects-v1");
            facts.put("observedThroughHouseholdDate", observedThrough);
            facts.put("expectation", expected);
            facts.put("selectedTransactions", rows);
            facts.put("closingPrincipalTerms", closing);
            facts.put("observedAmount", observed);
            facts.put("differenceObservedMinusExpected", difference);
            facts.put("currency", expected.get("currency"));
            facts.put("outcome", difference == null ? "UNDETERMINED" : difference.signum() == 0 ? "MATCH" : "DIFFERENCE");
            facts.put("planVersionMatches", versionMatches);
            facts.put("selectionEvidence", evidence);
            facts.put("gaps", gaps);
            facts.put("scope", "Exactly selected authorized observations; no entire household coverage, causal attribution or task completion is established.");
            facts.put("boundary", "A numerical match is not independent verification. Human statement principal differences may include adjustments or advances; no payment-to-principal attribution is inferred. Transfers count outgoing leg once and do not establish expenditure. No plan/task state changed.");
            return new Snapshot(epoch, expected, facts, sources);
         });
         String facts = CarlService.json(snapshot.facts());
         if(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024)
         {
            throw new IllegalArgumentException("Narrow selected evidence; report exceeds512KiB");
         }
         return service.saveArtifact(scope, request, "FINANCIAL_PLAN", LocalDate.parse(snapshot.expected().get("period_start").toString()), LocalDate.parse(snapshot.expected().get("period_end").toString()), facts, "", "NOT_REQUESTED", "Human-selected evidence only; no independent execution or full financial coverage verified", snapshot.sources(), null, "Incomplete scoped plan-effect comparison — " + snapshot.facts().get("outcome"), digest, snapshot.epoch());
      }
      finally
      {
         restore.run();
      }
   }

   private record Snapshot(long epoch, Map<String, Object> expected, Map<String, Object> facts, Map<Long, Long> sources)
   {
   }
   private static BigDecimal movement(List<Map<String, Object>> rows, Map<String, Object> expected, Kind kind, List<String> gaps)
   {
      if(rows.isEmpty())
      {
         gaps.add("No explicit observations selected; absence is not zero movement.");
         return null;
      }
      BigDecimal sum = BigDecimal.ZERO;
      var pairs = new LinkedHashMap<String, List<Map<String, Object>>>();
      for(var row : rows)
      {
         if(!expected.get("currency").equals(row.get("currency")) || !within(LocalDate.parse(row.get("effective_date").toString()), expected))
         {
            throw new IllegalArgumentException("Selected observations must match currency and date window");
         }
         BigDecimal amount = (BigDecimal) row.get("amount");
         if(kind == Kind.CASH_PAYMENT)
         {
            if(CarlService.number(row, "account_id") != CarlService.number(expected, "account_id") || amount.signum() >= 0 || "TRANSFER".equals(row.get("classification")) || row.get("transfer_key") != null)
            {
               throw new IllegalArgumentException("Cash payment requires selected outgoing nontransfer observations from expected account");
            }
            sum = sum.subtract(amount);
         }
         else
         {
            if(!"TRANSFER".equals(row.get("classification")) || row.get("transfer_key") == null)
            {
               throw new IllegalArgumentException("Transfers require explicitly attributed pairs");
            }
            pairs.computeIfAbsent(row.get("transfer_key").toString(), ignored -> new ArrayList<>()).add(row);
         }
      }
      for(var pair : pairs.values())
      {
         if(pair.size() != 2)
         {
            throw new IllegalArgumentException("Select exactly both transfer legs");
         }
         var a = pair.get(0);
         var b = pair.get(1);
         BigDecimal aa = (BigDecimal) a.get("amount");
         BigDecimal bb = (BigDecimal) b.get("amount");
         if(aa.add(bb).signum() != 0 || aa.signum() == 0 || CarlService.number(a, "account_id") == CarlService.number(b, "account_id"))
         {
            throw new IllegalArgumentException("Invalid transfer pair");
         }
         var out = aa.signum() < 0 ? a : b;
         if(CarlService.number(out, "account_id") != CarlService.number(expected, "account_id"))
         {
            throw new IllegalArgumentException("Transfer must leave expected account");
         }
         sum = sum.add(((BigDecimal) out.get("amount")).negate());
      }
      return sum;
   }



   private static boolean within(LocalDate day, Map<String, Object> expected)
   {
      return !day.isBefore(LocalDate.parse(expected.get("period_start").toString())) && !day.isAfter(LocalDate.parse(expected.get("period_end").toString()));
   }



   private static long lock(Connection c, CarlService.Scope scope, boolean write) throws SQLException
   {
      var before = CarlService.member(c, scope.principal());
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR " + (write ? "UPDATE" : "SHARE"), before.householdId());
      var actor = CarlService.member(c, scope.principal());
      if(actor.householdId() != before.householdId())
      {
         throw new SecurityException("Household changed");
      }
      for(String p : scope.audience())
      {
         if(CarlService.member(c, p).householdId() != actor.householdId())
         {
            throw new SecurityException("Audience unavailable");
         }
      }
      return actor.permissionRevision();
   }



   private static Map<String, Object> authorizePlan(Connection c, CarlService.Scope scope, long plan, Map<Long, Long> sources) throws SQLException
   {
      CarlService.rows(c, "SELECT record_id FROM carl_plan WHERE record_id=? FOR SHARE", plan);
      var current = source(c, scope, "carl_plan_view", plan, sources);
      var expected = new LinkedHashSet<Long>();
      for(String p : scope.audience())
      {
         expected.add(CarlService.member(c, p).id());
      }
      var actual = new LinkedHashSet<Long>();
      for(var r : CarlService.rows(c, "SELECT member_id FROM carl_artifact_audience WHERE artifact_id=?", current.get("source_artifact_id")))
      {
         actual.add(CarlService.number(r, "member_id"));
      }
      if(!expected.equals(actual))
      {
         throw new SecurityException("Use exact immutable plan audience");
      }
      source(c, scope, "carl_artifact_view", CarlService.number(current, "source_artifact_id"), sources);
      return current;
   }



   private static Map<String, Object> source(Connection c, CarlService.Scope scope, String view, long id, Map<Long, Long> sources) throws SQLException
   {
      if(!Set.of("carl_plan_view", "carl_plan_effect_view", "carl_account_view", "carl_transaction_view", "carl_debt_view", "carl_artifact_view").contains(view))
      {
         throw new IllegalArgumentException("Unsupported source");
      }
      CarlService.rows(c, "SELECT id FROM carl_record WHERE id=? FOR SHARE", id);
      Map<String, Object> result = null;
      for(String p : scope.audience())
      {
         var found = CarlService.rows(c, "SELECT v.*,r.revision AS source_revision FROM " + view + " v JOIN carl_record r ON r.id=v.id WHERE v.principal=? AND v.id=?", p, id);
         if(found.size() != 1)
         {
            throw new SecurityException("Source unavailable to plan audience");
         }
         result = new LinkedHashMap<>(found.getFirst());
      }
      if(result == null)
      {
         throw new SecurityException("Audience required");
      }
      sources.put(id, CarlService.number(result, "source_revision"));
      result.remove("principal");
      return result;
   }
}
