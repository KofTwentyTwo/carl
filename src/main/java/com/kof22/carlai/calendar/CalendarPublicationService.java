
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.calendar;


import javax.sql.DataSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


/** Durable, bounded publication of shared plan steps to one operator-configured collection. */
public final class CalendarPublicationService implements AutoCloseable
{
   /** Must recheck plan/source/field access for every configured recipient and reject private text.
    * Lock the household permission-epoch row and plan row FOR SHARE until commit; every grant/field change must update that epoch row atomically.
    * The actor is operator-configured standing authority, never a model-supplied principal.
    * Do not return arbitrary imported descriptions without establishing shared provenance. */
   @FunctionalInterface
   public interface Authority
   {
      /** Loads and locks a currently authorized shared projection. */
      SharedItem load(Connection connection, String principal, long plan, UUID step, int expectedVersion, Set<Long> audience, Action action) throws SQLException;
   }



   /** Domain authorization distinguishes publishing from retirement and observation. */
   public enum Action
   {
      PUBLISH, RETIRE, READ
   }



   /** Shared-safe projection of one agreed plan version; version/UID are verified by this service. */
   public record SharedItem(PlanCalendarCodec.Item item, LocalDate due, Instant reportedCompleted, String permissionRevision)
   {
      /** Requires a durable permission revision for reconciliation and replay. */
      public SharedItem
      {
         Objects.requireNonNull(item);
         Objects.requireNonNull(due);
         if(permissionRevision == null || permissionRevision.isBlank() || permissionRevision.length() > 200)
         {
            throw new IllegalArgumentException("A persisted permission revision is required");
         }
      }
   }



   /** Provider completion remains an observation, never verified financial execution. */
   public record Outcome(UUID request, UUID step, String status, String diagnostic)
   {
   }

   private final DataSource source;
   private final String collectionKey;
   private final String component;
   private final Set<Long> audience;
   private final String principal;
   private final Authority authority;
   private final CalDavClient provider;
   private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(2);

   /** Configuration must identify the actual shared collection audience; empty or implicit scope fails. */
   public CalendarPublicationService(DataSource source, URI collection, String component, Set<Long> audience,
      String principal, String user, char[] password, boolean localDevelopment, Authority authority)
   {
      this.source = Objects.requireNonNull(source);
      this.authority = Objects.requireNonNull(authority);
      if(!Set.of("VEVENT", "VTODO").contains(component) || audience == null || audience.isEmpty() || audience.size() > 32
         || audience.stream().anyMatch(id -> id == null || id <= 0) || principal == null || principal.isBlank() || principal.length() > 200)
      {
         throw new IllegalArgumentException("An explicit component, standing actor and shared audience are required");
      }
      this.component = component;
      this.audience = Set.copyOf(audience);
      this.principal = principal;
      provider = new CalDavClient(collection, user, password, localDevelopment);
      collectionKey = hash(collection.toASCIIString() + "\n" + component + "\n" + this.audience.stream().sorted().toList());
   }



   /** Identifies the configured collection, component and audience without exposing its endpoint or credentials. */
   public String collectionKey()
   {
      return collectionKey;
   }



   /** Explicit application workflow; ordinary read tools must not call this method. */
   public Outcome publish(UUID request, long plan, UUID step, int expectedVersion) throws SQLException
   {
      return execute(request, plan, step, expectedVersion, false, false);
   }



   /** Retires only a persisted Carl-owned resource under the last known strong ETag. */
   public Outcome retire(UUID request, long plan, UUID step, int expectedVersion) throws SQLException
   {
      return execute(request, plan, step, expectedVersion, true, false);
   }



   /** Resolves a saved uncertain intent under current plan access, without any provider mutation.
    * This remains possible after a newer plan version supersedes the intended publication. */
   public Outcome reconcile(UUID request, long plan, UUID step, int currentVersion) throws SQLException
   {
      return execute(request, plan, step, currentVersion, false, true);
   }

   /** Observed remote content is shared-source data, not a trusted instruction or verified payment. */
   public record Observation(UUID step, String state, String calendar, String etag, String diagnostic)
   {
   }

   /** Reads only a persisted mapping and records household edits/completion for explicit domain review. */
   public Observation synchronize(long plan, UUID step, int currentVersion) throws SQLException
   {
      Objects.requireNonNull(step);
      if(!capacity.tryAcquire())
      {
         throw new IllegalStateException("Calendar workflow capacity reached");
      }
      try(var c = source.getConnection())
      {
         c.setAutoCommit(false);
         try
         {
            sql(c, "SET LOCAL statement_timeout='5s'");
            sql(c, "SET LOCAL lock_timeout='2s'");
            try(var s = prepare(c, "SELECT pg_try_advisory_xact_lock(?)", lockKey(step)); var r = s.executeQuery())
            {
               if(!r.next() || !r.getBoolean(1))
               {
                  throw new IllegalStateException("Calendar item is already being processed");
               }
            }
            authorize(c, plan, step, currentVersion, Action.READ);
            Mapping mapping = mapping(c, step);
            if(mapping == null || mapping.plan != plan || !mapping.collection.equals(collectionKey) || !mapping.component.equals(component))
            {
               throw new SecurityException("Calendar mapping unavailable");
            }
            Observation observation;
            try
            {
               var remote = provider.read(step, component);
               String state = !mapping.retired && Objects.equals(mapping.etag, remote.etag()) && Objects.equals(mapping.publishedHash, hash(remote.calendar())) ? "UNCHANGED" : "CHANGED";
               sql(c, "UPDATE carl_calendar_mapping SET observed_calendar=?,observed_at=now(),last_read_success=now() WHERE step_id=?", remote.calendar(), step);
               observation = new Observation(step, state, remote.calendar(), remote.etag(), "Shared-source observation only; financial execution remains unverified");
            }
            catch(CalDavClient.DavException failure)
            {
               observation = new Observation(step, failure.status() == 404 ? "MISSING" : "UNKNOWN", null, null,
                  failure.status() == 404 ? "Resource missing; no automatic recreation" : "Read failed; prior observations may be stale");
            }
            sql(c, "UPDATE carl_calendar_mapping SET read_state=?,read_diagnostic=? WHERE step_id=?", observation.state(), observation.diagnostic(), step);
            c.commit();
            return observation;
         }
         catch(SQLException | RuntimeException failure)
         {
            c.rollback();
            throw failure;
         }
      }
      finally
      {
         capacity.release();
      }
   }



   private Outcome execute(UUID request, long plan, UUID step, int version, boolean retire, boolean reconcile) throws SQLException
   {
      Objects.requireNonNull(request);
      Objects.requireNonNull(step);
      if(plan <= 0 || version <= 0)
      {
         throw new IllegalArgumentException("A plan and expected version are required");
      }
      if(!capacity.tryAcquire())
      {
         throw new IllegalStateException("Calendar workflow capacity reached");
      }
      try(var c = source.getConnection())
      {
         c.setAutoCommit(false);
         sql(c, "SET LOCAL statement_timeout='5s'");
         sql(c, "SET LOCAL lock_timeout='2s'");
         // Session lock survives the durable intent commit and prevents concurrent service instances.
         try(var s = prepare(c, "SELECT pg_try_advisory_lock(?)", lockKey(step)); var r = s.executeQuery())
         {
            if(!r.next() || !r.getBoolean(1))
            {
               throw new IllegalStateException("Calendar item is already being processed");
            }
         }
         try
         {
            SharedItem projection = authorize(c, plan, step, version, reconcile ? Action.READ : retire ? Action.RETIRE : Action.PUBLISH);
            String desired = retire ? "" : render(projection);
            String input = hash(principal + ":" + plan + ":" + step + ":" + version + ":" + retire + ":" + collectionKey + ":" + desired);
            Mapping mapping = mapping(c, step);
            if(mapping == null)
            {
               if(retire)
               {
                  throw new IllegalArgumentException("Only an existing Carl resource can be retired");
               }
               sql(c, "INSERT INTO carl_calendar_mapping(step_id,plan_id,collection_key,component) VALUES(?,?,?,?)", step, plan, collectionKey, component);
               mapping = mapping(c, step);
            }
            if(mapping.plan != plan || !mapping.collection.equals(collectionKey) || !mapping.component.equals(component))
            {
               throw new SecurityException("Calendar mapping is outside the configured plan scope");
            }
            Operation operation = operation(c, request);
            if(operation != null && (!operation.step.equals(step) || !reconcile && !operation.input.equals(input)))
            {
               throw new IllegalArgumentException("Request identity is already bound to different work");
            }
            if(operation != null && !reconcile && !operation.revision.equals(projection.permissionRevision()))
            {
               throw new SecurityException("Calendar request permission scope changed");
            }
            if(operation != null && Set.of("COMPLETE", "CONFLICT").contains(operation.status))
            {
               c.commit();
               return new Outcome(request, step, operation.status, operation.diagnostic);
            }
            if(reconcile)
            {
               if(operation == null)
               {
                  throw new IllegalArgumentException("No saved request exists to reconcile");
               }
               desired = operation.desired;
               retire = operation.action.equals("RETIRE");
            }
            if(mapping.retired && !retire)
            {
               throw new IllegalArgumentException("Retired calendar resources cannot be recreated");
            }
            // An unresolved different intent must be reconciled with its original request identity first.
            try(var s = prepare(c, "SELECT request_id FROM carl_calendar_operation WHERE step_id=? AND request_id<>? AND status IN ('PENDING','UNKNOWN') LIMIT 1", step, request); var r = s.executeQuery())
            {
               if(r.next())
               {
                  throw new IllegalStateException("Reconcile the existing calendar request before new work");
               }
            }
            if(operation == null)
            {
               sql(c, "INSERT INTO carl_calendar_operation(request_id,step_id,plan_version,action,input_hash,desired_calendar,authority_revision,actor,status) VALUES(?,?,?,?,?,?,?,?,'PENDING')", request, step, version, retire ? "RETIRE" : "PUBLISH", input, desired, projection.permissionRevision(), principal);
            }
            c.commit(); // A crash from here is conservatively an unresolved outcome, never a blind retry.
            sql(c, "SET LOCAL statement_timeout='5s'");
            sql(c, "SET LOCAL lock_timeout='2s'");
            SharedItem current = authorize(c, plan, step, version, reconcile ? Action.READ : retire ? Action.RETIRE : Action.PUBLISH);
            if(!current.equals(projection))
            {
               throw new SecurityException("Plan publication authority changed");
            }
            Outcome result = apply(c, request, step, mapping, desired, retire, !reconcile);
            c.commit();
            return result;
         }
         catch(SQLException | RuntimeException failure)
         {
            c.rollback();
            throw failure;
         }
         finally
         {
            // Pool-return must never retain the session lock, even after a failed transaction.
            c.rollback();
            try(var s = prepare(c, "SELECT pg_advisory_unlock(?)", lockKey(step)))
            {
               s.execute();
            }
            c.commit();
         }
      }
      finally
      {
         capacity.release();
      }
   }



   private Outcome apply(Connection c, UUID request, UUID step, Mapping mapping, String desired, boolean retire, boolean allowWrite) throws SQLException
   {
      try
      {
         if(!provider.componentTypes().contains(component))
         {
            return status(c, request, step, "CONFLICT", "Configured collection does not support this component");
         }
         CalDavClient.Resource remote;
         try
         {
            remote = provider.read(step, component);
         }
         catch(CalDavClient.DavException failure)
         {
            if(failure.status() != 404)
            {
               throw failure;
            }
            remote = null;
         }
         if(remote != null)
         {
            sql(c, "UPDATE carl_calendar_mapping SET observed_calendar=?,observed_at=now() WHERE step_id=?", remote.calendar(), step);
            if(!retire && hash(remote.calendar()).equals(hash(desired)))
            {
               return complete(c, request, step, remote.etag(), desired, false);
            }
            if(mapping.publishedHash == null || !mapping.publishedHash.equals(hash(remote.calendar())) || !Objects.equals(mapping.etag, remote.etag()))
            {
               return status(c, request, step, "CONFLICT", "Provider content changed; review household edits before publishing");
            }
         }
         else if(retire)
         {
            return complete(c, request, step, null, "", true);
         }
         else if(mapping.publishedHash != null || mapping.retired)
         {
            return status(c, request, step, "CONFLICT", "Published resource was removed; no automatic recreation");
         }
         if(!allowWrite)
         {
            return status(c, request, step, "CONFLICT", "Intent was not observed at provider; current plan needs an explicit new request");
         }
         if(retire)
         {
            provider.remove(step, mapping.etag);
            return complete(c, request, step, null, "", true);
         }
         CalDavClient.Resource written = remote == null ? provider.create(step, desired) : provider.update(step, mapping.etag, desired);
         if(!strong(written.etag()))
         {
            return status(c, request, step, "UNKNOWN", "Write returned no usable ETag; reconcile the same request");
         }
         return complete(c, request, step, written.etag(), desired, false);
      }
      catch(CalDavClient.DavException failure)
      {
         return status(c, request, step, failure.status() == 409 || failure.status() == 412 ? "CONFLICT" : "UNKNOWN",
            failure.status() == 409 || failure.status() == 412 ? "Provider version conflict; no overwrite" : "Provider outcome unresolved; reconcile the same request");
      }
   }



   private Outcome complete(Connection c, UUID request, UUID step, String etag, String desired, boolean retired) throws SQLException
   {
      if(!retired && !strong(etag))
      {
         return status(c, request, step, "UNKNOWN", "Reconciliation needs a strong provider ETag");
      }
      sql(c, "UPDATE carl_calendar_mapping SET etag=?,published_hash=?,retired=?,last_success=now() WHERE step_id=?", etag, retired ? null : hash(desired), retired, step);
      return status(c, request, step, "COMPLETE", retired ? "Carl-managed item retired" : "Shared plan item synchronized; financial execution remains unverified");
   }



   private Outcome status(Connection c, UUID request, UUID step, String state, String diagnostic) throws SQLException
   {
      sql(c, "UPDATE carl_calendar_operation SET status=?,diagnostic=?,updated_at=now() WHERE request_id=?", state, diagnostic, request);
      return new Outcome(request, step, state, diagnostic);
   }



   private SharedItem authorize(Connection c, long plan, UUID step, int version, Action action) throws SQLException
   {
      SharedItem value = authority.load(c, principal, plan, step, version, audience, action);
      if(value == null || !step.equals(value.item().id()) || version != value.item().sequence())
      {
         throw new SecurityException("Shared plan projection does not match the requested version");
      }
      return value;
   }



   private String render(SharedItem value)
   {
      return component.equals("VTODO")
         ? PlanCalendarCodec.reminder(value.item(), value.due(), value.reportedCompleted())
         : PlanCalendarCodec.allDay(value.item(), value.due(), value.due().plusDays(1));
   }

   private record Mapping(long plan, String collection, String component, String etag, String publishedHash, boolean retired)
   {
   }



   private record Operation(UUID step, String input, String revision, String status, String diagnostic, String desired, String action)
   {
   }
   private static Mapping mapping(Connection c, UUID step) throws SQLException
   {
      try(var s = prepare(c, "SELECT * FROM carl_calendar_mapping WHERE step_id=?", step); var r = s.executeQuery())
      {
         return r.next() ? new Mapping(r.getLong("plan_id"), r.getString("collection_key"), r.getString("component"), r.getString("etag"), r.getString("published_hash"), r.getBoolean("retired")) : null;
      }
   }



   private static Operation operation(Connection c, UUID request) throws SQLException
   {
      try(var s = prepare(c, "SELECT * FROM carl_calendar_operation WHERE request_id=?", request); var r = s.executeQuery())
      {
         return r.next() ? new Operation(r.getObject("step_id", UUID.class), r.getString("input_hash"), r.getString("authority_revision"), r.getString("status"), r.getString("diagnostic"), r.getString("desired_calendar"), r.getString("action")) : null;
      }
   }



   private static boolean strong(String etag)
   {
      return etag != null && etag.length() <= 250 && etag.matches("\"[!#-~]+\"");
   }



   private static long lockKey(UUID id)
   {
      return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
   }



   private static String hash(String text)
   {
      try
      {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
      }
      catch(java.security.NoSuchAlgorithmException impossible)
      {
         throw new IllegalStateException(impossible);
      }
   }



   private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException
   {
      var s = c.prepareStatement(sql);
      s.setQueryTimeout(5);
      for(int i = 0; i < values.length; i++)
      {
         s.setObject(i + 1, values[i]);
      }
      return s;
   }



   private static void sql(Connection c, String sql, Object... values) throws SQLException
   {
      try(var s = prepare(c, sql, values))
      {
         s.execute();
      }
   }



   @Override
   public void close()
   {
      provider.close();
   }
}
