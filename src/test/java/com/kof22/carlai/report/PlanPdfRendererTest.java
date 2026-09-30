/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.report;


import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PlanPdfRendererTest
{
   static PlanPdfRenderer.Report fixture(int taskCount)
   {
      var tasks = new ArrayList<PlanPdfRenderer.Task>();
      for(int i = 1; i <= taskCount; i++)
      {
         tasks.add(new PlanPdfRenderer.Task("Task " + i + " - Review the long synthetic household account label and reconcile its supporting statement with the current plan", i % 3 == 0 ? null : "Alex (synthetic)", i % 4 == 0 ? null : LocalDate.of(2026, 10, 15), i % 5 == 0 ? PlanPdfRenderer.TaskStatus.COMPLETE : PlanPdfRenderer.TaskStatus.OPEN, "A human reviews this proposal. It does not authorize Carl to send, pay, transfer or commit. Evidence reference S1; confirm missing details before taking action."));
      }
      return new PlanPdfRenderer.Report("Household cash-flow review - synthetic fixture", "PLAN-42", 7, Instant.parse("2026-09-29T12:30:00Z"), "Alex and Sam: explicitly authorized shared records only. Household coverage may be partial.", "2026-09-28; imported statement S1", "Two tasks completed; one source is awaiting human review. No external action was performed.",
         List.of(new PlanPdfRenderer.Figure("Available monthly cash - scenario, not a bank balance", new BigDecimal("1250.25"), "USD", "S1; scenario assumptions require human confirmation"), new PlanPdfRenderer.Figure("Separate-currency asset estimate", new BigDecimal("74.7500"), "EUR", "S2; no conversion applied"), new PlanPdfRenderer.Figure("Unconfirmed balance", null, "USD", "Missing statement; never substituted with zero")), tasks,
         List.of(new PlanPdfRenderer.Source("S1", "Synthetic statement - account A", "Imported 2026-09-28; permitted record 101"), new PlanPdfRenderer.Source("S2", "Untrusted source text remains inert", "<script>alert('x')</script> file:///private/tmp/do-not-read https://example.invalid/not-fetched /Launch /JavaScript")),
         List.of("Draft planning information for human review. This document does not grant authority to perform external actions.", "Missing figures and unassigned due dates are shown explicitly. Figures are kept separate by currency.", "Untrusted Unicode example: mathematical symbol \u2211 and emoji \ud83d\udcb0 must remain visibly represented rather than silently disappear."));
   }



   @Test
   void preservesScopedFactsVersionMissingValuesAndInertUntrustedText() throws Exception
   {
      byte[] bytes = new PlanPdfRenderer().render(fixture(2));
      try(var pdf = PDDocument.load(bytes))
      {
         String text = new PDFTextStripper().getText(pdf);
         assertTrue(text.contains("Carl AI"));
         assertTrue(text.contains("PLAN-42 | Version 7"));
         assertTrue(text.contains("USD 1250.25"));
         assertTrue(text.contains("EUR 74.7500"));
         assertTrue(text.contains("Not available"));
         assertTrue(text.contains("Household coverage may be partial"));
         assertTrue(text.contains("<script>alert('x')</script>"));
         assertTrue(text.contains("https://example.invalid/not-fetched"));
         assertTrue(text.contains("[U+2211]"));
         assertNull(pdf.getDocumentCatalog().getOpenAction());
         assertNull(pdf.getDocumentCatalog().getAcroForm());
         assertNull(pdf.getDocumentCatalog().getNames());
         for(var page : pdf.getPages())
         {
            assertTrue(page.getAnnotations().isEmpty());
         }
      }
   }



   @Test
   void paginatesLongTasksWithFooterAndProducesVisualFixture() throws Exception
   {
      byte[] bytes = new PlanPdfRenderer().render(fixture(24));
      try(var pdf = PDDocument.load(bytes))
      {
         assertTrue(pdf.getNumberOfPages() >= 4);
         assertTrue(pdf.getNumberOfPages() <= PlanPdfRenderer.MAX_PAGES);
         for(int page = 1; page <= pdf.getNumberOfPages(); page++)
         {
            var stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(pdf);
            assertTrue(text.contains("PLAN-42 | Version 7"));
            assertTrue(text.contains("Page " + page + " of " + pdf.getNumberOfPages()));
         }
         assertTrue(new PDFTextStripper().getText(pdf).contains("Task 24"));
         assertTrue(new PDFTextStripper().getText(pdf).contains("Not assigned"));
         assertTrue(new PDFTextStripper().getText(pdf).contains("Not scheduled"));
      }
      Path output = Path.of("target/visual-fixtures/carl-plan.pdf");
      Files.createDirectories(output.getParent());
      Files.write(output, bytes);
   }



   @Test
   void longWordsAndIdentifiersStayInsidePageBoundsWithoutFetchingLinks() throws Exception
   {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var endpoint = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      endpoint.createContext("/source", exchange ->
      {
         calls.incrementAndGet();
         exchange.sendResponseHeaders(200, -1);
         exchange.close();
      });
      endpoint.start();
      try
      {
         var valid = fixture(0);
         var report = new PlanPdfRenderer.Report(valid.title(), "W".repeat(48), Long.MAX_VALUE, valid.generatedAt(), valid.scope(), valid.asOf(), "Z".repeat(2000), valid.figures(), valid.tasks(), List.of(new PlanPdfRenderer.Source("S1", "Inert URL", "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/source")), valid.limitations());
         try(var pdf = PDDocument.load(new PlanPdfRenderer().render(report)))
         {
            var positions = new PDFTextStripper()
            {
               @Override
               protected void processTextPosition(org.apache.pdfbox.text.TextPosition position)
               {
                  assertTrue(position.getXDirAdj() >= 47, "Text left of page margin");
                  assertTrue(position.getXDirAdj() + position.getWidthDirAdj() <= 565, "Text right of page margin");
                  assertTrue(position.getYDirAdj() >= 25 && position.getYDirAdj() <= 766, "Text outside vertical page bounds");
                  super.processTextPosition(position);
               }
            };
            String text = positions.getText(pdf);
            assertTrue(text.replaceAll("\\s", "").contains("Z".repeat(2000)));
            assertTrue(text.contains("Version " + Long.MAX_VALUE));
         }
         assertEquals(0, calls.get());
      }
      finally
      {
         endpoint.stop(0);
      }
   }



   @Test
   void rejectsOversizedMalformedAndExcessPageInputsWithoutSilentTruncation()
   {
      var valid = fixture(1);
      var renderer = new PlanPdfRenderer();
      assertThrows(IllegalArgumentException.class, () -> renderer.render(new PlanPdfRenderer.Report("x".repeat(2001), valid.planId(), 1, valid.generatedAt(), valid.scope(), valid.asOf(), valid.progress(), valid.figures(), valid.tasks(), valid.sources(), valid.limitations())));
      assertThrows(IllegalArgumentException.class, () -> renderer.render(fixture(101)));
      assertThrows(IllegalArgumentException.class, () -> renderer.render(new PlanPdfRenderer.Report(valid.title(), valid.planId(), 0, valid.generatedAt(), valid.scope(), valid.asOf(), valid.progress(), valid.figures(), valid.tasks(), valid.sources(), valid.limitations())));
      var huge = new ArrayList<PlanPdfRenderer.Task>();
      for(int i = 0; i < 100; i++)
      {
         huge.add(new PlanPdfRenderer.Task("Bounded task " + i, "A", null, PlanPdfRenderer.TaskStatus.OPEN, "Long repeated note. ".repeat(180)));
      }
      assertThrows(IllegalArgumentException.class, () -> renderer.render(new PlanPdfRenderer.Report(valid.title(), valid.planId(), 1, valid.generatedAt(), valid.scope(), valid.asOf(), valid.progress(), valid.figures(), huge, valid.sources(), valid.limitations())));
      assertThrows(IllegalArgumentException.class, () -> renderer.render(new PlanPdfRenderer.Report(valid.title(), valid.planId(), 1, valid.generatedAt(), valid.scope(), valid.asOf(), valid.progress(), List.of(new PlanPdfRenderer.Figure("Invalid", BigDecimal.ONE, "USD\nSEND", "S1")), valid.tasks(), valid.sources(), valid.limitations())));
   }
}
