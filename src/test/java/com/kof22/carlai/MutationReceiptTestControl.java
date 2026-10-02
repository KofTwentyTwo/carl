/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


/** Test-only barriers surround real JDBC commit; domain SQL and native authentication remain real. */
public final class MutationReceiptTestControl
{
   /** Tests choose a deterministic commit failure or concurrent-request window. */
   public enum Mode
   {
      NONE, FAIL_COMMIT, BLOCK_COMMIT
   }

   public final AtomicReference<String> commitSqlPrefix = new AtomicReference<>();
   public final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);
   public final AtomicReference<Runnable> beforeRequest = new AtomicReference<>();
   public final AtomicReference<Runnable> afterCommit = new AtomicReference<>();
   public final CountDownLatch reached = new CountDownLatch(1);
   public final CountDownLatch release = new CountDownLatch(1);

   /** Wraps only the domain data source; every underlying database operation uses PostgreSQL. */
   public DataSource wrap(DataSource source)
   {
      return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) ->
      {
         try
         {
            Object result = method.invoke(source, args);
            return result instanceof Connection connection ? connection(connection) : result;
         }
         catch(InvocationTargetException failure)
         {
            throw failure.getCause();
         }
      });
   }



   private Connection connection(Connection connection)
   {
      var dirty = new java.util.concurrent.atomic.AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) ->
      {
         if(method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
            && (commitSqlPrefix.get() != null ? sql.startsWith(commitSqlPrefix.get()) : (sql.startsWith("INSERT INTO carl_cash_expense_selection") || sql.startsWith("UPDATE carl_expense SET") || sql.startsWith("UPDATE carl_property SET"))))
         {
            dirty.set(true);
         }
         boolean commit = method.getName().equals("commit") && dirty.get();
         if(commit)
         {
            Mode next = mode.getAndSet(Mode.NONE);
            if(next == Mode.FAIL_COMMIT)
            {
               throw new SQLException("Controlled failed commit", "40001");
            }
            if(next == Mode.BLOCK_COMMIT)
            {
               reached.countDown();
               if(!release.await(10, TimeUnit.SECONDS))
               {
                  throw new SQLException("Controlled commit barrier timed out", "57014");
               }
            }
         }
         try
         {
            Object result = method.invoke(connection, args);
            if(commit)
            {
               Runnable callback = afterCommit.getAndSet(null);
               if(callback != null)
               {
                  callback.run();
               }
            }
            return result;
         }
         catch(InvocationTargetException failure)
         {
            throw failure.getCause();
         }
      });
   }
}
