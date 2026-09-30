/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RentalRecordsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static RentalRecords rentals;
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      rentals = new RentalRecords(service);
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic rental household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic reader',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','TAX']) d");
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void clear()
   {
      sql("TRUNCATE carl_record,carl_import,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_member SET active=true WHERE principal IN ('alice','bob')");
      sql("UPDATE carl_permission SET details=true");
   }



   private static BigDecimal n(String value)
   {
      return new BigDecimal(value);
   }



   private static RentalRecords.PropertyValues values(String ownership)
   {
      return new RentalRecords.PropertyValues("USD", "Synthetic Chester", ownership == null ? null : n(ownership), null, null, null, null, null, null, null, null, null, null);
   }



   private static long property(String visibility, String ownership)
   {
      return rentals.createProperty("alice", UUID.randomUUID(), "Synthetic house", visibility, "Synthetic deed reference", values(ownership));
   }



   private static long transaction(String signedAmount, String identity, String visibility)
   {
      var finances = new FinancialRecords(service);
      long account = finances.createAccount("alice", "Synthetic property checking", "CASH", "USD", true, BigDecimal.ONE, visibility, "Synthetic account evidence");
      finances.importTransactions("alice", UUID.randomUUID(), "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-10,Synthetic,Unclassified,Checking,Synthetic,Source note," + signedAmount + ",,,," + identity + "\n", Map.of("Checking", account), false);
      return service.view(CarlService.Scope.privateFor("alice"), "transactions").stream().filter(row -> row.get("source_id").equals(identity)).mapToLong(row -> CarlService.number(row, "id")).findFirst().orElseThrow();
   }



   private static RentalEconomics.Component component(String id, RentalEconomics.Kind kind, String amount, long property)
   {
      return new RentalEconomics.Component(id, kind, n(amount), Map.of(Long.toString(property), BigDecimal.ONE), BigDecimal.ZERO);
   }



   @Test
   void fat06PersistedSourceSplitRentApplicationAndReportReconcile()
   {
      long house = property("FAMILY", "0.5");
      long receipt = transaction("900.00", "rent-1", "FAMILY");
      UUID splitRequest = UUID.randomUUID();
      var rentParts = List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "900.00", house));
      long rentSplit = rentals.classify("alice", splitRequest, receipt, rentParts, "FAMILY", "Evidenced rent receipt");
      assertEquals(rentSplit, rentals.classify("alice", splitRequest, receipt, rentParts, "FAMILY", "Evidenced rent receipt"));
      long mortgage = transaction("-400.00", "mortgage-1", "FAMILY");
      rentals.classify("alice", UUID.randomUUID(), mortgage, List.of(component("principal", RentalEconomics.Kind.DEBT_PRINCIPAL, "300.00", house), component("interest", RentalEconomics.Kind.DEBT_INTEREST, "100.00", house)), "FAMILY", "Statement principal and interest");
      long due = rentals.rentDue("alice", UUID.randomUUID(), house, null, FROM, n("1000.00"), "FAMILY", "Synthetic lease");
      rentals.applyRent("alice", UUID.randomUUID(), due, rentSplit, 1, "rent", n("900.00"), "Applied receipt to September rent");
      UUID reportRequest = UUID.randomUUID();
      long artifact = rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), reportRequest, Set.of(house), FROM, THROUGH, THROUGH);
      assertEquals(artifact, rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), reportRequest, Set.of(house), FROM, THROUGH, THROUGH));
      String facts = service.artifact("bob", artifact).get("facts").toString();
      assertTrue(facts.contains("\"unpaidRent\":100.00"));
      assertTrue(facts.contains("\"debtPrincipal\":300.00"));
      assertTrue(facts.contains("\"debtInterest\":100.00"));
      assertTrue(facts.contains("\"cashBeforeReserves\":500.00"));
      assertTrue(service.artifact("bob", artifact).get("status_label").toString().startsWith("Incomplete"));
   }



   @Test
   void privateAccountAndPropertySourceCannotEnterSharedReport()
   {
      long house = property("FAMILY", "1");
      long receipt = transaction("100.00", "private-source", "PRIVATE");
      rentals.classify("alice", UUID.randomUUID(), receipt, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", house)), "FAMILY", "Private account source");
      long report = rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(house), FROM, THROUGH, THROUGH);
      String shared = service.artifact("bob", report).get("facts").toString();
      assertFalse(shared.contains("private-source"));
      assertTrue(shared.contains("\"rentCollected\":0.00"));
      assertTrue(shared.contains("partial"));
      long privateHouse = property("PRIVATE", "1");
      assertThrows(SecurityException.class, () -> rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(privateHouse), FROM, THROUGH, THROUGH));
      assertThrows(SecurityException.class, () -> rentals.classify("bob", UUID.randomUUID(), receipt, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", house)), "FAMILY", "Cannot manage"));
   }



   @Test
   void correctionsAndSourceChangesPreserveHistoryAndStaleReports()
   {
      long house = property("PRIVATE", "1");
      long tx = transaction("100.00", "rent-source", "PRIVATE");
      long split = rentals.classify("alice", UUID.randomUUID(), tx, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", house)), "PRIVATE", "Original split evidence");
      long report = rentals.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(house), FROM, THROUGH, THROUGH);
      long revision = CarlService.number(rentals.properties(CarlService.Scope.privateFor("alice")).getFirst(), "revision");
      rentals.correctProperty("alice", UUID.randomUUID(), house, revision, values("0.5"), "Confirmed proportional ownership");
      assertEquals(Boolean.TRUE, service.artifact("alice", report).get("stale"));
      assertEquals(1L, count("SELECT count(*) FROM carl_correction WHERE record_id=" + house));
      sql("UPDATE carl_transaction SET amount=120.00 WHERE record_id=" + tx);
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + tx);
      long changed = rentals.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(house), FROM, THROUGH, THROUGH);
      assertTrue(service.artifact("alice", changed).get("facts").toString().contains("stale"));
      rentals.reviseClassification("alice", UUID.randomUUID(), split, 1, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "120.00", house)), "New source confirmed");
      assertEquals(2L, count("SELECT count(*) FROM carl_rental_split_version WHERE split_id=" + split));
      assertThrows(IllegalArgumentException.class, () -> rentals.reviseClassification("alice", UUID.randomUUID(), split, 1, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "120.00", house)), "Stale version"));
   }



   @Test
   void applicationCapacityAndRevocationAreEnforcedAcrossSavedOutputs()
   {
      long house = property("FAMILY", "1");
      long tx = transaction("100.00", "limited-rent", "FAMILY");
      long split = rentals.classify("alice", UUID.randomUUID(), tx, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", house)), "FAMILY", "Receipt evidence");
      long due = rentals.rentDue("alice", UUID.randomUUID(), house, null, FROM, n("150.00"), "FAMILY", "Lease evidence");
      long applied = rentals.applyRent("alice", UUID.randomUUID(), due, split, 1, "rent", n("80.00"), "Partial September receipt");
      assertThrows(IllegalArgumentException.class, () -> rentals.applyRent("alice", UUID.randomUUID(), due, split, 1, "rent", n("30.00"), "Would overapply source"));
      assertThrows(IllegalArgumentException.class, () -> rentals.reviseClassification("alice", UUID.randomUUID(), split, 1, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", house)), "Linked applications require explicit removal"));
      rentals.unapplyRent("alice", UUID.randomUUID(), applied, "Correcting period allocation");
      rentals.applyRent("alice", UUID.randomUUID(), due, split, 1, "rent", n("100.00"), "Explicit replacement allocation");
      long report = rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(house), FROM, THROUGH, THROUGH);
      assertNotNull(service.artifact("bob", report));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
      assertThrows(SecurityException.class, () -> rentals.properties(CarlService.Scope.privateFor("unmapped")));
   }



   @Test
   void penniesRemainAssignedToOriginalPropertyWhenReportScopeChanges()
   {
      long first = property("FAMILY", "1");
      long second = property("FAMILY", "1");
      long tx = transaction("0.01", "one-cent", "FAMILY");
      var part = new RentalEconomics.Component("rent", RentalEconomics.Kind.RENT_RECEIPT, n("0.01"), Map.of(Long.toString(first), n("0.5"), Long.toString(second), n("0.5")), BigDecimal.ZERO);
      long split = rentals.classify("alice", UUID.randomUUID(), tx, List.of(part), "FAMILY", "Original penny allocation");
      long full = rentals.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(first, second), FROM, THROUGH, THROUGH);
      long subset = rentals.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), Set.of(second), FROM, THROUGH, THROUGH);
      assertTrue(service.artifact("alice", full).get("facts").toString().contains("\"rentCollected\":0.01"));
      assertTrue(service.artifact("alice", subset).get("facts").toString().contains("\"rentCollected\":0.00"));
      long due = rentals.rentDue("alice", UUID.randomUUID(), second, null, FROM, n("0.01"), "FAMILY", "Synthetic lease");
      assertThrows(IllegalArgumentException.class, () -> rentals.applyRent("alice", UUID.randomUUID(), due, split, 1, "rent", n("0.01"), "Cannot move original penny"));
   }



   @Test
   void changingClassificationToPrivatePropertyRevokesSavedSharedReport()
   {
      long shared = property("FAMILY", "1");
      long privateHouse = property("PRIVATE", "1");
      long tx = transaction("100.00", "changed-dependency", "FAMILY");
      long split = rentals.classify("alice", UUID.randomUUID(), tx, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", shared)), "FAMILY", "Originally shared classification");
      long report = rentals.report(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), Set.of(shared), FROM, THROUGH, THROUGH);
      assertNotNull(service.artifact("bob", report));
      rentals.reviseClassification("alice", UUID.randomUUID(), split, 1, List.of(component("rent", RentalEconomics.Kind.RENT_RECEIPT, "100.00", privateHouse)), "Reclassified to private property");
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
   }



   @Test
   void validComponentsAcrossManyPropertiesRemainReportableWithoutTruncation()
   {
      var properties = new java.util.HashSet<Long>();
      var shares = new java.util.LinkedHashMap<String, BigDecimal>();
      for(int index = 0; index < 60; index++)
      {
         long property = property("FAMILY", "1");
         properties.add(property);
         shares.put(Long.toString(property), index == 59 ? n("0.41") : n("0.01"));
      }
      long tx = transaction("120.00", "many-properties", "FAMILY");
      rentals.classify("alice", UUID.randomUUID(), tx, List.of(
         new RentalEconomics.Component("rent", RentalEconomics.Kind.RENT_RECEIPT, n("150.00"), shares, BigDecimal.ZERO),
         new RentalEconomics.Component("cost", RentalEconomics.Kind.OPERATING_EXPENSE, n("30.00"), shares, BigDecimal.ZERO)), "FAMILY", "Two complete evidenced components");
      long report = rentals.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), properties, FROM, THROUGH, THROUGH);
      String facts = service.artifact("alice", report).get("facts").toString();
      assertTrue(facts.contains("\"rentCollected\":150.00"));
      assertTrue(facts.contains("\"operatingExpenses\":30.00"));
      assertTrue(facts.contains("\"cashBeforeReserves\":120.00"));
   }



   private static long count(String statement)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement(); var r = s.executeQuery(statement))
      {
         r.next();
         return r.getLong(1);
      }
      catch(Exception failure)
      {
         throw new IllegalStateException(failure);
      }
   }



   private static void sql(String statement)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement())
      {
         s.execute(statement);
      }
      catch(Exception failure)
      {
         throw new IllegalStateException(failure);
      }
   }
}
