
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZoneUpdater;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;


/***************************************************************************
 ** Fixed-collection transport. The application owns authorization, durable
 ** operation state, reconciliation and the relationship between plans/UIDs.
 ** Every failed write is conservatively uncertain: reconcile the persisted
 ** plan/UID mapping before retrying. HTTP errors can follow a committed write.
 ***************************************************************************/
public final class CalDavClient implements AutoCloseable
{
   private static final String DAV = "DAV:";
   private static final String CAL = "urn:ietf:params:xml:ns:caldav";
   private static final int MAX_BYTES = 2_000_000;
   private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
   private final URI collection;
   private final String authorization;
   private final HttpClient http;

   /** A bounded calendar resource with its provider concurrency token. */
   public record Resource(URI href, String etag, String calendar)
   {
   }



   /***************************************************************************
    ** Status zero means transport or parsing outcome cannot be established.
    ** No response body or credentials are copied into the diagnostic message.
    ***************************************************************************/
   public static final class DavException extends RuntimeException
   {
      private static final long serialVersionUID = 1L;
      private final int status;
      private final boolean mayHaveCommitted;

      /** Creates a sanitized read failure without copying provider payloads. */
      public DavException(int status)
      {
         this(status, false);
      }



      private DavException(int status, boolean mayHaveCommitted)
      {
         super(status == 0 ? "CalDAV transport or response could not be verified" : "CalDAV returned HTTP " + status);
         this.status = status;
         this.mayHaveCommitted = mayHaveCommitted;
      }



      /** Indicates that a failed mutation requires reconciliation before retry. */
      public boolean mayHaveCommitted()
      {
         return mayHaveCommitted;
      }



      /** Returns the HTTP status, or zero for an unverifiable transport outcome. */
      public int status()
      {
         return status;
      }
   }

   /** Creates a fixed-collection client; loopback HTTP is allowed only in explicit development. */
   public CalDavClient(URI collection, String user, char[] password, boolean localDevelopment)
   {
      boolean loopback = collection != null && "http".equals(collection.getScheme())
         && collection.getHost() != null && Set.of("127.0.0.1", "[::1]").contains(collection.getHost()) && localDevelopment;
      if(collection == null || (!"https".equals(collection.getScheme()) && !loopback)
         || collection.getHost() == null || collection.getUserInfo() != null || collection.getQuery() != null
         || collection.getFragment() != null || !collection.getPath().endsWith("/")
         || !safePath(collection) || user == null || user.isBlank() || user.length() > 200
         || user.contains(":") || user.chars().anyMatch(Character::isISOControl)
         || password == null || password.length == 0 || password.length > 2000)
      {
         throw new IllegalArgumentException("A trusted HTTPS collection and explicit credentials are required");
      }
      this.collection = collection;
      authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
      http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   }



   /** Discovers supported VEVENT and VTODO components for the configured collection. */
   public Set<String> componentTypes()
   {
      String xml = "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"" + CAL
         + "\"><d:prop><c:supported-calendar-component-set/></d:prop></d:propfind>";
      var response = send("PROPFIND", collection, xml, "0", null, null);
      requireStatus(response, 207);
      var results = responses(response.body());
      var types = new HashSet<String>();
      for(var result : results)
      {
         if(!href(result).equals(collection))
         {
            throw new DavException(0);
         }
         for(var prop : properties(result))
         {
            for(var set : children(prop, CAL, "supported-calendar-component-set"))
            {
               for(var comp : children(set, CAL, "comp"))
               {
                  String name = comp.getAttribute("name");
                  if(Set.of("VEVENT", "VTODO").contains(name))
                  {
                     types.add(name);
                  }
               }
            }
         }
      }
      return Set.copyOf(types);
   }



   /** Queries a bounded interval without following provider-supplied external URLs. */
   public List<Resource> query(Instant from, Instant through, String component)
   {
      if(from == null || through == null || !from.isBefore(through)
         || Duration.between(from, through).compareTo(Duration.ofDays(90)) > 0
         || component == null || !Set.of("VEVENT", "VTODO").contains(component))
      {
         throw new IllegalArgumentException("An event/task query requires a 1–90 day bounded interval");
      }
      String xml = "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"" + CAL + "\"><d:prop><d:getetag/><c:calendar-data/></d:prop>"
         + "<c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"" + component + "\"><c:time-range start=\""
         + WIRE_TIME.format(from) + "\" end=\"" + WIRE_TIME.format(through) + "\"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>";
      var response = send("REPORT", collection, xml, "1", null, null);
      requireStatus(response, 207);
      var resources = new ArrayList<Resource>();
      var seen = new HashSet<URI>();
      for(var result : responses(response.body()))
      {
         URI uri = href(result);
         if(uri.equals(collection) || !seen.add(uri))
         {
            throw new DavException(0);
         }
         String etag = null;
         String data = null;
         for(var prop : properties(result))
         {
            for(var element : children(prop, DAV, "getetag"))
            {
               if(etag != null)
               {
                  throw new DavException(0);
               }
               etag = element.getTextContent();
            }
            for(var element : children(prop, CAL, "calendar-data"))
            {
               if(data != null)
               {
                  throw new DavException(0);
               }
               data = element.getTextContent();
            }
         }
         if(data == null || data.isBlank() || etag == null || etag.isBlank())
         {
            throw new DavException(0);
         }
         resources.add(new Resource(uri, etag, data));
      }
      return List.copyOf(resources);
   }



   /** Creates a stable UID resource only when absent. */
   public Resource create(UUID id, String calendar)
   {
      validateWrite(id, calendar);
      var response = mutate("PUT", id, calendar, "If-None-Match", "*", 201);
      return new Resource(resource(id), response.headers().firstValue("ETag").orElse(null), calendar);
   }



   /** Replaces an owned resource only when the expected ETag still matches. */
   public Resource update(UUID id, String etag, String calendar)
   {
      validateWrite(id, calendar);
      validateEtag(etag);
      var response = mutate("PUT", id, calendar, "If-Match", etag, 204, 200);
      return new Resource(resource(id), response.headers().firstValue("ETag").orElse(null), calendar);
   }



   /***************************************************************************
    ** Reconciliation read for a UID/type already owned by a persisted Carl plan.
    ** The caller must establish ownership; provider content cannot grant it.
    ***************************************************************************/
   /** Reads a known owned UID and validates its component before reconciliation. */
   public Resource read(UUID id, String expectedComponent)
   {
      if(expectedComponent == null || !Set.of("VEVENT", "VTODO").contains(expectedComponent))
      {
         throw new IllegalArgumentException("An expected event/task type is required");
      }
      var response = send("GET", resource(id), null, null, null, null);
      requireStatus(response, 200);
      String text = new String(response.body(), StandardCharsets.UTF_8);
      try
      {
         if(!expectedComponent.equals(validateWrite(id, text)))
         {
            throw new DavException(0);
         }
      }
      catch(IllegalArgumentException invalid)
      {
         throw new DavException(0);
      }
      return new Resource(resource(id), response.headers().firstValue("ETag").orElse(null), text);
   }



   /** Removes an owned resource only when the expected ETag still matches. */
   public void remove(UUID id, String etag)
   {
      validateEtag(etag);
      mutate("DELETE", id, null, "If-Match", etag, 204);
   }



   private HttpResponse<byte[]> mutate(String method, UUID id, String body, String condition, String etag, int... accepted)
   {
      try
      {
         var response = send(method, resource(id), body, null, condition, etag);
         requireStatus(response, accepted);
         return response;
      }
      catch(DavException failure)
      {
         throw new DavException(failure.status(), true);
      }
   }



   @Override
   public void close()
   {
      http.shutdownNow();
   }



   private HttpResponse<byte[]> send(String method, URI target, String body, String depth, String condition, String version)
   {
      var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
         .header("Authorization", authorization).header("Accept", "application/xml, text/calendar")
         .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
      if(body != null)
      {
         request.header("Content-Type", method.equals("PUT") ? "text/calendar; charset=utf-8" : "application/xml; charset=utf-8");
      }
      if(depth != null)
      {
         request.header("Depth", depth);
      }
      if(condition != null)
      {
         request.header(condition, version);
      }
      var future = http.sendAsync(request.build(), info -> new LimitedBody());
      try
      {
         return future.get(10, TimeUnit.SECONDS);
      }
      catch(InterruptedException interrupted)
      {
         future.cancel(true);
         Thread.currentThread().interrupt();
         throw new DavException(0);
      }
      catch(Exception failure)
      {
         future.cancel(true);
         throw new DavException(0);
      }
   }



   private URI resource(UUID id)
   {
      if(id == null)
      {
         throw new IllegalArgumentException("A stable plan resource identity is required");
      }
      return collection.resolve(id + ".ics");
   }



   private URI href(Element response)
   {
      var values = children(response, DAV, "href");
      if(values.size() != 1)
      {
         throw new DavException(0);
      }
      try
      {
         URI uri = collection.resolve(URI.create(values.getFirst().getTextContent()));
         if(!collection.getScheme().equals(uri.getScheme()) || !collection.getHost().equalsIgnoreCase(uri.getHost())
            || collection.getPort() != uri.getPort() || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || !uri.getRawPath().startsWith(collection.getRawPath()) || !safePath(uri))
         {
            throw new DavException(0);
         }
         return uri;
      }
      catch(IllegalArgumentException invalid)
      {
         throw new DavException(0);
      }
   }



   private static boolean safePath(URI uri)
   {
      String raw = uri.getRawPath().toLowerCase(java.util.Locale.ROOT);
      return uri.normalize().equals(uri) && !raw.contains("%2f") && !raw.contains("%5c") && !raw.contains("%25")
         && !uri.getPath().contains("\\") && !uri.getPath().contains("/../") && !uri.getPath().contains("/./")
         && !uri.getPath().endsWith("/..") && !uri.getPath().endsWith("/.");
   }



   private static List<Element> responses(byte[] bytes)
   {
      try
      {
         var factory = DocumentBuilderFactory.newInstance();
         factory.setNamespaceAware(true);
         factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
         factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
         factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
         factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
         factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
         factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
         factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
         factory.setXIncludeAware(false);
         factory.setExpandEntityReferences(false);
         var builder = factory.newDocumentBuilder();
         builder.setErrorHandler(new ErrorHandler()
         {
            public void warning(SAXParseException error) throws SAXParseException
            {
               throw error;
            }



            public void error(SAXParseException error) throws SAXParseException
            {
               throw error;
            }



            public void fatalError(SAXParseException error) throws SAXParseException
            {
               throw error;
            }
         });
         var root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
         if(!DAV.equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName()))
         {
            throw new DavException(0);
         }
         var result = children(root, DAV, "response");
         if(result.size() > 2000)
         {
            throw new DavException(0);
         }
         return result;
      }
      catch(Exception invalid)
      {
         throw new DavException(0);
      }
   }



   private static List<Element> properties(Element response)
   {
      var result = new ArrayList<Element>();
      for(var propstat : children(response, DAV, "propstat"))
      {
         var statuses = children(propstat, DAV, "status");
         if(statuses.size() != 1 || !statuses.getFirst().getTextContent().matches("HTTP/[0-9.]+ 200(?: .*)?"))
         {
            throw new DavException(0);
         }
         result.addAll(children(propstat, DAV, "prop"));
      }
      return result;
   }



   private static List<Element> children(Element parent, String namespace, String name)
   {
      var result = new ArrayList<Element>();
      for(Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      {
         if(node instanceof Element element && namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName()))
         {
            result.add(element);
         }
      }
      return result;
   }



   private static void validateEtag(String etag)
   {
      if(etag == null || etag.length() > 250 || !etag.matches("\"[!#-~]+\""))
      {
         throw new IllegalArgumentException("A strong provider ETag is required for calendar changes");
      }
   }



   private static String validateWrite(UUID id, String text)
   {
      if(id == null || text == null || text.length() > 100_000 || text.getBytes(StandardCharsets.UTF_8).length > 100_000
         || new TimeZoneUpdater().isEnabled())
      {
         throw new IllegalArgumentException("Calendar writes require a stable ID, bounded payload and offline timezone resolution");
      }
      try
      {
         boundStructure(text);
         var calendar = new CalendarBuilder().build(new StringReader(text));
         if(calendar.getProperty(Property.METHOD).isPresent() || calendar.validate().hasErrors())
         {
            throw new IllegalArgumentException("Invalid calendar payload or scheduling method");
         }
         int items = 0;
         String type = null;
         for(var component : calendar.getComponents())
         {
            if("VTIMEZONE".equals(component.getName()))
            {
               continue;
            }
            items++;
            type = component.getName();
            if(!Set.of("VEVENT", "VTODO").contains(component.getName())
               || component.getProperties(Property.UID).size() != 1
               || !id.toString().equals(component.getRequiredProperty(Property.UID).getValue())
               || component.getProperty(Property.ATTENDEE).isPresent()
               || component.getProperty(Property.ORGANIZER).isPresent()
               || component.getProperty(Property.ATTACH).isPresent()
               || component instanceof ComponentContainer<?> container && !container.getComponents().isEmpty())
            {
               throw new IllegalArgumentException("Only an internal plan event or task without invitations/attachments is allowed");
            }
         }
         if(items != 1)
         {
            throw new IllegalArgumentException("One plan event or task per resource is required");
         }
         return type;
      }
      catch(IllegalArgumentException invalid)
      {
         throw invalid;
      }
      catch(Exception invalid)
      {
         throw new IllegalArgumentException("Invalid internal calendar payload");
      }
   }



   private static void boundStructure(String text)
   {
      // Unfold before counting so folded component markers cannot bypass bounds.
      String unfolded = text.replaceAll("\\r?\\n[ \\t]", "");
      var stack = new java.util.ArrayDeque<String>();
      int count = 0;
      String[] lines = unfolded.split("\\r?\\n", -1);
      if(lines.length > 2000)
      {
         throw new IllegalArgumentException("Too many calendar properties");
      }
      for(String line : lines)
      {
         if(line.length() > 16_000)
         {
            throw new IllegalArgumentException("Calendar property exceeds its limit");
         }
         String upper = line.toUpperCase(java.util.Locale.ROOT);
         if(upper.startsWith("BEGIN:"))
         {
            stack.push(upper.substring(6));
            if(++count > 64 || stack.size() > 4)
            {
               throw new IllegalArgumentException("Calendar component complexity exceeds its limit");
            }
         }
         else if(upper.startsWith("END:") && (stack.isEmpty() || !stack.pop().equals(upper.substring(4))))
         {
            throw new IllegalArgumentException("Mismatched calendar component");
         }
      }
      if(!stack.isEmpty())
      {
         throw new IllegalArgumentException("Unterminated calendar component");
      }
   }



   private static void requireStatus(HttpResponse<?> response, int... accepted)
   {
      for(int status : accepted)
      {
         if(response.statusCode() == status)
         {
            return;
         }
      }
      throw new DavException(response.statusCode());
   }

   private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>
   {
      private final CompletableFuture<byte[]> result = new CompletableFuture<>();
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private Flow.Subscription subscription;

      public CompletionStage<byte[]> getBody()
      {
         return result;
      }



      public void onSubscribe(Flow.Subscription value)
      {
         subscription = value;
         subscription.request(1);
      }



      public void onNext(List<ByteBuffer> buffers)
      {
         for(var buffer : buffers)
         {
            if(buffer.remaining() > MAX_BYTES - bytes.size())
            {
               subscription.cancel();
               result.completeExceptionally(new DavException(0));
               return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
         }
         subscription.request(1);
      }



      public void onError(Throwable error)
      {
         result.completeExceptionally(new DavException(0));
      }



      public void onComplete()
      {
         result.complete(bytes.toByteArray());
      }
   }
}
