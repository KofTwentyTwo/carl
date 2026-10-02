/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QInputSource;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/** One native request may acknowledge only its own committed, household-locked fixed receipt. */
public final class NativeMutationReceipt
{
   public static final String MONARCH_MESSAGE = "Reviewed import processing finished. Open Import Reviews and Transactions for the current outcome and affected records. Original source evidence and revisions are retained.";
   public static final String CALENDAR_MESSAGE = "Calendar refresh request recorded. Review Calendar Connections for the current refresh status and coverage. Private event details remain excluded; synchronization does not book or change an appointment.";
   private static final Map<String, String> MESSAGES = Map.of(
      "carlSelectCashExpenses", "Complete selection replaced. Selected reserves are additional to the base floor and protected for the full interval. Remove overlapping manual events or reserve assumptions before relying on a purchase assessment. This guided form selects up to four schedules and four standalone payments.",
      "carlCorrectExpense", "Schedule corrected with attribution; dependent payment applications must be reviewed before material changes.",
      "carlCorrectRentalProperty", "Current facts corrected with attribution; original evidence and prior values retained.",
      "carlImportMonarch", MONARCH_MESSAGE, "carlResumeMonarch", MONARCH_MESSAGE, "carlSyncAgenda", CALENDAR_MESSAGE);
   private static final Semaphore CAPACITY = new Semaphore(10000);
   // The semaphore bounds the entire cache. A second size limit could evict a live pending holder
   // early because Guava partitions that limit between cache segments.
   private static final Cache<String, State> ACTIVE = CacheBuilder.newBuilder().expireAfterWrite(Duration.ofHours(24)).removalListener((com.google.common.cache.RemovalNotification<String, State> removal) ->
   {
      if(removal.getValue() != null)
      {
         removal.getValue().release();
      }
   }).build();
   private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

   private NativeMutationReceipt()
   {
   }

   /** The transport owns this holder; it is never persisted or selected by a process value. */
   public static final class State
   {
      private final QInstance instance;
      private final String process;
      private final String uuid;
      private final CarlService.Member before;
      private volatile CarlService.Member after;
      private final AtomicBoolean closed = new AtomicBoolean();
      private final AtomicBoolean acknowledged = new AtomicBoolean();
      private volatile String jobUUID;
      private volatile java.util.UUID review;
      private volatile java.util.UUID transactionRequest;
      private volatile java.util.UUID calendarRequest;
      private volatile boolean terminalCommitted;

      private State(QInstance instance, String process, String uuid, CarlService.Member before)
      {
         this.instance = instance;
         this.process = process;
         this.uuid = uuid;
         this.before = before;
      }



      /** Requires the exact current membership attested before the successful commit. */
      public boolean matches(CarlService.Member current)
      {
         return !closed.get() && !acknowledged.get() && terminalCommitted && after != null && after.equals(current);
      }



      /** Only the native engine response can associate its asynchronous job with this holder. */
      public synchronized void bindJob(String job)
      {
         java.util.UUID.fromString(job);
         if(closed.get() || acknowledged.get() || jobUUID != null && !jobUUID.equals(job))
         {
            throw new SecurityException("Native mutation job binding changed");
         }
         jobUUID = job;
      }



      /** Pending polls retain the original membership; committed polls require its exact attestation. */
      public boolean permitsStatus(CarlService.Member current)
      {
         return !closed.get() && !acknowledged.get() && (before.equals(current) || after != null && after.equals(current));
      }



      /** A receipt can be acknowledged once, even when concurrent polls complete together. */
      public boolean acknowledge(CarlService.Member current)
      {
         return matches(current) && acknowledged.compareAndSet(false, true);
      }



      /** An unrelated caller cannot discard the original owner's active operation. */
      public boolean owns(CarlService.Member current)
      {
         return before.id() == current.id() && before.principal().equals(current.principal());
      }



      private void release()
      {
         if(closed.compareAndSet(false, true))
         {
            CAPACITY.release();
         }
      }



      /** Fixed text is defined by trusted application code, never a process response or submitted value. */
      public String message()
      {
         return MESSAGES.get(process);
      }
   }



   private record Pending(State state, Connection connection, CarlService.Member after, boolean terminal)
   {
   }

   /** Opens only an eligible submitted input request after ordinary native identity/scope validation. */
   public static State open(QInstance instance, String process, String uuid, CarlService.Member before)
   {
      if(!supports(process))
      {
         return null;
      }
      ACTIVE.cleanUp();
      if(!CAPACITY.tryAcquire())
      {
         throw new SecurityException("Native mutation capacity reached");
      }
      State state = new State(instance, process, uuid, before);
      if(ACTIVE.asMap().putIfAbsent(uuid, state) != null)
      {
         state.release();
         throw new SecurityException("Native mutation request already active");
      }
      return state;
   }



   /** Eligible forms require the staged native input workflow, including on legacy routes. */
   public static boolean supports(String process)
   {
      return MESSAGES.containsKey(process);
   }



   /** The upload form executes after review; the resume form executes after input. */
   public static boolean supportsStep(String process, String step)
   {
      return supports(process) && ("carlImportMonarch".equals(process) ? "review" : "input").equals(step);
   }



   private static boolean monarch(State state)
   {
      return "carlImportMonarch".equals(state.process) || "carlResumeMonarch".equals(state.process);
   }



   private static State currentState()
   {
      var stack = QContext.getActionStack();
      if(stack != null)
      {
         for(var action : stack.reversed())
         {
            if(action instanceof RunProcessInput input && input.getInputSource() == QInputSource.USER && input.getProcessUUID() != null)
            {
               State state = ACTIVE.getIfPresent(input.getProcessUUID());
               return state != null && state.instance == QContext.getQInstance() && state.process.equals(input.getProcessName()) ? state : null;
            }
         }
      }
      return null;
   }



   /** Binds only an authoritative owned review after the current household lock is held. */
   static void bindMonarch(CarlService.Member member, java.util.UUID review, java.util.UUID transactionRequest)
   {
      State state = currentState();
      if(state == null || !monarch(state))
      {
         return;
      }
      synchronized(state)
      {
         if(state.closed.get() || !state.before.equals(member) || state.review != null && !state.review.equals(review))
         {
            throw new SecurityException("Native import review scope changed");
         }
         state.review = review;
         state.transactionRequest = transactionRequest;
      }
   }



   /** Intermediate import commits advance only this review's membership; they are not final receipts. */
   static void beforeMonarchTransaction(Connection connection, CarlService.Member member, java.util.UUID request)
   {
      State state = currentState();
      if(state != null && monarch(state))
      {
         if(state.transactionRequest == null || !state.transactionRequest.equals(request))
         {
            throw new SecurityException("Native import request changed");
         }
         beforeMonarch(connection, member, state, false);
      }
   }



   /** Final acknowledgement additionally requires the exact reviewed status transaction to commit. */
   static void beforeMonarchCompletion(Connection connection, CarlService.Member member, java.util.UUID review)
   {
      State state = currentState();
      if(state != null && monarch(state))
      {
         if(state.review == null || !state.review.equals(review))
         {
            throw new SecurityException("Native import completion review changed");
         }
         beforeMonarch(connection, member, state, true);
      }
   }



   private static void beforeMonarch(Connection connection, CarlService.Member member, State state, boolean terminal)
   {
      CarlService.Member expected = state.after == null ? state.before : state.after;
      if(state.closed.get() || state.terminalCommitted || !expected.equals(member) || PENDING.get() != null)
      {
         throw new SecurityException("Native import access changed before the locked operation");
      }
      PENDING.set(new Pending(state, connection, null, terminal));
   }



   /** Resolves only the exact engine-issued job on the same application instance and process. */
   public static State forStatus(QInstance instance, String process, String uuid, String job)
   {
      State state = ACTIVE.getIfPresent(uuid);
      return state != null && state.instance == instance && state.process.equals(process) && job != null && job.equals(state.jobUUID) ? state : null;
   }



   /** Binds only this calendar sync's authoritative request after the domain household lock is acquired. */
   static void beforeCalendar(Connection connection, CarlService.Member member, java.util.UUID request)
   {
      State state = currentState();
      if(state == null)
      {
         return;
      }
      synchronized(state)
      {
         if(!state.process.equals("carlSyncAgenda") || request == null || state.calendarRequest != null && !state.calendarRequest.equals(request))
         {
            throw new SecurityException("Native calendar request changed");
         }
         state.calendarRequest = request;
         before(connection, member);
      }
   }



   /** Binds the async native action to the exact request after its domain household lock is acquired. */
   static void before(Connection connection, CarlService.Member member)
   {
      State state = currentState();
      if(state != null)
      {
         if(monarch(state) || state.closed.get() || !state.before.equals(member) || PENDING.get() != null)
         {
            throw new SecurityException("Native mutation access changed before the locked operation");
         }
         PENDING.set(new Pending(state, connection, null, true));
      }
   }



   /** Stages the post-mutation membership while the same household transaction still holds its lock. */
   static void after(Connection connection, CarlService.Member member)
   {
      Pending pending = PENDING.get();
      if(pending == null || pending.connection() != connection)
      {
         return;
      }
      var before = pending.state().before;
      var identity = new CarlService.Member(member.id(), member.householdId(), member.principal(), member.manager(), member.zone(), before.permissionRevision());
      if(pending.state().closed.get() || !identity.equals(before) || member.permissionRevision() < before.permissionRevision())
      {
         throw new SecurityException("Native mutation membership changed during the operation");
      }
      PENDING.set(new Pending(pending.state(), connection, member, pending.terminal()));
   }



   /** Called only after JDBC acknowledges a successful commit; failed transactions cannot produce receipts. */
   static void committed(Connection connection)
   {
      Pending pending = PENDING.get();
      if(pending != null && pending.connection() == connection)
      {
         if(!pending.state().closed.get())
         {
            pending.state().after = pending.after();
            pending.state().terminalCommitted = pending.terminal() && pending.after() != null;
         }
         PENDING.remove();
      }
   }



   /** Rollback and all transaction exit paths release only the current transaction's pending state. */
   static void clear(Connection connection)
   {
      Pending pending = PENDING.get();
      if(pending != null && pending.connection() == connection)
      {
         PENDING.remove();
      }
   }



   /** Closes the request without allowing later cached state or parallel requests to borrow its attestation. */
   public static void close(State state)
   {
      if(state != null)
      {
         ACTIVE.asMap().remove(state.uuid, state);
         state.release();
      }
   }
}
