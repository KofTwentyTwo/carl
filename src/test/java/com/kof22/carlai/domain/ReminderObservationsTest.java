/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientFailure;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.agentadmin.client.FamilyAccess;
import com.kof22.carlai.calendar.CalendarPublicationService;
import com.kof22.carlai.calendar.PlanCalendarCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReminderObservationsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
   private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
   private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
   private long plan;
   private UUID step;

   @BeforeAll
   static void start()
   {
      DATABASE.start();
      var source = new PGSimpleDataSource();
      source.setURL(DATABASE.getJdbcUrl());
      source.setUser(DATABASE.getUsername());
      source.setPassword(DATABASE.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(source);
      service = new CarlService(source, Clock.fixed(NOW, ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic reminders','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Alice',true),(2,1,'bob','Bob',true)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','CALENDAR']) d");
   }



   @AfterAll
   static void stop()
   {
      DATABASE.stop();
   }



   private static void sql(String value)
   {
      service.transaction(c ->
      {
         CarlService.execute(c, value);
         return null;
      });
   }



   @BeforeEach
   void setup()
   {
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_permission SET details=true");
      var cash = new CashPlans(service);
      long cashPlan = cash.create("alice", "Synthetic source", "FAMILY", "Explicit scope", new CashPlans.Assumptions("USD", DATE, DATE.plusDays(30), new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("300"), true, true, true, true, true, true));
      long artifact = cash.assess(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), cashPlan, DATE.plusDays(5), new BigDecimal("100"), "Plan review", true);
      var plans = new PlanLifecycle(service);
      plan = plans.create("alice", UUID.randomUUID(), artifact, "Synthetic shared plan", "Human selected scenario");
      step = UUID.randomUUID();
      plans.step("alice", plan, 1, step, "Review statement", 1, DATE.plusDays(5), "Home", null, "Human task");
      plans.agree("alice", plan, 2, "Family agreement");
   }



   private CalendarPublicationService.Observation observed(boolean completed)
   {
      String text = PlanCalendarCodec.reminder(new PlanCalendarCodec.Item(step, 3, "Review statement", "No payment", "Home", NOW), DATE.plusDays(5), completed ? NOW : null);
      service.transaction(c ->
      {
         CarlService.execute(c, "INSERT INTO carl_calendar_mapping(step_id,plan_id,collection_key,component,observed_calendar,read_state) VALUES(?,?,?,'VTODO',?,'CHANGED') ON CONFLICT(step_id) DO UPDATE SET observed_calendar=excluded.observed_calendar,read_state='CHANGED'", step, plan, "synthetic-collection", text);
         return null;
      });
      return new CalendarPublicationService.Observation(step, "CHANGED", text, "synthetic-etag", null);
   }



   @Test
   void observationNeedsExplicitReviewAndNeverVerifiesPayment()
   {
      var reminders = new ReminderObservations(service);
      var remote = observed(true);
      long id = reminders.capture("alice", plan, step, 3, remote);
      assertEquals(id, reminders.capture("alice", plan, step, 3, remote));
      assertEquals("TODO", status());
      UUID request = UUID.randomUUID();
      assertEquals(4, reminders.review("alice", request, id, 3, "ACCEPT_REPORTED_COMPLETE", "I reviewed this shared reminder"));
      assertEquals("REPORTED_COMPLETE", status());
      assertEquals(4, reminders.review("alice", request, id, 3, "ACCEPT_REPORTED_COMPLETE", "I reviewed this shared reminder"));
      assertThrows(IllegalArgumentException.class, () -> reminders.review("alice", request, id, 3, "DISMISS", "Changed request"));
      assertThrows(IllegalArgumentException.class, () -> reminders.review("alice", UUID.randomUUID(), id, 3, "VERIFIED_COMPLETE", "Not allowed"));
   }



   @Test
   void staleRevokedAndNoncompleteObservationsCannotMarkDone()
   {
      var reminders = new ReminderObservations(service);
      var open = observed(false);
      long id = reminders.capture("alice", plan, step, 3, open);
      assertThrows(IllegalArgumentException.class, () -> reminders.review("alice", UUID.randomUUID(), id, 3, "ACCEPT_REPORTED_COMPLETE", "Open task"));
      assertEquals(3, reminders.review("alice", UUID.randomUUID(), id, 3, "DISMISS", "Not completed"));
      long completed = reminders.capture("alice", plan, step, 3, observed(true));
      observed(false);
      assertThrows(IllegalArgumentException.class, () -> reminders.review("alice", UUID.randomUUID(), completed, 3, "ACCEPT_REPORTED_COMPLETE", "Changed remote"));
      assertThrows(SecurityException.class, () -> reminders.capture("alice", plan, step, 3, new CalendarPublicationService.Observation(step, "CHANGED", open.calendar() + " ", "tag", null)));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='CALENDAR'");
      assertThrows(SecurityException.class, () -> reminders.review("alice", UUID.randomUUID(), completed, 3, "DISMISS", "Revoked"));
      assertEquals("TODO", status());
   }



   @Test
   void familyCalendarStatusAndReviewUseImmutableParticipantsAndRequestIds() throws Exception
   {
      long observation = new ReminderObservations(service).capture("alice", plan, step, 3, observed(true));
      var member = service.member("alice");
      var context = new ClientWorkflow.Context(new FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var calendar = new CalendarWorkflows(Map.of("reminders", ignored ->
      {
         throw new AssertionError("Local status must not connect");
      }), Map.of(), service);
      try(var workflows = new CarlClientWorkflows(service, calendar))
      {
         var json = new ObjectMapper();
         var input = json.valueToTree(Map.of("plan", plan, "expectedVersion", 3, "observation", observation, "decision", "ACCEPT_REPORTED_COMPLETE", "note", "Reviewed by household member"));
         var review = workflows.handlers().get("reminder-review");
         UUID request = UUID.randomUUID();
         review.start(context, request, input);
         var result = await(review, context, request);
         assertEquals(ClientWorkflow.Status.COMPLETE, result.status());
         assertEquals("REPORTED_COMPLETE", status());
         assertEquals(result, review.start(context, request, input));
         var statusInput = json.createObjectNode().put("plan", plan).put("expectedVersion", 4).put("step", step.toString()).put("collection", "reminders").put("operation", "STATUS").putNull("priorRequest");
         var history = workflows.handlers().get("calendar-plan");
         UUID statusId = UUID.randomUUID();
         history.start(context, statusId, statusInput);
         var saved = await(history, context, statusId);
         assertEquals(ClientWorkflow.Status.COMPLETE, saved.status());
         assertTrue(saved.toString().contains("REMOTE_REPORTED_COMPLETE"));
         statusInput.put("principal", "bob");
         assertThrows(ClientFailure.class, () -> history.start(context, UUID.randomUUID(), statusInput));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='FINANCE'");
         assertThrows(ClientFailure.class, () -> review.get(context, request));
      }
   }



   private String status()
   {
      return service.transaction(c -> CarlService.rows(c, "SELECT status FROM carl_plan_step WHERE id=?", step).getFirst().get("status").toString());
   }



   private static ClientWorkflow.Result await(ClientWorkflow handler, ClientWorkflow.Context context, UUID request) throws Exception
   {
      for(int i = 0; i < 200; i++)
      {
         var result = handler.get(context, request);
         if(result.status() != ClientWorkflow.Status.PENDING)
         {
            return result;
         }
         Thread.sleep(10);
      }
      throw new AssertionError("Workflow did not finish");
   }
}
