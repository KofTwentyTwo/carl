/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import javax.sql.DataSource;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;


/** Test-only pause after the actual PostgreSQL commit, before the authorized load returns its bytes. */
final class DownloadCommitBarrier implements DataSource
{
   private final DataSource delegate;
   private volatile String target;
   private final AtomicBoolean armed = new AtomicBoolean();
   private final CountDownLatch committed = new CountDownLatch(1);
   private final CountDownLatch release = new CountDownLatch(1);

   DownloadCommitBarrier(DataSource delegate)
   {
      this.delegate = delegate;
   }



   void arm(String targetClass)
   {
      target = targetClass;
      armed.set(true);
   }



   boolean awaitCommit() throws InterruptedException
   {
      return committed.await(15, TimeUnit.SECONDS);
   }



   void release()
   {
      release.countDown();
   }



   private Connection wrapped(Connection connection)
   {
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) ->
      {
         Object result;
         try
         {
            result = method.invoke(connection, args);
         }
         catch(InvocationTargetException failure)
         {
            throw failure.getCause();
         }
         if(method.getName().equals("commit") && armed.get() && java.util.Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame -> frame.getClassName().equals(target) && frame.getMethodName().equals("load")) && armed.compareAndSet(true, false))
         {
            committed.countDown();
            if(!release.await(20, TimeUnit.SECONDS))
            {
               throw new SQLException("Controlled download barrier timed out");
            }
         }
         return result;
      });
   }



   @Override
   public Connection getConnection() throws SQLException
   {
      return wrapped(delegate.getConnection());
   }



   @Override
   public Connection getConnection(String user, String password) throws SQLException
   {
      return wrapped(delegate.getConnection(user, password));
   }



   @Override
   public PrintWriter getLogWriter() throws SQLException
   {
      return delegate.getLogWriter();
   }



   @Override
   public void setLogWriter(PrintWriter writer) throws SQLException
   {
      delegate.setLogWriter(writer);
   }



   @Override
   public int getLoginTimeout() throws SQLException
   {
      return delegate.getLoginTimeout();
   }



   @Override
   public void setLoginTimeout(int seconds) throws SQLException
   {
      delegate.setLoginTimeout(seconds);
   }



   @Override
   public Logger getParentLogger()
   {
      return Logger.getLogger("controlled-download-barrier");
   }



   @Override
   public <T> T unwrap(Class<T> type) throws SQLException
   {
      return delegate.unwrap(type);
   }



   @Override
   public boolean isWrapperFor(Class<?> type) throws SQLException
   {
      return delegate.isWrapperFor(type);
   }
}
