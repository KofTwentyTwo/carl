/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


/** Bounded local parsing; exported owner labels and review flags never confer authority. */
public final class MonarchCsv
{
   /*******************************************************************************
    * One immutable Monarch source transaction; opaque IDs and owner labels remain data.
    ******************************************************************************/
   public record TransactionRow(int row, String id, LocalDate date, String merchant, String category, String account,
      String statement, String notes, BigDecimal amount, String tags, String owner, String reviewed, String identity)
   {
   }



   /*******************************************************************************
    * A daily observation without an inferred intraday timestamp or currency.
    ******************************************************************************/
   public record BalanceRow(int row, LocalDate date, BigDecimal balance, String account)
   {
   }



   /*******************************************************************************
    * Immutable preview results; callers must reject errors before persistence.
    ******************************************************************************/
   public record Preview<T>(String contentIdentity, List<T> rows, List<BillCsv.Problem> errors)
   {
      /*******************************************************************************
       * Immutable preview results; callers must reject errors before persistence.
       ******************************************************************************/
      public Preview
      {
         rows = List.copyOf(rows);
         errors = List.copyOf(errors);
      }



      /*******************************************************************************
       * Reports whether all supplied rows are valid and at least one remains.
       ******************************************************************************/
      public boolean valid()
      {
         return !rows.isEmpty() && errors.isEmpty();
      }
   }
   private MonarchCsv()
   {
   }



   /*******************************************************************************
    * Parses bounded multiline exports and rejects conflicting opaque source identities.
    ******************************************************************************/
   public static Preview<TransactionRow> transactions(String csv)
   {
      var records = records(csv, List.of("Date", "Merchant", "Category", "Account", "Original Statement", "Notes", "Amount", "Tags", "Owner", "Reviewed", "Id"));
      var rows = new ArrayList<TransactionRow>();
      var errors = new ArrayList<BillCsv.Problem>();
      var ids = new HashMap<String, String>();
      for(int i = 1; i < records.size(); i++)
      {
         try
         {
            var fields = records.get(i);
            width(fields, 11);
            String id = required(fields.get(10), 200, "Id");
            String identity = BillCsv.hash(String.join("\u001f", fields));
            String prior = ids.putIfAbsent(id, identity);
            if(prior != null)
            {
               if(!prior.equals(identity))
               {
                  throw new IllegalArgumentException("Conflicting rows for the same transaction Id");
               }
               continue;
            }
            rows.add(new TransactionRow(i + 1, id, LocalDate.parse(fields.getFirst()), required(fields.get(1), 2000, "Merchant"), fields.get(2),
               required(fields.get(3), 1000, "Account"), fields.get(4), fields.get(5), decimal(fields.get(6)), fields.get(7), fields.get(8), fields.get(9), identity));
         }
         catch(RuntimeException invalid)
         {
            errors.add(new BillCsv.Problem(i + 1, "Invalid transaction: " + invalid.getMessage()));
         }
      }
      return new Preview<>(BillCsv.hash(csv), rows, errors);
   }



   /*******************************************************************************
    * Rejects conflicting account-label/date observations instead of summing or choosing one.
    ******************************************************************************/
   public static Preview<BalanceRow> balances(String csv)
   {
      var records = records(csv, List.of("Date", "Balance", "Account"));
      var rows = new ArrayList<BalanceRow>();
      var errors = new ArrayList<BillCsv.Problem>();
      var keys = new HashMap<String, BigDecimal>();
      for(int i = 1; i < records.size(); i++)
      {
         try
         {
            var fields = records.get(i);
            width(fields, 3);
            LocalDate date = LocalDate.parse(fields.getFirst());
            String account = required(fields.get(2), 1000, "Account");
            BigDecimal value = decimal(fields.get(1));
            BigDecimal prior = keys.putIfAbsent(date + "\u001f" + account, value);
            if(prior != null)
            {
               if(prior.compareTo(value) != 0)
               {
                  throw new IllegalArgumentException("Ambiguous account label/date has conflicting balances; explicit source disambiguation required");
               }
               continue;
            }
            rows.add(new BalanceRow(i + 1, date, value, account));
         }
         catch(RuntimeException invalid)
         {
            errors.add(new BillCsv.Problem(i + 1, "Invalid balance: " + invalid.getMessage()));
         }
      }
      return new Preview<>(BillCsv.hash(csv), rows, errors);
   }



   private static List<List<String>> records(String csv, List<String> header)
   {
      if(csv == null || csv.length() > 20_000_000 || csv.indexOf('\0') >= 0)
      {
         throw new IllegalArgumentException("CSV must be at most20 million characters without NUL");
      }
      var rows = BillCsv.parse(csv.startsWith("\ufeff") ? csv.substring(1) : csv);
      if(rows.isEmpty() || !rows.getFirst().equals(header))
      {
         throw new IllegalArgumentException("Expected exact documented Monarch CSV header");
      }
      if(rows.size() > 100_001)
      {
         throw new IllegalArgumentException("Maximum100,000 data rows per preview");
      }
      return rows;
   }



   private static BigDecimal decimal(String text)
   {
      if(!text.matches("-?[0-9]{1,14}(\\.[0-9]{1,2})?"))
      {
         throw new IllegalArgumentException("Amount must be a signed exact decimal with at most two fractional digits");
      }
      return new BigDecimal(text).setScale(2);
   }



   private static void width(List<String> row, int expected)
   {
      if(row.size() != expected || row.stream().anyMatch(field -> field.length() > 20000))
      {
         throw new IllegalArgumentException("Wrong column count or oversized field");
      }
   }



   private static String required(String value, int max, String field)
   {
      if(value.isBlank() || value.length() > max)
      {
         throw new IllegalArgumentException(field + " missing or too long");
      }
      return value;
   }
}
