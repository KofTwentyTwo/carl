/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.agentadmin.bootstrap;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import com.kingsrook.qqq.esb.envelope.EsbEvent;
import com.kingsrook.qqq.esb.envelope.EsbEventCodec;
import com.kingsrook.qbits.quicksearch.opensearch.OpenSearchDocument;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;


/*******************************************************************************
 * Owned loopback-only synthetic transports for a separate packaged Carl process.
 ******************************************************************************/
public final class CarlQBitsVisualFixture implements AutoCloseable
{
   private final GenericContainer<?> search;
   private final EmbeddedActiveMQ broker;
   private final int brokerPort;
   private boolean closed;
   private Map<String, Object> lastEventEvidence = Map.of();



   private CarlQBitsVisualFixture(Path privateDirectory) throws Exception
   {
      Files.createDirectories(privateDirectory);
      search = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.19.1"))
         .withEnv("discovery.type", "single-node")
         .withEnv("DISABLE_SECURITY_PLUGIN", "true")
         .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
         .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
         .withExposedPorts(9200)
         .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(9200))))
         .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200))
         .withStartupTimeout(Duration.ofMinutes(2));
      try(var socket = new ServerSocket())
      {
         socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
         brokerPort = socket.getLocalPort();
      }
      broker = new EmbeddedActiveMQ().setConfiguration(new ConfigurationImpl()
         .setPersistenceEnabled(false).setSecurityEnabled(false)
         .setJournalDirectory(privateDirectory.resolve("journal").toString())
         .setBindingsDirectory(privateDirectory.resolve("bindings").toString())
         .setPagingDirectory(privateDirectory.resolve("paging").toString())
         .setLargeMessagesDirectory(privateDirectory.resolve("large-messages").toString())
         .addAcceptorConfiguration("synthetic-tcp", "tcp://127.0.0.1:" + brokerPort));
   }



   /*******************************************************************************
    * Starts both transports and releases allocations if either start fails.
    ******************************************************************************/
   public static CarlQBitsVisualFixture start(Path privateDirectory) throws Exception
   {
      var fixture = new CarlQBitsVisualFixture(privateDirectory);
      try
      {
         fixture.search.start();
         fixture.broker.start();
         return fixture;
      }
      catch(Exception failure)
      {
         try
         {
            fixture.close();
         }
         catch(Exception cleanup)
         {
            failure.addSuppressed(cleanup);
         }
         throw failure;
      }
   }



   /*******************************************************************************
    * Exact child environment; these endpoints contain no real credentials or data.
    ******************************************************************************/
   public Map<String, String> environment()
   {
      if(closed)
      {
         throw new IllegalStateException("Synthetic QBits transports are closed");
      }
      return Map.of(
         "CARL_QBITS_SEARCH_ENABLED", "true",
         "CARL_QBITS_ESB_ENABLED", "true",
         "CARL_QBITS_OPENSEARCH_HOST", "127.0.0.1",
         "CARL_QBITS_OPENSEARCH_PORT", Integer.toString(search.getMappedPort(9200)),
         "CARL_QBITS_OPENSEARCH_SSL", "false",
         "CARL_QBITS_OPENSEARCH_INDEX", "carl-synthetic-preview",
         "CARL_QBITS_BROKER_URL", "tcp://127.0.0.1:" + brokerPort,
         "CARL_QBITS_BROKER_USERNAME", "synthetic",
         "CARL_QBITS_BROKER_PASSWORD", "synthetic-only");
   }



   /*******************************************************************************
    * Actual packaged fixed-trigger acceptance over disposable records only.
    * Queue acknowledgement follows a successful process or dead-letter; requiring
    * no dead-letter and an observed backend step distinguishes transport from work.
    ******************************************************************************/
   public Map<String, Object> qualifyIndexRefresh(javax.sql.DataSource source, Path applicationLog) throws Exception
   {
      if(closed)
      {
         throw new IllegalStateException("Synthetic QBits transports are closed");
      }
      long record;
      try(var connection = source.getConnection(); var query = connection.prepareStatement(
         "SELECT r.id FROM carl_record r JOIN carl_account a ON a.record_id=r.id JOIN carl_member m ON m.id=r.owner_id WHERE r.title='Synthetic Checking' AND m.principal='alice' AND r.visibility='PRIVATE'"))
      {
         try(var rows = query.executeQuery())
         {
            if(!rows.next())
            {
               throw new IllegalStateException("Dedicated synthetic checking record required");
            }
            record = rows.getLong(1);
            if(rows.next())
            {
               throw new IllegalStateException("Synthetic checking record is ambiguous");
            }
         }
      }
      var queue = broker.getActiveMQServer().locateQueue("carlIndexChanges");
      if(queue == null || queue.getConsumerCount() != 1 || queue.getMessageCount() != 0)
      {
         throw new IllegalStateException("Dedicated packaged index consumer must be ready and idle");
      }
      String beforeTime = document(record).path("indexedAt").asText();
      // Observe a fresh periodic pass first, then finish each event within 15s,
      // before the next 30s reconciliation can make the index assertion pass.
      long refreshDeadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
      while(document(record).path("indexedAt").asText().equals(beforeTime))
      {
         if(System.nanoTime() >= refreshDeadline)
         {
            throw new IllegalStateException("Periodic index baseline did not advance");
         }
         Thread.sleep(100);
      }
      long initialAcknowledged = queue.getMessagesAcknowledged();
      long initialAdded = queue.getMessagesAdded();
      long initialLogSize = Files.size(applicationLog);
      String changed = "Synthetic Checking ESB event " + UUID.randomUUID();
      long started = System.nanoTime();
      boolean restored = false;
      try
      {
         changeTitle(source, record, "Synthetic Checking", changed);
         if(!document(record).path("recordLabel").asText().equals("Synthetic Checking"))
         {
            throw new IllegalStateException("Index must still contain the old title before publication");
         }
         publishRefresh();
         awaitEvent(record, changed, initialAcknowledged + 1, initialAdded + 1, initialLogSize, applicationLog);
         long eventMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
         if(eventMillis >= 15000)
         {
            throw new IllegalStateException("Event exceeded the window before periodic reconciliation");
         }
         changeTitle(source, record, changed, "Synthetic Checking");
         publishRefresh();
         awaitEvent(record, "Synthetic Checking", initialAcknowledged + 2, initialAdded + 2, initialLogSize, applicationLog);
         restored = true;
         return Map.of("status", "PASS", "destination", "carlIndexChanges", "process", "carlRefreshSearchIndex",
            "consumedEvents", 2, "acknowledgedEvents", queue.getMessagesAcknowledged() - initialAcknowledged,
            "firstEventMillis", eventMillis, "titleRestored", true, "payloadAuthorityIgnored", true,
            "nativeTelemetry", lastEventEvidence);
      }
      catch(Exception failure)
      {
         String report = System.getenv("CARL_QBITS_FIXTURE_REPORT_DIRECTORY");
         if(report != null && !report.isBlank() && Files.isRegularFile(applicationLog))
         {
            Path directory = Path.of(report);
            Files.createDirectories(directory);
            Files.copy(applicationLog, directory.resolve("synthetic-application.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         }
         throw failure;
      }
      finally
      {
         if(!restored)
         {
            // Failed acceptance may not leave even synthetic fixture state changed.
            try(var connection = source.getConnection(); var update = connection.prepareStatement("UPDATE carl_record SET title='Synthetic Checking' WHERE id=? AND title=?"))
            {
               connection.setAutoCommit(false);
               update.setLong(1, record);
               update.setString(2, changed);
               update.executeUpdate();
               connection.commit();
            }
         }
      }
   }



   private void publishRefresh() throws Exception
   {
      try(var factory = new ActiveMQConnectionFactory(environment().get("CARL_QBITS_BROKER_URL"));
         var connection = factory.createConnection();
         var session = connection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE);
         var producer = session.createProducer(session.createQueue("carlIndexChanges")))
      {
         var event = new EsbEvent().withId(UUID.randomUUID().toString()).withSource("synthetic://carl/qbits-acceptance")
            .withType("synthetic.index.changed").withTime(Instant.now())
            .withData(Map.of("caller", "bob", "permission", "ADMIN", "table", "not_a_carl_table", "sql", "INVALID SYNTHETIC SQL MUST NEVER RUN"));
         producer.send(EsbEventCodec.toMessage(session, event));
      }
   }



   private void awaitEvent(long record, String expected, long acknowledged, long added, long logOffset, Path applicationLog) throws Exception
   {
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while(System.nanoTime() < deadline)
      {
         var queue = broker.getActiveMQServer().locateQueue("carlIndexChanges");
         var dead = broker.getActiveMQServer().locateQueue("carlIndexChanges.dlq");
         long deadCount = dead == null ? 0 : dead.getMessageCount();
         if(deadCount != 0)
         {
            throw new IllegalStateException("Packaged fixed index event was dead-lettered");
         }
         String log;
         try(var input = Files.newInputStream(applicationLog))
         {
            input.skipNBytes(logOffset);
            log = new String(input.readNBytes(262144), java.nio.charset.StandardCharsets.UTF_8);
         }
         boolean labelMatches = document(record).path("recordLabel").asText().equals(expected);
         boolean stepLogged = log.contains("Running backend step [refresh] in process [carlRefreshSearchIndex]");
         // Read the counters once: the evidence must show the values the check below accepted,
         // not an earlier read taken just before the last acknowledgement landed.
         long acknowledgedNow = queue.getMessagesAcknowledged();
         long addedNow = queue.getMessagesAdded();
         long remaining = queue.getMessageCount();
         int consumers = queue.getConsumerCount();
         lastEventEvidence = Map.of("acknowledged", acknowledgedNow, "expectedAcknowledged", acknowledged,
            "added", addedNow, "expectedAdded", added, "remaining", remaining,
            "consumerCount", consumers, "deadLetters", deadCount, "labelMatches", labelMatches, "stepLogged", stepLogged);
         // Exact selected ESbTriggerRunner source commits a successful run, or
         // commits a dead-letter on failure. This fixed trigger retains the default
         // DEAD_LETTER_QUEUE policy. One unique packaged consumer, exact added/ack
         // counters and no DLQ therefore prove success independently of DEBUG logs.
         if(acknowledgedNow == acknowledged && addedNow == added && consumers == 1 && remaining == 0 && labelMatches)
         {
            return;
         }
         Thread.sleep(100);
      }
      throw new IllegalStateException("Packaged fixed trigger acknowledgement and index update were not all observed: " + lastEventEvidence);
   }



   private JsonNode document(long record) throws Exception
   {
      String id = OpenSearchDocument.buildDocumentId("carlAccounts", Long.toString(record));
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
         HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + search.getMappedPort(9200) + "/carl-synthetic-preview/_doc/" + id))
            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
      if(response.statusCode() != 200)
      {
         throw new IllegalStateException("Expected synthetic account index document is unavailable");
      }
      return new ObjectMapper().readTree(response.body()).path("_source");
   }



   private static void changeTitle(javax.sql.DataSource source, long record, String expected, String replacement) throws Exception
   {
      try(var connection = source.getConnection(); var update = connection.prepareStatement("UPDATE carl_record SET title=? WHERE id=? AND title=?"))
      {
         connection.setAutoCommit(false);
         update.setString(1, replacement);
         update.setLong(2, record);
         update.setString(3, expected);
         if(update.executeUpdate() != 1)
         {
            throw new IllegalStateException("Synthetic record changed unexpectedly");
         }
         connection.commit();
      }
   }



   /*******************************************************************************
    * Attempts every owned cleanup even if one transport fails to stop.
    ******************************************************************************/
   @Override
   public synchronized void close() throws Exception
   {
      if(closed)
      {
         return;
      }
      closed = true;
      Exception failure = null;
      try
      {
         broker.stop();
      }
      catch(Exception error)
      {
         failure = error;
      }
      try
      {
         search.stop();
      }
      catch(Exception error)
      {
         if(failure == null)
         {
            failure = error;
         }
         else
         {
            failure.addSuppressed(error);
         }
      }
      if(failure != null)
      {
         throw failure;
      }
   }



   /*******************************************************************************
    * Real HTTP and TCP/JMS transport proof; not business/UI acceptance.
    ******************************************************************************/
   public static void main(String[] arguments) throws Exception
   {
      String container;
      int searchPort;
      int brokerPort;
      try(var fixture = start(Path.of(arguments[0])))
      {
         var environment = fixture.environment();
         container = fixture.search.getContainerId();
         searchPort = fixture.search.getMappedPort(9200);
         brokerPort = fixture.brokerPort;
         var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + searchPort + "/_cluster/health"))
               .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
         if(response.statusCode() != 200 || !response.body().contains("cluster_name"))
         {
            throw new IllegalStateException("Synthetic OpenSearch health did not answer");
         }
         try(var factory = new ActiveMQConnectionFactory(environment.get("CARL_QBITS_BROKER_URL"));
            var connection = factory.createConnection();
            var session = connection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE))
         {
            var queue = session.createQueue("synthetic-transport-proof");
            try(var producer = session.createProducer(queue); var consumer = session.createConsumer(queue))
            {
               connection.start();
               producer.send(session.createTextMessage("synthetic-tcp-round-trip"));
               var message = consumer.receive(5000);
               if(!(message instanceof jakarta.jms.TextMessage text) || !text.getText().equals("synthetic-tcp-round-trip"))
               {
                  throw new IllegalStateException("Synthetic Artemis TCP round trip did not answer");
               }
            }
         }
         System.out.println("SYNTHETIC_QBITS_TRANSPORT=HTTP_200_AND_TCP_JMS_ROUND_TRIP_PASS");
      }
      for(int port : new int[]{searchPort, brokerPort})
      {
         try(var socket = new java.net.Socket())
         {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            throw new IllegalStateException("Synthetic transport remained reachable after cleanup");
         }
         catch(java.io.IOException expected)
         {
            // Both owned service endpoints must refuse connections after close.
         }
      }
      System.out.println("SYNTHETIC_QBITS_CLEANUP=ENDPOINTS_CLOSED_CONTAINER_" + container);
   }
}
