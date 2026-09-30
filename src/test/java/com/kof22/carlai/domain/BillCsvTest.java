
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BillCsvTest
{
   static final String HEADER = "source_id,vendor,description,amount,currency,due_date,status,visibility\r\n";

   @Test
   void exactMoneyMissingDataAndQuotedEvidence()
   {
      var preview = BillCsv.preview(HEADER + "one,Example Energy,\"Power, monthly\",125.25,USD,2026-10-01,UNPAID,PRIVATE\r\n"
         + "two,Example Water,Water,74.75,USD,2026-10-02,UNPAID,FAMILY\r\n"
         + "three,Example Vendor,Unknown,,EUR,,UNKNOWN,PRIVATE\r\n");
      assertTrue(preview.valid());
      assertEquals(new BigDecimal("200.00"), preview.rows().get(0).amount().add(preview.rows().get(1).amount()));
      assertEquals("Power, monthly", preview.rows().getFirst().description());
      assertNull(preview.rows().get(2).amount());
      assertNull(preview.rows().get(2).dueDate());
      assertEquals(preview.identity(), BillCsv.preview(HEADER + "one,Example Energy,\"Power, monthly\",125.25,USD,2026-10-01,UNPAID,PRIVATE\r\n"
         + "two,Example Water,Water,74.75,USD,2026-10-02,UNPAID,FAMILY\r\n"
         + "three,Example Vendor,Unknown,,EUR,,UNKNOWN,PRIVATE\r\n").identity());
   }



   @Test
   void invalidRowsAreExplicitAndBatchCannotCommit()
   {
      var preview = BillCsv.preview(HEADER + "one,V,Desc,NaN,USD,2026-10-01,UNPAID,PRIVATE\n"
         + "two,V,Desc,1.234,USD,2026-02-30,PAID,FAMILY\n");
      assertFalse(preview.valid());
      assertEquals(2, preview.errors().size());
      assertEquals(2, preview.errors().getFirst().line());
      assertTrue(preview.rows().isEmpty());
   }



   @Test
   void duplicateSourceIdsMalformedQuotesAndOversizedInputsReject()
   {
      assertFalse(BillCsv.preview(HEADER + "one,V,D,1,USD,,UNKNOWN,PRIVATE\none,V,D,2,USD,,UNKNOWN,PRIVATE\n").valid());
      assertThrows(IllegalArgumentException.class, () -> BillCsv.preview(HEADER + "\"unterminated"));
      assertThrows(IllegalArgumentException.class, () -> BillCsv.preview("x".repeat(1_000_001)));
      assertThrows(IllegalArgumentException.class, () -> BillCsv.preview("wrong,header\n"));
   }
}
