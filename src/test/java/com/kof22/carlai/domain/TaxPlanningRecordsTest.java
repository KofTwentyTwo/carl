/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TaxPlanningRecordsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
   private static CarlService service;
   private static TaxPlanningRecords tax;
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
      tax = new TaxPlanningRecords(service, Clock.fixed(NOW, ZoneOffset.UTC));
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic tax household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Family',true)");
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
      sql("TRUNCATE carl_record,carl_request RESTART IDENTITY CASCADE");
      sql("UPDATE carl_member SET active=true");
      sql("UPDATE carl_permission SET details=true");
   }



   private long property(String visibility)
   {
      return new RentalRecords(service).createProperty("alice", UUID.randomUUID(), "Synthetic rental", visibility, "Supplied title and basis remain unknown", new RentalRecords.PropertyValues("USD", "Chester, Randolph County, Illinois", null, null, null, null, null, null, null, null, null, null, null));
   }



   private TaxPlanningRecords.Reference reference(String visibility, Integer year, boolean archived, LocalDate until)
   {
      return new TaxPlanningRecords.Reference("Synthetic IRS edition", visibility, "US", "OWNERSHIP_STRUCTURE", "https://www.irs.gov/publications/p527", "Synthetic archived fixture", year, LocalDate.of(2026, 8, 1), Instant.parse("2026-09-01T00:00:00Z"), archived ? "a".repeat(64) : null, LocalDate.of(2026, 9, 1), until, "Synthetic human review of edition and applicability; not tax-rule qualification", "Synthetic official-source identity; no network fetch");
   }



   private long reference(String visibility)
   {
      return tax.reference("alice", UUID.randomUUID(), reference(visibility, 2025, true, LocalDate.of(2026, 10, 1)));
   }



   private long alternative(long property, long reference, String visibility)
   {
      return tax.alternative("alice", UUID.randomUUID(), new TaxPlanningRecords.Alternative("Synthetic structure discussion", visibility, property, "Human-proposed title-holding arrangement", null, "No legal election, ownership or tax treatment assumed", new BigDecimal("100.00"), new BigDecimal("50.00"), null, "Synthetic proposed costs; no savings asserted", List.of(reference)));
   }



   private String facts(String principal, long id)
   {
      return service.artifact(principal, id).get("facts").toString();
   }



   @Test
   void familyTaxPlanningUsesBoundedProtectedSectionsAndRevocation() throws Exception
   {
      long property = property("FAMILY");
      long reference = reference("FAMILY");
      long alternative = alternative(property, reference, "FAMILY");
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var input = json.createObjectNode().put("taxYear", 2025).put("asOf", "2026-09-29T00:00:00Z");
         input.putArray("properties").add(property);
         input.putArray("alternatives").add(alternative);
         input.putArray("references").add(reference);
         var handler = workflows.handlers().get("tax-planning-packet");
         UUID request = UUID.randomUUID();
         handler.start(context, request, input);
         var result = awaitFamily(handler, context, request);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL, result.status());
         assertTrue(result.toString().contains("artifact-section"));
         assertTrue(result.toString().length() < 10000);
         long artifact = Long.parseLong(result.artifactId());
         var pageInput = json.createObjectNode().put("artifact", artifact).put("path", "/facts/sourceReviews").put("offset", 0).put("limit", 10);
         var pages = workflows.handlers().get("artifact-section");
         UUID pageId = UUID.randomUUID();
         pages.start(context, pageId, pageInput);
         var page = awaitFamily(pages, context, pageId);
         assertEquals(com.kof22.agentadmin.client.ClientWorkflow.Status.COMPLETE, page.status());
         assertTrue(page.toString().contains("/facts/sourceReviews/0"));
         input.put("caller", "bob");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> handler.start(context, UUID.randomUUID(), input));
         tax.active("alice", UUID.randomUUID(), reference, 1, false, "Reference withdrawn pending review");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> pages.get(context, pageId));
      }
   }



   @Test
   void protectedSectionNavigationPreservesMoneyUnicodeAndExplicitContinuations()
   {
      long property = property("PRIVATE");
      long artifact = tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), null, NOW, List.of(property), List.of(), List.of());
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_artifact SET facts=? WHERE record_id=?", "{\"amount\":0.123456789012345678,\"text\":\"A\uD840\uDC00B\",\"parts\":[1,2,3],\"complex/key\":{\"tilde~field\":null}}", artifact);
         return null;
      });
      var presentation = new ArtifactPresentation(service);
      var scope = CarlService.Scope.privateFor("alice");
      var amount = presentation.detail(scope, artifact, "/facts/amount", 0, 1).path("section").path("value");
      assertEquals(new BigDecimal("0.123456789012345678"), amount.decimalValue());
      var text = presentation.detail(scope, artifact, "/facts/text", 1, 1).path("section");
      assertEquals("\uD840\uDC00", text.path("value").asText());
      assertEquals(2, text.path("nextOffset").asInt());
      assertEquals(3, text.path("totalItems").asInt());
      assertTrue(presentation.detail(scope, artifact, "/facts/complex~1key/tilde~0field", 0, 1).path("section").path("value").isNull());
      assertEquals(2, presentation.detail(scope, artifact, "/facts/parts", 0, 2).path("section").path("entries").size());
      assertThrows(IllegalArgumentException.class, () -> presentation.detail(scope, artifact, "/facts/parts", 0, 26));
      assertThrows(IllegalArgumentException.class, () -> presentation.detail(scope, artifact, "/absent", 0, 1));
      assertThrows(IllegalArgumentException.class, () -> presentation.detail(scope, artifact, "/facts/text", 99, 1));
      assertThrows(SecurityException.class, () -> presentation.summary(new CarlService.Scope("alice", Set.of("alice", "bob")), artifact));
   }



   private static com.kof22.agentadmin.client.ClientWorkflow.Result awaitFamily(com.kof22.agentadmin.client.ClientWorkflow handler, com.kof22.agentadmin.client.ClientWorkflow.Context context, UUID request) throws Exception
   {
      for(int i = 0; i < 200; i++)
      {
         var result = handler.get(context, request);
         if(result.status() != com.kof22.agentadmin.client.ClientWorkflow.Status.PENDING)
         {
            return result;
         }
         Thread.sleep(10);
      }
      throw new AssertionError("Family workflow timed out");
   }



   @Test
   void datedSourcePacketRetainsUnknownFactsAndNeverQualifiesTreatment()
   {
      long property = property("FAMILY");
      long reference = reference("FAMILY");
      long alternative = alternative(property, reference, "FAMILY");
      UUID request = UUID.randomUUID();
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long packet = tax.packet(scope, request, 2025, NOW, List.of(property), List.of(alternative), List.of());
      assertEquals(packet, tax.packet(scope, request, 2025, NOW, List.of(property), List.of(alternative), List.of()));
      String facts = facts("bob", packet);
      assertTrue(facts.contains("reference:" + reference));
      assertTrue(facts.contains("HUMAN_REVIEWED_REFERENCE_NOT_RULE_QUALIFICATION"));
      assertTrue(facts.contains("CONDITIONAL_DISCUSSION_ONLY"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try
      {
         var node = json.readTree(facts);
         var review = node.path("sourceReviews").get(0);
         assertEquals("2026-10-01", review.path("reviewUntil").asText());
         assertEquals("2026-09-01T00:00:00Z", review.path("retrievedAt").asText());
         assertEquals("a".repeat(64), review.path("suppliedContentHash").asText());
         assertTrue(review.path("archiveAvailability").asText().startsWith("UNVERIFIED"));
      }
      catch(java.io.IOException error)
      {
         throw new IllegalStateException(error);
      }
      assertTrue(facts.contains("acquisitionBasis\":null"));
      assertTrue(facts.contains("ownershipFraction\":null"));
      assertTrue(facts.contains("UNDETERMINED"));
      assertTrue(facts.contains("recommendedStructure\":null"));
      assertFalse(facts.contains("taxSavings"));
      assertEquals("PARTIAL", service.transaction(c -> CarlService.rows(c, "SELECT status FROM carl_request WHERE id=?", request).getFirst().get("status")));
   }



   @Test
   void urlAloneWrongYearAndExpiredReviewRemainUnqualified()
   {
      long property = property("PRIVATE");
      long unarchived = tax.reference("alice", UUID.randomUUID(), reference("PRIVATE", 2025, false, LocalDate.of(2026, 10, 1)));
      long wrongYear = tax.reference("alice", UUID.randomUUID(), reference("PRIVATE", 2024, true, LocalDate.of(2026, 10, 1)));
      long expired = tax.reference("alice", UUID.randomUUID(), reference("PRIVATE", 2025, true, LocalDate.of(2026, 9, 2)));
      String facts = facts("alice", tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), 2025, NOW, List.of(property), List.of(), List.of(unarchived, wrongYear, expired)));
      assertTrue(facts.contains("No supplied content identity"));
      assertTrue(facts.contains("Tax-year applicability unestablished or mismatched"));
      assertTrue(facts.contains("review absent, future or expired"));
      assertFalse(facts.contains("HUMAN_REVIEWED_REFERENCE_NOT_RULE_QUALIFICATION"));
      String unknown = facts("alice", tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), null, NOW, List.of(property), List.of(), List.of(unarchived)));
      assertTrue(unknown.contains("Select the tax year"));
   }



   @Test
   void privateReferencesAndPropertyDependenciesCannotEnterSharedPackets()
   {
      long sharedProperty = property("FAMILY");
      long privateReference = reference("PRIVATE");
      long caseId = alternative(sharedProperty, privateReference, "FAMILY");
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertThrows(SecurityException.class, () -> tax.packet(shared, UUID.randomUUID(), 2025, NOW, List.of(sharedProperty), List.of(caseId), List.of()));
      assertThrows(SecurityException.class, () -> tax.packet(shared, UUID.randomUUID(), 2025, NOW, List.of(sharedProperty), List.of(), List.of(privateReference)));
      long publicReference = reference("FAMILY");
      long privateProperty = property("PRIVATE");
      long privateCase = alternative(privateProperty, publicReference, "FAMILY");
      assertThrows(SecurityException.class, () -> tax.packet(CarlService.Scope.privateFor("bob"), UUID.randomUUID(), 2025, NOW, List.of(privateProperty), List.of(privateCase), List.of()));
      long visible = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_tax_alternative_view WHERE principal='bob'").getFirst(), "n"));
      assertEquals(0L, visible);
   }



   @Test
   void deactivationRevokesPriorPacketsAndChangedReferenceRequiresRereview()
   {
      long property = property("FAMILY");
      long ref = reference("FAMILY");
      long alt = alternative(property, ref, "FAMILY");
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long packet = tax.packet(scope, UUID.randomUUID(), 2025, NOW, List.of(property), List.of(alt), List.of());
      UUID statusRequest = UUID.randomUUID();
      tax.active("alice", statusRequest, ref, 1, false, "Superseded applicability review");
      tax.active("alice", statusRequest, ref, 1, false, "Superseded applicability review");
      assertThrows(SecurityException.class, () -> service.artifact("bob", packet));
      assertThrows(IllegalArgumentException.class, () -> tax.active("alice", UUID.randomUUID(), ref, 1, true, "Stale review"));
      String inactive = facts("alice", tax.packet(scope, UUID.randomUUID(), 2025, NOW, List.of(property), List.of(alt), List.of()));
      assertTrue(inactive.contains("Reference deactivated"));
      assertTrue(inactive.contains("human re-review required"));
      tax.active("alice", UUID.randomUUID(), ref, 2, true, "Reviewed reactivation; retained original review date");
      assertThrows(SecurityException.class, () -> service.artifact("alice", packet));
   }



   @Test
   void propertyChangesMarkSavedFactsStaleAndRevocationDeniesSourceAccess()
   {
      long property = property("FAMILY");
      long reference = reference("FAMILY");
      var scope = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long packet = tax.packet(scope, UUID.randomUUID(), 2025, NOW, List.of(property), List.of(), List.of(reference));
      new TaxRecords(service).context("alice", property, LocalDate.of(2020, 1, 1), true, "Human corrected placement evidence");
      assertEquals(true, service.artifact("bob", packet).get("stale"));
      sql("UPDATE carl_record SET visibility='PRIVATE' WHERE id=" + reference);
      assertThrows(SecurityException.class, () -> service.artifact("bob", packet));
      assertThrows(SecurityException.class, () -> tax.active("bob", UUID.randomUUID(), reference, 1, false, "Guessed private source"));
   }



   @Test
   void nonGovernmentArbitraryTargetsFutureEvidenceAndUnboundedInputsAreRejected()
   {
      var source = reference("PRIVATE", 2025, true, LocalDate.of(2026, 10, 1));
      for(String url : List.of("http://www.irs.gov/publications/p527", "https://www.irs.gov.attacker.invalid/a", "file:///etc/passwd", "https://www.irs.gov/a?target=private", "https://user@www.irs.gov/a"))
      {
         assertThrows(IllegalArgumentException.class, () -> tax.reference("alice", UUID.randomUUID(), new TaxPlanningRecords.Reference(source.title(), source.visibility(), source.jurisdiction(), source.topic(), url, source.edition(), source.taxYear(), source.published(), source.retrieved(), source.contentHash(), source.reviewed(), source.reviewUntil(), source.reviewEvidence(), source.provenance())));
      }
      long property = property("PRIVATE");
      long reference = reference("PRIVATE");
      assertThrows(IllegalArgumentException.class, () -> tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), 2025, NOW.plusSeconds(1), List.of(property), List.of(), List.of(reference)));
      assertThrows(IllegalArgumentException.class, () -> tax.alternative("alice", UUID.randomUUID(), new TaxPlanningRecords.Alternative("Huge", "PRIVATE", property, "Candidate", null, "Human assumption", new BigDecimal("1E+100000"), null, null, "Synthetic", List.of(reference))));
      String historical = facts("alice", tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), 2025, Instant.parse("2026-08-30T00:00:00Z"), List.of(property), List.of(), List.of(reference)));
      assertTrue(historical.contains("Reference retrieved after packet as-of"));
      assertTrue(historical.contains("CURRENT_SUPPLIED_RECORDS"));
      assertTrue(historical.contains("not reconstructed historical state"));
      assertTrue(historical.contains("domainFactsCollectedAt\":\"2026-09-30T12:00:00Z"));
      assertFalse(historical.contains("HUMAN_REVIEWED_REFERENCE_NOT_RULE_QUALIFICATION"));
   }



   @Test
   void privateDocumentsAreOmittedWithoutDisclosingTheirIdentity()
   {
      long property = property("FAMILY");
      long reference = reference("FAMILY");
      long hidden = new TaxRecords(service).document("alice", property, "Private tax document", "PRIVATE", 2025, TaxPreparation.Category.OWNERSHIP, TaxPreparation.Treatment.UNKNOWN, null, "Private supplied deed evidence");
      long packet = tax.packet(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), 2025, NOW, List.of(property), List.of(), List.of(reference));
      assertFalse(facts("bob", packet).contains("document:" + hidden));
      long linked = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_artifact_source WHERE artifact_id=? AND source_id=?", packet, hidden).getFirst(), "n"));
      assertEquals(0L, linked);
   }



   @Test
   void concurrentReferenceRetryCreatesOneIdentityAndNoLostStatusUpdate() throws Exception
   {
      UUID request = UUID.randomUUID();
      var input = reference("PRIVATE", 2025, true, LocalDate.of(2026, 10, 1));
      long id;
      try(var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      {
         var first = workers.submit(() -> tax.reference("alice", request, input));
         var second = workers.submit(() -> tax.reference("alice", request, input));
         id = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
         assertEquals(id, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      }
      long count = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT count(*) AS n FROM carl_tax_reference").getFirst(), "n"));
      assertEquals(1L, count);
      tax.active("alice", UUID.randomUUID(), id, 1, false, "New source supersedes this edition");
      assertThrows(IllegalArgumentException.class, () -> tax.active("alice", UUID.randomUUID(), id, 1, true, "Old version cannot overwrite"));
   }



   @Test
   void longTreatmentEvidenceUsesProtectedIdentityWithoutMisrepresentingProfessionalApproval()
   {
      long property = property("PRIVATE");
      long reference = reference("PRIVATE");
      long document = new TaxRecords(service).document("alice", property, "Supplied tax classification", "PRIVATE", 2025, TaxPreparation.Category.OWNERSHIP, TaxPreparation.Treatment.PROPOSED, "Human proposed evidence ".repeat(100), "Synthetic source document");
      String result = facts("alice", tax.packet(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), 2025, NOW, List.of(property), List.of(), List.of(reference)));
      assertTrue(result.contains("record:" + document + ":treatment-evidence"));
      assertTrue(result.contains("PROPOSED"));
      assertTrue(result.contains("UNDETERMINED"));
   }



   @Test
   void sharedDocumentLimitIsAppliedAfterAudienceIntersection()
   {
      long property = property("FAMILY");
      sql("WITH records AS (INSERT INTO carl_record(household_id,owner_id,domain,visibility,title,evidence) SELECT 1,1,'TAX','PRIVATE','Private source','Synthetic hidden record' FROM generate_series(1,1001) RETURNING id) INSERT INTO carl_tax_document(record_id,tax_year,jurisdiction,document_kind,property_id,classification_state) SELECT id,2025,'US / Illinois','OWNERSHIP'," + property + ",'UNKNOWN' FROM records");
      long common = new TaxRecords(service).document("alice", property, "Shared ownership evidence", "FAMILY", 2025, TaxPreparation.Category.OWNERSHIP, TaxPreparation.Treatment.UNKNOWN, null, "Synthetic shared source");
      long packet = tax.packet(new CarlService.Scope("alice", Set.of("alice", "bob")), UUID.randomUUID(), 2025, NOW, List.of(property), List.of(), List.of());
      assertTrue(facts("bob", packet).contains("document:" + common));
      assertFalse(facts("bob", packet).contains("Private source"));
   }



   private static void sql(String value)
   {
      try(var c = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var s = c.createStatement())
      {
         s.execute(value);
      }
      catch(Exception failure)
      {
         throw new IllegalStateException(failure);
      }
   }
}
