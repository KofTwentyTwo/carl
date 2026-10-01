
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TaxPreparationTest
{
   private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
   private static TaxPreparation.PropertyFacts unknown()
   {
      return new TaxPreparation.PropertyFacts("property", "USD", null, null, null, null, null, null, null, Set.of("synthetic-property-source"));
   }



   private static TaxPreparation.Reference reference(int year)
   {
      return new TaxPreparation.Reference("irs-527", URI.create("https://www.irs.gov/publications/p527"), "Publication527 " + year,
         Set.of(year), NOW.minusSeconds(60), null);
   }



   @Test
   void unknownBasisOwnershipYearAndRulesRemainUndetermined()
   {
      var packet = TaxPreparation.assemble(null, NOW, List.of(unknown()), List.of(), List.of(reference(2025)), false);
      assertEquals("UNDETERMINED", packet.taxCalculationStatus());
      assertNull(packet.taxYear());
      assertTrue(packet.gaps().stream().anyMatch(s -> s.contains("tax year")));
      assertTrue(packet.gaps().stream().anyMatch(s -> s.contains("ownership")));
      assertTrue(packet.gaps().stream().anyMatch(s -> s.contains("land/building")));
      assertFalse(packet.sourceCoverageComplete());
   }



   @Test
   void priorYearEvidenceAndReferenceDoNotQualifyCurrentYearRules()
   {
      var document = new TaxPreparation.Document("rent-2025", "property", TaxPreparation.Category.RENT_RECORDS, 2025,
         "authorized-source", TaxPreparation.Treatment.PROPOSED, null);
      var packet = TaxPreparation.assemble(2026, NOW, List.of(unknown()), List.of(document), List.of(reference(2025)), true);
      assertEquals(1, packet.documents().size());
      assertTrue(packet.checklist().stream().anyMatch(i -> i.category() == TaxPreparation.Category.RENT_RECORDS && i.missing()));
      assertTrue(packet.gaps().stream().anyMatch(s -> s.contains("2026") && s.contains("source")));
      assertEquals("UNDETERMINED", packet.taxCalculationStatus());
      assertEquals(TaxPreparation.Treatment.PROPOSED, packet.documents().getFirst().treatment());
   }



   @Test
   void yearMatchedPacketRetainsEvidenceAndNeverInventsTaxAmount()
   {
      var facts = new TaxPreparation.PropertyFacts("property", "USD", new BigDecimal("0.5"), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1),
         new BigDecimal("100000.00"), new BigDecimal("20000.00"), new BigDecimal("80000.00"), false, Set.of("deed", "closing", "owner-record"));
      var documents = java.util.Arrays.stream(TaxPreparation.Category.values()).map(category -> new TaxPreparation.Document(category.name(), "property", category,
         category.yearSpecific() ? 2025 : null, "source:" + category.name(), TaxPreparation.Treatment.UNKNOWN, null)).toList();
      var packet = TaxPreparation.assemble(2025, NOW, List.of(facts), documents, List.of(reference(2025)), true);
      assertTrue(packet.checklist().stream().noneMatch(TaxPreparation.CheckItem::missing));
      assertTrue(packet.gaps().isEmpty());
      assertEquals("UNDETERMINED", packet.taxCalculationStatus());
      assertTrue(packet.calculationLimitation().contains("No implemented, qualified"));
      assertTrue(packet.professionalQuestions().stream().anyMatch(s -> s.contains("lender")));
   }



   @Test
   void confirmedTreatmentRequiresItsOwnEvidenceAndBasisMustReconcile()
   {
      var badDocument = new TaxPreparation.Document("expense", "property", TaxPreparation.Category.EXPENSE_RECORDS, 2026, "invoice", TaxPreparation.Treatment.CONFIRMED, null);
      assertThrows(IllegalArgumentException.class, () -> TaxPreparation.assemble(2026, NOW, List.of(unknown()), List.of(badDocument), List.of(), true));
      var badBasis = new TaxPreparation.PropertyFacts("property", "USD", BigDecimal.ONE, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1),
         new BigDecimal("100000.00"), new BigDecimal("40000.00"), new BigDecimal("80000.00"), false, Set.of("basis-record"));
      assertThrows(IllegalArgumentException.class, () -> TaxPreparation.assemble(2026, NOW, List.of(badBasis), List.of(), List.of(), true));
   }



   @Test
   void rejectsUnauthorizedPropertyLinksDuplicateEvidenceAndNonGovernmentUrls()
   {
      var document = new TaxPreparation.Document("expense", "other", TaxPreparation.Category.EXPENSE_RECORDS, 2026, "invoice", TaxPreparation.Treatment.UNKNOWN, null);
      assertThrows(IllegalArgumentException.class, () -> TaxPreparation.assemble(2026, NOW, List.of(unknown()), List.of(document), List.of(), true));
      var reference = new TaxPreparation.Reference("untrusted", URI.create("https://example.com/tax"), "current", Set.of(2026), NOW, null);
      assertThrows(IllegalArgumentException.class, () -> TaxPreparation.assemble(2026, NOW, List.of(unknown()), List.of(), List.of(reference), true));
      assertThrows(IllegalArgumentException.class, () -> TaxPreparation.assemble(2026, NOW, List.of(unknown(), unknown()), List.of(), List.of(), true));
   }



   @Test
   void futureAcquisitionAndServiceRemainPlannedUnverifiedFacts()
   {
      var future = new TaxPreparation.PropertyFacts("future", "USD", BigDecimal.ONE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1),
         null, null, null, false, Set.of("proposed-acquisition"));
      var packet = TaxPreparation.assemble(2026, NOW, List.of(future), List.of(), List.of(), true);
      assertEquals(2, packet.gaps().stream().filter(s -> s.contains("planned/unverified")).count());
      assertEquals("UNDETERMINED", packet.taxCalculationStatus());
   }
}
