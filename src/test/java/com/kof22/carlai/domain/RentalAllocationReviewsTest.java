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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RentalAllocationReviewsTest
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
   void savedMultiPropertyReviewAppliesOneImmutableClassification()
   {
      long a = property("FAMILY", "1");
      long b = property("FAMILY", "1");
      long transaction = transaction("-101.01", "shared-repair", "FAMILY");
      var reviews = new RentalAllocationReviews(service);
      long review = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Repair split", "Synthetic invoice");
      long version = reviews.setComponent("alice", UUID.randomUUID(), review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("101.01"), BigDecimal.ZERO, "Invoice component");
      assertFalse(reviews.preview(CarlService.Scope.privateFor("alice"), review).canApply());
      version = reviews.setShare("alice", UUID.randomUUID(), review, version, "repair", a, n("0.5"), "Equal evidenced split");
      version = reviews.setShare("alice", UUID.randomUUID(), review, version, "repair", b, n("0.5"), "Equal evidenced split");
      var preview = reviews.preview(new CarlService.Scope("alice", Set.of("alice", "bob")), review);
      assertTrue(preview.canApply(), preview.toString());
      assertEquals(n("101.01"), preview.components().getFirst().propertyAmounts().values().stream().reduce(n("0.00"), BigDecimal::add));
      UUID request = UUID.randomUUID();
      long classified = reviews.apply("alice", request, review, version, "Confirmed complete split");
      assertEquals(classified, reviews.apply("alice", request, review, version, "Confirmed complete split"));
      assertEquals("APPLIED", reviews.preview(CarlService.Scope.privateFor("alice"), review).state());
      assertEquals(1, service.view(CarlService.Scope.privateFor("alice"), "rentalSources").size());
      long frozenVersion = version;
      assertThrows(IllegalStateException.class, () -> reviews.setShare("alice", UUID.randomUUID(), review, frozenVersion, "repair", a, n("0.25"), "Cannot silently edit an applied split"));
   }



   @Test
   void reviewDependenciesAndSourceRevisionAreEnforcedBeforeApply()
   {
      long a = property("PRIVATE", "1");
      long transaction = transaction("-100.00", "private-repair", "FAMILY");
      var reviews = new RentalAllocationReviews(service);
      long review = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Private property split", "Synthetic invoice");
      long version = reviews.setComponent("alice", UUID.randomUUID(), review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100.00"), BigDecimal.ZERO, "Invoice component");
      version = reviews.setShare("alice", UUID.randomUUID(), review, version, "repair", a, BigDecimal.ONE, "Private source");
      assertThrows(SecurityException.class, () -> reviews.preview(new CarlService.Scope("alice", Set.of("alice", "bob")), review));
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + transaction);
      assertFalse(reviews.preview(CarlService.Scope.privateFor("alice"), review).canApply());
      long frozenVersion = version;
      assertThrows(IllegalArgumentException.class, () -> reviews.apply("alice", UUID.randomUUID(), review, frozenVersion, "Stale review must not apply"));
      assertTrue(service.view(CarlService.Scope.privateFor("alice"), "rentalSources").isEmpty());
   }



   @Test
   void pinnedClassificationGuardChecksInsideAuthoritativeTransaction()
   {
      long a = property("FAMILY", "1");
      long transaction = transaction("-100.00", "pinned", "FAMILY");
      long revision = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", transaction).getFirst(), "revision"));
      var parts = List.of(component("repair", RentalEconomics.Kind.OPERATING_EXPENSE, "100.00", a));
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + transaction);
      assertThrows(IllegalArgumentException.class, () -> rentals.classifyPinned("alice", UUID.randomUUID(), transaction, revision, parts, "FAMILY", "Source changed after preview"));
      long classified = rentals.classifyPinned("alice", UUID.randomUUID(), transaction, revision + 1, parts, "FAMILY", "Fresh review");
      assertThrows(IllegalArgumentException.class, () -> rentals.reviseClassificationPinned("alice", UUID.randomUUID(), classified, 1, revision, parts, "Pinned revision mismatch"));
   }



   @Test
   void editsAreVersionedBoundedAndNeverChangeSourceRecords()
   {
      long a = property("FAMILY", "1");
      long transaction = transaction("-100.00", "editable", "FAMILY");
      var reviews = new RentalAllocationReviews(service);
      UUID create = UUID.randomUUID();
      long review = reviews.create("alice", create, transaction, "FAMILY", "Review", "Invoice");
      assertEquals(review, reviews.create("alice", create, transaction, "FAMILY", "Review", "Invoice"));
      UUID edit = UUID.randomUUID();
      assertEquals(2, reviews.setComponent("alice", edit, review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), n("0.2"), "Component"));
      assertEquals(2, reviews.setComponent("alice", edit, review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), n("0.2"), "Component"));
      assertThrows(IllegalArgumentException.class, () -> reviews.setComponent("alice", UUID.randomUUID(), review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), n("0.2"), "Stale edit"));
      assertThrows(IllegalArgumentException.class, () -> reviews.setShare("alice", UUID.randomUUID(), review, 2, "repair", a, n("0.9"), "Too much"));
      assertThrows(IllegalArgumentException.class, () -> reviews.setComponent("alice", UUID.randomUUID(), review, 2, "invalid", RentalEconomics.Kind.OPERATING_EXPENSE, n("100.001"), BigDecimal.ZERO, "Currency precision"));
      assertThrows(SecurityException.class, () -> reviews.setShare("bob", UUID.randomUUID(), review, 2, "repair", a, n("0.8"), "Not a manager"));
      assertEquals(3, reviews.setShare("alice", UUID.randomUUID(), review, 2, "repair", a, n("0.8"), "Property portion"));
      assertTrue(reviews.preview(CarlService.Scope.privateFor("alice"), review).canApply());
      assertEquals(4, reviews.setShare("alice", UUID.randomUUID(), review, 3, "repair", a, BigDecimal.ZERO, "Remove share"));
      assertFalse(reviews.preview(CarlService.Scope.privateFor("alice"), review).canApply());
      assertEquals(5, reviews.removeComponent("alice", UUID.randomUUID(), review, 4, "repair", "Remove component"));
      assertTrue(reviews.preview(CarlService.Scope.privateFor("alice"), review).components().isEmpty());
      assertTrue(service.view(CarlService.Scope.privateFor("alice"), "rentalSources").isEmpty());
   }



   @Test
   void revisedReviewRetainsPreviousClassificationAndConcurrentChangesRejectApply()
   {
      long a = property("FAMILY", "1");
      long transaction = transaction("-100.00", "revised", "FAMILY");
      long classified = rentals.classify("alice", UUID.randomUUID(), transaction, List.of(component("repair", RentalEconomics.Kind.OPERATING_EXPENSE, "100.00", a)), "FAMILY", "Original invoice");
      var reviews = new RentalAllocationReviews(service);
      long review = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Reclassify", "Capital evidence");
      reviews.setComponent("alice", UUID.randomUUID(), review, 1, "capital", RentalEconomics.Kind.CAPITAL_EXPENDITURE, n("100"), BigDecimal.ZERO, "Capital component");
      reviews.setShare("alice", UUID.randomUUID(), review, 2, "capital", a, BigDecimal.ONE, "Property");
      assertEquals(classified, reviews.apply("alice", UUID.randomUUID(), review, 3, "Confirmed correction"));
      assertEquals(2, service.<Integer>transaction(c -> CarlService.rows(c, "SELECT version FROM carl_rental_split_version WHERE split_id=?", classified).size()));
      long staleReview = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Older version", "Review evidence");
      reviews.setComponent("alice", UUID.randomUUID(), staleReview, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), BigDecimal.ZERO, "Reviewed component");
      reviews.setShare("alice", UUID.randomUUID(), staleReview, 2, "repair", a, BigDecimal.ONE, "Property");
      rentals.reviseClassification("alice", UUID.randomUUID(), classified, 2, List.of(component("interest", RentalEconomics.Kind.DEBT_INTEREST, "100", a)), "Another reviewed change");
      assertFalse(reviews.preview(CarlService.Scope.privateFor("alice"), staleReview).canApply());
      assertThrows(IllegalArgumentException.class, () -> reviews.apply("alice", UUID.randomUUID(), staleReview, 3, "Outdated classification must not overwrite"));
      assertEquals("DRAFT", reviews.preview(CarlService.Scope.privateFor("alice"), staleReview).state());
      assertEquals(3, service.<Integer>transaction(c -> CarlService.rows(c, "SELECT version FROM carl_rental_split_version WHERE split_id=?", classified).size()));
   }



   @Test
   void interruptedApplyRecoversSavedChildAndRevokedPropertyDeniesReplay()
   {
      long a = property("FAMILY", "1");
      long transaction = transaction("-100.00", "recover", "FAMILY");
      var reviews = new RentalAllocationReviews(service);
      long review = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Repair", "Invoice");
      reviews.setComponent("alice", UUID.randomUUID(), review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), BigDecimal.ZERO, "Component");
      reviews.setShare("alice", UUID.randomUUID(), review, 2, "repair", a, BigDecimal.ONE, "Property");
      UUID child = UUID.nameUUIDFromBytes(("rental-review:" + review + ":3").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      long revision = service.transaction(c -> CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", transaction).getFirst(), "revision"));
      sql("UPDATE carl_rental_review SET state='APPLYING',child_request='" + child + "',apply_evidence='Confirmed split' WHERE record_id=" + review);
      long classified = rentals.classifyPinned("alice", child, transaction, revision, List.of(component("repair", RentalEconomics.Kind.OPERATING_EXPENSE, "100", a)), "FAMILY", "Confirmed split");
      assertEquals(classified, reviews.apply("alice", UUID.randomUUID(), review, 3, "Recover interrupted apply"));
      assertEquals(1, service.<Integer>transaction(c -> CarlService.rows(c, "SELECT version FROM carl_rental_split_version WHERE split_id=?", classified).size()));
      sql("UPDATE carl_record SET visibility='PRIVATE' WHERE id=" + a);
      assertThrows(SecurityException.class, () -> reviews.preview(CarlService.Scope.privateFor("bob"), review));
      sql("UPDATE carl_member SET active=false WHERE principal='alice'");
      assertThrows(SecurityException.class, () -> reviews.apply("alice", UUID.randomUUID(), review, 3, "Revoked replay"));
   }



   @Test
   void dependencyEditsInvalidatePreviouslySharedContext()
   {
      long property = property("PRIVATE", "1");
      long transaction = transaction("-100.00", "epoch", "FAMILY");
      var reviews = new RentalAllocationReviews(service);
      long review = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Shared review", "Invoice");
      reviews.setComponent("alice", UUID.randomUUID(), review, 1, "repair", RentalEconomics.Kind.OPERATING_EXPENSE, n("100"), BigDecimal.ZERO, "Component");
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      assertFalse(reviews.preview(shared, review).canApply());
      long epoch = service.transaction(c -> CarlService.member(c, "alice").permissionRevision());
      reviews.setShare("alice", UUID.randomUUID(), review, 2, "repair", property, BigDecimal.ONE, "Private property dependency");
      long changed = service.transaction(c -> CarlService.member(c, "alice").permissionRevision());
      assertTrue(changed > epoch);
      assertThrows(SecurityException.class, () -> reviews.preview(shared, review));
      sql("DELETE FROM carl_rental_review_share WHERE property_id=" + property);
      assertTrue(service.transaction(c -> CarlService.member(c, "alice").permissionRevision()) > changed);
      assertFalse(reviews.preview(shared, review).canApply());
      long other = reviews.create("alice", UUID.randomUUID(), transaction, "FAMILY", "Another review", "Invoice");
      assertThrows(IllegalStateException.class, () -> sql("UPDATE carl_rental_review_component SET review_id=" + other + " WHERE review_id=" + review));
      assertThrows(IllegalStateException.class, () -> sql("UPDATE carl_rental_review SET transaction_revision=transaction_revision+1 WHERE record_id=" + review));
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
