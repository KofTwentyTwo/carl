/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.agentadmin.bootstrap;


import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;


/*******************************************************************************
 * Real HTTPS issuer for native-host tests; trusts only its temporary synthetic certificate.
 ******************************************************************************/
final class NativeVisualIdentity implements AutoCloseable
{
   private final HttpsServer server;
   private final KeyPair key;
   private final SSLSocketFactory previous;
   final String issuer;

   NativeVisualIdentity(Path temporaryDirectory) throws Exception
   {
      Path storePath = temporaryDirectory.resolve("synthetic-issuer.p12");
      Path log = temporaryDirectory.resolve("keytool.log");
      var keytool = new ProcessBuilder(
         Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
         "-genkeypair",
         "-alias",
         "issuer",
         "-keyalg",
         "RSA",
         "-keysize",
         "2048",
         "-storetype",
         "PKCS12",
         "-keystore",
         storePath.toString(),
         "-storepass",
         "synthetic-only",
         "-dname",
         "CN=localhost",
         "-ext",
         "SAN=dns:localhost,ip:127.0.0.1",
         "-validity",
         "2",
         "-noprompt")
         .redirectErrorStream(true)
         .redirectOutput(log.toFile())
         .start();
      if(!keytool.waitFor(15, TimeUnit.SECONDS))
      {
         keytool.destroyForcibly();
         throw new IllegalStateException("Synthetic issuer certificate generation timed out");
      }
      if(keytool.exitValue() != 0)
      {
         throw new IllegalStateException("Synthetic issuer certificate generation failed");
      }
      var store = KeyStore.getInstance("PKCS12");
      try(var input = Files.newInputStream(storePath))
      {
         store.load(input, "synthetic-only".toCharArray());
      }
      var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      managers.init(store, "synthetic-only".toCharArray());
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      var tls = SSLContext.getInstance("TLS");
      tls.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      key = generator.generateKeyPair();
      server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(tls));
      issuer = "https://localhost:" + server.getAddress().getPort() + "/";
      var publicKey = (RSAPublicKey) key.getPublic();
      byte[] jwks = ("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"native-synthetic\",\"alg\":\"RS256\","
         + "\"use\":\"sig\",\"n\":\""
         + unsigned(publicKey.getModulus().toByteArray())
         + "\",\"e\":\""
         + unsigned(publicKey.getPublicExponent().toByteArray())
         + "\"}]}")
         .getBytes(StandardCharsets.UTF_8);
      server.createContext(
         "/.well-known/jwks.json",
         exchange ->
         {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try(var output = exchange.getResponseBody())
            {
               output.write(jwks);
            }
         });
      previous = HttpsURLConnection.getDefaultSSLSocketFactory();
      HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
      addBrowserEndpoints();
      server.start();
   }



   String token(String subject)
   {
      return token(subject, Instant.now().plusSeconds(300), "native-admin");
   }



   String token(String subject, Instant expiry, String audience)
   {
      return JWT.create()
         .withKeyId("native-synthetic")
         .withIssuer(issuer)
         .withAudience(audience)
         .withSubject(subject)
         .withExpiresAt(expiry)
         .sign(Algorithm.RSA256((RSAPublicKey) key.getPublic(), (RSAPrivateKey) key.getPrivate()));
   }

   private final java.util.Map<String, java.util.Map<String, String>> codes = new java.util.concurrent.ConcurrentHashMap<>();

   private static java.util.Map<String, String> parameters(String text)
   {
      var values = new java.util.HashMap<String, String>();
      if(text != null)
      {
         for(String part : text.split("&"))
         {
            var pair = part.split("=", 2);
            values.put(java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
               pair.length == 2 ? java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
         }
      }
      return values;
   }



   private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String type, String body) throws java.io.IOException
   {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", type);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(code, bytes.length);
      try(var out = exchange.getResponseBody())
      {
         out.write(bytes);
      }
   }



   private void addBrowserEndpoints()
   {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      server.createContext("/authorize", exchange ->
      {
         var p = parameters(exchange.getRequestURI().getRawQuery());
         if(!"POST".equals(exchange.getRequestMethod()) && !"none".equals(p.get("prompt")))
         {
            reply(exchange, 200, "text/html; charset=UTF-8", "<html><body><h1>Local native QQQ acceptance</h1><p>Synthetic operator Alice. Disposable PostgreSQL data only.</p><form method='POST'><button type='submit'>Sign in as Alice</button></form></body></html>");
            return;
         }
         var code = java.util.UUID.randomUUID().toString();
         codes.put(code, p);
         var result = java.util.Map.of("code", code, "state", p.getOrDefault("state", ""));
         if("web_message".equals(p.get("response_mode")))
         {
            var redirect = java.net.URI.create(p.get("redirect_uri"));
            var origin = redirect.getScheme() + "://" + redirect.getRawAuthority();
            reply(exchange, 200, "text/html; charset=UTF-8", "<script>window.parent.postMessage(" + json.writeValueAsString(java.util.Map.of("type", "authorization_response", "response", result)) + "," + json.writeValueAsString(origin) + ");</script>");
         }
         else
         {
            exchange.getResponseHeaders().set("Location", p.get("redirect_uri") + "?code=" + code + "&state=" + java.net.URLEncoder.encode(p.getOrDefault("state", ""), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
         }
      });
      server.createContext("/oauth/token", exchange ->
      {
         String origin = exchange.getRequestHeaders().getFirst("Origin");
         if(origin != null && (origin.startsWith("https://127.0.0.1:") || origin.startsWith("https://localhost:")))
         {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "content-type,auth0-client");
         }
         if("OPTIONS".equals(exchange.getRequestMethod()))
         {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
         }
         String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
         java.util.Map<String, String> p;
         if(body.startsWith("{"))
         {
            p = json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>()
            {
            });
         }
         else
         {
            p = parameters(body);
         }
         var auth = codes.remove(p.get("code"));
         if(auth == null)
         {
            reply(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            return;
         }
         try
         {
            String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(p.getOrDefault("code_verifier", "").getBytes(StandardCharsets.UTF_8)));
            if(!challenge.equals(auth.get("code_challenge")))
            {
               reply(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
               return;
            }
         }
         catch(java.security.NoSuchAlgorithmException e)
         {
            throw new IllegalStateException(e);
         }
         var id = JWT.create().withKeyId("native-synthetic").withIssuer(issuer).withAudience("synthetic-client")
            .withSubject("alice").withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(1800))
            .withClaim("nonce", auth.get("nonce")).withClaim("name", "Alice (synthetic)").withClaim("email", "alice@example.invalid")
            .sign(Algorithm.RSA256((RSAPublicKey) key.getPublic(), (RSAPrivateKey) key.getPrivate()));
         reply(exchange, 200, "application/json", json.writeValueAsString(java.util.Map.of("access_token", token("alice", Instant.now().plusSeconds(1800), "native-admin"), "id_token", id, "token_type", "Bearer", "expires_in", 1800, "scope", auth.getOrDefault("scope", "openid profile email"))));
      });
      server.createContext("/v2/logout", exchange ->
      {
         var p = parameters(exchange.getRequestURI().getRawQuery());
         exchange.getResponseHeaders().set("Location", p.getOrDefault("returnTo", "/"));
         exchange.sendResponseHeaders(302, -1);
         exchange.close();
      });
      server.createContext("/userinfo", exchange -> reply(exchange, 200, "application/json", "{\"sub\":\"alice\",\"name\":\"Alice (synthetic)\"}"));
   }



   private static String unsigned(byte[] bytes)
   {
      return Base64.getUrlEncoder()
         .withoutPadding()
         .encodeToString(Arrays.copyOfRange(bytes, bytes[0] == 0 ? 1 : 0, bytes.length));
   }



   @Override
   public void close()
   {
      server.stop(0);
      HttpsURLConnection.setDefaultSSLSocketFactory(previous);
   }
}
