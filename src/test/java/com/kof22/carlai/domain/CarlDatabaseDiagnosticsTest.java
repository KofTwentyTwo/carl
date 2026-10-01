/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kof22.agentcore.store.AgentMigrations;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real catalog sizes and bounded PostgreSQL reads; no provider or family data. */
class CarlDatabaseDiagnosticsTest
{
   @Test
   void currentManagerReadsRealStorageWithoutConnectionSecretsOrRowCountsAndRevocationDenies() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine").withPassword("synthetic-diagnostics-pass"))
      {
         database.start();
         var source = source(database);
         seed(source);
         var diagnostics = new CarlDatabaseDiagnostics(new CarlService(source, Clock.systemUTC()));
         var snapshot = diagnostics.read("alice");
         assertEquals("Carl PostgreSQL", snapshot.connectionLabel());
         assertEquals(database.getDatabaseName(), snapshot.database());
         assertEquals("public", snapshot.schema());
         assertTrue(snapshot.tables().stream().anyMatch(table -> table.table().equals("carl_household") && table.tableBytes() > 0 && table.indexBytes() > 0 && table.totalBytes() >= table.tableBytes() + table.indexBytes()));
         assertFalse(snapshot.toString().contains(database.getPassword()));
         assertFalse(snapshot.toString().contains("jdbc:"));
         assertFalse(snapshot.toString().contains("row_count"));
         assertThrows(SecurityException.class, () -> diagnostics.read("bob"));
         assertThrows(SecurityException.class, () -> diagnostics.read("unknown"));
         assertThrows(SecurityException.class, () -> diagnostics.read("alice' OR true--"));
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='SETTINGS'");
         }
         assertThrows(SecurityException.class, () -> diagnostics.read("alice"));
      }
   }



   @Test
   void tableLimitAndReadTimeoutAreEnforcedByActualPostgres() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine").withPassword("synthetic-diagnostics-pass"))
      {
         database.start();
         var source = source(database);
         seed(source);
         try(var c = source.getConnection(); var sql = c.createStatement())
         {
            for(int n = 0; n < 201; n++)
            {
               sql.execute("CREATE TABLE diagnostics_" + n + "(id bigint)");
            }
         }
         var diagnostics = new CarlDatabaseDiagnostics(new CarlService(source, Clock.systemUTC()));
         var snapshot = diagnostics.read("alice");
         assertEquals(200, snapshot.tables().size());
         assertTrue(snapshot.truncated());
         assertThrows(UnsupportedOperationException.class, () -> snapshot.tables().clear());
         try(var lock = source.getConnection(); var sql = lock.createStatement())
         {
            lock.setAutoCommit(false);
            sql.execute("LOCK TABLE carl_household IN ACCESS EXCLUSIVE MODE");
            long start = System.nanoTime();
            var failure = assertThrows(IllegalStateException.class, () -> diagnostics.read("alice"));
            assertTrue(failure.getMessage().contains("57014"), failure.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0, "Blocked native diagnostics must stop at the configured server timeout");
            lock.rollback();
         }
         assertEquals(200, diagnostics.read("alice").tables().size());
      }
   }



   @Test
   void requestScopeAndCurrentEpochRejectRevocationAfterCatalogRead() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine").withPassword("synthetic-diagnostics-pass"))
      {
         database.start();
         var source = source(database);
         seed(source);
         var revoked = new AtomicBoolean();
         var wrapped = new HookSource(source, () ->
         {
            try(var c = source.getConnection(); var sql = c.createStatement())
            {
               sql.execute("UPDATE carl_permission SET details=false WHERE member_id=1 AND domain='SETTINGS'");
               revoked.set(true);
            }
            catch(java.sql.SQLException failure)
            {
               throw new IllegalStateException(failure);
            }
         });
         NativeReadScope.begin();
         try
         {
            var diagnostics = new CarlDatabaseDiagnostics(new CarlService(wrapped, Clock.systemUTC()));
            assertThrows(SecurityException.class, () -> diagnostics.read("alice"));
            assertTrue(revoked.get(), "Permission changed after real catalog rows were assembled");
         }
         finally
         {
            NativeReadScope.end();
         }
      }
   }



   private static PGSimpleDataSource source(PostgreSQLContainer<?> database)
   {
      var source = new PGSimpleDataSource();
      source.setURL(database.getJdbcUrl());
      source.setUser(database.getUsername());
      source.setPassword(database.getPassword());
      source.setSslMode("disable");
      return source;
   }



   private static void seed(PGSimpleDataSource source) throws Exception
   {
      AgentMigrations.migrate(source);
      try(var c = source.getConnection(); var sql = c.createStatement())
      {
         sql.execute("INSERT INTO carl_household(id,name,display_zone) VALUES(1,'Synthetic diagnostics','America/Chicago')");
         sql.execute("INSERT INTO carl_member(id,household_id,principal,label,can_manage) VALUES(1,1,'alice','Owner',true),(2,1,'bob','Family',false)");
         sql.execute("INSERT INTO carl_permission(member_id,domain,details) VALUES(1,'SETTINGS',true),(2,'SETTINGS',true)");
      }
   }

   /** Intercepts only the real catalog result-close boundary; identity and queries remain production-owned. */
   private record HookSource(PGSimpleDataSource source, Runnable afterCatalog) implements javax.sql.DataSource
   {
      @Override
      public java.sql.Connection getConnection() throws java.sql.SQLException
      {
         var connection = source.getConnection();
         return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) ->
         {
            try
            {
               Object result = method.invoke(connection, args);
               if(method.getName().equals("prepareStatement") && args[0].toString().contains("FROM pg_class"))
               {
                  var statement = (java.sql.PreparedStatement) result;
                  return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class}, (statementProxy, call, values) ->
                  {
                     try
                     {
                        Object value = call.invoke(statement, values);
                        if(call.getName().equals("close"))
                        {
                           afterCatalog.run();
                        }
                        return value;
                     }
                     catch(java.lang.reflect.InvocationTargetException failure)
                     {
                        throw failure.getCause();
                     }
                  });
               }
               return result;
            }
            catch(java.lang.reflect.InvocationTargetException failure)
            {
               throw failure.getCause();
            }
         });
      }



      @Override
      public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException
      {
         throw new java.sql.SQLException("Synthetic wrapper only supports configured application connections");
      }



      @Override
      public java.io.PrintWriter getLogWriter()
      {
         return null;
      }



      @Override
      public void setLogWriter(java.io.PrintWriter writer)
      {
      }



      @Override
      public void setLoginTimeout(int seconds)
      {
      }



      @Override
      public int getLoginTimeout()
      {
         return 0;
      }



      @Override
      public java.util.logging.Logger getParentLogger()
      {
         return java.util.logging.Logger.getGlobal();
      }



      @Override
      public <T> T unwrap(Class<T> type) throws java.sql.SQLException
      {
         throw new java.sql.SQLException("Not wrapped");
      }



      @Override
      public boolean isWrapperFor(Class<?> type)
      {
         return false;
      }
   }
}
