/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import javax.sql.DataSource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.auth0.jwk.Jwk;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.agentadmin.configuration.NativeAgentConfiguration;
import com.kof22.agentcore.security.RbacService;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.PlanReviewMetadataFixture;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.CashPlans;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialPlanning;
import com.kof22.carlai.domain.FinancialRecords;
import com.kof22.carlai.domain.FinancingOffers;
import com.kof22.carlai.domain.FinancingScenarios;
import com.kof22.carlai.domain.PlanLifecycle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 * Purchase, portfolio and plan processes over authenticated QQQ HTTP and real
 * PostgreSQL (issue #18): success, validation and negative access paths.
 ******************************************************************************/
class CarlFinanceProcessNativeTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final LocalDate DATE = LocalDate.of(2026, 9, 10);
   private static final String UNAVAILABLE = "Carl record or operation unavailable";
   private static final Pattern REPORT = Pattern.compile("(?:Saved protected report|Saved report) (\\d+)");

   private static PostgreSQLContainer<?> database;
   private static com.sun.net.httpserver.HttpServer keyServer;
   private static AdminServer server;
   private static HttpClient http;
   private static DataSource data;
   private static CarlService service;
   private static String base;
   private static String alice;
   private static String bob;
   private static String outsider;

   @BeforeAll
   static void start() throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      database = new PostgreSQLContainer<>("postgres:16-alpine");
      database.start();
      data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(data);
      service = new CarlService(data, Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic finance home','America/Chicago')");
         sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic owner',true),(2,1,'bob','Synthetic other',true)");
         sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'FINANCE',true),(2,'FINANCE',true),(1,'CALENDAR',true),(2,'CALENDAR',true)");
         sql.execute("CREATE ROLE carl_finance_reader LOGIN PASSWORD 'synthetic-reader'");
         sql.execute("GRANT USAGE ON SCHEMA public TO carl_finance_reader");
         for(var table : AdminApplication.READER_COLUMNS.entrySet())
         {
            if(!table.getValue().isEmpty())
            {
               sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO carl_finance_reader");
            }
         }
         sql.execute("GRANT SELECT ON carl_artifact_view,carl_member_view,carl_native_plan_step_view,carl_plan_view,carl_portfolio_move_view TO carl_finance_reader");
      }
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      var publicKey = (RSAPublicKey) pair.getPublic();
      var values = Map.<String, Object>of("kty", "RSA", "kid", "finance", "alg", "RS256", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray()));
      var key = Jwk.fromValues(values);
      keyServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      String issuer = "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/";
      byte[] jwks = JSON.writeValueAsBytes(Map.of("keys", List.of(values)));
      keyServer.createContext("/.well-known/jwks.json", exchange ->
      {
         exchange.getResponseHeaders().set("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, jwks.length);
         try(var output = exchange.getResponseBody())
         {
            output.write(jwks);
         }
      });
      keyServer.start();
      var identity = new BearerIdentity(issuer, "carl-admin", "synthetic-client", new RbacService(Map.of("alice", Role.OPERATOR, "bob", Role.OPERATOR)), ignored -> key);
      var configuration = NativeAgentConfiguration.load(Path.of("config/agent.properties"), Map.of(), "--kof22.agent.db.url=" + database.getJdbcUrl(), "--kof22.agent.db.username=" + database.getUsername(), "--kof22.agent.db.password=" + database.getPassword(), "--kof22.agent.qqq.db-password=synthetic-reader", "--kof22.agent.anthropic-api-key=synthetic-no-provider");
      var reader = NativeDatabases.backend("agentOperations", configuration.database(), "carl_finance_reader", "synthetic-reader");
      var runtime = NativeDatabases.backend("operatorSessions", configuration.database(), database.getUsername(), database.getPassword());
      var app = new AdminApplication(reader, List.of(new PlanReviewMetadataFixture(service)), identity, runtime);
      server = new AdminServer(app, 0, "127.0.0.1", "https://carl.synthetic", identity, OperatorSessions.usingBackend(runtime), ignored ->
      {
      }, new NativeDownloadPolicy(Map.of("carlProtectedPlanExports", "carlExportPlan")));
      server.start();
      http = HttpClient.newHttpClient();
      base = "http://127.0.0.1:" + server.port();
      var signer = Algorithm.RSA256(publicKey, (RSAPrivateKey) pair.getPrivate());
      alice = JWT.create().withKeyId("finance").withIssuer(issuer).withAudience("carl-admin").withSubject("alice").withExpiresAt(Instant.now().plusSeconds(600)).sign(signer);
      bob = JWT.create().withKeyId("finance").withIssuer(issuer).withAudience("carl-admin").withSubject("bob").withExpiresAt(Instant.now().plusSeconds(600)).sign(signer);
      outsider = JWT.create().withKeyId("finance").withIssuer(issuer).withAudience("carl-admin").withSubject("mallory").withExpiresAt(Instant.now().plusSeconds(600)).sign(signer);
   }



   @AfterAll
   static void stop() throws Exception
   {
      try(var ignoredDatabase = database; var ignoredServer = server)
      {
         if(http != null)
         {
            http.close();
         }
         if(keyServer != null)
         {
            keyServer.stop(0);
         }
      }
   }



   @BeforeEach
   void restorePermissions() throws Exception
   {
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("UPDATE carl_permission SET details=true");
      }
   }



   @Test
   void purchaseComparisonRendersProtectedReportAndRejectsInvalidOrForeignInputs() throws Exception
   {
      long cash = cashPlan("alice", "PRIVATE");
      long card = debtAccount("alice", "Synthetic purchase card", "PRIVATE");
      long offer = offer("alice", "PRIVATE", "PURCHASE_FINANCE");
      long refinance = offer("alice", "PRIVATE", "REFINANCE");

      var full = new LinkedHashMap<>(purchase(cash));
      full.put("card", Long.toString(card));
      full.put("cardPayoffDate", DATE.plusDays(25).toString());
      full.put("graceConfirmed", "true");
      full.put("balanceReviewed", "true");
      full.put("cardEvidence", "Synthetic reviewed card terms");
      full.put("offer1", Long.toString(offer));
      String html = html(run(alice, "carlComparePurchaseOptions", full));
      assertTrue(html.contains("Purchase payment choices"), html);
      assertTrue(html.contains("Evidence and limitations"), html);
      assertTrue(html.contains("offer:" + offer), html);
      assertTrue(html.contains("card:" + card), html);
      assertTrue(html.contains("not an order or financing application"), html);
      assertFalse(html.contains("<script"), html);
      long report = report(html);
      assertEquals(report, ((Number) service.artifact("alice", report).get("id")).longValue());
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));

      String unqualified = html(run(alice, "carlComparePurchaseOptions", purchase(cashPlan("alice", "PRIVATE", false))));
      assertTrue(unqualified.contains("Maximum supported cash budget: Not established"), unqualified);

      String cashOnly = html(run(alice, "carlComparePurchaseOptions", purchase(cash)));
      assertTrue(cashOnly.contains("Purchase payment choices"), cashOnly);
      assertFalse(cashOnly.contains("offer:"), cashOnly);
      assertFalse(report(cashOnly) == report, cashOnly);

      var duplicate = new LinkedHashMap<>(purchase(cash));
      duplicate.put("offer1", Long.toString(offer));
      duplicate.put("offer3", Long.toString(offer));
      assertRejected(run(alice, "carlComparePurchaseOptions", duplicate), "Select up to five distinct actual offer records");
      var transfer = new LinkedHashMap<>(purchase(cash));
      transfer.put("offer2", Long.toString(refinance));
      assertRejected(run(alice, "carlComparePurchaseOptions", transfer), "Choose a purchase-financing offer");

      assertRejected(run(bob, "carlComparePurchaseOptions", purchase(cash)), UNAVAILABLE);
      var foreignCard = new LinkedHashMap<>(purchase(cashPlan("bob", "PRIVATE")));
      foreignCard.put("card", Long.toString(card));
      foreignCard.put("cardPayoffDate", DATE.plusDays(25).toString());
      foreignCard.put("graceConfirmed", "true");
      foreignCard.put("balanceReviewed", "true");
      foreignCard.put("cardEvidence", "Forged card selection");
      assertRejected(run(bob, "carlComparePurchaseOptions", foreignCard), UNAVAILABLE);
      assertDenied(start(outsider, "carlComparePurchaseOptions"));
      revokeFinance(1);
      assertRejected(run(alice, "carlComparePurchaseOptions", purchase(cash)), "Carl ");
   }



   @Test
   void portfolioProcessesRecordRatesMovesAndComparisonsOnlyForAccessibleDebts() throws Exception
   {
      long card = debtAccount("alice", "Synthetic rate card", "PRIVATE");
      long source = debtAccount("alice", "Synthetic portfolio source", "PRIVATE");
      long second = debtAccount("alice", "Synthetic second debt", "PRIVATE");
      long offer = offer("alice", "PRIVATE", "REFINANCE");

      var rate = Map.of("account", Long.toString(card), "expectedRevision", Long.toString(revision(card)), "effective", DATE.plusMonths(2).toString(), "apr", "0.29", "monthlyFee", "2.00", "evidence", "Synthetic notice of change");
      String recorded = html(run(alice, "carlDebtRateChange", rate));
      assertTrue(recorded.contains("Effective-dated rate and fee recorded"), recorded);
      assertRejected(run(alice, "carlDebtRateChange", rate), "Debt changed");
      var foreignRate = new LinkedHashMap<>(rate);
      foreignRate.put("expectedRevision", Long.toString(revision(card)));
      assertRejected(run(bob, "carlDebtRateChange", foreignRate), UNAVAILABLE);

      String move = html(run(alice, "carlReviewPortfolioMove", move(offer, source)));
      var moveMatcher = Pattern.compile("Reviewed hypothetical allocation (\\d+) saved").matcher(move);
      assertTrue(moveMatcher.find(), move);
      long moveId = Long.parseLong(moveMatcher.group(1));
      assertTrue(move.contains("No loan application, transfer or acceptance occurred"), move);
      var repeated = new LinkedHashMap<>(move(offer, source));
      repeated.put("sourceAccount2", Long.toString(source));
      repeated.put("allocatedAmount2", "10.00");
      assertRejected(run(alice, "carlReviewPortfolioMove", repeated), "Select each source once, with its explicit amount");
      var unpaired = new LinkedHashMap<>(move(offer, source));
      unpaired.put("sourceAccount3", Long.toString(second));
      assertRejected(run(alice, "carlReviewPortfolioMove", unpaired), "Select each source once, with its explicit amount");
      var amountOnly = new LinkedHashMap<>(move(offer, source));
      amountOnly.put("allocatedAmount4", "5.00");
      assertRejected(run(alice, "carlReviewPortfolioMove", amountOnly), "Select each source once, with its explicit amount");
      assertRejected(run(bob, "carlReviewPortfolioMove", move(offer, source)), UNAVAILABLE);

      var compare = new LinkedHashMap<>(comparison());
      compare.put("account1", Long.toString(source));
      compare.put("account2", Long.toString(second));
      compare.put("move1", Long.toString(moveId));
      String html = html(run(alice, "carlComparePortfolio", compare));
      assertTrue(html.contains("Selected debt portfolio"), html);
      assertTrue(html.contains("AVALANCHE"), html);
      assertTrue(html.contains("CURRENT PAYMENT"), html);
      assertTrue(html.contains("Source records: "), html);
      long report = report(html);
      assertThrows(SecurityException.class, () -> service.artifact("bob", report));
      var shortHorizon = new LinkedHashMap<>(compare);
      shortHorizon.put("horizonMonths", "1");
      String unfinished = html(run(alice, "carlComparePortfolio", shortHorizon));
      assertTrue(unfinished.contains("Not established within horizon"), unfinished);
      var duplicateDebt = new LinkedHashMap<>(compare);
      duplicateDebt.put("account3", Long.toString(source));
      assertRejected(run(alice, "carlComparePortfolio", duplicateDebt), "Select each debt once");
      var duplicateMove = new LinkedHashMap<>(compare);
      duplicateMove.put("move2", Long.toString(moveId));
      assertRejected(run(alice, "carlComparePortfolio", duplicateMove), "Select each allocation once");
      var foreign = new LinkedHashMap<>(comparison());
      foreign.put("account1", Long.toString(source));
      assertRejected(run(bob, "carlComparePortfolio", foreign), UNAVAILABLE);
      assertDenied(start(outsider, "carlComparePortfolio"));
      revokeFinance(1);
      assertRejected(run(alice, "carlComparePortfolio", compare), "Carl ");
   }



   @Test
   void planCreationAgreementAndExportRequireExplicitHumanActionAndCurrentAccess() throws Exception
   {
      long debt = debtAccount("alice", "Synthetic plan debt", "PRIVATE");
      long source = new DebtPlans(service).compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), DATE, "USD", new BigDecimal("150.00"), 12, "Synthetic reviewed budget");
      assertTrue(debt > 0);

      String created = html(run(alice, "carlCreatePlan", Map.of("sourceArtifact", Long.toString(source), "title", "Synthetic payoff plan", "reason", "Human selected comparison")));
      var matcher = Pattern.compile("Draft plan saved: (\\d+)").matcher(created);
      assertTrue(matcher.find(), created);
      long plan = Long.parseLong(matcher.group(1));
      var plans = new PlanLifecycle(service);
      assertEquals("DRAFT", ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("state").toString());
      assertRejected(run(bob, "carlCreatePlan", Map.of("sourceArtifact", Long.toString(source), "title", "Forged plan", "reason", "Foreign comparison")), UNAVAILABLE);

      plans.step("alice", plan, 1, UUID.randomUUID(), "Synthetic task", 1, DATE.plusDays(20), "Human action", null, "Synthetic reviewed task");
      String process = review(alice, "carlAgreePlan", plan);
      assertRejected(submit(alice, "carlAgreePlan", process, "review", Map.of("confirm", "false", "reason", "Not yet agreed")), "Explicit agreement required");
      assertEquals("DRAFT", ((Map<?, ?>) plans.get("alice", plan).get("plan")).get("state").toString());

      var export = run(alice, "carlExportPlan", Map.of("planId", Long.toString(plan)));
      assertSuccess(export);
      var values = JSON.readTree(export.body()).path("values");
      assertEquals("Carl-plan-" + plan + ".pdf", values.path("downloadFileName").asText(), export.body());
      assertEquals("carlProtectedPlanExports", values.path("storageTableName").asText(), export.body());
      assertFalse(values.path("storageReference").asText().isBlank(), export.body());
      assertRejected(run(bob, "carlExportPlan", Map.of("planId", Long.toString(plan))), UNAVAILABLE);
      assertDenied(start(outsider, "carlExportPlan"));
   }



   private static Map<String, String> purchase(long cash)
   {
      return Map.of("cashPlan", Long.toString(cash), "purchaseDate", DATE.plusDays(2).toString(), "allInPrice", "600.00", "purpose", "Synthetic <b>table</b> & chairs", "allInCostsKnown", "true");
   }



   private static Map<String, String> move(long offer, long source)
   {
      var fields = new LinkedHashMap<String, String>();
      fields.put("title", "Synthetic reviewed transfer");
      fields.put("visibility", "PRIVATE");
      fields.put("offer", Long.toString(offer));
      fields.put("asOf", DATE.toString());
      fields.put("capacity", "510.00");
      fields.put("minimum", "100.00");
      fields.put("minimumFraction", "0");
      fields.put("monthlyFee", "1.00");
      fields.put("firstPayment", DATE.plusDays(8).toString());
      fields.put("proposedPayment", "100.00");
      fields.put("sourceAccount1", Long.toString(source));
      fields.put("allocatedAmount1", "500.00");
      fields.put("evidence", "Synthetic hypothetical transfer review");
      return fields;
   }



   private static Map<String, String> comparison()
   {
      return Map.of("asOf", DATE.toString(), "currency", "USD", "monthlyBudget", "200.00", "horizonMonths", "24", "rollover", "AVALANCHE", "budgetEvidence", "Synthetic assumed post-essential budget");
   }



   private static long cashPlan(String principal, String visibility)
   {
      return cashPlan(principal, visibility, true);
   }



   private static long cashPlan(String principal, String visibility, boolean balancesResolved)
   {
      return new CashPlans(service).create(principal, "Synthetic six-month cash plan", visibility, "Synthetic reviewed scope", new CashPlans.Assumptions("USD", DATE, DATE.plusMonths(6), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("1000"), balancesResolved, true, true, true, true, true));
   }



   private static long debtAccount(String principal, String title, String visibility)
   {
      long id = new FinancialRecords(service).createAccount(principal, title, "CREDIT_CARD", "USD", false, BigDecimal.ONE, visibility, "Synthetic balance evidence");
      var debts = new DebtPlans(service);
      debts.terms(principal, id, DATE, new BigDecimal("1000.00"), new BigDecimal("25.00"), BigDecimal.ZERO, new BigDecimal("0.24"), BigDecimal.ZERO, "Synthetic contract");
      debts.paymentProfile(principal, id, DATE, DATE.plusDays(5), new BigDecimal("100.00"), new BigDecimal("50.00"), FinancialPlanning.Strategy.AVALANCHE, "Synthetic reviewed dates");
      return id;
   }



   private static long offer(String principal, String visibility, String kind)
   {
      if(kind.equals("REFINANCE"))
      {
         var terms = new FinancingScenarios.Offer("synthetic", "USD", new BigDecimal("500.00"), new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("100.00"), 24, List.of(new FinancialPlanning.Rate(1, new BigDecimal("0.10"))), FinancingScenarios.Promotion.none(), FinancingScenarios.Evidence.HYPOTHETICAL);
         return new FinancingOffers(service).create(principal, "Synthetic partial refinance", visibility, kind, terms, DATE, DATE.plusMonths(1), DATE.plusDays(8), "Collateral unknown; review required", "Synthetic offer, eligibility unknown");
      }
      var terms = new FinancingScenarios.Offer("store", "USD", new BigDecimal("600"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"), 6, List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(7, new BigDecimal("0.24"))), new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 6, BigDecimal.ZERO, true), FinancingScenarios.Evidence.VERIFIED_TERMS);
      return new FinancingOffers(service).create(principal, "Synthetic store terms", visibility, kind, terms, DATE, DATE.plusDays(30), DATE.plusMonths(1), "Unsecured supplied terms; approval unverified", "Synthetic reviewed price and offer");
   }



   private static long revision(long record) throws Exception
   {
      try(var c = data.getConnection(); var sql = c.prepareStatement("SELECT revision FROM carl_record WHERE id=?"))
      {
         sql.setLong(1, record);
         try(var rows = sql.executeQuery())
         {
            assertTrue(rows.next());
            return rows.getLong(1);
         }
      }
   }



   private static void revokeFinance(long member) throws Exception
   {
      try(var c = data.getConnection(); var sql = c.prepareStatement("UPDATE carl_permission SET details=false WHERE member_id=? AND domain='FINANCE'"))
      {
         sql.setLong(1, member);
         assertEquals(1, sql.executeUpdate());
      }
   }



   private static long report(String html)
   {
      var matcher = REPORT.matcher(html);
      assertTrue(matcher.find(), html);
      return Long.parseLong(matcher.group(1));
   }



   private static String html(HttpResponse<String> response) throws Exception
   {
      assertSuccess(response);
      return JSON.readTree(response.body()).path("values").path("result.html").asText();
   }



   private static HttpResponse<String> run(String token, String name, Map<String, String> fields) throws Exception
   {
      var started = start(token, name);
      assertSuccess(started);
      String id = JSON.readTree(started.body()).path("processUUID").asText();
      assertFalse(id.isBlank(), started.body());
      return submit(token, name, id, "input", fields);
   }



   private static String review(String token, String name, long plan) throws Exception
   {
      var started = start(token, name);
      assertSuccess(started);
      String id = JSON.readTree(started.body()).path("processUUID").asText();
      assertSuccess(submit(token, name, id, "choose", Map.of("planId", Long.toString(plan))));
      return id;
   }



   private static HttpResponse<String> start(String token, String name) throws Exception
   {
      return request("/qqq/v1/processes/" + name + "/init", token, Map.of());
   }



   private static HttpResponse<String> submit(String token, String name, String id, String step, Map<String, String> fields) throws Exception
   {
      return request("/qqq/v1/processes/" + name + "/" + id + "/step/" + step, token, Map.of("values", JSON.writeValueAsString(fields)));
   }



   private static HttpResponse<String> request(String route, String token, Map<String, String> fields) throws Exception
   {
      var body = new StringBuilder();
      fields.forEach((key, value) -> body.append("--carl-finance\r\nContent-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n").append(value).append("\r\n"));
      body.append("--carl-finance--\r\n");
      var request = HttpRequest.newBuilder(URI.create(base + route)).header("Authorization", "Bearer " + token).header("Origin", "https://carl.synthetic")
         .header("Content-Type", "multipart/form-data; boundary=carl-finance").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }



   private static void assertSuccess(HttpResponse<String> response) throws Exception
   {
      assertEquals(200, response.statusCode(), response.body());
      assertFalse(JSON.readTree(response.body()).hasNonNull("error"), response.body());
   }



   private static void assertRejected(HttpResponse<String> response, String reason) throws Exception
   {
      JsonNode body = response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
      assertTrue(response.statusCode() >= 400 || (body != null && body.hasNonNull("error")), response.body());
      assertTrue(response.body().contains(reason), response.body());
      assertFalse(response.body().contains("result.html"), response.body());
   }



   private static void assertDenied(HttpResponse<String> response) throws Exception
   {
      assertTrue(response.statusCode() >= 400 || JSON.readTree(response.body()).hasNonNull("error"), response.statusCode() + " " + response.body());
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
