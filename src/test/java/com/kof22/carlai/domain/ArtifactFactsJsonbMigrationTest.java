/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kof22.agentcore.store.AgentMigrations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Real PostgreSQL upgrade proof (issue #15): rows written while
 ** carl_artifact.facts was text convert to jsonb with exact decimals, and the
 ** existing view, selection and service reads return the same results.
 *******************************************************************************/
class ArtifactFactsJsonbMigrationTest
{
   private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

   private static final List<String> LEGACY_FACTS = List.of(
      "{\"calculationVersion\":\"rental-cash-v1\",\"totals\":{\"rent\":1500.00,\"expenses\":[12.5000,0.10]},\"label\":\"\\u0394 unit\"}",
      "{\"calculationVersion\":\"selected-balance-sheet-v1\",\"asOf\":\"2026-09-30\",\"net\":-1234.5600,\"text\":\"A𠀀B\",\"complex/key\":{\"tilde~field\":null}}",
      "{\"calculationVersion\":\"plan-effects-v1\",\"expectation\":{\"plan_id\":\"42\",\"expected_amount\":0.123456789012345678},\"outcome\":\"MET\"}",
      "{\"zeta\":1,\"alpha\":[3,2,1],\"nested\":{\"b\":true,\"a\":false},\"big\":12345678901234567890.0100}");

   private static final List<String> DEPENDENT_VIEWS = List.of("carl_artifact_view", "carl_draft_revision_view", "carl_plan_view", "carl_rental_baseline_selection_view",
      "carl_rental_shock_view", "carl_calendar_operation_view", "carl_plan_effect_view", "carl_plan_step_view", "carl_reminder_observation_view",
      "carl_native_calendar_operation_view", "carl_native_plan_step_view");

   private static final String LEGACY_BALANCE_SELECTION = "SELECT v.id,v.facts::jsonb->>'asOf' AS as_of,v.stale,v.status_label FROM carl_artifact_view v WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND v.facts::jsonb->>'calculationVersion'='selected-balance-sheet-v1' ORDER BY v.id DESC LIMIT 1001";
   private static final String TYPED_BALANCE_SELECTION = "SELECT v.id,selection.facts->>'asOf' AS as_of,v.stale,v.status_label FROM carl_artifact_view v JOIN carl_artifact selection ON selection.record_id=v.id WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND selection.facts->>'calculationVersion'='selected-balance-sheet-v1' ORDER BY v.id DESC LIMIT 1001";
   private static final String TYPED_PLAN_EFFECT_SELECTION = "SELECT v.id FROM carl_artifact_view v JOIN carl_artifact selection ON selection.record_id=v.id WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND selection.facts->>'calculationVersion'='plan-effects-v1' AND selection.facts->'expectation'->>'plan_id'=? ORDER BY v.id DESC LIMIT 101";
   private static final String LEGACY_PLAN_EFFECT_SELECTION = "SELECT v.id FROM carl_artifact_view v WHERE v.principal=? AND v.kind='FINANCIAL_PLAN' AND v.facts::jsonb->>'calculationVersion'='plan-effects-v1' AND v.facts::jsonb->'expectation'->>'plan_id'=? ORDER BY v.id DESC LIMIT 101";

   /*******************************************************************************
    **
    *******************************************************************************/
   @Test
   void textFactsConvertToJsonbAndExistingReadsReturnTheSameResults() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = new PGSimpleDataSource();
         source.setURL(database.getJdbcUrl());
         source.setUser(database.getUsername());
         source.setPassword(database.getPassword());
         Flyway.configure().dataSource(source).locations(AgentMigrations.CORE_LOCATION).table(AgentMigrations.CORE_HISTORY).load().migrate();
         Flyway.configure().dataSource(source).locations(AgentMigrations.DOMAIN_LOCATION).table(AgentMigrations.DOMAIN_HISTORY)
            .baselineOnMigrate(true).baselineVersion("0").target("62").load().migrate();
         var service = new CarlService(source, Clock.systemUTC());
         service.transaction(c ->
         {
            CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic facts','America/Chicago')");
            CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true)");
            CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT 1,d,true FROM unnest(ARRAY['FINANCE','BILLS']) d");
            return null;
         });
         assertEquals("text", columnType(service, "carl_artifact"));

         var ids = new ArrayList<Long>();
         for(String facts : LEGACY_FACTS)
         {
            long id = save(service, facts.contains("calculationVersion") ? "FINANCIAL_PLAN" : "HOUSEHOLD_REPORT", "{}");
            service.transaction(c ->
            {
               CarlService.execute(c, "UPDATE carl_artifact SET facts=? WHERE record_id=?", facts, id);
               return null;
            });
            ids.add(id);
         }

         Map<String, Object> before = observe(service, ids);
         AgentMigrations.migrate(source);
         Map<String, Object> after = observe(service, ids);

         assertEquals("jsonb", columnType(service, "carl_artifact"));
         assertEquals("text", columnType(service, "carl_artifact_view"));
         assertEquals(before, after);
         assertEquals(List.of(ids.get(1)), ((List<?>) after.get("balanceSelection")).stream().map(row -> ((Map<?, ?>) row).get("id")).toList());
         assertEquals(List.of(ids.get(0)), ((List<?>) after.get("rentalSelection")).stream().map(row -> ((Map<?, ?>) row).get("id")).toList());
         assertEquals(List.of(Map.of("id", ids.get(2))), after.get("planEffectSelection"));
         assertEquals(after.get("balanceSelection"), service.transaction(c -> CarlService.rows(c, TYPED_BALANCE_SELECTION, "alice")));
         assertEquals(after.get("planEffectSelection"), service.transaction(c -> CarlService.rows(c, TYPED_PLAN_EFFECT_SELECTION, "alice", "42")));
         String storedText = service.transaction(c -> CarlService.rows(c, "SELECT string_agg(facts,' ' ORDER BY id) AS facts FROM carl_artifact_view WHERE principal='alice'").getFirst().get("facts").toString());
         for(String exact : List.of("1500.00", "12.5000", "-1234.5600", "0.123456789012345678", "12345678901234567890.0100", "\u0394", "\uD840\uDC00"))
         {
            assertTrue(storedText.contains(exact), exact);
         }

         service.transaction(c ->
         {
            CarlService.execute(c, "SET LOCAL enable_seqscan=off");
            var plan = CarlService.rows(c, "EXPLAIN SELECT record_id FROM carl_artifact WHERE facts->>'calculationVersion'='rental-cash-v1'");
            assertTrue(plan.toString().contains("carl_artifact_calculation_version"), plan.toString());
            return null;
         });

         long created = save(service, "FINANCIAL_PLAN", "{\"calculationVersion\":\"plan-effects-v1\",\"expectation\":{\"plan_id\":\"42\"},\"amount\":7.10}");
         assertEquals(JSON.readTree("{\"calculationVersion\":\"plan-effects-v1\",\"expectation\":{\"plan_id\":\"42\"},\"amount\":7.10}"), JSON.readTree(service.artifact("alice", created).get("facts").toString()));
         assertEquals(List.of(Map.of("id", created), Map.of("id", ids.get(2))), service.transaction(c -> CarlService.rows(c, LEGACY_PLAN_EFFECT_SELECTION, "alice", "42")));
         assertThrows(RuntimeException.class, () -> save(service, "FINANCIAL_PLAN", "{\"unterminated\":"));
      }
   }



   private static long save(CarlService service, String kind, String facts) throws Exception
   {
      long epoch = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=1").getFirst(), "permission_revision"));
      return service.saveArtifact(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), kind, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), facts, "Synthetic narrative", "NOT_REQUESTED",
         "Synthetic limitations", Map.of(), null, "Synthetic", "digest-" + UUID.randomUUID(), epoch);
   }



   private static String columnType(CarlService service, String table) throws Exception
   {
      return service.transaction(c -> CarlService.rows(c, "SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name='facts'", table).getFirst().get("data_type").toString());
   }



   private static Map<String, Object> observe(CarlService service, List<Long> ids) throws Exception
   {
      var observed = new LinkedHashMap<String, Object>();
      var viewFacts = new LinkedHashMap<Long, JsonNode>();
      var serviceFacts = new LinkedHashMap<Long, JsonNode>();
      for(long id : ids)
      {
         serviceFacts.put(id, JSON.readTree(service.artifact("alice", id).get("facts").toString()));
      }
      var viewRows = service.transaction(c -> CarlService.rows(c, "SELECT id,facts FROM carl_artifact_view WHERE principal='alice' ORDER BY id"));
      for(var row : viewRows)
      {
         viewFacts.put(CarlService.number(row, "id"), JSON.readTree(row.get("facts").toString()));
      }
      service.transaction(c ->
      {
         observed.put("balanceSelection", CarlService.rows(c, LEGACY_BALANCE_SELECTION, "alice"));
         observed.put("planEffectSelection", CarlService.rows(c, LEGACY_PLAN_EFFECT_SELECTION, "alice", "42"));
         observed.put("rentalSelection", CarlService.rows(c, "SELECT * FROM carl_rental_baseline_selection_view WHERE principal='alice' ORDER BY id"));
         for(String view : DEPENDENT_VIEWS)
         {
            observed.put(view, CarlService.rows(c, "SELECT count(*) AS total FROM " + view));
         }
         return null;
      });
      observed.put("viewFacts", viewFacts);
      observed.put("serviceFacts", serviceFacts);
      return observed;
   }
}
