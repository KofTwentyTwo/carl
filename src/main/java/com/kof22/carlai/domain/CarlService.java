/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Carl's authoritative local capabilities. Transport supplies verified principals; tools only invoke reads. */
public final class CarlService
{
   /*******************************************************************************
    * Current verified membership and permission epoch, refreshed from persistent state.
    ******************************************************************************/
   public record Member(long id, long householdId, String principal, boolean manager, ZoneId zone, long permissionRevision)
   {
   }



   /*******************************************************************************
    * Explicit requester and audience; shared output uses their intersecting access.
    ******************************************************************************/
   public record Scope(String principal, Set<String> audience)
   {
      /*******************************************************************************
       * Explicit requester and audience; shared output uses their intersecting access.
       ******************************************************************************/
      public Scope
      {
         Objects.requireNonNull(principal);
         audience = Set.copyOf(audience);
         if(audience.isEmpty() || audience.size() > 16 || !audience.contains(principal))
         {
            throw new IllegalArgumentException("Audience must include requester");
         }
      }



      /*******************************************************************************
       * Creates a requester-only scope for ordinary private conversation reads.
       ******************************************************************************/
      public static Scope privateFor(String principal)
      {
         return new Scope(principal, Set.of(principal));
      }
   }



   /*******************************************************************************
    * Optional inference boundary receiving only preauthorized deterministic facts.
    ******************************************************************************/
   public interface Narrator
   {
      /*******************************************************************************
       * May explain already verified facts; it cannot authorize data access or external actions.
       ******************************************************************************/
      String narrate(String verifiedFacts) throws Exception;
   }



   interface Transaction<T>
   {
      T run(Connection connection) throws SQLException;
   }
   private final DataSource source;
   private final Clock clock;
   private static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
   private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

   /*******************************************************************************
    * Uses the dedicated domain database and explicit clock for authoritative operations.
    ******************************************************************************/
   public CarlService(DataSource source, Clock clock)
   {
      this.source = Objects.requireNonNull(source);
      this.clock = Objects.requireNonNull(clock);
   }



   /*******************************************************************************
    * Rejects unmapped or inactive principals even when transport RBAC has a fallback role.
    ******************************************************************************/
   public Member member(String principal)
   {
      return transaction(c -> member(c, principal));
   }



   /*******************************************************************************
    * Reads permitted bills, retaining missing dates and limiting the requested interval.
    ******************************************************************************/
   public List<Map<String, Object>> bills(Scope scope, LocalDate from, LocalDate through)
   {
      interval(from, through);
      return transaction(c ->
      {
         authorizeAudience(c, scope);
         var rows = rows(c, "SELECT * FROM carl_bill_view WHERE principal=? AND (due_date BETWEEN ? AND ? OR due_date IS NULL) ORDER BY due_date NULLS LAST,id LIMIT 1001", scope.principal(), from, through);
         return shared(c, scope, "carl_bill_view", rows);
      });
   }



   /*******************************************************************************
    * Computes exact totals separately by currency and recorded payment status.
    ******************************************************************************/
   public Map<String, Object> billSummary(Scope scope, LocalDate from, LocalDate through)
   {
      var bills = bills(scope, from, through);
      var totals = new LinkedHashMap<String, BigDecimal>();
      var gaps = new ArrayList<Long>();
      for(var bill : bills)
      {
         if(bill.get("amount") == null || bill.get("due_date") == null || bill.get("status").equals("UNKNOWN"))
         {
            gaps.add(((Number) bill.get("id")).longValue());
         }
         if(bill.get("amount") != null)
         {
            String key = bill.get("currency") + ":" + bill.get("status");
            totals.merge(key, (BigDecimal) bill.get("amount"), BigDecimal::add);
         }
      }
      return Map.of("scope", "Authorized records only; household coverage may be partial", "from", from.toString(), "through", through.toString(),
         "totals", totals, "missingOrUncertainRecordIds", gaps, "bills", bills, "asOf", clock.instant().toString());
   }



   /*******************************************************************************
    * Atomically applies a human-reviewed import with stable request and source identities.
    ******************************************************************************/
   public long importBills(String principal, UUID requestId, String sourceName, String csv)
   {
      var preview = BillCsv.preview(csv);
      if(!preview.valid())
      {
         throw new IllegalArgumentException("Import rejected atomically: " + preview.errors());
      }
      bounded(sourceName, 200, "source name");
      return transaction(c ->
      {
         var member = manager(c, principal, "BILLS");
         String digest = BillCsv.hash(sourceName + "\n" + csv);
         Long existing = request(c, member, requestId, "BILL_IMPORT", digest);
         if(existing != null)
         {
            return existing;
         }
         var duplicate = rows(c, "SELECT id FROM carl_import WHERE household_id=? AND member_id=? AND source_name=? AND content_digest=?", member.householdId(), member.id(), sourceName, preview.identity());
         if(!duplicate.isEmpty())
         {
            long batch = number(duplicate.getFirst(), "id");
            complete(c, requestId, batch, "COMPLETE", "Identical import already stored");
            return batch;
         }
         long batch = insert(c, "INSERT INTO carl_import(household_id,member_id,source_name,content_digest,request_id,row_count) VALUES(?,?,?,?,?,?) RETURNING id",
            member.householdId(), member.id(), sourceName, preview.identity(), requestId, preview.rows().size());
         for(var row : preview.rows())
         {
            var prior = rows(c, "SELECT b.record_id,b.vendor_label,b.amount,b.currency,b.due_date,b.status,r.title,r.visibility FROM carl_bill b JOIN carl_import i ON i.id=b.import_id JOIN carl_record r ON r.id=b.record_id WHERE i.household_id=? AND i.member_id=? AND i.source_name=? AND b.source_id=? ORDER BY b.record_id",
               member.householdId(), member.id(), sourceName, row.sourceId());
            if(!prior.isEmpty())
            {
               var old = prior.getFirst();
               if(prior.size() != 1 || !Objects.equals(old.get("vendor_label"), row.vendor()) || !Objects.equals(old.get("title"), row.description())
                  || !decimalEqual((BigDecimal) old.get("amount"), row.amount()) || !Objects.equals(old.get("currency"), row.currency())
                  || !Objects.equals(old.get("due_date"), row.dueDate() == null ? null : row.dueDate().toString())
                  || !Objects.equals(old.get("status"), row.status()) || !Objects.equals(old.get("visibility"), row.visibility()))
               {
                  throw new IllegalArgumentException("Source identity changed; review and correct the existing record explicitly");
               }
               continue;
            }
            long record = record(c, member, "BILLS", row.visibility(), row.description(), "Import " + batch + ", logical row " + row.line() + ", source " + row.sourceId());
            execute(c, "INSERT INTO carl_bill(record_id,vendor_label,amount,currency,due_date,status,import_id,source_id) VALUES(?,?,?,?,?,?,?,?)", record, row.vendor(), row.amount(), row.currency(), row.dueDate(), row.status(), batch, row.sourceId());
         }
         bump(c, member.householdId());
         complete(c, requestId, batch, "COMPLETE", "Import committed atomically");
         return batch;
      });
   }



   /*******************************************************************************
    * Routes manual bill entry through the same validation and provenance path as imports.
    ******************************************************************************/
   public long enterBill(String principal, UUID requestId, BillCsv.Row bill)
   {
      String csv = "source_id,vendor,description,amount,currency,due_date,status,visibility\n" +
         String.join(",", csv(bill.sourceId()), csv(bill.vendor()), csv(bill.description()), bill.amount() == null ? "" : bill.amount().toPlainString(), bill.currency(), bill.dueDate() == null ? "" : bill.dueDate().toString(), bill.status(), bill.visibility()) + "\n";
      return importBills(principal, requestId, "Manual entry", csv);
   }



   /*******************************************************************************
    * Attributes a human correction while preserving original imported evidence.
    ******************************************************************************/
   public void correctBill(String principal, long id, BigDecimal amount, LocalDate due, String status, String paymentEvidence, String reason)
   {
      bounded(reason, 2000, "correction reason");
      if(!Set.of("UNPAID", "PAID_ASSERTED", "DISPUTED", "UNKNOWN").contains(status) || amount != null && amount.signum() < 0)
      {
         throw new IllegalArgumentException("Invalid bill correction");
      }
      if(status.equals("PAID_ASSERTED"))
      {
         bounded(paymentEvidence, 2000, "human payment assertion evidence");
      }
      transaction(c ->
      {
         var member = manager(c, principal, "BILLS");
         requireRecord(c, principal, id);
         var before = rows(c, "SELECT * FROM carl_bill WHERE record_id=? FOR UPDATE", id);
         if(before.size() != 1)
         {
            throw unavailable();
         }
         String currency = (String) before.getFirst().get("currency");
         if(amount != null)
         {
            if(amount.stripTrailingZeros().scale() > java.util.Currency.getInstance(currency).getDefaultFractionDigits())
            {
               throw new IllegalArgumentException("Amount exceeds currency precision");
            }
         }
         execute(c, "UPDATE carl_bill SET amount=?,due_date=?,status=?,payment_evidence=? WHERE record_id=?", amount, due, status, paymentEvidence, id);
         execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
         execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", id, member.id(), reason, json(before), json(rows(c, "SELECT * FROM carl_bill WHERE record_id=?", id)));
         bump(c, member.householdId());
         return null;
      });
   }



   /*******************************************************************************
    * Reads a fixed domain view and filters to the authorized audience intersection.
    ******************************************************************************/
   public List<Map<String, Object>> view(Scope scope, String kind)
   {
      String table = switch(kind)
      {
         case "members" -> "carl_member_view";
         case "plans" -> "carl_plan_view";
         case "planEffects" -> "carl_plan_effect_view";
         case "reminderObservations" -> "carl_reminder_observation_view";
         case "vendors" -> "carl_vendor_view";
         case "work" -> "carl_work_view";
         case "portfolioMoves" -> "carl_portfolio_move_view";
         case "debts" -> "carl_debt_view";
         case "accounts" -> "carl_account_view";
         case "properties" -> "carl_rental_property_view";
         case "rentalScenarios" -> "carl_rental_shock_view";
         case "taxAlternatives" -> "carl_tax_alternative_view";
         case "taxReferences" -> "carl_tax_reference_view";
         case "transactions" -> "carl_transaction_view";
         case "budgets" -> "carl_budget_view";
         case "cashPlans" -> "carl_cash_plan_view";
         case "financialGoals" -> "carl_financial_goal_view";
         case "financingOffers" -> "carl_financing_offer_view";
         case "expenses" -> "carl_expense_view";
         case "expenseActuals" -> "carl_expense_actual_view";
         case "expenseSettlements" -> "carl_expense_settlement_view";
         case "rentalUnits" -> "carl_rental_unit_view";
         case "rentalSources" -> "carl_rental_source_view";
         case "rentDues" -> "carl_rent_due_view";
         case "rentApplications" -> "carl_rent_application_view";
         case "taxProperties" -> "carl_tax_property_view";
         case "tax" -> "carl_tax_view";
         case "calendar" -> "carl_calendar_view";
         case "calendarConnections" -> "carl_calendar_connection_view";
         case "artifacts" -> "carl_artifact_view";
         default -> throw new IllegalArgumentException("Unsupported Carl view");
      };
      return transaction(c ->
      {
         authorizeAudience(c, scope);
         return shared(c, scope, table, rows(c, "SELECT * FROM " + table + " WHERE principal=? ORDER BY id LIMIT 1001", scope.principal()));
      });
   }



   /*******************************************************************************
    * Stores supplied vendor facts without treating a contact as verified by inference.
    ******************************************************************************/
   public long createVendor(String principal, String title, String category, String contact, boolean verified, String visibility, String evidence)
   {
      bounded(title, 200, "vendor name");
      bounded(category, 200, "category");
      return transaction(c ->
      {
         var member = manager(c, principal, "VENDORS");
         long id = record(c, member, "VENDORS", visibility, title, evidence);
         execute(c, "INSERT INTO carl_vendor(record_id,category,contact,contact_verified) VALUES(?,?,?,?)", id, category, contact, verified);
         bump(c, member.householdId());
         return id;
      });
   }



   /*******************************************************************************
    * Persists an evidence-linked work item without creating an external commitment.
    ******************************************************************************/
   public long createWorkItem(String principal, long vendorId, String title, String status, LocalDate followUp, String evidence, String visibility)
   {
      if(!Set.of("FAMILY_RESPONSE", "VENDOR_RESPONSE", "INFORMATION_NEEDED", "COMPLETE").contains(status))
      {
         throw new IllegalArgumentException("Invalid work status");
      }
      return transaction(c ->
      {
         var member = manager(c, principal, "VENDORS");
         requireRecord(c, principal, vendorId);
         long id = record(c, member, "VENDORS", visibility, title, evidence);
         execute(c, "INSERT INTO carl_work_item(record_id,vendor_id,status,follow_up) VALUES(?,?,?,?)", id, vendorId, status, followUp);
         bump(c, member.householdId());
         return id;
      });
   }



   /*******************************************************************************
    * Claims an idempotent request before narration and retains verified facts on inference failure.
    ******************************************************************************/
   public long generateReport(Scope scope, UUID requestId, LocalDate from, LocalDate through, Narrator narrator)
   {
      interval(from, through);
      long permissionRevision = member(scope.principal()).permissionRevision();
      String requestDigest = BillCsv.hash(from + ":" + through);
      Long existing = claimArtifact(scope, requestId, "HOUSEHOLD_REPORT", requestDigest);
      if(existing != null)
      {
         artifact(scope.principal(), existing);
         return existing;
      }
      // Freeze source facts before narration, then re-authorize those exact sources at persistence.
      Map<String, Object> facts = new LinkedHashMap<>(billSummary(scope, from, through));
      facts.put("calendar", reportCalendar(scope, from, through));
      @SuppressWarnings("unchecked")
      var calendarRows = (List<Map<String, Object>>) facts.get("calendar");
      var overlaps = calendarOverlaps(calendarRows, member(scope.principal()).zone());
      facts.put("calendarOverlaps", overlaps);
      var connections = view(scope, "calendarConnections");
      facts.put("calendarConnections", connections);
      var limitations = new ArrayList<String>();
      if(Boolean.TRUE.equals(overlaps.get("truncated")))
      {
         limitations.add("More than 1000 apparent calendar overlaps exist; narrow the report interval.");
      }

      if(connections.isEmpty())
      {
         limitations.add("No authorized calendar connection is configured; an empty agenda does not establish availability.");
      }
      for(var connection : connections)
      {
         if(connection.get("coverage_from") == null || connection.get("coverage_through") == null || LocalDate.parse(connection.get("coverage_from").toString()).isAfter(from) || LocalDate.parse(connection.get("coverage_through").toString()).isBefore(through))
         {
            limitations.add("Calendar connection " + connection.get("id") + " does not have confirmed coverage for the entire requested interval.");
         }
         if(!"CURRENT".equals(connection.get("sync_state")) || connection.get("last_success") == null)
         {
            limitations.add("Calendar connection " + connection.get("id") + " has incomplete synchronization; last success: " + connection.get("last_success"));
         }
      }
      if(!((List<?>) facts.get("missingOrUncertainRecordIds")).isEmpty())
      {
         limitations.add("Some bills have missing fields or uncertain status.");
      }
      if(((List<?>) facts.get("calendar")).stream().anyMatch(row -> "Busy".equals(((Map<?, ?>) row).get("title"))))
      {
         limitations.add("Some permitted calendar intervals are free/busy only; private event details are excluded.");
      }
      facts.put("coverageLimitations", limitations);
      facts.put("vendorWork", view(scope, "work"));
      String verified = json(facts);
      String narrative = "";
      String narrationState = "NOT_REQUESTED";
      if(narrator != null)
      {
         try
         {
            narrative = Objects.requireNonNull(narrator.narrate(verified));
            bounded(narrative, 100000, "narrative");
            narrationState = "COMPLETE";
         }
         catch(Exception failure)
         {
            narrationState = "FAILED";
            narrative = "Narration failed; verified facts are retained.";
         }
      }
      var sources = new LinkedHashMap<Long, Long>();
      for(String key : List.of("bills", "calendar", "calendarConnections", "vendorWork"))
      {
         @SuppressWarnings("unchecked")
         var rows = (List<Map<String, Object>>) facts.get(key);
         rows.forEach(row -> sources.put(number(row, "id"), number(row, "revision")));
      }
      return saveArtifact(scope, requestId, "HOUSEHOLD_REPORT", from, through, verified, narrative, narrationState,
         "Authorized accessible records only. " + String.join(" ", limitations), sources, null, limitations.isEmpty() ? "Report — no external action" : "Incomplete household report — review coverage", requestDigest, permissionRevision);
   }



   /*******************************************************************************
    * Produces a local unsent draft from permitted vendor evidence only.
    ******************************************************************************/
   public long generateDraft(Scope scope, UUID requestId, long workId, String purpose)
   {
      if(!Set.of("FOLLOW_UP", "QUOTE_REQUEST", "SCHEDULING_INQUIRY", "SERVICE_QUESTION").contains(purpose))
      {
         throw new IllegalArgumentException("Unsupported draft purpose");
      }
      long permissionRevision = member(scope.principal()).permissionRevision();
      String requestDigest = BillCsv.hash(workId + ":" + purpose);
      Long existing = claimArtifact(scope, requestId, "VENDOR_DRAFT", requestDigest);
      if(existing != null)
      {
         artifact(scope.principal(), existing);
         return existing;
      }
      var work = view(scope, "work").stream().filter(row -> number(row, "id") == workId).findFirst().orElseThrow(CarlService::unavailable);
      long vendorId = number(work, "vendor_id");
      var vendor = view(scope, "vendors").stream().filter(row -> number(row, "id") == vendorId).findFirst().orElseThrow(CarlService::unavailable);
      String requestText = switch(purpose)
      {
         case "QUOTE_REQUEST" -> "Could you provide an itemized quote and clarify the scope and exclusions for ";
         case "SCHEDULING_INQUIRY" -> "Could you share possible appointment windows and any scheduling requirements for ";
         case "SERVICE_QUESTION" -> "Could you explain the available service options and information you need concerning ";
         default -> "Could you provide an update concerning ";
      };
      String draft = "Hello,\n\n" + requestText + work.get("title") + "? "
         + "Please let us know what further information you need. "
         + "This inquiry does not approve a price, appointment, purchase or contract.\n\nThank you.";
      String recipient = Boolean.TRUE.equals(vendor.get("contact_verified")) ? (String) vendor.get("contact") : null;
      return saveArtifact(scope, requestId, "VENDOR_DRAFT", null, null, json(Map.of("work", work, "vendor", vendor, "purpose", purpose)), draft, "NOT_REQUESTED",
         recipient == null ? "Incomplete: select and verify recipient before human use." : "Human review required; Carl cannot send this draft.", Map.of(workId, number(work, "revision"), vendorId, number(vendor, "revision")), recipient, "Draft — not sent", requestDigest, permissionRevision);
   }



   /** Computes apparent overlaps only from the already intersected permitted calendar projection. */
   List<Map<String, Object>> reportCalendar(Scope scope, LocalDate from, LocalDate through)
   {
      interval(from, through);
      return transaction(c ->
      {
         authorizeAudience(c, scope);
         var zone = member(c, scope.principal()).zone();
         return shared(c, scope, "carl_calendar_view", rows(c, "SELECT * FROM carl_calendar_view WHERE principal=? AND ((start_at<? AND end_at>?) OR (all_day_start<=? AND all_day_end_exclusive>?)) ORDER BY id LIMIT 1001", scope.principal(), java.sql.Timestamp.from(through.plusDays(1).atStartOfDay(zone).toInstant()), java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()), through, from));
      });
   }



   static Map<String, Object> calendarOverlaps(List<Map<String, Object>> rows, ZoneId zone)
   {
      record Span(long id, Instant start, Instant end)
      {
      }
      var spans = new ArrayList<Span>();
      for(var row : rows)
      {
         if(Boolean.TRUE.equals(row.get("cancelled")) || Boolean.TRUE.equals(row.get("transparent")))
         {
            continue;
         }
         Instant start = row.get("start_at") == null ? LocalDate.parse(row.get("all_day_start").toString()).atStartOfDay(zone).toInstant() : Instant.parse(row.get("start_at").toString());
         Instant end = row.get("end_at") == null ? LocalDate.parse(row.get("all_day_end_exclusive").toString()).atStartOfDay(zone).toInstant() : Instant.parse(row.get("end_at").toString());
         spans.add(new Span(number(row, "id"), start, end));
      }
      spans.sort(java.util.Comparator.comparing(Span::start));
      var pairs = new ArrayList<Map<String, Long>>();
      for(int first = 0; first < spans.size(); first++)
      {
         for(int next = first + 1; next < spans.size() && spans.get(next).start().isBefore(spans.get(first).end()); next++)
         {
            if(pairs.size() == 1000)
            {
               return Map.of("pairs", pairs, "truncated", true);
            }
            pairs.add(Map.of("firstRecord", spans.get(first).id(), "secondRecord", spans.get(next).id()));
         }
      }
      return Map.of("pairs", pairs, "truncated", false);
   }



   /*******************************************************************************
    * Rechecks current membership and every source permission on retrieval or export.
    ******************************************************************************/
   public Map<String, Object> artifact(String principal, long id)
   {
      return transaction(c ->
      {
         member(c, principal);
         var found = rows(c, "SELECT * FROM carl_artifact_view WHERE principal=? AND id=?", principal, id);
         if(found.size() != 1)
         {
            throw unavailable();
         }
         return found.getFirst();
      });
   }



   Long claimArtifact(Scope scope, UUID id, String kind, String inputDigest)
   {
      return transaction(c ->
      {
         var actor = member(c, scope.principal());
         authorizeAudience(c, scope);
         String digest = BillCsv.hash(kind + ":" + inputDigest + ":" + String.join(",", scope.audience().stream().sorted().toList()));
         var inserted = rows(c, "INSERT INTO carl_request(id,member_id,kind,digest,status) VALUES(?,?,?,?,'PENDING') ON CONFLICT(id) DO NOTHING RETURNING id", id, actor.id(), kind, digest);
         if(!inserted.isEmpty())
         {
            execute(c, "INSERT INTO carl_report_claim(request_id,household_id,permission_revision) VALUES(?,?,?)", id, actor.householdId(), actor.permissionRevision());
            for(String principal : scope.audience())
            {
               execute(c, "INSERT INTO carl_report_claim_audience(request_id,member_id) VALUES(?,?)", id, member(c, principal).id());
            }
            return null;
         }
         var prior = rows(c, "SELECT * FROM carl_request WHERE id=?", id).getFirst();
         if(number(prior, "member_id") != actor.id() || !prior.get("kind").equals(kind) || !prior.get("digest").equals(digest))
         {
            throw new IllegalArgumentException("Request ID conflicts with previous input");
         }
         if(!Set.of("COMPLETE", "PARTIAL").contains(prior.get("status")))
         {
            throw new IllegalStateException("Request is pending or unknown; reconcile it instead of replaying provider work");
         }
         return number(prior, "result_id");
      });
   }



   long saveArtifact(Scope scope, UUID requestId, String kind, LocalDate from, LocalDate through, String facts, String narrative, String narrationState,
      String limitations, Map<Long, Long> sources, String recipient, String statusLabel, String inputDigest, long expectedPermissionRevision)
   {
      return transaction(c -> saveArtifact(c, scope, requestId, kind, from, through, facts, narrative, narrationState, limitations, sources, recipient, statusLabel, inputDigest, expectedPermissionRevision));
   }



   long saveArtifact(Connection c, Scope scope, UUID requestId, String kind, LocalDate from, LocalDate through, String facts, String narrative, String narrationState,
      String limitations, Map<Long, Long> sources, String recipient, String statusLabel, String inputDigest, long expectedPermissionRevision) throws SQLException
   {
      var requester = member(c, scope.principal());
      long currentEpoch = number(rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", requester.householdId()).getFirst(), "permission_revision");
      if(currentEpoch != expectedPermissionRevision)
      {
         throw unavailable();
      }
      authorizeAudience(c, scope);
      String digest = BillCsv.hash(kind + ":" + inputDigest + ":" + String.join(",", scope.audience().stream().sorted().toList()));
      Long prior = request(c, requester, requestId, kind, digest);
      if(prior != null)
      {
         requireRecord(c, scope.principal(), prior);
         return prior;
      }
      for(String principal : scope.audience())
      {
         for(long sourceId : sources.keySet())
         {
            if(rows(c, "SELECT id FROM carl_access WHERE principal=? AND id=? AND details", principal, sourceId).isEmpty()
               && rows(c, "SELECT id FROM carl_calendar_view WHERE principal=? AND id=?", principal, sourceId).isEmpty())
            {
               throw unavailable();
            }
         }
      }
      String domain = Set.of("VENDOR_DRAFT", "VENDOR_REPORT").contains(kind) ? "VENDORS" : kind.equals("CALENDAR_REPORT") ? "CALENDAR" : kind.equals("FINANCIAL_PLAN") ? "FINANCE" : "BILLS";
      long id = record(c, requester, domain, "PRIVATE", kind.replace('_', ' '), "Explicit authenticated generation request " + requestId);
      long revision = number(rows(c, "SELECT revision FROM carl_household WHERE id=?", requester.householdId()).getFirst(), "revision");
      execute(c, "INSERT INTO carl_artifact(record_id,request_id,kind,period_start,period_end,facts,narrative,limitations,narration_state,source_revision,formula_version,intended_recipient,status_label,permission_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
         id, requestId, kind, from, through, facts, narrative, limitations, narrationState, revision, "carl-v1", recipient, statusLabel, requester.permissionRevision());
      for(long sourceId : sources.keySet())
      {
         execute(c, "INSERT INTO carl_artifact_source(artifact_id,source_id,source_revision) VALUES(?,?,?)", id, sourceId, sources.get(sourceId));
      }
      for(String principal : scope.audience())
      {
         long memberId = member(c, principal).id();
         execute(c, "INSERT INTO carl_artifact_audience(artifact_id,member_id) VALUES(?,?)", id, memberId);
         execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,?,true)", id, memberId);
      }
      complete(c, requestId, id, narrationState.equals("FAILED") || statusLabel.startsWith("Incomplete") ? "PARTIAL" : "COMPLETE", statusLabel);
      return id;
   }



   private List<Map<String, Object>> shared(Connection c, Scope scope, String view, List<Map<String, Object>> found) throws SQLException
   {
      if(found.size() > 1000)
      {
         throw new IllegalArgumentException("Narrow the query; maximum 1000 records");
      }
      var result = new ArrayList<Map<String, Object>>();
      for(var row : found)
      {
         long id = number(row, "id");
         var safe = new LinkedHashMap<>(row);
         safe.remove("principal");
         boolean allowed = true;
         for(String principal : scope.audience())
         {
            var projected = rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
            if(!projected.isEmpty())
            {
               projected.getFirst().remove("principal");
            }
            if(view.equals("carl_calendar_view") && projected.size() == 1 && (!safe.equals(projected.getFirst()) || "Busy".equals(safe.get("title"))))
            {
               for(var calendar : List.of(safe, projected.getFirst()))
               {
                  calendar.put("title", "Busy");
                  calendar.put("evidence", "Event details restricted");
                  calendar.put("source_zone", null);
               }
            }
            if(projected.size() != 1 || !safe.equals(projected.getFirst()))
            {
               allowed = false;
               break;
            }
         }
         if(allowed)
         {
            result.add(safe);
         }
      }
      return List.copyOf(result);
   }



   private void authorizeAudience(Connection c, Scope scope) throws SQLException
   {
      long household = member(c, scope.principal()).householdId();
      for(String principal : scope.audience())
      {
         if(member(c, principal).householdId() != household)
         {
            throw unavailable();
         }
      }
   }



   static Member member(Connection c, String principal) throws SQLException
   {
      var found = rows(c, "SELECT m.id,m.household_id,m.principal,m.can_manage,h.display_zone,h.permission_revision FROM carl_member m JOIN carl_household h ON h.id=m.household_id WHERE m.principal=? AND m.active", principal);
      if(found.size() != 1)
      {
         throw unavailable();
      }
      var row = found.getFirst();
      return new Member(number(row, "id"), number(row, "household_id"), (String) row.get("principal"), (Boolean) row.get("can_manage"), ZoneId.of((String) row.get("display_zone")), number(row, "permission_revision"));
   }



   static Member manager(Connection c, String principal, String domain) throws SQLException
   {
      var member = member(c, principal);
      if(!member.manager() || rows(c, "SELECT member_id FROM carl_permission WHERE member_id=? AND domain=? AND details", member.id(), domain).isEmpty())
      {
         throw unavailable();
      }
      return member;
   }



   static void requireRecord(Connection c, String principal, long id) throws SQLException
   {
      member(c, principal);
      if(rows(c, "SELECT id FROM carl_access WHERE principal=? AND id=? AND details", principal, id).size() != 1)
      {
         throw unavailable();
      }
   }



   /** Accepts documentary evidence only through its current typed view, including dependent access. */
   static void requireEvidence(Connection c, String principal, long id) throws SQLException
   {
      requireRecord(c, principal, id);
      var permitted = rows(c, "SELECT id FROM carl_bill_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_vendor_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_work_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_account_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_transaction_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_artifact_view WHERE principal=? AND id=? UNION ALL SELECT id FROM carl_document_view WHERE principal=? AND id=? UNION ALL SELECT v.id FROM carl_calendar_view v JOIN carl_calendar_event e ON e.record_id=v.id WHERE v.principal=? AND v.id=? AND NOT e.free_busy_only", principal, id, principal, id, principal, id, principal, id, principal, id, principal, id, principal, id, principal, id);
      if(permitted.isEmpty())
      {
         throw unavailable();
      }
   }



   static long record(Connection c, Member member, String domain, String visibility, String title, String evidence) throws SQLException
   {
      bounded(title, 2000, "title");
      bounded(evidence, 20000, "evidence");
      if(!Set.of("PRIVATE", "FAMILY").contains(visibility))
      {
         throw new IllegalArgumentException("Explicit visibility required");
      }
      return insert(c, "INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) VALUES(?,?,?,?,?,?) RETURNING id", member.householdId(), member.id(), domain, visibility, title, evidence);
   }



   static Long request(Connection c, Member member, UUID id, String kind, String digest) throws SQLException
   {
      Objects.requireNonNull(id);
      execute(c, "INSERT INTO carl_request(id,member_id,kind,digest,status) VALUES(?,?,?,?,'PENDING') ON CONFLICT(id) DO NOTHING", id, member.id(), kind, digest);
      var prior = rows(c, "SELECT * FROM carl_request WHERE id=? FOR UPDATE", id).getFirst();
      if(number(prior, "member_id") != member.id() || !kind.equals(prior.get("kind")) || !digest.equals(prior.get("digest")))
      {
         throw new IllegalArgumentException("Request ID conflicts with previous input");
      }
      if(Set.of("COMPLETE", "PARTIAL").contains(prior.get("status")))
      {
         return number(prior, "result_id");
      }
      if(!prior.get("status").equals("PENDING"))
      {
         throw new IllegalStateException("Prior request outcome requires reconciliation");
      }
      return null;
   }



   static void complete(Connection c, UUID id, long result, String status, String detail) throws SQLException
   {
      execute(c, "UPDATE carl_request SET result_id=?,status=?,detail=?,finished_at=now() WHERE id=?", result, status, detail, id);
   }



   static void bump(Connection c, long household) throws SQLException
   {
      execute(c, "UPDATE carl_household SET revision=revision+1 WHERE id=?", household);
   }



   <T> T transaction(Transaction<T> operation)
   {
      remainingSeconds();
      try(var c = source.getConnection())
      {
         c.setAutoCommit(false);
         try
         {
            T result = operation.run(c);
            c.commit();
            NativeMutationReceipt.committed(c);
            return result;
         }
         catch(SQLException | RuntimeException failure)
         {
            c.rollback();
            throw failure;
         }
         finally
         {
            NativeMutationReceipt.clear(c);
         }
      }
      catch(SQLException failure)
      {
         throw new IllegalStateException("Carl database operation failed (SQLSTATE " + failure.getSQLState() + ")");
      }
   }



   static List<Map<String, Object>> rows(Connection c, String sql, Object... values) throws SQLException
   {
      try(var statement = prepare(c, sql, values); var results = statement.executeQuery())
      {
         var rows = new ArrayList<Map<String, Object>>();
         var metadata = results.getMetaData();
         while(results.next())
         {
            var row = new LinkedHashMap<String, Object>();
            for(int i = 1; i <= metadata.getColumnCount(); i++)
            {
               Object value = results.getObject(i);
               if(value instanceof java.sql.Timestamp timestamp)
               {
                  value = timestamp.toInstant().toString();
               }
               else if(value instanceof java.sql.Date || value instanceof UUID)
               {
                  value = value.toString();
               }
               row.put(metadata.getColumnLabel(i), value);
            }
            rows.add(row);
         }
         return rows;
      }
   }



   static void execute(Connection c, String sql, Object... values) throws SQLException
   {
      try(var statement = prepare(c, sql, values))
      {
         statement.executeUpdate();
      }
   }



   static long insert(Connection c, String sql, Object... values) throws SQLException
   {
      return number(rows(c, sql, values).getFirst(), "id");
   }



   static void deadline(java.time.Duration limit)
   {
      if(limit.isNegative() || limit.isZero() || limit.compareTo(java.time.Duration.ofMinutes(5)) > 0)
      {
         throw new IllegalArgumentException("Bounded workflow duration required");
      }
      DEADLINE.set(System.nanoTime() + limit.toNanos());
   }



   static Runnable nestedDeadline(java.time.Duration limit)
   {
      Long prior = DEADLINE.get();
      deadline(limit);
      if(prior != null)
      {
         DEADLINE.set(Math.min(prior, DEADLINE.get()));
      }
      return () ->
      {
         if(prior == null)
         {
            DEADLINE.remove();
         }
         else
         {
            DEADLINE.set(prior);
         }
      };
   }



   static void clearDeadline()
   {
      DEADLINE.remove();
   }



   private static int remainingSeconds()
   {
      if(Thread.currentThread().isInterrupted())
      {
         throw new IllegalStateException("Carl operation interrupted");
      }
      Long deadline = DEADLINE.get();
      if(deadline == null)
      {
         return 10;
      }
      long remaining = deadline - System.nanoTime();
      if(remaining <= 0)
      {
         throw new IllegalStateException("Carl workflow time limit reached");
      }
      return (int) Math.min(10, Math.max(1, (remaining + 999_999_999) / 1_000_000_000));
   }



   private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException
   {
      int timeout = remainingSeconds();
      var statement = c.prepareStatement(sql);
      statement.setQueryTimeout(timeout);
      for(int i = 0; i < values.length; i++)
      {
         statement.setObject(i + 1, values[i]);
      }
      return statement;
   }



   static long number(Map<String, Object> row, String key)
   {
      return ((Number) row.get(key)).longValue();
   }



   private static boolean decimalEqual(BigDecimal a, BigDecimal b)
   {
      return a == null ? b == null : b != null && a.compareTo(b) == 0;
   }



   LocalDate dataAsOf(String principal)
   {
      return clock.instant().atZone(member(principal).zone()).toLocalDate();
   }



   static String json(Object value)
   {
      try
      {
         return JSON.writeValueAsString(value);
      }
      catch(JsonProcessingException failure)
      {
         throw new IllegalArgumentException("Cannot encode Carl facts", failure);
      }
   }



   private static String csv(String value)
   {
      return "\"" + value.replace("\"", "\"\"") + "\"";
   }



   Instant reportTime()
   {
      return clock.instant();
   }



   static void interval(LocalDate from, LocalDate through)
   {
      if(from == null || through == null || through.isBefore(from) || through.isAfter(from.plusYears(2)))
      {
         throw new IllegalArgumentException("Date interval must be ordered and at most two years");
      }
   }



   static void bounded(String value, int maximum, String field)
   {
      if(value == null || value.isBlank() || value.length() > maximum)
      {
         throw new IllegalArgumentException(field + " is required and bounded to " + maximum + " characters");
      }
   }



   private static SecurityException unavailable()
   {
      return new SecurityException("Carl record or operation unavailable");
   }
}
