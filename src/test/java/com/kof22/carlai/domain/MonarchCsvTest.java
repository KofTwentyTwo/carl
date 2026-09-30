/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MonarchCsvTest
{
   @Test
   void boundedLargeExportPreservesOneHundredThousandOpaqueIdentities()
   {
      var csv = new StringBuilder("Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n");
      for(int row = 0; row < 100_000; row++)
      {
         csv.append("2026-09-01,Synthetic shop,Food,Checking,Synthetic,,-12.30,,,Reviewed,").append(100000000000000000L + row).append('\n');
      }
      var preview = MonarchCsv.transactions(csv.toString());
      assertTrue(preview.valid());
      assertEquals(100_000, preview.rows().size());
      assertEquals("100000000000099999", preview.rows().getLast().id());
      csv.append("2026-09-01,Synthetic shop,Food,Checking,Synthetic,,-12.30,,,Reviewed,100000000000100000\n");
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> MonarchCsv.transactions(csv.toString()));
   }



   @Test
   void preservesOpaqueIdsMultilineNotesAndUntrustedOwner()
   {
      var preview = MonarchCsv.transactions("Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-01,Synthetic shop,Food,Checking,Synthetic,\"line one\nline two\",-12.30,,admin,Reviewed,987654321012345678\n");
      assertTrue(preview.valid());
      assertEquals("987654321012345678", preview.rows().getFirst().id());
      assertEquals("line one\nline two", preview.rows().getFirst().notes());
      assertEquals("-12.30", preview.rows().getFirst().amount().toPlainString());
   }



   @Test
   void balanceConflictsNeverSumOrChooseFirst()
   {
      var preview = MonarchCsv.balances("Date,Balance,Account\n2026-09-01,12.00,Checking\n2026-09-01,13.00,Checking\n");
      assertFalse(preview.valid());
      assertTrue(preview.errors().getFirst().reason().contains("conflicting balances"));
   }



   @Test
   void exactBalanceDuplicatesAreIdempotent()
   {
      var preview = MonarchCsv.balances("Date,Balance,Account\n2026-09-01,12.00,Checking\n2026-09-01,12.00,Checking\n");
      assertTrue(preview.valid());
      assertEquals(1, preview.rows().size());
   }
}
