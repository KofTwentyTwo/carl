/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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


class ArtifactExportsTest
{
   private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
   private static CarlService service;
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
      sql("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic rental household','America/Chicago')");
      sql("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic reader',false)");
      sql("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['FINANCE','TAX','BILLS','CALENDAR','VENDORS']) d");
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



   private static long report(CarlService.Scope scope)
   {
      service.importBills("alice", UUID.randomUUID(), "Synthetic bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic power,Power,125.25,USD,2026-09-10,UNPAID,FAMILY\nb,Synthetic gas,Gas,74.75,USD,2026-09-12,UNPAID,FAMILY\ne,Synthetic EUR,Separate currency,5.50,EUR,2026-09-14,UNPAID,FAMILY\np,Synthetic private,Private bill,999.00,USD,2026-09-14,UNPAID,PRIVATE\n");
      return service.generateReport(scope, UUID.randomUUID(), FROM, THROUGH, verified -> "Synthetic explanation only; no payment was made.");
   }



   @Test
   void familyExportsUseExplicitBytePagesAndRecheckEveryRecipient() throws Exception
   {
      long artifact = report(new CarlService.Scope("alice", java.util.Set.of("alice", "bob")));
      var member = service.member("alice");
      var context = new com.kof22.agentadmin.client.ClientWorkflow.Context(new com.kof22.agentadmin.client.FamilyAccess.Member("1", "1", "alice", Long.toString(member.permissionRevision())), UUID.randomUUID(), true, java.util.Set.of("1", "2"));
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      try(var workflows = new CarlClientWorkflows(service))
      {
         var exports = workflows.handlers().get("report-export");
         UUID export = UUID.randomUUID();
         exports.start(context, export, json.createObjectNode().put("artifact", artifact).put("format", "TEXT"));
         var manifest = await(exports, context, export).artifact();
         assertFalse(manifest.has("contentBase64"));
         assertEquals(64, manifest.get("sha256").asText().length());
         var download = workflows.handlers().get("report-download");
         int offset = 0;
         var bytes = new java.io.ByteArrayOutputStream();
         UUID finalPage = null;
         while(true)
         {
            finalPage = UUID.randomUUID();
            download.start(context, finalPage, json.createObjectNode().put("exportRequest", export.toString()).put("format", "TEXT").put("offset", offset).put("limit", 4096));
            var page = await(download, context, finalPage).artifact();
            bytes.write(java.util.Base64.getDecoder().decode(page.get("contentBase64").asText()));
            if(page.get("nextOffset").isNull())
            {
               break;
            }
            offset = page.get("nextOffset").intValue();
         }
         assertEquals(manifest.get("totalBytes").intValue(), bytes.size());
         assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("$200.00 USD"));
         assertEquals(manifest.get("sha256").asText(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
         UUID savedPage = finalPage;
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> download.start(context, UUID.randomUUID(), json.createObjectNode().put("exportRequest", export.toString()).put("format", "TEXT").put("offset", 0).put("limit", 65537)));
         sql("UPDATE carl_permission SET details=false WHERE member_id=2 AND domain='BILLS'");
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> exports.get(context, export));
         assertThrows(com.kof22.agentadmin.client.ClientFailure.class, () -> download.get(context, savedPage));
      }
   }



   private static com.kof22.agentadmin.client.ClientWorkflow.Result await(com.kof22.agentadmin.client.ClientWorkflow handler, com.kof22.agentadmin.client.ClientWorkflow.Context context, UUID request) throws Exception
   {
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      do
      {
         var result = handler.get(context, request);
         if(result.status() != com.kof22.agentadmin.client.ClientWorkflow.Status.PENDING)
         {
            return result;
         }
         Thread.sleep(10);
      }
      while(System.nanoTime() < until);
      throw new AssertionError("Workflow did not finish");
   }



   @Test
   void sharedReportCopyAndPdfPreserveScopeExactTotalsAndPartialCoverage() throws Exception
   {
      var exports = new ArtifactExports(service);
      long report = report(new CarlService.Scope("alice", Set.of("alice", "bob")));
      String copy = exports.copy("alice", report);
      assertTrue(copy.contains("$200.00 USD"), copy);
      assertTrue(copy.contains("€5.50 EUR"), copy);
      assertFalse(copy.contains("999.00"), copy);
      assertTrue(copy.contains("No authorized calendar connection"), copy);
      UUID request = UUID.randomUUID();
      assertEquals(request, exports.generate("alice", request, report, ArtifactExports.Format.PDF));
      assertEquals(request, exports.generate("alice", request, report, ArtifactExports.Format.PDF));
      byte[] bytes = exports.load("alice", request, ArtifactExports.Format.PDF);
      try(var pdf = org.apache.pdfbox.pdmodel.PDDocument.load(bytes))
      {
         String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
         assertTrue(text.contains("$200.00 USD"), text);
         assertTrue(text.contains("€5.50 EUR"), text);
         assertTrue(text.contains("No authorized calendar connection"), text);
         assertFalse(text.contains("999.00"), text);
         assertEquals(null, pdf.getDocumentCatalog().getOpenAction());
      }
      assertThrows(SecurityException.class, () -> exports.load("bob", request, ArtifactExports.Format.PDF));
      assertThrows(SecurityException.class, () -> exports.load("alice", request, ArtifactExports.Format.TEXT));
      assertThrows(IllegalArgumentException.class, () -> exports.generate("alice", request, report, ArtifactExports.Format.TEXT));
      var output = java.nio.file.Path.of("target/visual-fixtures/carl-household-report.pdf");
      java.nio.file.Files.createDirectories(output.getParent());
      java.nio.file.Files.write(output, bytes);
   }



   @Test
   void sourceChangesAndRevocationInvalidateExistingExports()
   {
      var exports = new ArtifactExports(service);
      long report = report(CarlService.Scope.privateFor("alice"));
      UUID request = exports.generate("alice", UUID.randomUUID(), report, ArtifactExports.Format.TEXT);
      assertTrue(new String(exports.load("alice", request, ArtifactExports.Format.TEXT), java.nio.charset.StandardCharsets.UTF_8).contains("Synthetic private"));
      long source = service.bills(CarlService.Scope.privateFor("alice"), FROM, THROUGH).getFirst().get("id") instanceof Number id ? id.longValue() : -1;
      sql("UPDATE carl_record SET revision=revision+1 WHERE id=" + source);
      assertThrows(SecurityException.class, () -> exports.load("alice", request, ArtifactExports.Format.TEXT));
      assertThrows(SecurityException.class, () -> exports.generate("alice", request, report, ArtifactExports.Format.TEXT));
      UUID current = exports.generate("alice", UUID.randomUUID(), report, ArtifactExports.Format.TEXT);
      assertTrue(new String(exports.load("alice", current, ArtifactExports.Format.TEXT), java.nio.charset.StandardCharsets.UTF_8).contains("currently stale: true"));
      sql("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='BILLS'");
      assertThrows(SecurityException.class, () -> exports.load("alice", current, ArtifactExports.Format.TEXT));
      assertThrows(SecurityException.class, () -> exports.copy("alice", report));
   }



   @Test
   void narrationFailureAndRequestIdentityRemainTruthful()
   {
      service.importBills("alice", UUID.randomUUID(), "Synthetic bills", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic,Power,10.01,USD,2026-09-10,UNPAID,PRIVATE\n");
      long report = service.generateReport(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), FROM, THROUGH, verified ->
      {
         throw new IllegalStateException("Controlled narration failure");
      });
      var exports = new ArtifactExports(service);
      String copy = exports.copy("alice", report);
      assertTrue(copy.contains("narration: FAILED"), copy);
      assertTrue(copy.contains("Narration failed; verified facts are retained"), copy);
      UUID request = exports.generate("alice", UUID.randomUUID(), report, ArtifactExports.Format.PDF);
      assertThrows(SecurityException.class, () -> exports.generate("bob", request, report, ArtifactExports.Format.PDF));
      sql("UPDATE carl_member SET active=false WHERE principal='alice'");
      assertThrows(SecurityException.class, () -> exports.load("alice", request, ArtifactExports.Format.PDF));
   }



   @Test
   void longSourceEvidenceIsCompleteAcrossPdfSections() throws Exception
   {
      service.importBills("alice", UUID.randomUUID(), "Synthetic source", "source_id,vendor,description,amount,currency,due_date,status,visibility\na,Synthetic,Long evidence,10.01,USD,2026-09-10,UNPAID,PRIVATE\n");
      long bill = CarlService.number(service.bills(CarlService.Scope.privateFor("alice"), FROM, THROUGH).getFirst(), "id");
      String evidence = "Synthetic supporting words ".repeat(650) + "END OF COMPLETE SUPPLIED EVIDENCE";
      service.transaction(c ->
      {
         CarlService.execute(c, "UPDATE carl_record SET evidence=? WHERE id=?", evidence, bill);
         return null;
      });
      long report = service.generateReport(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), FROM, THROUGH, null);
      var exports = new ArtifactExports(service);
      UUID reference = exports.generate("alice", UUID.randomUUID(), report, ArtifactExports.Format.PDF);
      try(var pdf = org.apache.pdfbox.pdmodel.PDDocument.load(exports.load("alice", reference, ArtifactExports.Format.PDF)))
      {
         String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
         assertTrue(text.replaceAll("\\s+", " ").contains("END OF COMPLETE SUPPLIED EVIDENCE"), text);
         assertTrue(pdf.getNumberOfPages() > 1);
         assertTrue(pdf.getNumberOfPages() <= com.kof22.carlai.report.PlanPdfRenderer.MAX_PAGES);
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
