/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.HomeMetadataFixture;
import com.kof22.carlai.MutationReceiptTestControl;
import com.kof22.carlai.domain.CarlService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Native fixed receipts must truthfully acknowledge their own committed scope-changing edits. */
class CarlMutationReceiptNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String SELECTION = "Complete selection replaced. Selected reserves are additional to the base floor and protected for the full interval. Remove overlapping manual events or reserve assumptions before relying on a purchase assessment. This guided form selects up to four schedules and four standalone payments.";
   private static final String EXPENSE = "Schedule corrected with attribution; dependent payment applications must be reviewed before material changes.";
   private static final String PROPERTY = "Current facts corrected with attribution; original evidence and prior values retained.";

   private static final String MONARCH = "Reviewed import processing finished. Open Import Reviews and Transactions for the current outcome and affected records. Original source evidence and revisions are retained.";
   private static final String MONARCH_CSV = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-01,Private initial merchant,Food,Checking,Synthetic,,-12.30,,,Reviewed,monarch-receipt-source\n";

   @Test
   void reviewedMonarchRemapReturnsCommittedReceiptOnV1() throws Exception
   {
      monarchRemap(true, false, false);
   }



   @Test
   void reviewedMonarchRemapReturnsCommittedReceiptOnLegacy() throws Exception
   {
      monarchRemap(false, false, false);
   }



   @Test
   void uploadedMonarchRemapKeepsGeneratedReviewAndReturnsReceipt() throws Exception
   {
      monarchRemap(true, true, false);
   }



   @Test
   void monarchAsyncCannotAcknowledgeIntermediateCommitBeforeReviewStatusCommit() throws Exception
   {
      monarchRemap(true, false, true);
   }



   @Test
   void privateAndStaleMonarchReviewsCannotBorrowReceiptOrMutateSource() throws Exception
   {
      exercise(context ->
      {
         var finance = new com.kof22.carlai.domain.FinancialRecords(context.service());
         var workflow = new com.kof22.carlai.domain.MonarchImportWorkflow(context.service());
         long original = context.account();
         long destination = context.account();
         finance.importTransactions("alice", UUID.randomUUID(), MONARCH_CSV, Map.of("Checking", original), false);
         workflow.mapAccount("alice", "Checking", destination);
         UUID own = reviewedUpload(workflow, "alice", MONARCH_CSV.replace("initial merchant", "revised merchant"));
         UUID other = reviewedUpload(workflow, "bob", MONARCH_CSV.replace("monarch-receipt-source", "private-bob-source"));
         String process = context.start("carlResumeMonarch");
         var privateResult = context.submit("carlResumeMonarch", process, Map.of("reviewId", other.toString(), "confirm", "true", "acceptRevisions", "true"));
         assertTrue(privateResult.statusCode() >= 400 || JSON.readTree(privateResult.body()).hasNonNull("error"), privateResult.body());
         assertFalse(privateResult.body().contains(MONARCH), privateResult.body());
         assertEquals(1, context.count("SELECT count(*) FROM carl_transaction_source WHERE external_id='monarch-receipt-source'"));
         context.sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
         var fields = Map.of("reviewId", own.toString(), "confirm", "true", "acceptRevisions", "true");
         assertEquals(403, context.submit("carlResumeMonarch", process, fields).statusCode());
         assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + original));
         context.sql("UPDATE carl_permission SET details=true WHERE member_id=1 AND domain='FINANCE'");
         assertEquals(403, context.submit("carlResumeMonarch", process, fields).statusCode());
         String fresh = context.start("carlResumeMonarch");
         assertReceipt(context.submit("carlResumeMonarch", fresh, fields), MONARCH);
         assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + destination));
      });
   }



   @Test
   void monarchIndependentMembershipChangeBetweenCommitsNeverFinishesReceipt() throws Exception
   {
      exercise(context ->
      {
         var finance = new com.kof22.carlai.domain.FinancialRecords(context.service());
         var workflow = new com.kof22.carlai.domain.MonarchImportWorkflow(context.service());
         long original = context.account();
         long destination = context.account();
         finance.importTransactions("alice", UUID.randomUUID(), MONARCH_CSV, Map.of("Checking", original), false);
         workflow.mapAccount("alice", "Checking", destination);
         UUID review = reviewedUpload(workflow, "alice", MONARCH_CSV.replace("initial merchant", "revised merchant"));
         String process = context.start("carlResumeMonarch");
         context.control().commitSqlPrefix.set("UPDATE carl_transaction SET account_id=");
         context.control().afterCommit.set(() -> context.sql("UPDATE carl_household SET display_zone='UTC' WHERE id=1"));
         var fields = Map.of("reviewId", review.toString(), "confirm", "true", "acceptRevisions", "true");
         var denied = context.submit("carlResumeMonarch", process, fields);
         assertEquals(403, denied.statusCode(), denied.body());
         assertFalse(denied.body().contains(MONARCH), denied.body());
         assertFalse(denied.body().contains("Private"), denied.body());
         assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + destination));
         assertEquals(0, context.count("SELECT count(*) FROM carl_import_review WHERE id='" + review + "' AND status='COMPLETE'"));
         context.assertExpired("carlResumeMonarch", process);
         String fresh = context.start("carlResumeMonarch");
         assertReceipt(context.submit("carlResumeMonarch", fresh, fields), MONARCH);
         assertEquals(2, context.count("SELECT count(*) FROM carl_transaction_source WHERE external_id='monarch-receipt-source'"));
      });
   }



   private static UUID reviewedUpload(com.kof22.carlai.domain.MonarchImportWorkflow workflow, String principal, String csv)
   {
      String reference = "synthetic-upload-" + UUID.randomUUID();
      workflow.storeUpload(principal, reference, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return workflow.preview(principal, List.of(reference));
   }



   private static void monarchRemap(boolean v1, boolean upload, boolean async) throws Exception
   {
      exercise(context ->
      {
         var finance = new com.kof22.carlai.domain.FinancialRecords(context.service());
         var workflow = new com.kof22.carlai.domain.MonarchImportWorkflow(context.service());
         long original = context.account();
         long destination = context.account();
         finance.importTransactions("alice", UUID.randomUUID(), MONARCH_CSV, Map.of("Checking", original), false);
         workflow.mapAccount("alice", "Checking", destination);
         String revised = MONARCH_CSV.replace("initial merchant", "revised merchant");
         UUID review;
         String process;
         String name = upload ? "carlImportMonarch" : "carlResumeMonarch";
         if(upload)
         {
            var preview = context.uploadMonarch(revised);
            process = preview.getKey();
            review = preview.getValue();
         }
         else
         {
            String reference = "synthetic-upload-" + UUID.randomUUID();
            workflow.storeUpload("alice", reference, revised.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            review = workflow.preview("alice", List.of(reference));
            process = context.start(name, v1);
         }
         String route = (v1 ? "/qqq/v1" : "") + "/processes/" + name + "/" + process + "/step/" + (upload ? "review" : "input");
         var fields = new java.util.LinkedHashMap<String, String>();
         fields.put("confirm", "true");
         fields.put("acceptRevisions", "true");
         if(!upload)
         {
            fields.put("reviewId", review.toString());
         }
         assertEquals(403, context.request(route, context.bob(), fields).statusCode());
         if(upload)
         {
            var forged = new java.util.LinkedHashMap<>(fields);
            forged.put("reviewId", UUID.randomUUID().toString());
            var denied = context.request(route, context.alice(), forged);
            assertTrue(denied.statusCode() >= 400 || JSON.readTree(denied.body()).hasNonNull("error"), denied.body());
            assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + original));
         }
         long epoch = context.service().member("alice").permissionRevision();
         HttpResponse<String> result;
         if(async)
         {
            context.control().commitSqlPrefix.set("UPDATE carl_import_review SET status=");
            context.control().mode.set(MutationReceiptTestControl.Mode.BLOCK_COMMIT);
            String job;
            try(var executor = java.util.concurrent.Executors.newSingleThreadExecutor())
            {
               var submission = executor.submit(() -> context.request(route, context.alice(), fields));
               try
               {
                  assertTrue(context.control().reached.await(10, java.util.concurrent.TimeUnit.SECONDS));
                  var started = submission.get(10, java.util.concurrent.TimeUnit.SECONDS);
                  assertSuccess(started);
                  assertEquals("JOB_STARTED", JSON.readTree(started.body()).path("type").asText(), started.body());
                  job = JSON.readTree(started.body()).path("jobUUID").asText();
                  var pending = context.status(name, process, job, v1);
                  assertEquals(200, pending.statusCode(), pending.body());
                  assertEquals("RUNNING", JSON.readTree(pending.body()).path("type").asText(), pending.body());
                  assertFalse(pending.body().contains(MONARCH), pending.body());
                  assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + destination));
                  assertEquals(0, context.count("SELECT count(*) FROM carl_import_review WHERE id='" + review + "' AND status='COMPLETE'"));
               }
               finally
               {
                  context.control().release.countDown();
               }
            }
            result = context.awaitStatus(name, process, job, v1);
            assertEquals(403, context.status(name, process, job, v1).statusCode());
         }
         else
         {
            result = context.request(route, context.alice(), fields);
         }
         assertEquals(1, context.count("SELECT count(*) FROM carl_transaction WHERE account_id=" + destination));
         assertEquals(2, context.count("SELECT count(*) FROM carl_transaction_source WHERE external_id='monarch-receipt-source'"));
         assertEquals(1, context.count("SELECT count(*) FROM carl_import_review WHERE id='" + review + "' AND status='COMPLETE'"));
         assertTrue(context.service().member("alice").permissionRevision() > epoch);
         assertReceipt(result, MONARCH);
         context.assertExpired(name, process);
         assertEquals(403, context.request(route, context.alice(), fields).statusCode());
         assertEquals(2, context.count("SELECT count(*) FROM carl_transaction_source WHERE external_id='monarch-receipt-source'"));
         String retry = context.start("carlResumeMonarch", v1);
         assertReceipt(context.submit("carlResumeMonarch", retry, Map.of("reviewId", review.toString(), "confirm", "true", "acceptRevisions", "true"), v1, false), MONARCH);
         assertEquals(2, context.count("SELECT count(*) FROM carl_transaction_source WHERE external_id='monarch-receipt-source'"));
      });
   }



   @Test
   void activeReceiptCapacityReleasesExactlyOnceAndKeepsJobIdentityIsolated()
   {
      var instance = new com.kingsrook.qqq.backend.core.model.metadata.QInstance();
      var member = new CarlService.Member(1, 1, "synthetic-owner", true, java.time.ZoneId.of("UTC"), 1);
      var states = new java.util.ArrayList<com.kof22.carlai.domain.NativeMutationReceipt.State>();
      try
      {
         for(int i = 0; i < 10000; i++)
         {
            states.add(com.kof22.carlai.domain.NativeMutationReceipt.open(instance, "carlSelectCashExpenses", UUID.randomUUID().toString(), member));
         }
         assertThrows(SecurityException.class, () -> com.kof22.carlai.domain.NativeMutationReceipt.open(instance, "carlSelectCashExpenses", UUID.randomUUID().toString(), member));
         var first = states.getFirst();
         com.kof22.carlai.domain.NativeMutationReceipt.close(first);
         com.kof22.carlai.domain.NativeMutationReceipt.close(first);
         String process = UUID.randomUUID().toString();
         String job = UUID.randomUUID().toString();
         var replacement = com.kof22.carlai.domain.NativeMutationReceipt.open(instance, "carlSelectCashExpenses", process, member);
         states.add(replacement);
         replacement.bindJob(job);
         assertEquals(replacement, com.kof22.carlai.domain.NativeMutationReceipt.forStatus(instance, "carlSelectCashExpenses", process, job));
         assertEquals(null, com.kof22.carlai.domain.NativeMutationReceipt.forStatus(new com.kingsrook.qqq.backend.core.model.metadata.QInstance(), "carlSelectCashExpenses", process, job));
         assertEquals(null, com.kof22.carlai.domain.NativeMutationReceipt.forStatus(instance, "carlCorrectExpense", process, job));
         assertEquals(null, com.kof22.carlai.domain.NativeMutationReceipt.forStatus(instance, "carlSelectCashExpenses", process, UUID.randomUUID().toString()));
         assertFalse(replacement.acknowledge(member));
         assertThrows(SecurityException.class, () -> replacement.bindJob(UUID.randomUUID().toString()));
         assertThrows(SecurityException.class, () -> com.kof22.carlai.domain.NativeMutationReceipt.open(instance, "carlSelectCashExpenses", UUID.randomUUID().toString(), member));
      }
      finally
      {
         states.forEach(com.kof22.carlai.domain.NativeMutationReceipt::close);
      }
      var next = com.kof22.carlai.domain.NativeMutationReceipt.open(instance, "carlSelectCashExpenses", UUID.randomUUID().toString(), member);
      com.kof22.carlai.domain.NativeMutationReceipt.close(next);
   }



   @Test
   void defaultV1TimeoutRetainsPendingReceiptUntilActualCommit() throws Exception
   {
      asyncReceipt(true, false, false);
   }



   @Test
   void legacyAsyncReceiptUsesNativeJobBindingAndFlatFields() throws Exception
   {
      asyncReceipt(false, true, false);
   }



   @Test
   void defaultLegacyTimeoutRetainsPendingReceiptUntilActualCommit() throws Exception
   {
      asyncReceipt(false, false, false);
   }



   @Test
   void immediateV1TimeoutRetainsPendingReceiptUntilActualCommit() throws Exception
   {
      asyncReceipt(true, true, false);
   }



   @Test
   void asyncMembershipChangeAfterCommitStillDeniesAcknowledgement() throws Exception
   {
      asyncReceipt(true, false, true);
   }



   @Test
   void legacyFlatInputReturnsFixedReceiptsForAllThreeMutations() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String selection = context.start("carlSelectCashExpenses", false);
         assertReceipt(context.submit("carlSelectCashExpenses", selection, Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private legacy selection"), false, false), SELECTION);
         context.assertExpired("carlSelectCashExpenses", selection);
         long property = context.property();
         String correction = context.start("carlCorrectExpense", false);
         var fields = new java.util.LinkedHashMap<>(scheduleFields());
         fields.putAll(Map.of("expense", Long.toString(expense), "expectedRevision", "1", "property", Long.toString(property), "reason", "Private legacy correction"));
         assertReceipt(context.submit("carlCorrectExpense", correction, fields, false, false), EXPENSE);
         context.assertExpired("carlCorrectExpense", correction);
         long account = context.account();
         String rental = context.start("carlCorrectRentalProperty", false);
         assertReceipt(context.submit("carlCorrectRentalProperty", rental, Map.of("property", Long.toString(property), "expectedRevision", "1", "currency", "USD", "locality", "Private legacy locality", "assetAccount", Long.toString(account), "reason", "Private legacy correction"), false, false), PROPERTY);
         context.assertExpired("carlCorrectRentalProperty", rental);
      });
   }



   @Test
   void directLegacyRunIsRejectedBeforeAnyMutation() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         var denied = context.request("/processes/carlSelectCashExpenses/run", context.alice(), Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private direct run"));
         assertTrue(denied.statusCode() >= 400, denied.body());
         assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
         assertEquals(0, context.count("SELECT count(*) FROM carl_correction WHERE record_id=" + plan));
      });
   }



   private static void asyncReceipt(boolean v1, boolean immediate, boolean revoke) throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses", v1);
         var fields = Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private delayed selection");
         context.control().mode.set(MutationReceiptTestControl.Mode.BLOCK_COMMIT);
         if(revoke)
         {
            context.control().afterCommit.set(() -> context.sql("UPDATE carl_household SET display_zone='UTC' WHERE id=1"));
         }
         String job;
         try(var executor = java.util.concurrent.Executors.newSingleThreadExecutor())
         {
            var submitted = executor.submit(() -> context.submit("carlSelectCashExpenses", process, fields, v1, immediate));
            try
            {
               if(!immediate)
               {
                  assertTrue(context.control().reached.await(10, java.util.concurrent.TimeUnit.SECONDS));
               }
               var started = submitted.get(10, java.util.concurrent.TimeUnit.SECONDS);
               assertSuccess(started);
               var body = JSON.readTree(started.body());
               assertEquals("JOB_STARTED", body.path("type").asText(), started.body());
               job = body.path("jobUUID").asText();
               assertFalse(job.isBlank(), started.body());
               assertTrue(context.control().reached.await(10, java.util.concurrent.TimeUnit.SECONDS));
               var pending = context.status("carlSelectCashExpenses", process, job, v1);
               assertEquals(200, pending.statusCode(), pending.body());
               var state = JSON.readTree(pending.body());
               assertTrue("RUNNING".equals(state.path("type").asText()) || "RUNNING".equals(state.path("jobStatus").path("state").asText()), pending.body());
               assertFalse(pending.body().contains(SELECTION), pending.body());
               assertFalse(pending.body().contains("Private"), pending.body());
               assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
               assertEquals(403, context.request((v1 ? "/qqq/v1" : "") + "/processes/carlSelectCashExpenses/" + process + "/status/" + job, context.bob(), null).statusCode());
            }
            finally
            {
               context.control().release.countDown();
            }
         }
         var completed = context.awaitStatus("carlSelectCashExpenses", process, job, v1);
         if(revoke)
         {
            assertEquals(403, completed.statusCode(), completed.body());
            assertFalse(completed.body().contains("Private"), completed.body());
         }
         else
         {
            assertReceipt(completed, SELECTION);
         }
         assertEquals(1, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
         assertEquals(1, context.count("SELECT count(*) FROM carl_correction WHERE record_id=" + plan));
         assertEquals(403, context.status("carlSelectCashExpenses", process, job, v1).statusCode());
         context.assertExpired("carlSelectCashExpenses", process);
      });
   }



   @Test
   void cashSelectionReturnsFixedReceiptAfterItsOwnCommittedEpochChange() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         long epoch = context.service().member("alice").permissionRevision();
         var result = context.submit("carlSelectCashExpenses", process, Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private selected facts must not be echoed"));
         assertEquals(1, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan + " AND expense_id=" + expense));
         assertTrue(context.service().member("alice").permissionRevision() > epoch);
         assertReceipt(result, SELECTION);
         context.assertExpired("carlSelectCashExpenses", process);
      });
   }



   @Test
   void expensePropertyRelinkReturnsFixedReceiptAfterCommit() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long property = context.property();
         String process = context.start("carlCorrectExpense");
         var fields = new java.util.LinkedHashMap<>(scheduleFields());
         fields.putAll(Map.of("expense", Long.toString(expense), "expectedRevision", "1", "property", Long.toString(property), "reason", "Private relink evidence must not be echoed"));
         var result = context.submit("carlCorrectExpense", process, fields);
         assertEquals(1, context.count("SELECT count(*) FROM carl_expense WHERE record_id=" + expense + " AND property_id=" + property));
         assertReceipt(result, EXPENSE);
         context.assertExpired("carlCorrectExpense", process);
      });
   }



   @Test
   void rentalAccountRelinkReturnsFixedReceiptAfterCommit() throws Exception
   {
      exercise(context ->
      {
         long property = context.property();
         long account = context.account();
         String process = context.start("carlCorrectRentalProperty");
         var result = context.submit("carlCorrectRentalProperty", process, Map.of("property", Long.toString(property), "expectedRevision", "1", "currency", "USD", "locality", "Private corrected location", "assetAccount", Long.toString(account), "reason", "Private relink evidence must not be echoed"));
         assertEquals(1, context.count("SELECT count(*) FROM carl_property WHERE record_id=" + property + " AND asset_account_id=" + account));
         assertReceipt(result, PROPERTY);
         context.assertExpired("carlCorrectRentalProperty", process);
      });
   }



   @Test
   void exactDomainRetryIsIdempotentAndStaleNativeRetryIsDenied() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         var fields = Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Human exact selection");
         assertReceipt(context.submit("carlSelectCashExpenses", process, fields), SELECTION);
         long epoch = context.service().member("alice").permissionRevision();
         new com.kof22.carlai.domain.ExpenseRecords(context.service()).attachCashPlan("alice", context.requests().get(process), plan, java.util.Set.of(expense), java.util.Set.of(), "Human exact selection");
         assertEquals(epoch, context.service().member("alice").permissionRevision());
         assertEquals(1, context.count("SELECT count(*) FROM carl_correction WHERE record_id=" + plan));
         assertEquals(403, context.submit("carlSelectCashExpenses", process, fields).statusCode());
         assertFalse(context.start("carlSelectCashExpenses").isBlank());
      });
   }



   @Test
   void revokedPrefilledInputAndAnonymousOrOtherCallerNeverProduceReceipts() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         var fields = Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private submitted evidence");
         String route = "/qqq/v1/processes/carlSelectCashExpenses/" + process + "/step/input";
         assertEquals(401, context.request(route, null, fields).statusCode());
         assertEquals(403, context.request(route, context.bob(), fields).statusCode());
         context.sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='FINANCE'");
         var denied = context.submit("carlSelectCashExpenses", process, fields);
         assertEquals(403, denied.statusCode(), denied.body());
         assertFalse(denied.body().contains("Private"), denied.body());
         assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
         context.sql("UPDATE carl_permission SET details=true WHERE member_id=1 AND domain='FINANCE'");
         assertEquals(403, context.submit("carlSelectCashExpenses", process, fields).statusCode());
      });
   }



   @Test
   void revocationAfterNativeScopeCaptureRejectsBeforeLockedMutation() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         context.control().beforeRequest.set(() -> context.sql("UPDATE carl_member SET can_manage=false WHERE id=1"));
         var denied = context.submit("carlSelectCashExpenses", process, Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Human selection"));
         assertEquals(403, denied.statusCode(), denied.body());
         assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
      });
   }



   @Test
   void failedCommitRollsBackAndCannotIssueSuccessReceipt() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         long epoch = context.service().member("alice").permissionRevision();
         context.control().mode.set(MutationReceiptTestControl.Mode.FAIL_COMMIT);
         var failed = context.submit("carlSelectCashExpenses", process, Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Human selection"));
         assertTrue(failed.statusCode() >= 400 || JSON.readTree(failed.body()).hasNonNull("error"), failed.body());
         assertFalse(failed.body().contains(SELECTION), failed.body());
         assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
         assertEquals(0, context.count("SELECT count(*) FROM carl_correction WHERE record_id=" + plan));
         assertEquals(epoch, context.service().member("alice").permissionRevision());
         assertReceipt(context.submit("carlSelectCashExpenses", context.start("carlSelectCashExpenses"), Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Human selection")), SELECTION);
      });
   }



   @Test
   void concurrentRequestCannotBorrowTheFirstRequestsCommitAttestation() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         var fields = Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Human concurrent selection");
         context.control().mode.set(MutationReceiptTestControl.Mode.BLOCK_COMMIT);
         try(var executor = java.util.concurrent.Executors.newSingleThreadExecutor())
         {
            var first = executor.submit(() -> context.submit("carlSelectCashExpenses", process, fields));
            try
            {
               assertTrue(context.control().reached.await(10, java.util.concurrent.TimeUnit.SECONDS));
               assertEquals(403, context.submit("carlSelectCashExpenses", process, fields).statusCode());
            }
            finally
            {
               context.control().release.countDown();
            }
            assertReceipt(first.get(10, java.util.concurrent.TimeUnit.SECONDS), SELECTION);
         }
         assertEquals(1, context.count("SELECT count(*) FROM carl_correction WHERE record_id=" + plan));
      });
   }



   @Test
   void postCommitMembershipChangeAndIndependentReadScopeStillReject() throws Exception
   {
      exercise(context ->
      {
         long expense = context.expense();
         long plan = context.cashPlan();
         String process = context.start("carlSelectCashExpenses");
         context.control().afterCommit.set(() -> context.sql("UPDATE carl_household SET display_zone='UTC' WHERE id=1"));
         var denied = context.submit("carlSelectCashExpenses", process, Map.of("plan", Long.toString(plan), "expense1", Long.toString(expense), "evidence", "Private submitted evidence"));
         assertEquals(403, denied.statusCode(), denied.body());
         assertFalse(denied.body().contains("Private"), denied.body());
         assertEquals(1, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
         context.assertExpired("carlSelectCashExpenses", process);
         String second = context.start("carlSelectCashExpenses");
         context.control().beforeRequest.set(() -> com.kof22.carlai.domain.NativeReadScope.check(context.service().member("alice")));
         var scoped = context.submit("carlSelectCashExpenses", second, Map.of("plan", Long.toString(plan), "evidence", "Private replacement evidence"));
         assertEquals(403, scoped.statusCode(), scoped.body());
         assertFalse(scoped.body().contains("Private"), scoped.body());
         assertEquals(0, context.count("SELECT count(*) FROM carl_cash_expense_selection WHERE record_id=" + plan));
      });
   }



   private static void exercise(Scenario scenario) throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(data);
         var control = new MutationReceiptTestControl();
         var service = new CarlService(control.wrap(data), Clock.systemUTC());
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic vendor home','America/Chicago')");
            sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic other',true)");
            sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'VENDORS',true),(2,'VENDORS',true),(1,'BILLS',true),(2,'BILLS',true),(1,'CALENDAR',true),(2,'CALENDAR',true),(1,'FINANCE',true),(2,'FINANCE',true)");
            sql.execute("CREATE ROLE carl_report_reader LOGIN PASSWORD 'synthetic-reader'");
            sql.execute("GRANT USAGE ON SCHEMA public TO carl_report_reader");
            for(var table : AdminApplication.READER_COLUMNS.entrySet())
            {
               if(!table.getValue().isEmpty())
               {
                  sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_report_reader");
               }
            }
            sql.execute("GRANT SELECT ON carl_vendor_view,carl_work_view,carl_artifact_view,carl_draft_revision_view,carl_member_view,carl_native_plan_step_view,carl_transaction_view,carl_expense_view,carl_cash_plan_view,carl_rental_property_view TO carl_report_reader");
         }
         var generator = KeyPairGenerator.getInstance("RSA");
         generator.initialize(2048);
         var pair = generator.generateKeyPair();
         var publicKey = (RSAPublicKey) pair.getPublic();
         var key = Jwk.fromValues(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())));
         var keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
         String issuer = "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/";
         byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "fixture", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray())))));
         keyServer.createContext("/.well-known/jwks.json", exchange ->
         {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try(var output = exchange.getResponseBody())
            {
               output.write(jwks);
            }
         });
         keyServer.start();
         var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> key);
         var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
         var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_report_reader", "synthetic-reader");
         var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
         var app = new AdminApplication(reader, List.of(new HomeMetadataFixture(service)), identity, runtime);
         try(var server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), config ->
         {
            config.routes.beforeMatched(context ->
            {
               Runnable callback = control.beforeRequest.getAndSet(null);
               if(callback != null)
               {
                  callback.run();
               }
            });
         }, new NativeDownloadPolicy(Map.of("carlProtectedVendorDrafts", "carlDownloadVendorDraft"))); var http = HttpClient.newHttpClient())
         {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            String alice = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            String bob = JWT.create().withKeyId("fixture").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(300)).sign(Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate()));
            scenario.run(new Context(http, base, alice, bob, data, service, control, new java.util.HashMap<>()));

         }
         finally
         {
            keyServer.stop(0);
         }
      }
   }



   private static Map<String, String> scheduleFields()
   {
      return Map.of("currency", "USD", "cadence", "MONTHLY", "firstDue", "2026-09-01", "amount", "100.00", "kind", "EXPENSE", "basis", "COMMITTED");
   }



   private static void assertReceipt(HttpResponse<String> response, String message) throws Exception
   {
      assertEquals(200, response.statusCode(), response.body());
      var result = JSON.readTree(response.body());
      assertFalse(result.hasNonNull("error"), response.body());
      assertEquals("COMPLETE", result.path("type").asText());
      assertEquals(message, result.path("values").path("result").asText());
      var keys = new java.util.HashSet<String>();
      result.path("values").fieldNames().forEachRemaining(keys::add);
      assertEquals(java.util.Set.of("result", "result.html"), keys, response.body());
      assertFalse(response.body().contains("Private"), response.body());
   }



   private static void assertSuccess(HttpResponse<String> response) throws Exception
   {
      assertEquals(200, response.statusCode(), response.body());
      assertFalse(JSON.readTree(response.body()).hasNonNull("error"), response.body());
   }

   @FunctionalInterface
   private interface Scenario
   {
      void run(Context context) throws Exception;
   }



   private record Context(HttpClient http, String base, String alice, String bob, javax.sql.DataSource data, CarlService service, MutationReceiptTestControl control, Map<String, UUID> requests)
   {
      long expense()
      {
         return new com.kof22.carlai.domain.ExpenseRecords(service).create("alice", UUID.randomUUID(), "Private expense facts", "PRIVATE", "Private expense source evidence", new com.kof22.carlai.domain.ExpenseRecords.Schedule("USD", com.kof22.carlai.domain.ExpenseForecast.Cadence.MONTHLY, java.time.LocalDate.of(2026, 9, 1), null, new java.math.BigDecimal("100.00"), Map.of(), com.kof22.carlai.domain.ExpenseForecast.Kind.EXPENSE, com.kof22.carlai.domain.ExpenseForecast.Basis.COMMITTED, null));
      }



      long property()
      {
         return new com.kof22.carlai.domain.RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Private property facts", "PRIVATE", "Private property source evidence", new com.kof22.carlai.domain.RentalRecords.PropertyValues("USD", "Private location", null, null, null, null, null, null, null, null, null, null, null));
      }



      long account()
      {
         return new com.kof22.carlai.domain.FinancialRecords(service).createAccount("alice", "Private account facts", "OTHER_ASSET", "USD", false, java.math.BigDecimal.ONE, "PRIVATE", "Private account source evidence");
      }



      long cashPlan()
      {
         return new com.kof22.carlai.domain.CashPlans(service).create("alice", "Private cash plan facts", "PRIVATE", "Private cash source evidence", new com.kof22.carlai.domain.CashPlans.Assumptions("USD", java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30), new java.math.BigDecimal("1000"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, true, true, true, true, true, true));
      }



      void sql(String query)
      {
         try(var c = data.getConnection(); var sql = c.createStatement())
         {
            sql.execute(query);
         }
         catch(java.sql.SQLException failure)
         {
            throw new IllegalStateException("Controlled test SQL failed", failure);
         }
      }



      int count(String query) throws Exception
      {
         try(var c = data.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery(query))
         {
            assertTrue(rows.next());
            return rows.getInt(1);
         }
      }



      String start(String name) throws Exception
      {
         return start(name, true);
      }



      String start(String name, boolean v1) throws Exception
      {
         var started = request((v1 ? "/qqq/v1" : "") + "/processes/" + name + "/init", alice, Map.of());
         assertSuccess(started);
         String id = JSON.readTree(started.body()).path("processUUID").asText();
         assertFalse(id.isBlank(), started.body());
         String request = JSON.readTree(started.body()).path("values").path("requestId").asText();
         if(!request.isBlank())
         {
            requests.put(id, UUID.fromString(request));
         }
         return id;
      }



      Map.Entry<String, UUID> uploadMonarch(String csv) throws Exception
      {
         String process = start("carlImportMonarch");
         String body = "--carl-upload\r\nContent-Disposition: form-data; name=\"values\"\r\n\r\n{}\r\n"
            + "--carl-upload\r\nContent-Disposition: form-data; name=\"transactionsFile\"; filename=\"transactions.csv\"\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n--carl-upload--\r\n";
         var request = HttpRequest.newBuilder(URI.create(base + "/qqq/v1/processes/carlImportMonarch/" + process + "/step/upload"))
            .header("Origin", "https://carl.synthetic").header("Authorization", "Bearer " + alice)
            .header("Content-Type", "multipart/form-data; boundary=carl-upload").POST(HttpRequest.BodyPublishers.ofString(body)).build();
         var preview = http.send(request, HttpResponse.BodyHandlers.ofString());
         assertSuccess(preview);
         return Map.entry(process, UUID.fromString(JSON.readTree(preview.body()).path("values").path("reviewId").asText()));
      }



      HttpResponse<String> submit(String name, String id, Map<String, String> fields) throws Exception
      {
         return request("/qqq/v1/processes/" + name + "/" + id + "/step/input", alice, fields);
      }



      HttpResponse<String> submit(String name, String id, Map<String, String> fields, boolean v1, boolean immediate) throws Exception
      {
         String route = (v1 ? "/qqq/v1" : "") + "/processes/" + name + "/" + id + "/step/input";
         if(immediate)
         {
            route += v1 ? "?stepTimeoutMillis=0" : "?_qStepTimeoutMillis=0";
         }
         return request(route, alice, fields);
      }



      HttpResponse<String> status(String name, String id, String job, boolean v1) throws Exception
      {
         return request((v1 ? "/qqq/v1" : "") + "/processes/" + name + "/" + id + "/status/" + job, alice, null);
      }



      HttpResponse<String> awaitStatus(String name, String id, String job, boolean v1) throws Exception
      {
         long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
         HttpResponse<String> response;
         do
         {
            response = status(name, id, job, v1);
            var body = JSON.readTree(response.body());
            if(response.statusCode() != 200 || body.hasNonNull("error") || "COMPLETE".equals(body.path("type").asText()))
            {
               return response;
            }
            Thread.sleep(10);
         }
         while(System.nanoTime() < deadline);
         throw new AssertionError("Native job did not finish within controlled deadline");
      }



      void assertExpired(String name, String id) throws Exception
      {
         for(String prefix : List.of("/qqq/v1", ""))
         {
            for(String path : List.of("/step/result", "/status/" + UUID.randomUUID(), "/records?skip=0&limit=20"))
            {
               var response = request(prefix + "/processes/" + name + "/" + id + path, alice, path.equals("/step/result") ? Map.of() : null);
               assertEquals(403, response.statusCode(), response.body());
               assertFalse(response.body().contains("Private"), response.body());
            }
            assertEquals(403, request(prefix + "/processes/" + name + "/" + id + "/step/result", bob, Map.of()).statusCode());
         }
      }



      HttpResponse<String> request(String route, String token, Map<String, String> fields) throws Exception
      {
         return CarlMutationReceiptNativeTest.request(http, base, route, token, fields);
      }
   }

   private static HttpResponse<String> request(HttpClient http, String base, String route, String token, Map<String, String> fields) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + route)).header("Origin", "https://carl.synthetic");
      if(token != null)
      {
         request.header("Authorization", "Bearer " + token);
      }
      if(fields == null)
      {
         request.GET();
      }
      else
      {
         if(route.startsWith("/qqq/v1/") && route.contains("/step/"))
         {
            fields = Map.of("values", JSON.writeValueAsString(fields));
         }
         var body = new StringBuilder();
         fields.forEach((key, value) -> body.append("--carl-vendor\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
         body.append("--carl-vendor--\r\n");
         request.header("Content-Type", "multipart/form-data; boundary=carl-vendor").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
