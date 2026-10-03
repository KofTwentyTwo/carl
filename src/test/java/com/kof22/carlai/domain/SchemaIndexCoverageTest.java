/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** Real PostgreSQL proof (issue #14) that dashboard/report filter columns and
 ** every Carl foreign key are served by a leading, non-partial btree index.
 *******************************************************************************/
class SchemaIndexCoverageTest
{
   private static final List<String> FILTER_COLUMNS = List.of(
      "carl_transaction.effective_date",
      "carl_bill.due_date",
      "carl_bill.vendor_id",
      "carl_plan_step.plan_id",
      "carl_plan_step.assignee_id",
      "carl_grant.member_id",
      "carl_artifact_source.source_id",
      "carl_correction.record_id",
      "carl_client_workflow.requester_id",
      "carl_expense_settlement.expense_id",
      "carl_balance_source.import_id",
      "carl_record.created_at");

   private static final String LEADING_INDEX = """
      SELECT count(*) FROM pg_index i
      JOIN pg_class t ON t.oid=i.indrelid
      JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=i.indkey[0]
      WHERE t.relname=? AND a.attname=? AND i.indpred IS NULL""";

   private static final String UNINDEXED_FOREIGN_KEYS = """
      SELECT c.conrelid::regclass::text||'('||string_agg(a.attname,',' ORDER BY k.n)||')'
      FROM pg_constraint c
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum,n)
      JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum
      WHERE c.contype='f' AND c.conrelid::regclass::text LIKE 'carl\\_%'
      AND NOT EXISTS(SELECT 1 FROM pg_index i WHERE i.indrelid=c.conrelid AND i.indpred IS NULL
         AND (i.indkey::int2[])[0:cardinality(c.conkey)-1] @> c.conkey
         AND (i.indkey::int2[])[0:cardinality(c.conkey)-1] <@ c.conkey)
      GROUP BY c.oid,c.conrelid ORDER BY 1""";

   /*******************************************************************************
    **
    *******************************************************************************/
   @Test
   void primaryFiltersAndForeignKeysHaveLeadingIndexes() throws Exception
   {
      try(var database = new PostgreSQLContainer<>("postgres:16-alpine"))
      {
         database.start();
         var source = new PGSimpleDataSource();
         source.setURL(database.getJdbcUrl());
         source.setUser(database.getUsername());
         source.setPassword(database.getPassword());
         com.kof22.agentcore.store.AgentMigrations.migrate(source);
         try(var c = source.getConnection())
         {
            var unindexedFilters = new ArrayList<String>();
            for(String column : FILTER_COLUMNS)
            {
               int dot = column.indexOf('.');
               try(var statement = c.prepareStatement(LEADING_INDEX))
               {
                  statement.setString(1, column.substring(0, dot));
                  statement.setString(2, column.substring(dot + 1));
                  try(var rows = statement.executeQuery())
                  {
                     rows.next();
                     if(rows.getLong(1) == 0)
                     {
                        unindexedFilters.add(column);
                     }
                  }
               }
            }
            assertEquals(List.of(), unindexedFilters, "Dashboard/report filter columns need a leading index");

            var unindexedForeignKeys = new ArrayList<String>();
            try(var statement = c.createStatement(); var rows = statement.executeQuery(UNINDEXED_FOREIGN_KEYS))
            {
               while(rows.next())
               {
                  unindexedForeignKeys.add(rows.getString(1));
               }
            }
            assertEquals(List.of(), unindexedForeignKeys, "Every Carl foreign key needs an index led by its columns");
         }
      }
   }
}
