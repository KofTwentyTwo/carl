/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientFailure;
import com.kof22.agentadmin.client.ClientWorkflow;


/** Explicit user-requested report/draft workflows, separate from read-only model tools. */
public final class CarlClientWorkflows implements AutoCloseable
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private final CarlService service;
   private final CalendarWorkflows calendars;
   private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));

   /** Reconciles interrupted requests without replaying model or provider work. */
   public CarlClientWorkflows(CarlService service)
   {
      this(service, new CalendarWorkflows(Map.of()));
   }



   /** Uses only trusted configured calendar collections, never client-supplied endpoints. */
   public CarlClientWorkflows(CarlService service, CalendarWorkflows calendars)
   {
      this.calendars = calendars;
      this.service = service;
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_client_workflow SET status='UNKNOWN' WHERE status='PENDING'");
         return null;
      });
   }



   /** Named handlers share a bounded worker pool and durable request ledger. */
   public Map<String, ClientWorkflow> handlers()
   {
      var handlers = new java.util.LinkedHashMap<String, ClientWorkflow>();
      for(String kind : java.util.List.of("report", "draft", "debt-comparison", "purchase-assessment", "offer-comparison", "rental-report", "tax-packet", "plan-create", "plan-step", "plan-agree", "plan-check-in", "plan-export", "plan-rebase", "portfolio-comparison", "portfolio-detail", "purchase-options", "purchase-detail", "calendar-plan", "reminder-review", "tax-planning-packet", "artifact-section", "focused-report", "bill-period-comparison", "report-status", "report-reconciliation", "balance-sheet", "rental-stress-report", "report-export", "report-download", "plan-expectation", "plan-effect-comparison"))
      {
         handlers.put(kind, new Handler(kind));
      }
      return Map.copyOf(handlers);
   }

   private final class Handler implements ClientWorkflow
   {
      private final String kind;
      private Handler(String kind)
      {
         this.kind = kind;
      }



      @Override
      public Result start(Context context, UUID requestId, JsonNode input)
      {
         validate(kind, input);
         var canonical = new java.util.TreeMap<String, JsonNode>();
         input.fields().forEachRemaining(entry -> canonical.put(entry.getKey(), entry.getValue()));
         String digest = BillCsv.hash(CarlService.json(canonical));
         boolean created = service.transaction(c ->
         {
            scope(c, context);
            var inserted = CarlService.rows(c, "INSERT INTO carl_client_workflow(request_id,conversation_id,requester_id,kind,audience,permission_revision,input_digest,status) VALUES(?,?,?,?,?,?,?,'PENDING') ON CONFLICT(request_id) DO NOTHING RETURNING request_id", requestId, context.conversationId(), Long.parseLong(context.member().id()), kind, audience(context), Long.parseLong(context.member().permissionRevision()), digest);
            if(inserted.isEmpty())
            {
               var row = CarlService.rows(c, "SELECT * FROM carl_client_workflow WHERE request_id=?", requestId).getFirst();
               if(!row.get("input_digest").equals(digest) || !row.get("kind").equals(kind)
                  || !row.get("conversation_id").toString().equals(context.conversationId().toString())
                  || !row.get("requester_id").toString().equals(context.member().id()) || !row.get("audience").equals(audience(context)))
               {
                  throw new ClientFailure(409, "idempotency_conflict");
               }
               return false;
            }
            return true;
         });
         if(created)
         {
            try
            {
               workers.execute(() -> run(context, requestId, input.deepCopy()));
            }
            catch(java.util.concurrent.RejectedExecutionException capacity)
            {
               finish(requestId, "FAILED", null);
               throw new ClientFailure(429, "workflow_capacity");
            }
         }
         return get(context, requestId);
      }



      private void run(Context context, UUID id, JsonNode input)
      {
         CarlService.deadline(java.time.Duration.ofSeconds(30));
         try
         {
            CarlService.Scope authorized = scope(context);
            if(kind.equals("calendar-plan") || kind.equals("reminder-review"))
            {
               runCalendar(context, id, input);
               return;
            }
            if(kind.startsWith("plan-") && !Set.of("plan-expectation", "plan-effect-comparison").contains(kind))
            {
               runPlan(context, id, input);
               return;
            }
            if(kind.equals("plan-expectation"))
            {
               long expectation = new PlanEffects(service).expect(authorized, id, input.get("plan").longValue(), input.get("version").intValue(), UUID.fromString(input.get("step").asText()), PlanEffects.Kind.valueOf(input.get("kind").asText()), input.get("account").longValue(), new java.math.BigDecimal(input.get("amount").asText()), LocalDate.parse(input.get("from").asText()), LocalDate.parse(input.get("through").asText()), input.get("reason").asText());
               expectationOutput(authorized, expectation);
               service.transaction(c ->
               {
                  scope(c, context);
                  CarlService.execute(c, "UPDATE carl_client_workflow SET plan_result=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", CarlService.json(Map.of("expectation", expectation)), id);
                  return null;
               });
               return;
            }
            if(kind.equals("report-export") || kind.equals("report-download"))
            {
               var exports = new ArtifactExports(service);
               var format = ArtifactExports.Format.valueOf(input.get("format").asText());
               UUID export = kind.equals("report-export") ? id : UUID.fromString(input.get("exportRequest").asText());
               if(kind.equals("report-export"))
               {
                  for(String principal : authorized.audience())
                  {
                     service.artifact(principal, input.get("artifact").longValue());
                  }
                  exports.generate(authorized.principal(), export, input.get("artifact").longValue(), format);
               }
               var descriptor = exports.page(authorized.principal(), export, format, 0, 0);
               long artifact = ((Number) descriptor.get("artifactId")).longValue();
               for(String principal : authorized.audience())
               {
                  service.artifact(principal, artifact);
               }
               if(kind.equals("report-download"))
               {
                  descriptor.put("offset", input.get("offset").intValue());
                  descriptor.put("limit", input.get("limit").intValue());
               }
               service.transaction(c ->
               {
                  scope(c, context);
                  CarlService.execute(c, "UPDATE carl_client_workflow SET artifact_id=?,plan_result=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", artifact, CarlService.json(descriptor), id);
                  return null;
               });
               return;
            }
            if(kind.equals("report-status") || kind.equals("report-reconciliation"))
            {
               UUID original = UUID.fromString(input.get("reportRequest").asText());
               var recovery = new ReportRecovery(service);
               var result = kind.equals("report-status") ? recovery.inspect(authorized, original) : recovery.reconcile(authorized, id, original, input.get("reason").asText());
               service.transaction(c ->
               {
                  scope(c, context);
                  CarlService.execute(c, "UPDATE carl_client_workflow SET plan_result=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", CarlService.json(result), id);
                  return null;
               });
               return;
            }
            if(kind.equals("artifact-section"))
            {
               long artifact = input.get("artifact").longValue();
               var result = new ArtifactPresentation(service).detail(authorized, artifact, input.get("path").asText(), input.get("offset").intValue(), input.get("limit").intValue());
               service.transaction(c ->
               {
                  scope(c, context);
                  CarlService.execute(c, "UPDATE carl_client_workflow SET artifact_id=?,plan_result=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", artifact, result.toString(), id);
                  return null;
               });
               return;
            }
            if(kind.equals("portfolio-detail") || kind.equals("purchase-detail"))
            {
               long artifact = input.get("artifact").longValue();
               var result = kind.equals("portfolio-detail") ? new PortfolioPresentation(service).detail(authorized, artifact, input.get("section").asText(), input.get("strategy").isNull() ? null : input.get("strategy").asText(), input.get("offset").intValue(), input.get("limit").intValue()) : new PurchasePresentation(service).detail(authorized, artifact, input.get("option").asText(), input.get("offset").intValue(), input.get("limit").intValue());
               service.transaction(c ->
               {
                  scope(c, context);
                  CarlService.execute(c, "UPDATE carl_client_workflow SET artifact_id=?,plan_result=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", artifact, result.toString(), id);
                  return null;
               });
               return;
            }
            long artifact = switch(kind)
            {
               case "plan-effect-comparison" -> new PlanEffects(service).compare(authorized, id, input.get("expectation").longValue(), ids(input.get("transactions")), input.get("evidence").asText());
               case "balance-sheet" -> new BalanceSheets(service).report(authorized, id, LocalDate.parse(input.get("asOf").asText()), input.get("maximumAgeDays").intValue(), ids(input.get("accounts")), propertyChoices(input));
               case "rental-stress-report" -> new RentalStress(service).report(authorized, id, input.get("scenario").longValue());
               case "focused-report" -> new FocusedReports(service).generate(authorized, id, FocusedReports.Focus.valueOf(input.get("focus").asText()), LocalDate.parse(input.get("from").asText()), LocalDate.parse(input.get("through").asText()), null);
               case "bill-period-comparison" -> new FocusedReports(service).compareBills(authorized, id, LocalDate.parse(input.get("beforeFrom").asText()), LocalDate.parse(input.get("beforeThrough").asText()), LocalDate.parse(input.get("afterFrom").asText()), LocalDate.parse(input.get("afterThrough").asText()), null);
               case "tax-planning-packet" -> new TaxPlanningRecords(service).packet(authorized, id, input.get("taxYear").isNull() ? null : input.get("taxYear").intValue(), java.time.Instant.parse(input.get("asOf").asText()), ids(input.get("properties")), ids(input.get("alternatives")), ids(input.get("references")));
               case "purchase-options" -> new PurchaseAssessments(service).compare(authorized, id, input.get("cashPlan").longValue(), LocalDate.parse(input.get("purchaseDate").asText()), new java.math.BigDecimal(input.get("allInPrice").asText()), input.get("purpose").asText(), input.get("allInCostsKnown").booleanValue(), cardInput(input.get("card")), ids(input.get("offers")));
               case "portfolio-comparison" -> new PortfolioPlans(service).compare(authorized, id, new LinkedHashSet<>(ids(input.get("accounts"))), new LinkedHashSet<>(ids(input.get("moves"))), LocalDate.parse(input.get("asOf").asText()), input.get("currency").asText(), new java.math.BigDecimal(input.get("monthlyBudget").asText()), input.get("horizonMonths").intValue(), FinancialPlanning.Strategy.valueOf(input.get("rollover").asText()), input.get("budgetEvidence").asText());
               case "report" -> service.generateReport(authorized, id, LocalDate.parse(input.get("from").asText()), LocalDate.parse(input.get("through").asText()), null);
               case "draft" -> service.generateDraft(authorized, id, input.get("workId").longValue(), input.get("purpose").asText());
               case "debt-comparison" -> new DebtPlans(service).compare(authorized, id, LocalDate.parse(input.get("asOf").asText()), input.get("currency").asText(), new java.math.BigDecimal(input.get("monthlyBudget").asText()), input.get("horizonMonths").intValue(), input.get("budgetEvidence").asText());
               case "rental-report" -> new RentalRecords(service).report(authorized, id, new java.util.LinkedHashSet<>(ids(input.get("propertyIds"))), LocalDate.parse(input.get("from").asText()), LocalDate.parse(input.get("through").asText()), LocalDate.parse(input.get("asOf").asText()));
               case "tax-packet" -> new TaxRecords(service).packet(authorized, id, input.get("taxYear").intValue(), LocalDate.parse(input.get("asOf").asText()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), ids(input.get("propertyIds")));
               case "offer-comparison" -> new FinancingOffers(service).compare(authorized, id, java.util.stream.StreamSupport.stream(input.get("offerIds").spliterator(), false).map(JsonNode::longValue).toList(), LocalDate.parse(input.get("asOf").asText()));
               case "purchase-assessment" -> new CashPlans(service).assess(authorized, id, input.get("cashPlan").longValue(), LocalDate.parse(input.get("purchaseDate").asText()), new java.math.BigDecimal(input.get("allInPrice").asText()), input.get("purpose").asText(), input.get("allInCostsKnown").booleanValue());
               default -> throw new IllegalArgumentException("Unsupported workflow");
            };
            scope(context);
            var saved = service.artifact(context.member().caller(), artifact);
            finish(id, saved.get("narration_state").equals("FAILED") || saved.get("status_label").toString().startsWith("Incomplete") ? "PARTIAL" : "COMPLETE", artifact);
         }
         catch(RuntimeException failure)
         {
            // A commit may have happened before a transport/permission failure. Never replay blindly.
            CarlService.clearDeadline();
            finish(id, "UNKNOWN", null);
         }
         finally
         {
            CarlService.clearDeadline();
         }
      }



      private void runCalendar(Context context, UUID id, JsonNode input)
      {
         long plan = input.get("plan").longValue();
         service.transaction(c ->
         {
            PlanClientOperations.authorize(c, context, scope(c, context), plan);
            if(kind.equals("reminder-review") && CarlService.rows(c, "SELECT id FROM carl_reminder_observation_view WHERE principal=? AND id=? AND plan_id=?", context.member().caller(), input.get("observation").longValue(), plan).size() != 1)
            {
               throw new SecurityException("Reminder unavailable for this plan");
            }
            return null;
         });
         Map<String, Object> outcome;
         if(kind.equals("calendar-plan"))
         {
            String operation = input.get("operation").asText();
            UUID operationId = operation.equals("RECONCILE") ? UUID.fromString(input.get("priorRequest").asText()) : id;
            outcome = calendars.execute(context.member().caller(), input.get("collection").asText(), operation, operationId, plan, UUID.fromString(input.get("step").asText()), input.get("expectedVersion").intValue());
         }
         else
         {
            int version = new ReminderObservations(service).review(context.member().caller(), id, input.get("observation").longValue(), input.get("expectedVersion").intValue(), input.get("decision").asText(), input.get("note").asText());
            outcome = Map.of("reviewedVersion", version, "meaning", "Human-reviewed remote observation; financial execution remains unverified.");
         }
         service.transaction(c ->
         {
            var authorized = scope(c, context);
            var snapshot = new java.util.LinkedHashMap<>(PlanClientOperations.snapshot(c, context, authorized, plan));
            snapshot.put("calendar", outcome);
            String status = outcome.containsKey("reviewState") || Set.of("UNKNOWN", "CONFLICT", "RETRYABLE").contains(outcome.getOrDefault("state", "COMPLETE").toString()) ? "PARTIAL" : "COMPLETE";
            CarlService.execute(c, "UPDATE carl_client_workflow SET plan_id=?,plan_result=?,status=? WHERE request_id=? AND status='PENDING'", plan, CarlService.json(snapshot), status, id);
            return null;
         });
      }



      private void runPlan(Context context, UUID id, JsonNode input)
      {
         UUID export = null;
         if(kind.equals("plan-export"))
         {
            service.transaction(c ->
            {
               var authorized = scope(c, context);
               PlanClientOperations.authorize(c, context, authorized, input.get("plan").longValue());
               return null;
            });
            export = new PlanExports(service).generate(context.member().caller(), id, input.get("plan").longValue());
         }
         UUID generatedExport = export;
         service.transaction(c ->
         {
            var authorized = scope(c, context);
            CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", Long.parseLong(context.member().household()));
            authorized = scope(c, context);
            var pending = CarlService.rows(c, "SELECT status FROM carl_client_workflow WHERE request_id=? FOR UPDATE", id);
            if(pending.size() != 1 || !pending.getFirst().get("status").equals("PENDING"))
            {
               throw new IllegalArgumentException("Workflow no longer pending");
            }
            long plan = generatedExport == null ? PlanClientOperations.mutate(c, service, context, authorized, id, kind, input) : input.get("plan").longValue();
            var snapshot = new java.util.LinkedHashMap<>(PlanClientOperations.snapshot(c, context, authorized, plan));
            if(generatedExport != null)
            {
               var savedExport = CarlService.rows(c, "SELECT plan_version,created_at FROM carl_plan_export WHERE id=? AND plan_id=?", generatedExport, plan).getFirst();
               snapshot.put("exportedPlanVersion", savedExport.get("plan_version"));
               snapshot.put("exportedAt", savedExport.get("created_at"));
               snapshot.put("exportSnapshotNotice", "The PDF is the dated exported version. The plan object is the current snapshot when this workflow completed and may have a newer version.");
            }
            CarlService.execute(c, "UPDATE carl_client_workflow SET plan_id=?,plan_result=?,export_id=?,status='COMPLETE' WHERE request_id=? AND status='PENDING'", plan, CarlService.json(snapshot), generatedExport, id);
            return null;
         });
      }



      @Override
      public Result get(Context context, UUID requestId)
      {
         scope(context);
         var row = service.transaction(c ->
         {
            var rows = CarlService.rows(c, "SELECT * FROM carl_client_workflow WHERE request_id=? AND conversation_id=? AND kind=? AND audience=? AND permission_revision=?", requestId, context.conversationId(), kind, audience(context), Long.parseLong(context.member().permissionRevision()));
            if(rows.size() != 1)
            {
               throw new ClientFailure(404, "workflow_unavailable");
            }
            return rows.getFirst();
         });
         Status status = Status.valueOf(row.get("status").toString());
         if(kind.equals("plan-expectation") && row.get("plan_result") != null)
         {
            try
            {
               long expectation = JSON.readTree(row.get("plan_result").toString()).get("expectation").longValue();
               var output = expectationOutput(scope(context), expectation);
               scope(context);
               return new Result(status, Long.toString(expectation), "Saved explicit expected effect; no financial action or completion occurred", output);
            }
            catch(java.io.IOException invalid)
            {
               throw new IllegalStateException("Stored expectation result invalid", invalid);
            }
            catch(SecurityException denied)
            {
               throw new ClientFailure(404, "workflow_unavailable");
            }
         }
         if((kind.equals("report-status") || kind.equals("report-reconciliation")) && row.get("plan_result") != null)
         {
            try
            {
               JsonNode saved = JSON.readTree(row.get("plan_result").toString());
               var current = new ReportRecovery(service).inspect(scope(context), UUID.fromString(saved.get("request").asText()));
               scope(context);
               return new Result(status, current.request().toString(), current.instruction(), JSON.valueToTree(current));
            }
            catch(java.io.IOException invalid)
            {
               throw new IllegalStateException("Stored recovery outcome is invalid", invalid);
            }
            catch(SecurityException denied)
            {
               throw new ClientFailure(404, "workflow_unavailable");
            }
         }
         if(row.get("plan_id") != null)
         {
            try
            {
               service.transaction(c ->
               {
                  var authorized = scope(c, context);
                  PlanClientOperations.authorize(c, context, authorized, CarlService.number(row, "plan_id"));
                  return null;
               });
               var result = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(row.get("plan_result").toString());
               if(row.get("export_id") != null)
               {
                  byte[] bytes = new PlanExports(service).load(context.member().caller(), UUID.fromString(row.get("export_id").toString()));
                  if(bytes.length > 524288)
                  {
                     throw new ClientFailure(503, "export_size_limit");
                  }
                  result.put("mediaType", "application/pdf");
                  result.put("filename", "carl-plan-" + row.get("plan_id") + ".pdf");
                  result.put("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes));
               }
               scope(context);
               return new Result(status, "plan-" + row.get("plan_id"), "Saved Carl plan workflow; no external financial action", result);
            }
            catch(java.io.IOException malformed)
            {
               throw new IllegalStateException("Stored plan outcome is invalid", malformed);
            }
            catch(SecurityException denied)
            {
               throw new ClientFailure(404, "workflow_unavailable");
            }
         }
         if(row.get("artifact_id") == null)
         {
            return new Result(status, null, status == Status.UNKNOWN ? "Outcome requires reconciliation; do not replay with a new request ID." : "Carl workflow " + status.name().toLowerCase(java.util.Locale.ROOT), null);
         }
         try
         {
            var artifact = service.artifact(context.member().caller(), CarlService.number(row, "artifact_id"));
            var authorized = scope(context);
            JsonNode output = JSON.valueToTree(artifact);
            if(Set.of("tax-planning-packet", "focused-report", "bill-period-comparison", "balance-sheet", "rental-stress-report", "plan-effect-comparison").contains(kind))
            {
               output = new ArtifactPresentation(service).summary(authorized, CarlService.number(row, "artifact_id"));
            }
            if(kind.equals("report-export") || kind.equals("report-download"))
            {
               for(String principal : authorized.audience())
               {
                  service.artifact(principal, CarlService.number(row, "artifact_id"));
               }
               try
               {
                  var saved = JSON.readTree(row.get("plan_result").toString());
                  output = JSON.valueToTree(new ArtifactExports(service).page(authorized.principal(), UUID.fromString(saved.get("exportRequest").asText()), ArtifactExports.Format.valueOf(saved.get("format").asText()), saved.get("offset").intValue(), saved.get("limit").intValue()));
               }
               catch(java.io.IOException invalid)
               {
                  throw new IllegalStateException("Stored export manifest invalid", invalid);
               }
            }
            if(kind.equals("portfolio-comparison"))
            {
               output = new PortfolioPresentation(service).summary(authorized, CarlService.number(row, "artifact_id"));
            }
            if(kind.equals("purchase-options"))
            {
               output = new PurchasePresentation(service).summary(authorized, CarlService.number(row, "artifact_id"));
            }
            if(kind.equals("portfolio-detail") || kind.equals("purchase-detail") || kind.equals("artifact-section"))
            {
               for(String principal : authorized.audience())
               {
                  service.artifact(principal, CarlService.number(row, "artifact_id"));
               }
               try
               {
                  output = JSON.readTree(row.get("plan_result").toString());
               }
               catch(java.io.IOException invalid)
               {
                  throw new IllegalStateException("Stored detail page is invalid", invalid);
               }
            }
            scope(context);
            return new Result(status, row.get("artifact_id").toString(), artifact.get("status_label").toString(), output);
         }
         catch(SecurityException inaccessible)
         {
            throw new ClientFailure(404, "workflow_unavailable");
         }
      }



      @Override
      public void close() throws InterruptedException
      {
         CarlClientWorkflows.this.close();
      }
   }

   private JsonNode expectationOutput(CarlService.Scope scope, long expectation)
   {
      if(!new PlanEffects(service).expectationScope(scope.principal(), expectation).audience().equals(scope.audience()))
      {
         throw new SecurityException("Expectation audience unavailable");
      }
      return service.transaction(c ->
      {
         Map<String, Object> result = null;
         for(String principal : scope.audience())
         {
            var rows = CarlService.rows(c, "SELECT * FROM carl_plan_effect_view WHERE principal=? AND id=?", principal, expectation);
            if(rows.size() != 1)
            {
               throw new SecurityException("Expectation unavailable");
            }
            if(principal.equals(scope.principal()))
            {
               result = new java.util.LinkedHashMap<>(rows.getFirst());
            }
         }
         java.util.Objects.requireNonNull(result).remove("principal");
         return JSON.valueToTree(result);
      });
   }



   private CarlService.Scope scope(ClientWorkflow.Context context)
   {
      return service.transaction(c -> scope(c, context));
   }



   private CarlService.Scope scope(java.sql.Connection c, ClientWorkflow.Context context) throws java.sql.SQLException
   {
      var actor = CarlService.member(c, context.member().caller());
      if(!Long.toString(actor.id()).equals(context.member().id()) || !Long.toString(actor.householdId()).equals(context.member().household())
         || !Long.toString(actor.permissionRevision()).equals(context.member().permissionRevision()))
      {
         throw new ClientFailure(404, "workflow_unavailable");
      }
      var principals = new LinkedHashSet<String>();
      for(String participant : context.participants())
      {
         if(!participant.matches("[1-9][0-9]{0,17}"))
         {
            throw new ClientFailure(404, "workflow_unavailable");
         }
         var members = CarlService.rows(c, "SELECT principal FROM carl_member WHERE id=? AND household_id=? AND active", Long.parseLong(participant), actor.householdId());
         if(members.size() != 1)
         {
            throw new ClientFailure(404, "workflow_unavailable");
         }
         principals.add(members.getFirst().get("principal").toString());
      }
      if(!principals.contains(actor.principal()) || (!context.shared() && principals.size() != 1))
      {
         throw new ClientFailure(404, "workflow_unavailable");
      }
      return new CarlService.Scope(actor.principal(), principals);
   }



   private void finish(UUID id, String status, Long artifact)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_client_workflow SET status=?,artifact_id=? WHERE request_id=? AND status='PENDING'", status, artifact, id);
         return null;
      });
   }



   private static String audience(ClientWorkflow.Context context)
   {
      return String.join(",", context.participants().stream().sorted().toList());
   }



   private static java.util.List<Long> ids(JsonNode input)
   {
      return java.util.stream.StreamSupport.stream(input.spliterator(), false).map(JsonNode::longValue).toList();
   }



   private static java.util.List<BalanceSheets.PropertyChoice> propertyChoices(JsonNode input)
   {
      var choices = new java.util.ArrayList<BalanceSheets.PropertyChoice>();
      ids(input.get("estimatedProperties")).forEach(id -> choices.add(new BalanceSheets.PropertyChoice(id, BalanceSheets.Valuation.PROPERTY_ESTIMATE)));
      ids(input.get("linkedProperties")).forEach(id -> choices.add(new BalanceSheets.PropertyChoice(id, BalanceSheets.Valuation.LINKED_ACCOUNT)));
      return java.util.List.copyOf(choices);
   }



   private static PurchaseAssessments.CardInput cardInput(JsonNode card)
   {
      if(card != null && card.isNull())
      {
         return null;
      }
      if(card == null || !card.isObject())
      {
         throw new IllegalArgumentException("Card terms must be explicit or null");
      }
      var keys = new LinkedHashSet<String>();
      card.fieldNames().forEachRemaining(keys::add);
      if(!keys.equals(Set.of("account", "payoffDate", "graceConfirmed", "balanceReviewed", "evidence")) || !card.get("account").isIntegralNumber() || !card.get("account").canConvertToLong() || card.get("account").longValue() <= 0 || !(card.get("payoffDate").isNull() || card.get("payoffDate").isTextual()) || !card.get("graceConfirmed").isBoolean() || !card.get("balanceReviewed").isBoolean() || !card.get("evidence").isTextual())
      {
         throw new IllegalArgumentException("Explicit card terms are required");
      }
      CarlService.bounded(card.get("evidence").asText(), 4000, "Card terms evidence");
      LocalDate date = card.get("payoffDate").isNull() ? null : LocalDate.parse(card.get("payoffDate").asText());
      if(date != null && (date.getYear() < 1900 || date.getYear() > 2200))
      {
         throw new IllegalArgumentException("Bounded payoff date required");
      }
      return new PurchaseAssessments.CardInput(card.get("account").longValue(), date, card.get("graceConfirmed").booleanValue(), card.get("balanceReviewed").booleanValue(), card.get("evidence").asText());
   }



   private static void validateIds(JsonNode input, int maximum, boolean emptyAllowed)
   {
      if(input == null || !input.isArray() || input.size() > maximum || (!emptyAllowed && input.isEmpty()))
      {
         throw new IllegalArgumentException();
      }
      var unique = new java.util.HashSet<Long>();
      for(var id : input)
      {
         if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !unique.add(id.longValue()))
         {
            throw new IllegalArgumentException();
         }
      }
   }



   private static void validate(String kind, JsonNode input)
   {
      try
      {
         if(input == null || !input.isObject())
         {
            throw new IllegalArgumentException();
         }
         var keys = new LinkedHashSet<String>();
         input.fieldNames().forEachRemaining(keys::add);
         if(kind.equals("calendar-plan") || kind.equals("reminder-review"))
         {
            CalendarClientInput.validate(kind, input);
         }
         else if(kind.startsWith("plan-") && !Set.of("plan-expectation", "plan-effect-comparison").contains(kind))
         {
            PlanClientOperations.validate(kind, input);
         }
         else if(kind.equals("plan-expectation"))
         {
            if(!keys.equals(Set.of("plan", "version", "step", "kind", "account", "amount", "from", "through", "reason")))
            {
               throw new IllegalArgumentException();
            }
            for(String key : Set.of("plan", "account"))
            {
               if(!input.get(key).isIntegralNumber() || !input.get(key).canConvertToLong() || input.get(key).longValue() < 1)
               {
                  throw new IllegalArgumentException();
               }
            }
            if(!input.get("version").isIntegralNumber() || !input.get("version").canConvertToInt() || input.get("version").intValue() < 1)
            {
               throw new IllegalArgumentException();
            }
            for(String key : Set.of("step", "kind", "amount", "from", "through", "reason"))
            {
               if(!input.get(key).isTextual())
               {
                  throw new IllegalArgumentException();
               }
            }
            if(!UUID.fromString(input.get("step").asText()).toString().equals(input.get("step").asText()) || !input.get("amount").asText().matches("[0-9]{1,12}(\\.[0-9]{1,4})?"))
            {
               throw new IllegalArgumentException();
            }
            PlanEffects.Kind.valueOf(input.get("kind").asText());
            CarlService.interval(LocalDate.parse(input.get("from").asText()), LocalDate.parse(input.get("through").asText()));
            CarlService.bounded(input.get("reason").asText(), 2000, "Expectation reason");
         }
         else if(kind.equals("plan-effect-comparison"))
         {
            if(!keys.equals(Set.of("expectation", "transactions", "evidence")) || !input.get("expectation").isIntegralNumber() || !input.get("expectation").canConvertToLong() || input.get("expectation").longValue() < 1 || !input.get("evidence").isTextual())
            {
               throw new IllegalArgumentException();
            }
            validateIds(input.get("transactions"), 100, true);
            CarlService.bounded(input.get("evidence").asText(), 2000, "Observed selection evidence");
         }
         else if(kind.equals("report-export") || kind.equals("report-download"))
         {
            if(!keys.equals(kind.equals("report-export") ? Set.of("artifact", "format") : Set.of("exportRequest", "format", "offset", "limit")) || !input.get("format").isTextual())
            {
               throw new IllegalArgumentException();
            }
            ArtifactExports.Format.valueOf(input.get("format").asText());
            if(kind.equals("report-export"))
            {
               if(!input.get("artifact").isIntegralNumber() || !input.get("artifact").canConvertToLong() || input.get("artifact").longValue() < 1)
               {
                  throw new IllegalArgumentException();
               }
            }
            else if(!input.get("exportRequest").isTextual() || !UUID.fromString(input.get("exportRequest").asText()).toString().equals(input.get("exportRequest").asText()) || !input.get("offset").isIntegralNumber() || !input.get("offset").canConvertToInt() || input.get("offset").intValue() < 0 || input.get("offset").intValue() > 4_000_000 || !input.get("limit").isIntegralNumber() || !input.get("limit").canConvertToInt() || input.get("limit").intValue() < 1 || input.get("limit").intValue() > 65536)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("balance-sheet"))
         {
            if(!keys.equals(Set.of("asOf", "maximumAgeDays", "accounts", "estimatedProperties", "linkedProperties")) || !input.get("asOf").isTextual() || !input.get("maximumAgeDays").isIntegralNumber() || !input.get("maximumAgeDays").canConvertToInt() || input.get("maximumAgeDays").intValue() < 0 || input.get("maximumAgeDays").intValue() > 3660)
            {
               throw new IllegalArgumentException();
            }
            BalanceSheets.date(LocalDate.parse(input.get("asOf").asText()));
            validateIds(input.get("accounts"), 100, true);
            validateIds(input.get("estimatedProperties"), 100, true);
            validateIds(input.get("linkedProperties"), 100, true);
            var properties = propertyChoices(input);
            if(properties.size() > 100 || properties.stream().map(BalanceSheets.PropertyChoice::property).distinct().count() != properties.size() || properties.isEmpty() && input.get("accounts").isEmpty())
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("rental-stress-report"))
         {
            if(!keys.equals(Set.of("scenario")) || !input.get("scenario").isIntegralNumber() || !input.get("scenario").canConvertToLong() || input.get("scenario").longValue() < 1)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("report-status") || kind.equals("report-reconciliation"))
         {
            if(!keys.equals(kind.equals("report-status") ? Set.of("reportRequest") : Set.of("reportRequest", "reason")) || !input.get("reportRequest").isTextual() || !UUID.fromString(input.get("reportRequest").asText()).toString().equals(input.get("reportRequest").asText()))
            {
               throw new IllegalArgumentException();
            }
            if(kind.equals("report-reconciliation"))
            {
               if(!input.get("reason").isTextual())
               {
                  throw new IllegalArgumentException();
               }
               CarlService.bounded(input.get("reason").asText(), 4000, "reconciliation reason");
            }
         }
         else if(kind.equals("focused-report"))
         {
            if(!keys.equals(Set.of("focus", "from", "through")) || !input.get("focus").isTextual())
            {
               throw new IllegalArgumentException();
            }
            FocusedReports.Focus.valueOf(input.get("focus").asText());
            var interval = input.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) interval).remove("focus");
            validate("report", interval);
         }
         else if(kind.equals("bill-period-comparison"))
         {
            if(!keys.equals(Set.of("beforeFrom", "beforeThrough", "afterFrom", "afterThrough")))
            {
               throw new IllegalArgumentException();
            }
            for(String key : keys)
            {
               if(!input.get(key).isTextual())
               {
                  throw new IllegalArgumentException();
               }
            }
            CarlService.interval(LocalDate.parse(input.get("beforeFrom").asText()), LocalDate.parse(input.get("beforeThrough").asText()));
            CarlService.interval(LocalDate.parse(input.get("afterFrom").asText()), LocalDate.parse(input.get("afterThrough").asText()));
         }
         else if(kind.equals("tax-planning-packet"))
         {
            if(!keys.equals(Set.of("taxYear", "asOf", "properties", "alternatives", "references")) || !(input.get("taxYear").isNull() || (input.get("taxYear").isIntegralNumber() && input.get("taxYear").canConvertToInt() && input.get("taxYear").intValue() >= 1900 && input.get("taxYear").intValue() <= 2200)) || !input.get("asOf").isTextual())
            {
               throw new IllegalArgumentException();
            }
            java.time.Instant.parse(input.get("asOf").asText());
            validateIds(input.get("properties"), 20, false);
            validateIds(input.get("alternatives"), 40, true);
            validateIds(input.get("references"), 100, true);
         }
         else if(kind.equals("artifact-section"))
         {
            if(!keys.equals(Set.of("artifact", "path", "offset", "limit")) || !input.get("artifact").isIntegralNumber() || !input.get("artifact").canConvertToLong() || input.get("artifact").longValue() < 1 || !input.get("path").isTextual() || input.get("path").asText().length() > 1000 || !input.get("offset").isIntegralNumber() || !input.get("offset").canConvertToInt() || input.get("offset").intValue() < 0 || input.get("offset").intValue() > 2_097_152 || !input.get("limit").isIntegralNumber() || !input.get("limit").canConvertToInt() || input.get("limit").intValue() < 1 || input.get("limit").intValue() > 4096)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("purchase-options"))
         {
            if(!keys.equals(Set.of("cashPlan", "purchaseDate", "allInPrice", "purpose", "allInCostsKnown", "card", "offers")))
            {
               throw new IllegalArgumentException();
            }
            var cash = input.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) cash).remove(java.util.List.of("card", "offers"));
            validate("purchase-assessment", cash);
            validateIds(input.get("offers"), 5, true);
            cardInput(input.get("card"));
         }
         else if(kind.equals("purchase-detail"))
         {
            if(!keys.equals(Set.of("artifact", "option", "offset", "limit")) || !input.get("artifact").isIntegralNumber() || !input.get("artifact").canConvertToLong() || input.get("artifact").longValue() <= 0 || !input.get("option").isTextual() || input.get("option").asText().length() > 100 || !input.get("offset").isIntegralNumber() || !input.get("offset").canConvertToInt() || input.get("offset").intValue() < 0 || input.get("offset").intValue() > 601 || !input.get("limit").isIntegralNumber() || !input.get("limit").canConvertToInt() || input.get("limit").intValue() < 1 || input.get("limit").intValue() > 24)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("portfolio-detail"))
         {
            if(!keys.equals(Set.of("artifact", "section", "strategy", "offset", "limit")) || !input.get("artifact").isIntegralNumber() || !input.get("artifact").canConvertToLong() || input.get("artifact").longValue() <= 0 || !input.get("section").isTextual() || !Set.of("assumptions", "sources", "gaps", "months", "payoffDates", "cashDifferences").contains(input.get("section").asText()) || !(input.get("strategy").isNull() || input.get("strategy").isTextual()) || !input.get("offset").isIntegralNumber() || !input.get("offset").canConvertToInt() || input.get("offset").intValue() < 0 || input.get("offset").intValue() > 25000 || !input.get("limit").isIntegralNumber() || !input.get("limit").canConvertToInt() || input.get("limit").intValue() < 1 || input.get("limit").intValue() > 10)
            {
               throw new IllegalArgumentException();
            }
            if(Set.of("months", "payoffDates", "cashDifferences").contains(input.get("section").asText()))
            {
               if(!Set.of("CURRENT_PAYMENT", "MINIMUM_ONLY", "AVALANCHE", "SNOWBALL", "USER_DIRECTED").contains(input.get("strategy").asText()))
               {
                  throw new IllegalArgumentException();
               }
            }
            else if(!input.get("strategy").isNull())
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("portfolio-comparison"))
         {
            if(!keys.equals(Set.of("accounts", "moves", "asOf", "currency", "monthlyBudget", "horizonMonths", "rollover", "budgetEvidence")))
            {
               throw new IllegalArgumentException();
            }
            validateIds(input.get("accounts"), 100, false);
            validateIds(input.get("moves"), 20, true);
            var debtInput = input.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) debtInput).remove(java.util.List.of("accounts", "moves", "rollover"));
            validate("debt-comparison", debtInput);
            if(!input.get("rollover").isTextual())
            {
               throw new IllegalArgumentException();
            }
            FinancialPlanning.Strategy.valueOf(input.get("rollover").asText());
         }
         else if(kind.equals("report"))
         {
            if(!keys.equals(Set.of("from", "through")) || !input.get("from").isTextual() || !input.get("through").isTextual())
            {
               throw new IllegalArgumentException();
            }
            var from = LocalDate.parse(input.get("from").asText());
            var through = LocalDate.parse(input.get("through").asText());
            if(through.isBefore(from) || through.isAfter(from.plusYears(2)))
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("rental-report") || kind.equals("tax-packet"))
         {
            var expected = kind.equals("rental-report") ? Set.of("propertyIds", "from", "through", "asOf") : Set.of("propertyIds", "taxYear", "asOf");
            if(!keys.equals(expected) || !input.get("propertyIds").isArray() || input.get("propertyIds").isEmpty() || input.get("propertyIds").size() > 100 || !input.get("asOf").isTextual())
            {
               throw new IllegalArgumentException();
            }
            var unique = new java.util.HashSet<Long>();
            for(var id : input.get("propertyIds"))
            {
               if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !unique.add(id.longValue()))
               {
                  throw new IllegalArgumentException();
               }
            }
            var asOf = LocalDate.parse(input.get("asOf").asText());
            if(asOf.getYear() < 1900 || asOf.getYear() > 2200)
            {
               throw new IllegalArgumentException();
            }
            if(kind.equals("rental-report"))
            {
               if(!input.get("from").isTextual() || !input.get("through").isTextual())
               {
                  throw new IllegalArgumentException();
               }
               var from = LocalDate.parse(input.get("from").asText());
               var through = LocalDate.parse(input.get("through").asText());
               if(through.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, through) >= 366)
               {
                  throw new IllegalArgumentException();
               }
            }
            else if(!input.get("taxYear").isIntegralNumber() || !input.get("taxYear").canConvertToInt() || input.get("taxYear").intValue() < 1900 || input.get("taxYear").intValue() > 2200)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("offer-comparison"))
         {
            if(!keys.equals(Set.of("offerIds", "asOf")) || !input.get("offerIds").isArray() || input.get("offerIds").isEmpty() || input.get("offerIds").size() > 10 || !input.get("asOf").isTextual())
            {
               throw new IllegalArgumentException();
            }
            var ids = new java.util.HashSet<Long>();
            for(var id : input.get("offerIds"))
            {
               if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !ids.add(id.longValue()))
               {
                  throw new IllegalArgumentException();
               }
            }
            var asOf = LocalDate.parse(input.get("asOf").asText());
            if(asOf.getYear() < 1900 || asOf.getYear() > 2200)
            {
               throw new IllegalArgumentException();
            }
         }
         else if(kind.equals("purchase-assessment"))
         {
            if(!keys.equals(Set.of("cashPlan", "purchaseDate", "allInPrice", "purpose", "allInCostsKnown"))
               || !input.get("cashPlan").isIntegralNumber() || !input.get("cashPlan").canConvertToLong() || input.get("cashPlan").longValue() <= 0
               || !input.get("purchaseDate").isTextual() || !input.get("allInPrice").isTextual() || !input.get("purpose").isTextual()
               || !input.get("allInCostsKnown").isBoolean() || !input.get("allInPrice").asText().matches("[0-9]{1,12}(\\.[0-9]{1,4})?"))
            {
               throw new IllegalArgumentException();
            }
            var purchase = LocalDate.parse(input.get("purchaseDate").asText());
            if(purchase.getYear() < 1900 || purchase.getYear() > 2200)
            {
               throw new IllegalArgumentException();
            }
            CarlService.bounded(input.get("purpose").asText(), 500, "purchase purpose");
         }
         else if(kind.equals("debt-comparison"))
         {
            if(!keys.equals(Set.of("asOf", "currency", "monthlyBudget", "horizonMonths", "budgetEvidence"))
               || !input.get("asOf").isTextual() || !input.get("currency").isTextual() || !input.get("monthlyBudget").isTextual()
               || !input.get("budgetEvidence").isTextual() || !input.get("horizonMonths").isIntegralNumber()
               || !input.get("horizonMonths").canConvertToInt() || input.get("horizonMonths").intValue() < 1 || input.get("horizonMonths").intValue() > 600
               || !input.get("monthlyBudget").asText().matches("[0-9]{1,12}(\\.[0-9]{1,4})?"))
            {
               throw new IllegalArgumentException();
            }
            var asOf = LocalDate.parse(input.get("asOf").asText());
            if(asOf.getYear() < 1900 || asOf.getYear() > 2200 || new java.math.BigDecimal(input.get("monthlyBudget").asText()).signum() <= 0)
            {
               throw new IllegalArgumentException();
            }
            java.util.Currency.getInstance(input.get("currency").asText());
            CarlService.bounded(input.get("budgetEvidence").asText(), 4000, "budget evidence");
         }
         else if(!keys.equals(Set.of("workId", "purpose")) || !input.get("workId").canConvertToLong() || !input.get("workId").isIntegralNumber()
            || input.get("workId").longValue() <= 0 || !Set.of("FOLLOW_UP", "QUOTE_REQUEST", "SCHEDULING_INQUIRY", "SERVICE_QUESTION").contains(input.get("purpose").asText()))
         {
            throw new IllegalArgumentException();
         }
      }
      catch(RuntimeException invalid)
      {
         throw new ClientFailure(400, "invalid_workflow_input");
      }
   }



   @Override
   public void close() throws InterruptedException
   {
      workers.shutdownNow();
      if(!workers.awaitTermination(10, TimeUnit.SECONDS))
      {
         throw new IllegalStateException("Carl workflow workers are still stopping");
      }
   }
}
