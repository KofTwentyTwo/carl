/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class BulkTransactionClassificationTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private BulkTransactionClassification bulk;
   private List<Long> ids;
   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Bulk test family','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','A',true),(2,1,'bob','B',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true)");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void seed()
   {
      bulk = new BulkTransactionClassification(service);
      ids = service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
         CarlService.execute(c, "UPDATE carl_permission SET details=true");
         long account = CarlService.record(c, CarlService.member(c, "alice"), "FINANCE", "FAMILY", "Test account", "Explicit test account evidence");
         CarlService.execute(c, "INSERT INTO carl_account(record_id,kind,currency,liquid,ownership_share) VALUES(?,'CASH','USD',true,1)", account);
         long otherAccount = CarlService.record(c, CarlService.member(c, "alice"), "FINANCE", "FAMILY", "Other test account", "Explicit transfer account evidence");
         CarlService.execute(c, "INSERT INTO carl_account(record_id,kind,currency,liquid,ownership_share) VALUES(?,'CASH','USD',true,1)", otherAccount);
         var values = new java.util.ArrayList<Long>();
         for(int i = 0; i < 101; i++)
         {
            long id = CarlService.record(c, CarlService.member(c, "alice"), "FINANCE", "FAMILY", "Test movement " + i, "Imported original evidence");
            CarlService.execute(c, "INSERT INTO carl_transaction(record_id,account_id,effective_date,amount,currency,classification,category,source_id) VALUES(?,?,'2026-09-01',?,'USD','UNCLASSIFIED','Source category',?)", id, i == 1 ? otherAccount : account, new java.math.BigDecimal(i == 1 ? "1.25" : "-1.25"), "source:" + i);
            values.add(id);
         }
         UUID request = UUID.randomUUID();
         CarlService.execute(c, "INSERT INTO carl_request(id,member_id,kind,digest,status) VALUES(?,1,'TEST_IMPORT',repeat('a',64),'COMPLETE')", request);
         long batch = CarlService.insert(c, "INSERT INTO carl_import(household_id,member_id,source_name,content_digest,request_id,row_count) VALUES(1,1,'Test original',repeat('b',64),?,1) RETURNING id", request);
         CarlService.execute(c, "INSERT INTO carl_transaction_source(household_id,external_id,transaction_id,version,payload_digest,import_id,logical_row,account_label,merchant,category,original_statement,notes,tags,owner_label,reviewed,amount,effective_date) VALUES(1,'opaque-source',?,1,repeat('c',64),?,2,'Source label','Test merchant','Original source category','Original statement','Original notes','','Unknown source owner','',-1.25,'2026-09-01')", values.getFirst(), batch);
         return List.copyOf(values);
      });
   }



   @Test
   void oneAndOneHundredCommitOnceAndRejectOutOfBounds()
   {
      var selected = ids.subList(0, 100);
      UUID request = UUID.randomUUID();
      var preview = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed category", "Human reviewed selected rows");
      assertThat(preview.count()).isEqualTo(100);
      assertThat(preview.summary()).contains("100", "EXPENSE", "Reviewed category");
      assertThat(bulk.apply("alice", request, selected, "EXPENSE", "Reviewed category", "Human reviewed selected rows", preview.token(), true)).isEqualTo(100);
      assertThat(bulk.apply("alice", request, selected, "EXPENSE", "Reviewed category", "Human reviewed selected rows", preview.token(), true)).isEqualTo(100);
      assertThat(corrections()).isEqualTo(100);
      assertThat(classification(ids.get(100))).isEqualTo("UNCLASSIFIED");
      UUID single = UUID.randomUUID();
      var one = bulk.preview("alice", single, List.of(ids.get(100)), "INCOME", "Reviewed income", "Human reviewed record");
      assertThat(bulk.apply("alice", single, List.of(ids.get(100)), "INCOME", "Reviewed income", "Human reviewed record", one.token(), true)).isEqualTo(1);
      assertThatThrownBy(() -> bulk.preview("alice", UUID.randomUUID(), List.of(), "EXPENSE", "Reviewed", "Evidence")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> bulk.preview("alice", UUID.randomUUID(), ids, "EXPENSE", "Reviewed", "Evidence")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> bulk.preview("alice", UUID.randomUUID(), List.of(ids.getFirst(), ids.getFirst()), "EXPENSE", "Reviewed", "Evidence")).isInstanceOf(IllegalArgumentException.class);
   }



   @Test
   void changedOrUnreadableRecordRejectsTheWholeBatch()
   {
      var selected = ids.subList(0, 3);
      UUID request = UUID.randomUUID();
      var preview = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed", "Evidence");
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_record SET owner_id=2,visibility='PRIVATE' WHERE id=?", selected.getLast());
         return null;
      });
      assertThatThrownBy(() -> bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Evidence", preview.token(), true)).isInstanceOf(SecurityException.class);
      assertThat(corrections()).isZero();
      assertThat(classification(selected.getFirst())).isEqualTo("UNCLASSIFIED");
   }



   @Test
   void transferOrUnconfirmedOperationNeverPartiallyClassifies()
   {
      new FinancialRecords(service).pairTransfer("alice", ids.get(0), ids.get(1), "Reviewed opposite transfer legs");
      assertThatThrownBy(() -> bulk.preview("alice", UUID.randomUUID(), ids.subList(0, 3), "EXPENSE", "Reviewed", "Evidence")).isInstanceOf(IllegalArgumentException.class);
      assertThat(classification(ids.get(2))).isEqualTo("UNCLASSIFIED");
      var selected = List.of(ids.get(2));
      UUID request = UUID.randomUUID();
      var preview = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed", "Evidence");
      assertThatThrownBy(() -> bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Evidence", preview.token(), false)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> bulk.preview("alice", UUID.randomUUID(), selected, "TRANSFER", "Reviewed", "Evidence")).isInstanceOf(IllegalArgumentException.class);
      assertThat(classification(ids.get(2))).isEqualTo("UNCLASSIFIED");
   }



   @Test
   void stalePreviewConflictingRetryAndOriginalSourceArePreserved()
   {
      var selected = List.of(ids.getFirst());
      UUID request = UUID.randomUUID();
      var stale = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed", "Evidence");
      new FinancialRecords(service).classify("alice", selected.getFirst(), "UNCLASSIFIED", "Human override", "Human revision");
      assertThatThrownBy(() -> bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Evidence", stale.token(), true)).isInstanceOf(IllegalArgumentException.class);
      var fresh = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed", "Evidence");
      bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Evidence", fresh.token(), true);
      assertThatThrownBy(() -> bulk.apply("alice", request, selected, "INCOME", "Reviewed", "Evidence", fresh.token(), true)).isInstanceOf(IllegalArgumentException.class);
      String original = service.transaction(c -> CarlService.rows(c, "SELECT category,notes,original_statement FROM carl_transaction_source WHERE transaction_id=?", selected.getFirst()).toString());
      assertThat(original).contains("Original source category", "Original notes", "Original statement");
      assertThat(corrections()).isEqualTo(2);
      assertThatThrownBy(() -> bulk.apply("bob", request, selected, "EXPENSE", "Reviewed", "Evidence", fresh.token(), true)).isInstanceOf(IllegalArgumentException.class);
   }



   @Test
   void auditFailureRollsBackEveryCorrectionAndSameRequestCanRetryAfterRecovery()
   {
      var selected = ids.subList(0, 2);
      UUID request = UUID.randomUUID();
      var preview = bulk.preview("alice", request, selected, "EXPENSE", "Reviewed", "Human reviewed both records");
      service.transaction(c ->
      {
         CarlService.execute(c, "CREATE FUNCTION bulk_test_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.record_id=" + selected.getLast() + " THEN RAISE EXCEPTION 'Controlled test audit failure'; END IF; RETURN NEW; END $$");
         CarlService.execute(c, "CREATE TRIGGER bulk_test_audit_failure BEFORE INSERT ON carl_correction FOR EACH ROW EXECUTE FUNCTION bulk_test_reject_audit()");
         return null;
      });
      try
      {
         assertThatThrownBy(() -> bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Human reviewed both records", preview.token(), true)).isInstanceOf(IllegalStateException.class);
         assertThat(corrections()).isZero();
         assertThat(classification(selected.getFirst())).isEqualTo("UNCLASSIFIED");
         assertThat(classification(selected.getLast())).isEqualTo("UNCLASSIFIED");
      }
      finally
      {
         service.transaction(c ->
         {
            CarlService.execute(c, "DROP TRIGGER bulk_test_audit_failure ON carl_correction");
            CarlService.execute(c, "DROP FUNCTION bulk_test_reject_audit()");
            return null;
         });
      }
      assertThat(bulk.apply("alice", request, selected, "EXPENSE", "Reviewed", "Human reviewed both records", preview.token(), true)).isEqualTo(2);
      assertThat(corrections()).isEqualTo(2);
   }



   @ParameterizedTest
   @ValueSource(strings = {"classify", "pair", "unpair", "account", "register"})
   void financialCorrectionsAndBulkUseTheSameLockOrder(String operation) throws Exception
   {
      var selected = List.of(ids.getFirst());
      UUID request = UUID.randomUUID();
      var preview = bulk.preview("alice", request, selected, "EXPENSE", "Bulk reviewed", "Reviewed bulk evidence");
      var financial = new FinancialRecords(service);
      if(operation.equals("unpair"))
      {
         financial.pairTransfer("alice", ids.get(0), ids.get(1), "Reviewed transfer evidence");
      }
      var imports = new MonarchImportWorkflow(service);
      UUID registrationReview = null;
      if(operation.equals("register"))
      {
         String reference = "lock-test-" + UUID.randomUUID();
         imports.storeUpload("alice", reference, "Date,Balance,Account\n2026-09-01,100.00,New observed source\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
         registrationReview = imports.preview("alice", List.of(reference));
      }
      UUID preparedReview = registrationReview;
      String table = operation.equals("register") ? "carl_record" : operation.equals("account") ? "carl_account" : "carl_transaction";
      long record = operation.equals("account") ? 1 : ids.getFirst();
      int barrierKey = 78231;
      String condition = operation.equals("register") ? "NEW.title='New observed source'" : "NEW.record_id=" + record;
      String event = operation.equals("register") ? "INSERT" : "UPDATE";
      service.transaction(c ->
      {
         CarlService.execute(c, "CREATE FUNCTION financial_test_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition + " THEN PERFORM pg_advisory_lock(" + barrierKey + "); PERFORM pg_advisory_unlock(" + barrierKey + "); END IF; RETURN NEW; END $$");
         CarlService.execute(c, "CREATE TRIGGER financial_test_pause BEFORE " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION financial_test_pause()");
         return null;
      });
      var executor = Executors.newFixedThreadPool(2);
      try(var barrier = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()))
      {
         CarlService.rows(barrier, "SELECT pg_advisory_lock(?)", barrierKey);
         try
         {
            var individual = executor.submit(() -> capture(() ->
            {
               switch(operation)
               {
                  case "classify" -> financial.classify("alice", ids.getFirst(), "EXPENSE", "Single reviewed", "Reviewed single evidence");
                  case "pair" -> financial.pairTransfer("alice", ids.get(0), ids.get(1), "Reviewed transfer evidence");
                  case "unpair" -> financial.unpairTransfer("alice", ids.getFirst(), "Reviewed unpair evidence");
                  case "register" -> imports.registerSourceAccounts("alice", preparedReview, "USD");
                  case "account" -> financial.reviewAccount("alice", 1, "OTHER_ASSET", false, java.math.BigDecimal.ONE, "Reviewed account evidence");
                  default -> throw new IllegalArgumentException("Unknown test operation");
               }
            }));
            awaitDatabaseLock(barrier, "SELECT count(*) AS count FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=" + barrierKey);
            var batch = executor.submit(() -> capture(() -> bulk.apply("alice", request, selected, "EXPENSE", "Bulk reviewed", "Reviewed bulk evidence", preview.token(), true)));
            awaitDatabaseLock(barrier, "SELECT count(*) AS count FROM pg_stat_activity WHERE datname=current_database() AND state='active' AND wait_event_type='Lock' AND wait_event IN ('transactionid','tuple')");
            CarlService.rows(barrier, "SELECT pg_advisory_unlock(?)", barrierKey);
            Throwable individualFailure = individual.get(15, TimeUnit.SECONDS);
            Throwable bulkFailure = batch.get(15, TimeUnit.SECONDS);
            assertThat(individualFailure).as("Individual correction must complete without a PostgreSQL deadlock").isNull();
            if(operation.equals("register"))
            {
               assertThat(bulkFailure).as("New independent source accounts must not deadlock an existing transaction correction").isNull();
               assertThat(imports.registerSourceAccounts("alice", preparedReview, "USD")).isZero();
               assertThat(bulk.apply("alice", request, selected, "EXPENSE", "Bulk reviewed", "Reviewed bulk evidence", preview.token(), true)).isEqualTo(1);
            }
            else
            {
               assertThat(bulkFailure).as("Bulk must report the real concurrent revision, not SQLSTATE 40P01").isInstanceOf(IllegalArgumentException.class);
               assertThat(bulkFailure.getMessage()).contains(operation.equals("pair") ? "transfer" : "preview");
            }
            if(operation.equals("pair"))
            {
               assertThat(classification(ids.getFirst())).isEqualTo("TRANSFER");
            }
            else if(!operation.equals("register"))
            {
               var fresh = bulk.preview("alice", request, selected, "EXPENSE", "Bulk reviewed", "Reviewed bulk evidence");
               assertThat(bulk.apply("alice", request, selected, "EXPENSE", "Bulk reviewed", "Reviewed bulk evidence", fresh.token(), true)).isEqualTo(1);
            }
         }
         finally
         {
            CarlService.rows(barrier, "SELECT pg_advisory_unlock(?)", barrierKey);
         }
      }
      finally
      {
         executor.shutdown();
         if(!executor.awaitTermination(20, TimeUnit.SECONDS))
         {
            executor.shutdownNow();
         }
         service.transaction(c ->
         {
            CarlService.execute(c, "DROP TRIGGER financial_test_pause ON " + table);
            CarlService.execute(c, "DROP FUNCTION financial_test_pause()");
            return null;
         });
      }
   }



   private static Throwable capture(Runnable operation)
   {
      try
      {
         operation.run();
         return null;
      }
      catch(RuntimeException failure)
      {
         return failure;
      }
   }



   private static void awaitDatabaseLock(java.sql.Connection connection, String query) throws Exception
   {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while(System.nanoTime() < deadline)
      {
         if(CarlService.number(CarlService.rows(connection, query).getFirst(), "count") > 0)
         {
            return;
         }
         Thread.sleep(20);
      }
      throw new AssertionError("Expected controlled PostgreSQL lock was not reached");
   }



   private long corrections()
   {
      return service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS count FROM carl_correction").getFirst(), "count"));
   }



   private String classification(long id)
   {
      return service.transaction(c -> CarlService.rows(c, "SELECT classification FROM carl_transaction WHERE record_id=?", id).getFirst().get("classification").toString());
   }
}
