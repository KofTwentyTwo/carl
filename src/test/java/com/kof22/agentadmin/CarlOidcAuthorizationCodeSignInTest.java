/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.bootstrap.NativeAgentRuntime;
import com.kof22.agentadmin.bootstrap.NativeConfigurationFiles;
import com.kof22.agentadmin.bootstrap.NativeDatabases;
import com.kof22.carlai.AgentApplication;
import com.kof22.carlai.domain.CalendarWorkflows;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.FinancialRecords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 * Issue #20: Carl's production composition, configured for oidc-bearer
 * administration, completes the dashboard's authorization-code + PKCE sign-in
 * against an in-process OpenID provider, bootstraps a native session from the
 * verified access token, and rejects mismatched state, audience and keys.
 ******************************************************************************/
class CarlOidcAuthorizationCodeSignInTest
{
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final SecureRandom RANDOM = new SecureRandom();
   private static final String ORIGIN = "https://carl.synthetic";
   private static final String AUDIENCE = "carl-admin";
   private static final String READER = "carl_oidc_reader";

   @TempDir
   Path directory;

   @Test
   void dashboardAuthorizationCodeSignInEstablishesVerifiedNativeSession() throws Exception
   {
      System.setProperty("qqq.logger.logSessionId.disabled", "true");
      System.setProperty("qqq.rdbms.logSQL", "false");
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         seed(database);
         try(var provider = new SyntheticAuthorizationCodeProvider(directory, ORIGIN + "/");
            var host = NativeAgentRuntime.start(configuration(database, provider), AgentApplication.components((source, service) -> new CalendarWorkflows(Map.of()), Map.of()));
            var nativeHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var browser = HttpClient.newBuilder().sslContext(provider.tls()).followRedirects(HttpClient.Redirect.NEVER).build())
         {
            assertTrue(host.isReady());
            var client = new DashboardClient(nativeHttp, browser, "http://127.0.0.1:" + host.port());

            // The host publishes the provider the dashboard must use; nothing protected is readable yet.
            var authentication = client.get("/qqq/v1/metaData/authentication", null, false);
            assertEquals(200, authentication.statusCode(), authentication.body());
            var metadata = JSON.readTree(authentication.body());
            assertEquals("AUTH_0", metadata.path("type").asText(), authentication.body());
            assertEquals(provider.issuer, metadata.path("values").path("baseUrl").asText(), authentication.body());
            assertEquals(SyntheticAuthorizationCodeProvider.CLIENT_ID, metadata.path("values").path("clientId").asText(), authentication.body());
            assertEquals(AUDIENCE, metadata.path("values").path("audience").asText(), authentication.body());
            assertEquals(401, client.get("/qqq/v1/metaData", null, false).statusCode());
            var discovery = JSON.readTree(browser.send(HttpRequest.newBuilder(URI.create(provider.issuer + ".well-known/openid-configuration")).build(), HttpResponse.BodyHandlers.ofString()).body());
            String providerBase = metadata.path("values").path("baseUrl").asText().replaceAll("/+$", "");
            assertEquals(providerBase + "/authorize", discovery.path("authorization_endpoint").asText());
            assertEquals(providerBase + "/oauth/token", discovery.path("token_endpoint").asText());
            client.provider(providerBase, metadata.path("values").path("clientId").asText());

            // Alice: authorize, front-channel callback, PKCE code exchange, then native session bootstrap.
            var aliceAuthorization = client.authorize("alice", AUDIENCE);
            var callback = client.callback(aliceAuthorization);
            var spa = client.get("/?" + callback.query(), null, false);
            assertNotEquals(401, spa.statusCode(), spa.body());
            assertTrue(spa.statusCode() < 400, spa.statusCode() + " " + spa.body());
            var tokens = client.exchange(callback.code(), aliceAuthorization.verifier());
            assertEquals(200, tokens.statusCode(), tokens.body());
            String aliceToken = JSON.readTree(tokens.body()).path("access_token").asText();
            assertEquals(400, client.exchange(callback.code(), aliceAuthorization.verifier()).statusCode(), "authorization codes are single use");
            var aliceSession = client.manageSession(aliceToken);
            assertEquals(200, aliceSession.statusCode(), aliceSession.body());
            String alice = sessionCookie(aliceSession).orElseThrow(() -> new AssertionError("No native session cookie: " + aliceSession.headers()));
            String cookieHeader = aliceSession.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("sessionUUID=")).findFirst().orElseThrow();
            assertTrue(cookieHeader.contains("HttpOnly"), cookieHeader);
            assertTrue(cookieHeader.contains("Secure"), cookieHeader);
            assertTrue(cookieHeader.toLowerCase(java.util.Locale.ROOT).contains("samesite=strict"), cookieHeader);
            assertFalse(cookieHeader.contains(aliceToken), cookieHeader);

            var who = client.get("/kof22/session", alice, true);
            assertEquals(200, who.statusCode(), who.body());
            assertTrue(who.body().contains("alice"), who.body());
            var appMetadata = client.get("/qqq/v1/metaData", alice, false);
            assertEquals(200, appMetadata.statusCode(), appMetadata.body());
            assertTrue(appMetadata.body().contains("carlAccounts"), appMetadata.body());
            var accounts = client.get("/data/carlAccounts", alice, false);
            assertEquals(200, accounts.statusCode(), accounts.body());
            assertTrue(accounts.body().contains("Private synthetic account"), accounts.body());
            var started = client.post("/qqq/v1/processes/carlCreateGoal/init", alice, true);
            assertEquals(200, started.statusCode(), started.body());
            assertFalse(JSON.readTree(started.body()).path("processUUID").asText().isBlank(), started.body());
            assertEquals(403, client.post("/qqq/v1/processes/carlCreateGoal/init", alice, false).statusCode(), "session cookies require the public origin");

            // Bob signs in through the same flow and cannot read Alice's private account.
            var bobAuthorization = client.authorize("bob", AUDIENCE);
            var bobCallback = client.callback(bobAuthorization);
            var bobSession = client.manageSession(JSON.readTree(client.exchange(bobCallback.code(), bobAuthorization.verifier()).body()).path("access_token").asText());
            assertEquals(200, bobSession.statusCode(), bobSession.body());
            String bob = sessionCookie(bobSession).orElseThrow();
            assertNotEquals(alice, bob);
            assertTrue(client.get("/kof22/session", bob, true).body().contains("bob"));
            var bobAccounts = client.get("/data/carlAccounts", bob, false);
            assertEquals(200, bobAccounts.statusCode(), bobAccounts.body());
            assertFalse(bobAccounts.body().contains("Private synthetic account"), bobAccounts.body());

            // Wrong state: a callback whose state differs from the pending request is refused, and its
            // code is neither accepted by the native host as a credential nor exchangeable without PKCE.
            var pending = client.authorize("alice", AUDIENCE);
            var forged = client.authorize("bob", AUDIENCE);
            var injected = new Authorization(pending.state(), pending.verifier(), forged.location());
            assertThrows(IllegalStateException.class, () -> client.callback(injected));
            String forgedCode = SyntheticAuthorizationCodeProvider.parameters(URI.create(forged.location()).getRawQuery()).get("code");
            assertEquals(401, client.get("/qqq/v1/metaData?" + URI.create(forged.location()).getRawQuery(), null, false).statusCode());
            assertEquals(401, client.manageSession(forgedCode).statusCode());
            assertEquals(400, client.exchange(forgedCode, pending.verifier()).statusCode(), "PKCE verifier from another request");

            // Wrong audience: a token the provider issued for another API cannot open an administration session.
            var other = client.authorize("alice", "carl-other-api");
            var otherCallback = client.callback(other);
            String otherToken = JSON.readTree(client.exchange(otherCallback.code(), other.verifier()).body()).path("access_token").asText();
            var wrongAudience = client.manageSession(otherToken);
            assertEquals(401, wrongAudience.statusCode(), wrongAudience.body());
            assertTrue(sessionCookie(wrongAudience).isEmpty(), wrongAudience.headers().toString());
            assertEquals(401, client.bearer("/qqq/v1/metaData", otherToken).statusCode());

            // A correctly shaped token signed by an unpublished key is rejected.
            var forgedKey = client.manageSession(provider.unpublishedKeyToken("alice", AUDIENCE));
            assertEquals(401, forgedKey.statusCode(), forgedKey.body());
            assertTrue(sessionCookie(forgedKey).isEmpty());

            // Sign-out revokes the server-side session.
            var logout = client.post("/qqq/v1/logout", alice, true);
            assertEquals(200, logout.statusCode(), logout.body());
            assertEquals(401, client.get("/kof22/session", alice, true).statusCode());
            assertEquals(401, client.get("/data/carlAccounts", alice, false).statusCode());
            assertEquals(200, client.get("/kof22/session", bob, true).statusCode());
         }
      }
   }



   private static void seed(PostgreSQLContainer<?> database) throws Exception
   {
      var data = NativeDatabases.source(database.getJdbcUrl(), database.getUsername(), database.getPassword());
      com.kof22.agentcore.store.AgentMigrations.migrate(data);
      try(var c = data.getConnection(); var sql = c.createStatement())
      {
         sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic sign-in home','America/Chicago')");
         sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Synthetic A',true),(2,1,'bob','Synthetic B',true)");
         sql.execute("INSERT INTO carl_permission(member_id,domain,details) SELECT m.id,d,true FROM carl_member m CROSS JOIN unnest(ARRAY['BILLS','VENDORS','CALENDAR','FINANCE','TAX','SETTINGS']) d");
         sql.execute("CREATE ROLE " + READER + " LOGIN PASSWORD 'synthetic-reader-only'");
         sql.execute("GRANT USAGE ON SCHEMA public TO " + READER);
         for(var table : AdminApplication.READER_COLUMNS.entrySet())
         {
            if(!table.getValue().isEmpty())
            {
               sql.execute("GRANT SELECT(" + String.join(",", table.getValue()) + ") ON " + table.getKey() + " TO " + READER);
            }
         }
         for(String view : List.of("bill", "vendor", "work", "account", "property", "artifact", "calendar", "transaction", "budget", "tax", "calendar_connection", "import_review", "plan", "plan_step", "native_plan_step", "native_calendar_operation", "member", "debt", "cash_plan", "calendar_operation", "financial_goal", "financing_offer", "tax_property", "rental_property", "rental_unit", "rental_source", "rent_due", "rent_application", "expense", "expense_actual", "expense_settlement"))
         {
            sql.execute("GRANT SELECT ON carl_" + view + "_view TO " + READER);
         }
      }
      new FinancialRecords(new CarlService(data, Clock.systemUTC())).createAccount("alice", "Private synthetic account", "CASH", "USD", true, BigDecimal.ONE, "PRIVATE", "Synthetic local fixture");
   }



   private static NativeConfigurationFiles.Prepared configuration(PostgreSQLContainer<?> database, SyntheticAuthorizationCodeProvider provider) throws Exception
   {
      return NativeConfigurationFiles.read(Path.of("config/agent.properties"), Map.of(),
         "--kof22.agent.deployment.mode=TEST",
         "--kof22.agent.db.url=" + database.getJdbcUrl(),
         "--kof22.agent.db.username=" + database.getUsername(),
         "--kof22.agent.db.password=" + database.getPassword(),
         "--kof22.agent.qqq.db-username=" + READER,
         "--kof22.agent.qqq.db-password=synthetic-reader-only",
         "--kof22.agent.qqq.auth-mode=oidc-bearer",
         "--kof22.agent.qqq.host=127.0.0.1",
         "--kof22.agent.qqq.port=0",
         "--kof22.agent.qqq.public-origin=" + ORIGIN,
         "--kof22.agent.qqq.oidc.issuer=" + provider.issuer,
         "--kof22.agent.qqq.oidc.audience=" + AUDIENCE,
         "--kof22.agent.qqq.oidc.client-id=" + SyntheticAuthorizationCodeProvider.CLIENT_ID,
         "--kof22.agent.qqq.password=synthetic-unused-bootstrap",
         "--kof22.agent.rbac.users.alice=OPERATOR",
         "--kof22.agent.rbac.users.bob=OPERATOR",
         "--kof22.agent.anthropic-api-key=synthetic-no-provider",
         "--kof22.agent.anthropic-base-url=http://127.0.0.1:9");
   }



   private static Optional<String> sessionCookie(HttpResponse<String> response)
   {
      return response.headers().allValues("Set-Cookie").stream()
         .flatMap(header -> java.net.HttpCookie.parse(header).stream())
         .filter(cookie -> cookie.getName().equals("sessionUUID") && !cookie.getValue().isBlank())
         .map(java.net.HttpCookie::getValue)
         .findFirst();
   }



   private static String random(int bytes)
   {
      byte[] value = new byte[bytes];
      RANDOM.nextBytes(value);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
   }

   /** A pending authorization request as the dashboard stores it before redirecting. */
   private record Authorization(String state, String verifier, String location)
   {
   }



   /** The provider's front-channel response after the dashboard verified its state. */
   private record Callback(String code, String query)
   {
   }



   /*******************************************************************************
    * Follows the dashboard's AUTH_0 client contract: authorize at baseUrl/authorize
    * with response_type=code, S256 PKCE, state and audience, redirect to the public
    * origin root, verify state on callback, exchange at baseUrl/oauth/token, and
    * post the access token to manageSession.
    ******************************************************************************/
   private static final class DashboardClient
   {
      private final HttpClient nativeHttp;
      private final HttpClient browser;
      private final String base;
      private String providerBase;
      private String clientId;

      DashboardClient(HttpClient nativeHttp, HttpClient browser, String base)
      {
         this.nativeHttp = nativeHttp;
         this.browser = browser;
         this.base = base;
      }



      void provider(String providerBase, String clientId)
      {
         this.providerBase = providerBase;
         this.clientId = clientId;
      }



      Authorization authorize(String subject, String audience) throws Exception
      {
         String verifier = random(32);
         String state = random(16);
         var query = new LinkedHashMap<String, String>();
         query.put("response_type", "code");
         query.put("client_id", clientId);
         query.put("redirect_uri", ORIGIN + "/");
         query.put("scope", "openid profile email offline_access");
         query.put("state", state);
         query.put("code_challenge", SyntheticAuthorizationCodeProvider.challenge(verifier));
         query.put("code_challenge_method", "S256");
         query.put("audience", audience);
         // Stands in for the provider's interactive sign-in page choosing a synthetic subject.
         query.put("login_hint", subject);
         var response = browser.send(HttpRequest.newBuilder(URI.create(providerBase + "/authorize?" + form(query))).GET().build(), HttpResponse.BodyHandlers.ofString());
         assertEquals(302, response.statusCode(), response.body());
         String location = response.headers().firstValue("Location").orElseThrow();
         assertTrue(location.startsWith(ORIGIN + "/?"), location);
         return new Authorization(state, verifier, location);
      }



      Callback callback(Authorization authorization)
      {
         String query = URI.create(authorization.location()).getRawQuery();
         var values = SyntheticAuthorizationCodeProvider.parameters(query);
         if(values.containsKey("error") || values.get("code") == null || !authorization.state().equals(values.get("state")))
         {
            throw new IllegalStateException("callback_failed");
         }
         return new Callback(values.get("code"), query);
      }



      HttpResponse<String> exchange(String code, String verifier) throws Exception
      {
         var body = new LinkedHashMap<String, String>();
         body.put("grant_type", "authorization_code");
         body.put("client_id", clientId);
         body.put("code", code);
         body.put("code_verifier", verifier);
         body.put("redirect_uri", ORIGIN + "/");
         return browser.send(HttpRequest.newBuilder(URI.create(providerBase + "/oauth/token")).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form(body))).build(), HttpResponse.BodyHandlers.ofString());
      }



      HttpResponse<String> manageSession(String accessToken) throws Exception
      {
         return nativeHttp.send(HttpRequest.newBuilder(URI.create(base + "/manageSession")).header("Origin", ORIGIN).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("accessToken", accessToken)))).build(), HttpResponse.BodyHandlers.ofString());
      }



      HttpResponse<String> get(String route, String session, boolean origin) throws Exception
      {
         var request = HttpRequest.newBuilder(URI.create(base + route)).GET();
         if(session != null)
         {
            request.header("Cookie", "sessionUUID=" + session);
         }
         if(origin)
         {
            request.header("Origin", ORIGIN);
         }
         return nativeHttp.send(request.build(), HttpResponse.BodyHandlers.ofString());
      }



      HttpResponse<String> post(String route, String session, boolean origin) throws Exception
      {
         var request = HttpRequest.newBuilder(URI.create(base + route)).header("Cookie", "sessionUUID=" + session).header("Content-Type", "multipart/form-data; boundary=carl-oidc")
            .POST(HttpRequest.BodyPublishers.ofString("--carl-oidc--\r\n"));
         if(origin)
         {
            request.header("Origin", ORIGIN);
         }
         return nativeHttp.send(request.build(), HttpResponse.BodyHandlers.ofString());
      }



      HttpResponse<String> bearer(String route, String token) throws Exception
      {
         return nativeHttp.send(HttpRequest.newBuilder(URI.create(base + route)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
      }



      private static String form(Map<String, String> values)
      {
         return values.entrySet().stream().map(entry -> SyntheticAuthorizationCodeProvider.encode(entry.getKey()) + "=" + SyntheticAuthorizationCodeProvider.encode(entry.getValue())).collect(Collectors.joining("&"));
      }
   }
}
