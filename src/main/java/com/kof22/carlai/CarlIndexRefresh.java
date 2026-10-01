/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.Instant;

import com.kingsrook.qbits.quicksearch.QuickSearchQBitContext;
import com.kingsrook.qbits.quicksearch.opensearch.OpenSearchDocument;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QRuntimeServiceInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kof22.carlai.domain.CarlSearchAccess;


/** Fixed title-only index reconciliation; no event may select a caller, table, SQL or external action. */
public final class CarlIndexRefresh implements QRuntimeServiceInterface
{
   private final CarlSearchAccess access;
   private java.util.concurrent.ScheduledExecutorService worker;
   private volatile boolean stopped;
   private volatile String state = "STARTING";

   /** Authoritative SQL reads are application-owned; the actual QBit owns the OpenSearch connection. */
   public CarlIndexRefresh(CarlSearchAccess access)
   {
      this.access = access;
   }



   /** Internal index process and periodic reconciliation serialize one complete refresh. */
   public synchronized void refresh() throws QException
   {
      if(stopped)
      {
         throw new QException("Carl search indexer stopped");
      }
      var client = QuickSearchQBitContext.getClient();
      if(client == null)
      {
         throw new QException("Carl search index unavailable");
      }
      Instant run = Instant.now();
      try
      {
         client.ensureIndexExists();
         for(String table : CarlSearchAccess.TABLES.keySet().stream().sorted().toList())
         {
            long after = 0;
            int pages = 0;
            while(true)
            {
               var rows = access.indexPage(table, after);
               if(rows.isEmpty())
               {
                  break;
               }
               if(stopped || Thread.currentThread().isInterrupted() || ++pages > 100)
               {
                  throw new QException("Carl index refresh interrupted or exceeds 100000 records per table");
               }
               var documents = rows.stream().map(row -> new OpenSearchDocument().withSourceTable(table).withRecordId(row.get("id").toString()).withRecordLabel(row.get("title").toString()).withSearchableText(row.get("title").toString()).withIndexedAt(run)).toList();
               var indexed = client.indexDocuments(documents, 500);
               if(!Boolean.TRUE.equals(indexed.isFullySuccessful()))
               {
                  throw new QException("Carl search index batch incomplete");
               }
               after = Long.parseLong(rows.getLast().get("id").toString());
            }
            client.deleteDocumentsIndexedBefore(table, run);
         }
         client.refreshIndex();
         state = "READY";
      }
      catch(QException | RuntimeException failure)
      {
         state = "DEGRADED";
         throw new QException("Carl search index refresh unavailable");
      }
   }



   @Override
   public String getName()
   {
      return "carlIndexRefresh";
   }



   @Override
   public void start(QInstance instance)
   {
      worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "carl-index-refresh"));
      worker.scheduleWithFixedDelay(() ->
      {
         try
         {
            refresh();
         }
         catch(QException unavailable)
         {
            state = "DEGRADED";
         }
      }, 0, 30, java.util.concurrent.TimeUnit.SECONDS);
   }



   /** Diagnostic state contains no private index content or credentials. */
   public String state()
   {
      return state;
   }



   @Override
   public void stop()
   {
      stopped = true;
      if(worker != null)
      {
         worker.shutdownNow();
      }
   }



   @Override
   public void afterApplicationStop()
   {
      if(worker != null)
      {
         try
         {
            if(!worker.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS))
            {
               throw new IllegalStateException("Carl index refresh has not stopped");
            }
         }
         catch(InterruptedException interrupted)
         {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Carl index cleanup interrupted", interrupted);
         }
      }
      state = "STOPPED";
   }
}
