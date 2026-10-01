/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.List;

import com.kingsrook.qqq.backend.core.actions.values.QValueFormatter;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlMoneyDisplayTest
{
   @Test
   void nativeReportFactsDisplayLeavesTheStoredMachineSchemaNumeric()
   {
      var table = CarlMetadata.table("reportTest", "Report", "unused", "facts:T");
      String raw = "{\"currency\":\"USD\",\"amount\":8100.00,\"annualRate\":0.24,\"id\":8100}";
      var record = new QRecord().withValue("facts", raw);
      QContext.init(null, null);
      try
      {
         QValueFormatter.setDisplayValuesInRecords(table, List.of(record));
         assertTrue(record.getDisplayValue("facts").contains("$8,100.00 USD"));
         assertTrue(record.getDisplayValue("facts").contains("0.24"));
         assertEquals(raw, record.getValue("facts"));
      }
      finally
      {
         QContext.clear();
      }
   }



   @Test
   void nativeDisplayValuesUseEachRecordsCurrencyAndPreserveExactNumericSortingValues()
   {
      var table = CarlMetadata.table("moneyTest", "Money", "unused", "currency:S,amount:M,ownership_share:R");
      var usd = new QRecord().withValue("currency", "USD").withValue("amount", new BigDecimal("8100.0000")).withValue("ownership_share", new BigDecimal("0.5"));
      var euro = new QRecord().withValue("currency", "EUR").withValue("amount", new BigDecimal("-8100.25"));
      var yen = new QRecord().withValue("currency", "JPY").withValue("amount", new BigDecimal("8100"));
      var precise = new QRecord().withValue("currency", "KWD").withValue("amount", new BigDecimal("8100.125"));
      var missing = new QRecord().withValue("currency", "USD");
      var unknownCurrency = new QRecord().withValue("amount", new BigDecimal("8100.00"));
      QContext.init(null, null);
      try
      {
         QValueFormatter.setDisplayValuesInRecords(table, List.of(usd, euro, yen, precise, missing, unknownCurrency));
         assertEquals("$8,100.00 USD", usd.getDisplayValue("amount"));
         assertEquals("-€8,100.25 EUR", euro.getDisplayValue("amount"));
         assertEquals("¥8,100 JPY", yen.getDisplayValue("amount"));
         assertEquals("KWD 8,100.125", precise.getDisplayValue("amount"));
         assertEquals("Not supplied", missing.getDisplayValue("amount"));
         assertEquals("8,100.00 — currency not supplied", unknownCurrency.getDisplayValue("amount"));
         assertEquals(new BigDecimal("8100.0000"), usd.getValue("amount"));
         assertEquals(QFieldType.DECIMAL, table.getField("ownership_share").getType());
         assertFalse(usd.getDisplayValue("ownership_share").contains("$"));
         assertEquals("0.5", usd.getDisplayValue("ownership_share"));
      }
      finally
      {
         QContext.clear();
      }
   }
}
