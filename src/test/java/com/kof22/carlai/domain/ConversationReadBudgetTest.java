/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.carlai.CarlTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;


/** CHAT-01/REL-04: real PostgreSQL source catalogs fit the actual 16,000-character tool boundary without hiding rows or totals. */
class ConversationReadBudgetTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final ObjectMapper JSON = new ObjectMapper();
   private static CarlService service;

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
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Controlled bounded-read family','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','A',true),(2,1,'bob','B',true)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         CarlService.execute(c, "INSERT INTO carl_record(id,household_id,owner_id,domain,visibility,title,evidence) SELECT n,1,1,'FINANCE',CASE WHEN n=80 THEN 'PRIVATE' ELSE 'FAMILY' END,repeat('Source account ',12)||n,repeat('Original imported evidence ',65) FROM generate_series(1,80) n");
         CarlService.execute(c, "INSERT INTO carl_account(record_id,currency,kind,liquid,ownership_share,review_state) SELECT id,'USD','UNCLASSIFIED',NULL,NULL,'NEEDS_REVIEW' FROM carl_record");
         CarlService.execute(c, "UPDATE carl_account SET kind='CASH',liquid=true,ownership_share=1,review_state='CONFIRMED' WHERE record_id=1");
         CarlService.execute(c, "INSERT INTO carl_balance(account_id,as_of,amount,basis,evidence) VALUES(1,'2026-09-30',125.25,'STATEMENT','Controlled statement')");
         CarlService.execute(c, "UPDATE carl_record SET evidence=repeat('Original imported evidence ',700) WHERE id=1");
         CarlService.rows(c, "SELECT setval(pg_get_serial_sequence('carl_record','id'),80)");
         return null;
      });
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @Test
   void requestedPageSizeIsAMaximumAndAdaptiveCursorsNeverSkipRecords()
   {
      var reads = new ConversationReads(service);
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long cursor = 0;
      var visited = new HashSet<Long>();
      int pages = 0;
      do
      {
         var page = reads.records(scope, "accounts", cursor, 100, null, null, null);
         assertThat(CarlService.json(page).length()).isLessThanOrEqualTo(12000);
         assertThat(page.get("matchedRecords")).isEqualTo(79L);
         @SuppressWarnings("unchecked")
         var rows = (List<Map<String, Object>>) page.get("records");
         assertThat(rows).isNotEmpty().hasSizeLessThanOrEqualTo(100);
         for(var row : rows)
         {
            long id = CarlService.number(row, "id");
            assertThat(visited.add(id)).isTrue();
            assertThat(row).containsKey("sourceReference").doesNotContainKey("evidence");
            assertThat(row.get("omittedFields").toString()).contains("evidence");
         }
         Object next = page.get("nextCursor");
         if(next == null)
         {
            break;
         }
         assertThat(((Number) next).longValue()).isEqualTo(CarlService.number(rows.getLast(), "id"));
         cursor = ((Number) next).longValue();
         assertThat(++pages).isLessThan(20);
      }
      while(true);
      assertThat(visited).hasSize(79).doesNotContain(80L);
      assertThat(reads.record(scope, "accounts", 1).get("record").toString()).contains("Original imported evidence");
   }



   @Test
   void compactFinanceToolPreservesExactQualifiedTotalsAndExplicitCoverage() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      var expected = new FinancialRecords(service).overview(scope, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
      assertThat(CarlService.json(expected).length()).isGreaterThan(16000);
      var tool = CarlTools.bind(service, () -> scope).stream().filter(binding -> binding.definition().name().equals("carl_read_finances")).findFirst().orElseThrow();
      var result = tool.executor().execute("{\"from\":\"2026-09-01\",\"through\":\"2026-09-30\"}", "alice");
      assertThat(result.isError()).isFalse();
      assertThat(result.content().length()).isLessThanOrEqualTo(12000);
      var actual = JSON.readTree(result.content());
      for(String key : List.of("signedBalancesByCurrency", "liquidBalancesByCurrency", "classifiedFlows", "transactionCount"))
      {
         assertThat(actual.get(key)).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected.get(key))));
      }
      assertThat(actual.path("signedBalancesByCurrency").path("USD").decimalValue()).isEqualByComparingTo("125.25");
      assertThat(actual.path("unreviewedSourceAccounts").asInt()).isEqualTo(79);
      assertThat(actual.path("accountCount").asInt()).isEqualTo(80);
      assertThat(actual.path("gapsLimited").asBoolean()).isTrue();
      assertThat(actual.path("detailTools").toString()).contains("carl_read_page", "carl_read_record", "carl_read_transactions");
      assertThat(actual.has("accounts")).isFalse();
      assertThat(actual.has("transactions")).isFalse();
   }



   @Test
   void protectedSectionsNavigateFullUntruncatedRecordTextAndDenyPrivateSharedRecords() throws Exception
   {
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      var binding = CarlTools.bind(service, () -> scope).stream().filter(tool -> tool.definition().name().equals("carl_read_detail_section")).findFirst();
      assertThat(binding).isPresent();
      var tool = binding.orElseThrow();
      var preview = JSON.valueToTree(new ConversationReads(service).record(scope, "accounts", 1));
      var target = preview.path("detailTargets").get(0);
      assertThat(target.path("source").asText()).isEqualTo("record");
      assertThat(target.path("version").isNull()).isTrue();
      String wrapperPath = "/record/" + preview.path("record").path("truncatedFields").get(0).asText();
      String normalized = wrapperPath.substring(target.path("previewPrefix").asText().length());
      assertThat(normalized).isEqualTo("/evidence");
      var complete = new StringBuilder();
      int offset = 0;
      do
      {
         var input = JSON.createObjectNode().put("source", "record").put("kind", "accounts").put("id", 1).putNull("version").put("pointer", normalized).put("offset", offset).put("limit", 4096);
         var result = tool.executor().execute(input.toString(), "alice");
         assertThat(result.isError()).isFalse();
         assertThat(result.content().length()).isLessThanOrEqualTo(12000);
         var page = JSON.readTree(result.content()).path("section");
         complete.append(page.path("value").asText());
         if(page.path("nextOffset").isNull())
         {
            break;
         }
         offset = page.path("nextOffset").asInt();
      }
      while(true);
      assertThat(complete.toString()).isEqualTo("Original imported evidence ".repeat(700));
      var privateInput = JSON.createObjectNode().put("source", "record").put("kind", "accounts").put("id", 80).putNull("version").put("pointer", "/evidence").put("offset", 0).put("limit", 4096);
      assertThat(tool.executor().execute(privateInput.toString(), "alice").isError()).isTrue();
      assertThat(tool.executor().execute(privateInput.put("source", "arbitrary_file").toString(), "alice").isError()).isTrue();
   }



   @Test
   void largePlanHistoryIsBoundedAndFullImmutableSnapshotRemainsNavigable() throws Exception
   {
      var scope = CarlService.Scope.privateFor("alice");
      long artifact = service.saveArtifact(scope, UUID.randomUUID(), "FINANCIAL_PLAN", null, null, "{}", "Controlled assumptions", "NOT_REQUESTED", "Not execution", Map.of(1L, 1L), null, "Comparison", "controlled-history", service.member("alice").permissionRevision());
      var plans = new PlanLifecycle(service);
      long plan = plans.create("alice", UUID.randomUUID(), artifact, "Controlled plan history", "Human request");
      int version = 1;
      for(int i = 0; i < 6; i++)
      {
         UUID step = UUID.randomUUID();
         version = plans.step("alice", plan, version, step, "Controlled task " + i, 1, LocalDate.parse("2026-10-01"), "Controlled location ".repeat(80), null, "Human assignment");
         version = plans.checkIn("alice", plan, version, step, "TODO", "Controlled check-in ".repeat(180), null);
      }
      var reads = new ConversationReads(service);
      var bounded = reads.plan(scope, plan, Integer.MAX_VALUE, 25);
      assertThat(CarlService.json(bounded).length()).isLessThanOrEqualTo(12000);
      var tool = CarlTools.bind(service, () -> scope).stream().filter(binding -> binding.definition().name().equals("carl_read_detail_section")).findFirst();
      assertThat(tool).isPresent();
      var input = JSON.createObjectNode().put("source", "planVersion").put("kind", "plans").put("id", plan).put("version", version).put("pointer", "/steps/0/checkin").put("offset", 0).put("limit", 4096);
      var result = tool.orElseThrow().executor().execute(input.toString(), "alice");
      assertThat(result.isError()).isFalse();
      assertThat(JSON.readTree(result.content()).path("section").path("value").asText()).isEqualTo("Controlled check-in ".repeat(180));
   }



   @Test
   void immutableTransactionVersionsKeepFullTextAndCurrentSharedPermissions() throws Exception
   {
      String original = "Original source note ".repeat(400);
      String revised = "Revised source note ".repeat(400);
      String header = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n";
      String row = "2026-09-01,Controlled source,Utilities,Shared source,Controlled statement," + original + ",-1.25,,,Reviewed,100000000000000091\n";
      var finance = new FinancialRecords(service);
      finance.importTransactions("alice", UUID.randomUUID(), header + row, Map.of("Shared source", 1L), false);
      finance.importTransactions("alice", UUID.randomUUID(), header + row.replace(original, revised), Map.of("Shared source", 1L), true);
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long id = CarlService.number(service.view(scope, "transactions").stream().filter(value -> value.get("source_id").equals("100000000000000091")).findFirst().orElseThrow(), "id");
      var reads = new ConversationReads(service);
      var sourcePreview = JSON.valueToTree(reads.record(scope, "transactions", id));
      var sourceTarget = sourcePreview.path("detailTargets").get(1);
      assertThat(sourceTarget.path("source").asText()).isEqualTo("transactionSource");
      assertThat(sourceTarget.path("version").asInt()).isEqualTo(2);
      assertThat("/latestSource/notes".substring(sourceTarget.path("previewPrefix").asText().length())).isEqualTo("/notes");
      var first = reads.sourceHistory(scope, "transactions", id, 9007199254740991L, 1);
      assertThat(CarlService.json(first).length()).isLessThanOrEqualTo(12000);
      assertThat(first.get("nextBefore")).isEqualTo(2);
      assertThat(reads.sourceHistory(scope, "transactions", id, 2, 1).get("nextBefore")).isNull();
      var tool = CarlTools.bind(service, () -> scope).stream().filter(binding -> binding.definition().name().equals("carl_read_detail_section")).findFirst().orElseThrow();
      var input = JSON.createObjectNode().put("source", "transactionSource").put("kind", "transactions").put("id", id).put("version", 1).put("pointer", "/notes").put("offset", 0).put("limit", 4096);
      var full = new StringBuilder();
      do
      {
         var result = tool.executor().execute(input.toString(), "alice");
         assertThat(result.isError()).isFalse();
         assertThat(result.content().length()).isLessThanOrEqualTo(12000);
         var section = JSON.readTree(result.content()).path("section");
         full.append(section.path("value").asText());
         if(section.path("nextOffset").isNull())
         {
            break;
         }
         input.put("offset", section.path("nextOffset").asInt());
      }
      while(true);
      assertThat(full.toString()).isEqualTo(original);
      service.transaction(c ->
      {
         CarlService.execute(c, "DELETE FROM carl_permission WHERE member_id=2 AND domain='FINANCE'");
         return null;
      });
      try
      {
         var denied = tool.executor().execute(input.put("offset", 0).toString(), "alice");
         assertThat(denied.isError()).isTrue();
         assertThat(denied.content()).doesNotContain("Original source note", "Revised source note");
      }
      finally
      {
         service.transaction(c ->
         {
            CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) VALUES(2,'FINANCE',true)");
            return null;
         });
      }
   }
}
