/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FocusedReportsTest
{
   private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static final CarlService.Scope PRIVATE = CarlService.Scope.privateFor("alice");
   private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 9, 30);
   @BeforeAll
   static void start()
   {
      DB.start();
      var ds = new PGSimpleDataSource();
      ds.setURL(DB.getJdbcUrl());
      ds.setUser(DB.getUsername());
      ds.setPassword(DB.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(ds);
      service = new CarlService(ds, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic focused reports','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m,d,true FROM generate_series(1,2)m CROSS JOIN unnest(ARRAY['BILLS','CALENDAR','VENDORS'])d");
   }



   @AfterAll
   static void stop()
   {
      DB.stop();
   }



   @BeforeEach
   void reset()
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_permission SET details=true");
   }



   static void sql(String statement)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, statement);
         return null;
      });
   }



   private long bill(String title, String amount, String currency, String date, String visibility)
   {
      return service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "BILLS", visibility, title, "Synthetic invoice evidence");
         CarlService.execute(c, "INSERT INTO carl_bill(record_id,vendor_label,amount,currency,due_date,status,source_id) VALUES(?,'Synthetic utility',?,?,?,'UNPAID',?)", id, amount == null ? null : new java.math.BigDecimal(amount), currency, date == null ? null : LocalDate.parse(date), UUID.randomUUID().toString());
         return id;
      });
   }



   private com.fasterxml.jackson.databind.JsonNode facts(long id) throws Exception
   {
      return new ObjectMapper().readTree(service.artifact("alice", id).get("facts").toString());
   }



   @Test
   void exactCurrenciesPrivateScopesAndFailedNarrationRemainExplicit() throws Exception
   {
      bill("Electric", "125.25", "USD", "2026-09-15", "FAMILY");
      bill("Water", "74.75", "USD", "2026-09-20", "FAMILY");
      bill("Euro", "5.00", "EUR", "2026-09-20", "FAMILY");
      bill("Private", "999.00", "USD", "2026-09-21", "PRIVATE");
      bill("Uncertain", null, "USD", null, "FAMILY");
      var reports = new FocusedReports(service);
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      UUID request = UUID.randomUUID();
      long id = reports.generate(scope, request, FocusedReports.Focus.BILLS, FROM, THROUGH, ignored ->
      {
         throw new IllegalStateException("controlled narration failure");
      });
      var facts = facts(id);
      assertEquals(0, new java.math.BigDecimal("200.00").compareTo(facts.get("totals").get("USD:UNPAID").decimalValue()));
      assertEquals(0, new java.math.BigDecimal("5.00").compareTo(facts.get("totals").get("EUR:UNPAID").decimalValue()));
      assertEquals(4, facts.get("bills").size());
      assertEquals(1, facts.get("missingOrUncertainRecordIds").size());
      assertEquals("FAILED", service.artifact("alice", id).get("narration_state"));
      assertEquals("PARTIAL", new ReportRecovery(service).inspect(scope, request).state());
      assertTrue(reports.presentation("alice", id).contains("USD UNPAID: 200"));
      assertTrue(reports.presentation("alice", id).contains("Narration: FAILED"));
      assertEquals(id, reports.generate(scope, request, FocusedReports.Focus.BILLS, FROM, THROUGH, null));
      sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='BILLS'");
      assertThrows(SecurityException.class, () -> service.artifact("bob", id));
   }



   @Test
   void comparableRecordsCarryExactSourcesAndChangedCoverageBlocksDifferences() throws Exception
   {
      long old = bill("Electric", "100.00", "USD", "2026-09-10", "FAMILY");
      long next = bill("Electric", "125.25", "USD", "2026-10-10", "FAMILY");
      var reports = new FocusedReports(service);
      LocalDate end = LocalDate.of(2026, 10, 30);
      long id = reports.compareBills(PRIVATE, UUID.randomUUID(), FROM, THROUGH, LocalDate.of(2026, 10, 1), end, null);
      var facts = facts(id);
      assertTrue(facts.get("comparableSelectedRecords").asBoolean());
      var delta = facts.get("recordDifferences").get(0);
      assertEquals(old, delta.get("beforeRecord").asLong());
      assertEquals(next, delta.get("afterRecord").asLong());
      assertEquals("25.25", delta.get("difference").asText());
      assertEquals("25.25", facts.get("selectedCurrencyStatusDifferences").get("USD:UNPAID").asText());
      assertTrue(facts.get("causes").asText().startsWith("Not established"));
      bill("New source", "10.00", "EUR", "2026-10-15", "FAMILY");
      var changed = facts(reports.compareBills(PRIVATE, UUID.randomUUID(), FROM, THROUGH, LocalDate.of(2026, 10, 1), end, null));
      assertFalse(changed.get("comparableSelectedRecords").asBoolean());
      assertEquals(0, changed.get("recordDifferences").size());
   }



   @Test
   void calendarFailureAndVendorReportsPersistWithoutClaimingCompleteness() throws Exception
   {
      service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", "FAMILY", "Synthetic calendar", "Controlled provider failure");
         CarlService.execute(c, "INSERT INTO carl_calendar_connection(record_id,provider,calendar_identity,sync_state,last_success) VALUES(?,'CALDAV','fixed-synthetic','FAILED',?)", id, java.sql.Timestamp.from(Instant.parse("2026-09-20T12:00:00Z")));
         return null;
      });
      var reports = new FocusedReports(service);
      UUID request = UUID.randomUUID();
      var calendar = facts(reports.generate(PRIVATE, request, FocusedReports.Focus.CALENDAR, FROM, THROUGH, null));
      assertEquals(0, calendar.get("events").size());
      assertTrue(calendar.get("coverageLimitations").toString().contains("incomplete or stale"));
      assertEquals("PARTIAL", new ReportRecovery(service).inspect(PRIVATE, request).state());
      long vendor = service.createVendor("alice", "Synthetic plumber", "PLUMBING", "supplied@test.invalid", false, "FAMILY", "Synthetic supplied contact");
      service.createWorkItem("alice", vendor, "Follow up", "VENDOR_RESPONSE", LocalDate.of(2026, 9, 20), "No quoted price or appointment", "FAMILY");
      var vendors = facts(reports.generate(PRIVATE, UUID.randomUUID(), FocusedReports.Focus.VENDORS, FROM, THROUGH, null));
      assertEquals(1, vendors.get("awaitingVendor").size());
      assertEquals(1, vendors.get("followUpsInPeriod").size());
      assertTrue(vendors.get("commitmentRule").asText().contains("not an agreement"));
   }



   @Test
   void narrowCalendarWindowIgnoresLargeOutOfPeriodHistory() throws Exception
   {
      long connection = service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", "FAMILY", "Synthetic history", "Bounded date query fixture");
         CarlService.execute(c, "INSERT INTO carl_calendar_connection(record_id,provider,calendar_identity,sync_state) VALUES(?,'CALDAV','history','FAILED')", id);
         return id;
      });
      sql("WITH records AS (INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) SELECT 1,1,'CALENDAR','FAMILY','Old event','Synthetic old source' FROM generate_series(1,1001) RETURNING id) INSERT INTO carl_calendar_event(record_id,connection_id,provider_event_id,occurrence_id,start_at,end_at,source_zone) SELECT id," + connection + ",id::text,id::text,'2010-01-01T10:00:00Z','2010-01-01T11:00:00Z','UTC' FROM records");
      long event = service.transaction(c ->
      {
         long id = CarlService.record(c, CarlService.member(c, "alice"), "CALENDAR", "FAMILY", "Current event", "Synthetic current source");
         CarlService.execute(c, "INSERT INTO carl_calendar_event(record_id,connection_id,provider_event_id,occurrence_id,start_at,end_at,source_zone) VALUES(?,?,'current','current','2026-09-10T10:00:00Z','2026-09-10T11:00:00Z','UTC')", id, connection);
         return id;
      });
      var report = facts(new FocusedReports(service).generate(PRIVATE, UUID.randomUUID(), FocusedReports.Focus.CALENDAR, FROM, THROUGH, null));
      assertEquals(1, report.get("events").size());
      assertEquals(event, report.get("events").get(0).get("id").asLong());
   }
}
