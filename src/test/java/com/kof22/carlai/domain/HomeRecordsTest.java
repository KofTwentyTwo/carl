/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Supplied home terms decorate existing authoritative records, with current access and revision protection. */
class HomeRecordsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private HomeRecords homes;
   private long property;
   private long debt;

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = com.kof22.agentadmin.bootstrap.NativeDatabases.source(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.systemUTC());
   }



   @org.junit.jupiter.api.AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   @BeforeEach
   void prepare()
   {
      service.transaction(c ->
      {
         CarlService.execute(c, "TRUNCATE carl_household,carl_request RESTART IDENTITY CASCADE");
         CarlService.execute(c, "INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Fictional home household','America/Chicago')");
         CarlService.execute(c, "INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Fictional Aster',true),(2,1,'bob','Fictional Basil',true),(3,1,'reader','Fictional Reader',false)");
         CarlService.execute(c, "INSERT INTO carl_permission(member_id,domain,details) SELECT id,'FINANCE',true FROM carl_member");
         return null;
      });
      var finance = new FinancialRecords(service);
      long asset = finance.createAccount("alice", "Fictional home value", "OTHER_ASSET", "USD", false, BigDecimal.ONE, "FAMILY", "Fictional valuation");
      debt = finance.createAccount("alice", "Fictional mortgage", "LOAN", "USD", false, BigDecimal.ONE, "PRIVATE", "Fictional statement");
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_grant(record_id,member_id,details) VALUES(?,2,true),(?,3,true)", debt, debt);
         return null;
      });
      property = new RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Fictional primary home", "FAMILY", "Fictional deed", new RentalRecords.PropertyValues("USD", "Fictional Meadow", BigDecimal.ONE, asset, debt, "Fictional Aster and Basil", new BigDecimal("300000"), LocalDate.of(2026, 9, 30), null, null, null, null, null));
      homes = new HomeRecords(service);
   }



   private static HomeRecords.ProfileValues profile(HomeRecords.Use use)
   {
      return new HomeRecords.ProfileValues(use, "USD", new HomeRecords.Mortgage(LocalDate.of(2026, 9, 30), new BigDecimal("200000.00"), new BigDecimal("0.0425"), new BigDecimal("1400.00"), LocalDate.of(2026, 10, 1), LocalDate.of(2042, 9, 30), 240, HomeRecords.RateKind.FIXED, new BigDecimal("250.00"), BigDecimal.ZERO, "Fictional supplied contract; escrow separate from payment"), "Fictional homeowner supplied facts");
   }



   @Test
   void missingProfileIsUnknownAndPrimaryResidenceCreatesNoRentOrLiability()
   {
      assertEquals("UNKNOWN", homes.get("bob", property).get("property_use"));
      int accounts = service.view(CarlService.Scope.privateFor("alice"), "accounts").size();
      assertEquals(2, homes.save("alice", UUID.randomUUID(), property, 1, profile(HomeRecords.Use.PRIMARY_RESIDENCE)));
      var row = homes.get("bob", property);
      assertEquals("PRIMARY_RESIDENCE", row.get("property_use"));
      assertEquals(new BigDecimal("200000.0000"), row.get("mortgage_principal"));
      assertEquals(debt, ((Number) row.get("debt_account_id")).longValue());
      assertEquals(accounts, service.view(CarlService.Scope.privateFor("alice"), "accounts").size());
      assertTrue(new RentalRecords(service).records(CarlService.Scope.privateFor("alice"), "dues").isEmpty());
      assertTrue(new RentalRecords(service).records(CarlService.Scope.privateFor("alice"), "units").isEmpty());
   }



   @Test
   void retryIsIdempotentAndStaleRevisionCannotOverwriteAttributedHistory()
   {
      UUID request = UUID.randomUUID();
      assertEquals(2, homes.save("alice", request, property, 1, profile(HomeRecords.Use.PRIMARY_RESIDENCE)));
      assertEquals(2, homes.save("alice", request, property, 1, profile(HomeRecords.Use.PRIMARY_RESIDENCE)));
      assertEquals(3, homes.save("bob", UUID.randomUUID(), property, 2, profile(HomeRecords.Use.SECOND_HOME)));
      assertThrows(IllegalArgumentException.class, () -> homes.save("alice", UUID.randomUUID(), property, 2, profile(HomeRecords.Use.RENTAL)));
      var history = homes.history("alice", property);
      assertEquals(2, history.size());
      assertEquals(1, ((Number) history.get(0).get("actor_id")).intValue());
      assertEquals(2, ((Number) history.get(1).get("actor_id")).intValue());
      assertTrue(history.get(0).get("snapshot").toString().contains("PRIMARY_RESIDENCE"));
      assertThrows(IllegalArgumentException.class, () -> homes.save("alice", request, property, 1, profile(HomeRecords.Use.RENTAL)));
   }



   @Test
   void unknownContractFieldsStayUnknownAndWrongCurrencyPrecisionAndDatesFail()
   {
      homes.save("alice", UUID.randomUUID(), property, 1, new HomeRecords.ProfileValues(HomeRecords.Use.RENTAL, "USD", null, "Fictional rental use; mortgage terms not supplied"));
      var row = homes.get("alice", property);
      assertEquals(null, row.get("mortgage_principal"));
      assertEquals(null, row.get("annual_rate"));
      assertEquals("UNKNOWN", row.get("rate_kind"));
      assertThrows(IllegalArgumentException.class, () -> homes.save("alice", UUID.randomUUID(), property, 2, new HomeRecords.ProfileValues(HomeRecords.Use.OTHER_REAL_ESTATE, "EUR", null, "Fictional currency mismatch")));
      var bad = new HomeRecords.Mortgage(null, new BigDecimal("1.001"), null, null, null, null, null, HomeRecords.RateKind.UNKNOWN, null, null, "Fictional excessive cents");
      assertThrows(IllegalArgumentException.class, () -> homes.save("alice", UUID.randomUUID(), property, 2, new HomeRecords.ProfileValues(HomeRecords.Use.RENTAL, "USD", bad, "Fictional amount")));
      var dates = new HomeRecords.Mortgage(null, null, null, null, LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1), null, HomeRecords.RateKind.UNKNOWN, null, null, "Fictional invalid dates");
      assertThrows(IllegalArgumentException.class, () -> homes.save("alice", UUID.randomUUID(), property, 2, new HomeRecords.ProfileValues(HomeRecords.Use.RENTAL, "USD", dates, "Fictional dates")));
   }



   @Test
   void nonManagerAndRevokedLinkedAccountCannotWriteOrReadCurrentOrOldProfiles()
   {
      homes.save("alice", UUID.randomUUID(), property, 1, profile(HomeRecords.Use.PRIMARY_RESIDENCE));
      assertThrows(SecurityException.class, () -> homes.save("reader", UUID.randomUUID(), property, 2, profile(HomeRecords.Use.RENTAL)));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_grant SET details=false WHERE record_id=? AND member_id=2", debt);
         return null;
      });
      assertThrows(SecurityException.class, () -> homes.get("bob", property));
      assertThrows(SecurityException.class, () -> homes.history("bob", property));
      assertThrows(SecurityException.class, () -> homes.save("bob", UUID.randomUUID(), property, 2, profile(HomeRecords.Use.RENTAL)));
      assertEquals(1, homes.history("alice", property).size());
      assertTrue(homes.records(new CarlService.Scope("alice", Set.of("alice", "bob"))).isEmpty());
   }



   @Test
   void concurrentHumanProfilesCannotOverwriteEachOther() throws Exception
   {
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      try(var pool = java.util.concurrent.Executors.newFixedThreadPool(2))
      {
         var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
         for(String actor : java.util.List.of("alice", "bob"))
         {
            results.add(pool.submit(() ->
            {
               ready.countDown();
               if(!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
               {
                  throw new IllegalStateException("Concurrent fixture did not start");
               }
               try
               {
                  homes.save(actor, UUID.randomUUID(), property, 1, profile(actor.equals("alice") ? HomeRecords.Use.PRIMARY_RESIDENCE : HomeRecords.Use.RENTAL));
                  return true;
               }
               catch(IllegalArgumentException stale)
               {
                  assertEquals("Property changed; reload its current revision", stale.getMessage());
                  return false;
               }
            }));
         }
         assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
         start.countDown();
         int successes = 0;
         for(var result : results)
         {
            if(result.get(15, java.util.concurrent.TimeUnit.SECONDS))
            {
               successes++;
            }
         }
         assertEquals(1, successes);
         assertEquals(2, ((Number) homes.get("alice", property).get("revision")).intValue());
         assertEquals(1, homes.history("alice", property).size());
      }
   }



   @Test
   void replacementLoanCannotMakeOldPrivateLoanProfilePublic()
   {
      homes.save("alice", UUID.randomUUID(), property, 1, profile(HomeRecords.Use.PRIMARY_RESIDENCE));
      long replacement = new FinancialRecords(service).createAccount("alice", "Fictional replacement mortgage", "LOAN", "USD", false, BigDecimal.ONE, "FAMILY", "Fictional replacement statement");
      var old = homes.get("alice", property);
      new RentalRecords(service).correctProperty("alice", UUID.randomUUID(), property, 2, new RentalRecords.PropertyValues("USD", "Fictional Meadow", BigDecimal.ONE, ((Number) old.get("asset_account_id")).longValue(), replacement, "Fictional Aster and Basil", new BigDecimal("300000"), LocalDate.of(2026, 9, 30), null, null, null, null, null), "Fictional reviewed replacement link");
      homes.save("alice", UUID.randomUUID(), property, 3, profile(HomeRecords.Use.SECOND_HOME));
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_grant SET details=false WHERE record_id=? AND member_id=2", debt);
         return null;
      });
      assertEquals("SECOND_HOME", homes.get("bob", property).get("property_use"));
      assertEquals(1, homes.history("bob", property).size());
      assertEquals(4, ((Number) homes.history("bob", property).getFirst().get("property_revision")).intValue());
      assertEquals(2, homes.history("alice", property).size());
   }



   @Test
   void pendingRevocationMustFinishBeforeProfileAuthorization() throws Exception
   {
      var revoked = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      try(var pool = java.util.concurrent.Executors.newFixedThreadPool(2))
      {
         var revoker = pool.submit(() -> service.transaction(c ->
         {
            CarlService.execute(c, "UPDATE carl_grant SET details=false WHERE record_id=? AND member_id=2", debt);
            revoked.countDown();
            try
            {
               if(!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
               {
                  throw new IllegalStateException("Revocation fixture did not release");
               }
            }
            catch(InterruptedException interrupted)
            {
               Thread.currentThread().interrupt();
               throw new IllegalStateException(interrupted);
            }
            return null;
         }));
         try
         {
            assertTrue(revoked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var writer = pool.submit(() -> homes.save("bob", UUID.randomUUID(), property, 1, profile(HomeRecords.Use.RENTAL)));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            revoker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(denied.getCause() instanceof SecurityException);
            assertEquals("UNKNOWN", homes.get("alice", property).get("property_use"));
            assertTrue(homes.history("alice", property).isEmpty());
         }
         finally
         {
            release.countDown();
         }
      }
   }

}
