/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL/service acceptance for the public fixture, including reimport/correction/privacy mutations. */
public final class CarlPublicHouseholdFixtureTest
{
   private CarlPublicHouseholdFixtureTest() {}
   @SuppressWarnings("unchecked")
   public static void main(String[] args) throws Exception
   {
      if(args.length != 1) throw new IllegalArgumentException("Exactly one public fixture directory required");
      Path directory = Path.of(args[0]);
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = com.kof22.agentadmin.bootstrap.NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(source);
         var service = new CarlService(source, Clock.systemUTC());
         service.transaction(c ->
         {
            CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic Household','America/Chicago')");
            CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Fictional Aster',true),(2,1,'bob','Fictional Basil',true)");
            CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
            return null;
         });
         for(String input : java.util.List.of("transactions.csv", "balances.csv", "household.json", "calendar.ics", "reminders.ics"))
         {
            Path changed = Files.createTempDirectory(directory.toAbsolutePath().getParent().getParent().resolve("target"), "public-bundle-corruption-");
            for(String name : java.util.List.of("household.json", "transactions.csv", "balances.csv", "calendar.ics", "reminders.ics"))
               Files.copy(directory.resolve(name), changed.resolve(name));
            var modified = changed.resolve(input);
            String before = Files.readString(modified);
            if(input.equals("transactions.csv")) before = before.replaceFirst("Fictional independently authored ledger event", "UNAPPROVED_PRIVATE_CANARY");
            else if(input.equals("balances.csv")) before = before.replaceFirst("50000.00", "UNAPPROVED_PRIVATE_CANARY");
            else if(input.equals("household.json")) before = before.replaceFirst("Fictional Household Cash", "UNAPPROVED_PRIVATE_CANARY");
            else before = before.replace("Fictional shared household review", "UNAPPROVED_PRIVATE_CANARY").replace("Fictional review maintenance quote", "UNAPPROVED_PRIVATE_CANARY");
            Files.writeString(modified, before);
            var denied = assertThrows(SecurityException.class, () -> CarlPublicHouseholdSeed.seed(service, changed));
            assertEquals("Canonical public fictional v1 bundle required; modified files cannot enter the fixture", denied.getMessage());
            assertEquals(Long.valueOf(0), counts(service).get("records"), "Corrupt input must fail before records");
            assertEquals(Long.valueOf(0), service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_request").getFirst(), "n")), "Corrupt input must fail before request claims");
         }
         System.out.println("PUBLIC_FIXTURE_STAGE=seed");
         var manifest = CarlPublicHouseholdSeed.seed(service, directory);
         var finance = new FinancialRecords(service);
         Map<String, Long> accounts = (Map<String, Long>) manifest.get("accountIds");
         Map<String, Long> properties = (Map<String, Long>) manifest.get("propertyIds");
         var authored = new ObjectMapper().readTree(Files.readString(directory.resolve("household.json")));
         var counts = counts(service);
         assertEquals(10920L, counts.get("transactions"));
         assertEquals(22365L, counts.get("balances"));
         assertEquals(36L, counts.get("accounts"));
         assertEquals(3L, counts.get("properties"));
         assertEquals(3L, counts.get("units"));
         assertEquals(4L, counts.get("bills"));
         for(var account : accounts.entrySet())
         {
            if(account.getKey().equals("Fictional Unvalued Collectible")) continue;
            var reconciled = finance.reconcile("alice", account.getValue(), LocalDate.of(2024, 12, 31), LocalDate.of(2026, 9, 30));
            assertEquals(0, ((BigDecimal) reconciled.get("unexplainedDifference")).signum(), account.getKey());
            assertEquals(0, new BigDecimal(authored.path("expected").path("closingByAccount").path(account.getKey()).asText()).compareTo((BigDecimal) reconciled.get("closing")), account.getKey());
         }
         var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
         var overview = finance.overview(shared, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
         assertEquals(0, new BigDecimal("136615.00").compareTo((BigDecimal) ((Map<?, ?>) overview.get("liquidBalancesByCurrency")).get("USD")));
         var actualFlows = (Map<String, BigDecimal>) overview.get("classifiedFlows");
         assertEquals(authored.path("expected").path("sharedSeptemberFlows").size(), actualFlows.size());
         for(var flow : actualFlows.entrySet()) assertEquals(0, new BigDecimal(authored.path("expected").path("sharedSeptemberFlows").path(flow.getKey()).asText()).compareTo(flow.getValue()), flow.getKey());
         assertFalse(service.view(shared, "accounts").stream().anyMatch(a -> a.get("id").equals(accounts.get("Fictional Aster Wallet"))));
         var homes = new HomeRecords(service);
         assertEquals("PRIMARY_RESIDENCE", homes.get("bob", properties.get("home")).get("property_use"));
         assertEquals(Integer.valueOf(0), service.transaction(c -> ((Number) CarlService.rows(c, "SELECT count(*) AS n FROM carl_rental_unit WHERE property_id=?", properties.get("home")).getFirst().get("n")).intValue()));
         assertEquals(Integer.valueOf(0), service.transaction(c -> ((Number) CarlService.rows(c, "SELECT count(*) AS n FROM carl_rent_due WHERE property_id=?", properties.get("home")).getFirst().get("n")).intValue()));
         var arrears = service.transaction(c -> (BigDecimal) CarlService.rows(c, "SELECT sum(d.amount-coalesce((SELECT sum(x.amount) FROM carl_rent_application_view x WHERE x.principal=d.principal AND x.rent_due_id=d.id AND x.active),0)) AS amount FROM carl_rent_due_view d WHERE d.principal='alice'").getFirst().get("amount"));
         assertEquals(0, new BigDecimal("250.00").compareTo(arrears));
         assertEquals("COMPLETE", service.artifact("bob", ((Number) manifest.get("reportId")).longValue()).get("narration_state"));
         assertEquals(Long.valueOf(2), service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_artifact_audience WHERE artifact_id=?", manifest.get("reportId")).getFirst(), "n")));
         var receipt = CarlPublicHouseholdSeed.seed(service, directory);
         assertEquals(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(manifest)), new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(receipt)));
         assertEquals(counts, counts(service), "Successful seed retry must not duplicate artifacts, revisions, balances or accounts");
         System.out.println("PUBLIC_FIXTURE_STAGE=dedup-corrections");
         var before = counts(service);
         finance.importTransactions("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-overlap.csv")), accounts, false);
         finance.importTransactions("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-omitted.csv")), accounts, false);
         assertEquals(before.get("transactions"), counts(service).get("transactions"), "Overlap does not duplicate; omission never deletes");
         assertThrows(IllegalArgumentException.class, () -> finance.importTransactions("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-correction.csv")), accounts, false));
         finance.importTransactions("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-correction.csv")), accounts, true);
         var corrected = service.transaction(c -> CarlService.rows(c, "SELECT notes,version FROM carl_transaction_source WHERE notes LIKE 'Fictional corrected multiline note%'").getFirst());
         assertEquals(2L, ((Number) corrected.get("version")).longValue());
         var original = service.transaction(c -> CarlService.rows(c, "SELECT notes FROM carl_transaction_source WHERE external_id=(SELECT external_id FROM carl_transaction_source WHERE notes LIKE 'Fictional corrected multiline note%') AND version=1").getFirst());
         assertEquals("Fictional independently authored ledger event", original.get("notes"));
         assertThrows(IllegalArgumentException.class, () -> finance.importTransactions("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-missingAmount.csv")), accounts, false));
         assertThrows(IllegalArgumentException.class, () -> finance.importBalances("alice", UUID.randomUUID(), Files.readString(directory.resolve("case-balanceConflict.csv")), accounts, false));
         assertEquals(before.get("transactions"), counts(service).get("transactions"));
         service.transaction(c -> { CarlService.execute(c, "UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'"); return null; });
         assertThrows(SecurityException.class, () -> homes.get("bob", properties.get("home")));
         assertThrows(SecurityException.class, () -> homes.history("bob", properties.get("home")));
         assertThrows(SecurityException.class, () -> service.artifact("bob", ((Number) manifest.get("balanceId")).longValue()));
         service.transaction(c -> { CarlService.execute(c, "UPDATE carl_household SET name='UNPREPARED TARGET' WHERE id=1"); return null; });
         assertThrows(SecurityException.class, () -> CarlPublicHouseholdSeed.seed(service, directory));
         System.out.println("PUBLIC_FIXTURE_PASS=" + new ObjectMapper().writeValueAsString(Map.of("mode", "controlled", "providerCalls", 0, "counts", before, "reconciledAccounts", 35, "privateSharedAndRevocation", "PASS", "duplicateSeed", "PASS", "overlapCorrectionConflict", "PASS")));
      }
   }
   private static Map<String, Long> counts(CarlService service)
   {
      return service.transaction(c ->
      {
         var result = new java.util.LinkedHashMap<String, Long>();
         for(var entry : Map.of("records", "carl_record", "accounts", "carl_account", "transactions", "carl_transaction", "balances", "carl_balance", "properties", "carl_property", "units", "carl_rental_unit", "bills", "carl_bill", "artifacts", "carl_artifact", "corrections", "carl_correction").entrySet())
            result.put(entry.getKey(), CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM " + entry.getValue()).getFirst(), "n"));
         return result;
      });
   }
}
