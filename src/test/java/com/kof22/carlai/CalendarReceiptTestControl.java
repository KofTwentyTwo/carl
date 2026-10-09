/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;


/** Test-only barriers surround real calendar projection commits without replacing PostgreSQL. */
public final class CalendarReceiptTestControl
{
   public final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();
   public final AtomicReference<Runnable> afterCommit = new AtomicReference<>();
   public final AtomicReference<Runnable> beforeRequest = new AtomicReference<>();
   public final java.util.concurrent.atomic.AtomicBoolean failCommit = new java.util.concurrent.atomic.AtomicBoolean();

   /** Hooks only the calendar projection transaction around its actual JDBC commit. */
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
      var changed = new java.util.concurrent.atomic.AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) ->
      {
         if(method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql && sql.startsWith("INSERT INTO carl_calendar_event"))
         {
            changed.set(true);
         }
         boolean commit = method.getName().equals("commit") && changed.get();
         if(commit)
         {
            Runnable hook = beforeCommit.getAndSet(null);
            if(hook != null)
            {
               hook.run();
            }
            if(failCommit.getAndSet(false))
            {
               throw new SQLException("Controlled calendar commit failure", "40001");
            }
         }
         try
         {
            Object result = method.invoke(connection, args);
            if(commit)
            {
               Runnable hook = afterCommit.getAndSet(null);
               if(hook != null)
               {
                  hook.run();
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
