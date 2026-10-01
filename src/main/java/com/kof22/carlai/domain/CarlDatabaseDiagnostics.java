/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/** Bounded read-only storage diagnostics require current household-management authority. */
public final class CarlDatabaseDiagnostics
{
   private final CarlService service;

   /** Uses the same authoritative application database and current domain access rules. */
   public CarlDatabaseDiagnostics(CarlService service)
   {
      this.service = Objects.requireNonNull(service);
   }

   /** Safe connection information deliberately contains no JDBC URL, host, credentials or row counts. */
   public record Snapshot(String connectionLabel, String database, String schema, List<TableSize> tables, boolean truncated)
   {
      /** Retains an immutable bounded table snapshot. */
      public Snapshot
      {
         tables = List.copyOf(tables);
      }
   }



   /** PostgreSQL reports physical storage in bytes, not financial records or activity. */
   public record TableSize(String schema, String table, long tableBytes, long indexBytes, long totalBytes)
   {
   }

   /** The native interface separately requires verified ADMIN RBAC; an owner label grants no authority. */
   public Snapshot read(String principal)
   {
      return service.transaction(c ->
      {
         boundedRead(c);
         var member = CarlService.manager(c, principal, "SETTINGS");
         NativeReadScope.check(member);
         var connection = CarlService.rows(c, "SELECT current_database() AS database,current_schema() AS schema").getFirst();
         var found = CarlService.rows(c, "SELECT n.nspname AS schema,c.relname AS table,pg_relation_size(c.oid) AS table_bytes,pg_indexes_size(c.oid) AS index_bytes,pg_total_relation_size(c.oid) AS total_bytes FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relkind IN ('r','p') ORDER BY n.nspname,c.relname LIMIT 201");
         var tables = new ArrayList<TableSize>();
         for(var row : found.subList(0, Math.min(200, found.size())))
         {
            tables.add(new TableSize((String) row.get("schema"), (String) row.get("table"), CarlService.number(row, "table_bytes"), CarlService.number(row, "index_bytes"), CarlService.number(row, "total_bytes")));
         }
         if(!member.equals(CarlService.manager(c, principal, "SETTINGS")))
         {
            throw new SecurityException("Carl access changed during this request");
         }
         return new Snapshot("Carl PostgreSQL", (String) connection.get("database"), (String) connection.get("schema"), tables, found.size() > 200);
      });
   }



   private static void boundedRead(Connection connection) throws SQLException
   {
      try(var statement = connection.createStatement())
      {
         statement.setQueryTimeout(2);
         statement.execute("SET TRANSACTION READ ONLY");
         statement.execute("SET LOCAL statement_timeout = '2s'");
      }
   }
}
