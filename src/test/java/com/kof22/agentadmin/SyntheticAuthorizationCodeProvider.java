/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;


/*******************************************************************************
 * In-process HTTPS OpenID provider for authorization-code sign-in tests. It
 * serves discovery, JWKS, authorize and token endpoints for synthetic subjects
 * only, binds each single-use code to its client, redirect URI and PKCE
 * challenge, and never contacts a real identity provider.
 ******************************************************************************/
final class SyntheticAuthorizationCodeProvider implements AutoCloseable
{
   static final String CLIENT_ID = "carl-native-client";
   static final String KEY_ID = "carl-synthetic-oidc";

   private static final ObjectMapper JSON = new ObjectMapper();
   private static final Set<String> SUBJECTS = Set.of("alice", "bob");

   private final HttpsServer server;
   private final SSLContext tls;
   private final SSLSocketFactory previous;
   private final KeyPair key;
   private final String redirectUri;
   private final Map<String, Grant> codes = new ConcurrentHashMap<>();
   final String issuer;

   private record Grant(String clientId, String redirectUri, String challenge, String audience, String subject, String scope)
   {
   }

   /*******************************************************************************
    * Starts a loopback issuer whose certificate is trusted only by this JVM's
    * default HTTPS connections until {@link #close()}.
    ******************************************************************************/
   SyntheticAuthorizationCodeProvider(Path directory, String redirectUri) throws Exception
   {
      this.redirectUri = redirectUri;
      Path storePath = directory.resolve("synthetic-oidc.p12");
      var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "issuer", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", "synthetic-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt")
         .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
      if(!keytool.waitFor(30, TimeUnit.SECONDS) || keytool.exitValue() != 0)
      {
         keytool.destroyForcibly();
         throw new IllegalStateException("Synthetic issuer certificate generation failed");
      }
      var store = KeyStore.getInstance("PKCS12");
      try(var input = Files.newInputStream(storePath))
      {
         store.load(input, "synthetic-only".toCharArray());
      }
      var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      managers.init(store, "synthetic-only".toCharArray());
      var trusted = KeyStore.getInstance("PKCS12");
      trusted.load(null, null);
      trusted.setCertificateEntry("carl-synthetic-oidc", store.getCertificate("issuer"));
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(trusted);
      tls = SSLContext.getInstance("TLS");
      tls.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      key = generator.generateKeyPair();
      server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(tls));
      issuer = "https://localhost:" + server.getAddress().getPort() + "/";
      server.createContext("/.well-known/openid-configuration", this::discovery);
      server.createContext("/.well-known/jwks.json", this::keys);
      server.createContext("/authorize", this::authorize);
      server.createContext("/oauth/token", this::token);
      previous = HttpsURLConnection.getDefaultSSLSocketFactory();
      HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
      server.start();
   }



   /*******************************************************************************
    * TLS context that trusts only this provider, for the test's browser client.
    ******************************************************************************/
   SSLContext tls()
   {
      return tls;
   }



   /*******************************************************************************
    * Signs an access token with a key this provider never publishes.
    ******************************************************************************/
   String unpublishedKeyToken(String subject, String audience) throws Exception
   {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return accessToken(generator.generateKeyPair(), subject, audience, "openid");
   }



   @Override
   public void close()
   {
      HttpsURLConnection.setDefaultSSLSocketFactory(previous);
      server.stop(0);
   }



   private void discovery(HttpExchange exchange) throws IOException
   {
      String base = issuer.substring(0, issuer.length() - 1);
      var document = new HashMap<String, Object>();
      document.put("issuer", issuer);
      document.put("authorization_endpoint", base + "/authorize");
      document.put("token_endpoint", base + "/oauth/token");
      document.put("jwks_uri", base + "/.well-known/jwks.json");
      document.put("response_types_supported", List.of("code"));
      document.put("grant_types_supported", List.of("authorization_code"));
      document.put("code_challenge_methods_supported", List.of("S256"));
      document.put("subject_types_supported", List.of("public"));
      document.put("id_token_signing_alg_values_supported", List.of("RS256"));
      reply(exchange, 200, "application/json", JSON.writeValueAsString(document));
   }



   private void keys(HttpExchange exchange) throws IOException
   {
      var publicKey = (RSAPublicKey) key.getPublic();
      var jwk = Map.of("kty", "RSA", "kid", KEY_ID, "alg", "RS256", "use", "sig", "n", unsigned(publicKey.getModulus().toByteArray()), "e", unsigned(publicKey.getPublicExponent().toByteArray()));
      reply(exchange, 200, "application/json", JSON.writeValueAsString(Map.of("keys", List.of(jwk))));
   }



   private void authorize(HttpExchange exchange) throws IOException
   {
      var p = parameters(exchange.getRequestURI().getRawQuery());
      if(!"GET".equals(exchange.getRequestMethod()) || !CLIENT_ID.equals(p.get("client_id")) || !redirectUri.equals(p.get("redirect_uri")))
      {
         // Unknown clients and unregistered redirect targets never receive a redirect.
         reply(exchange, 400, "text/plain", "Unregistered client or redirect URI");
         return;
      }
      String state = p.getOrDefault("state", "");
      if(!"code".equals(p.get("response_type")) || !"S256".equals(p.get("code_challenge_method")) || p.getOrDefault("code_challenge", "").isBlank() || state.isBlank()
         || !List.of(p.getOrDefault("scope", "").split(" ")).contains("openid"))
      {
         redirect(exchange, "error=invalid_request&state=" + encode(state));
         return;
      }
      String subject = p.get("login_hint");
      if(!SUBJECTS.contains(subject))
      {
         redirect(exchange, "error=access_denied&state=" + encode(state));
         return;
      }
      String code = UUID.randomUUID().toString();
      codes.put(code, new Grant(CLIENT_ID, redirectUri, p.get("code_challenge"), p.getOrDefault("audience", CLIENT_ID), subject, p.get("scope")));
      redirect(exchange, "code=" + encode(code) + "&state=" + encode(state));
   }



   private void token(HttpExchange exchange) throws IOException
   {
      if(!"POST".equals(exchange.getRequestMethod()))
      {
         reply(exchange, 405, "text/plain", "POST required");
         return;
      }
      var p = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      var grant = p.get("code") == null ? null : codes.remove(p.get("code"));
      if(grant == null || !"authorization_code".equals(p.get("grant_type")) || !grant.clientId().equals(p.get("client_id")) || !grant.redirectUri().equals(p.get("redirect_uri"))
         || !grant.challenge().equals(challenge(p.getOrDefault("code_verifier", ""))))
      {
         reply(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
         return;
      }
      var response = new HashMap<String, Object>();
      response.put("access_token", accessToken(key, grant.subject(), grant.audience(), grant.scope()));
      response.put("id_token", JWT.create().withKeyId(KEY_ID).withIssuer(issuer).withAudience(grant.clientId()).withSubject(grant.subject()).withClaim("name", grant.subject() + " (synthetic)").withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(300)).sign(algorithm(key)));
      response.put("token_type", "Bearer");
      response.put("expires_in", 300);
      response.put("scope", grant.scope());
      reply(exchange, 200, "application/json", JSON.writeValueAsString(response));
   }



   private String accessToken(KeyPair signer, String subject, String audience, String scope)
   {
      return JWT.create().withKeyId(KEY_ID).withIssuer(issuer).withAudience(audience).withSubject(subject).withClaim("azp", CLIENT_ID).withClaim("scope", scope).withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(300)).sign(algorithm(signer));
   }



   private void redirect(HttpExchange exchange, String query) throws IOException
   {
      exchange.getResponseHeaders().set("Location", redirectUri + "?" + query);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
   }



   static String challenge(String verifier)
   {
      try
      {
         return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
      }
      catch(java.security.NoSuchAlgorithmException unavailable)
      {
         throw new IllegalStateException(unavailable);
      }
   }



   static Map<String, String> parameters(String text)
   {
      var values = new HashMap<String, String>();
      if(text != null && !text.isEmpty())
      {
         for(String part : text.split("&"))
         {
            var pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
         }
      }
      return values;
   }



   static String encode(String value)
   {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }



   private static Algorithm algorithm(KeyPair signer)
   {
      return Algorithm.RSA256((RSAPublicKey) signer.getPublic(), (RSAPrivateKey) signer.getPrivate());
   }



   private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException
   {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, bytes.length);
      try(var output = exchange.getResponseBody())
      {
         output.write(bytes);
      }
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }
}
