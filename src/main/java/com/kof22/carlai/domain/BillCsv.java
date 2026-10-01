
/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;


/** Validated, bounded CSV preview. Persistence is a separate human-requested operation. */
public final class BillCsv
{
   /*******************************************************************************
    * Validated bill fields with logical source row and explicit visibility.
    ******************************************************************************/
   public record Row(int line, String sourceId, String vendor, String description,
      BigDecimal amount, String currency, LocalDate dueDate, String status, String visibility)
   {
   }



   /*******************************************************************************
    * A row-level validation failure; no input is silently converted to zero.
    ******************************************************************************/
   public record Problem(int line, String reason)
   {
   }



   /*******************************************************************************
    * Immutable preview results; callers must reject errors before persistence.
    ******************************************************************************/
   public record Preview(String identity, List<Row> rows, List<Problem> errors)
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
         return errors.isEmpty() && !rows.isEmpty();
      }
   }

   private BillCsv()
   {
   }



   /*******************************************************************************
    * Validates bounded source input before any domain records are changed.
    ******************************************************************************/
   public static Preview preview(String source)
   {
      if(source == null || source.length() > 1_000_000 || source.indexOf('\0') >= 0)
      {
         throw new IllegalArgumentException("CSV must contain at most 1,000,000 characters without NUL");
      }
      var records = parse(source);
      if(records.isEmpty() || !records.getFirst().equals(List.of("source_id", "vendor", "description",
         "amount", "currency", "due_date", "status", "visibility")))
      {
         throw new IllegalArgumentException("CSV header must match the documented eight columns");
      }
      if(records.size() > 10_001)
      {
         throw new IllegalArgumentException("CSV allows at most 10,000 records");
      }
      var rows = new ArrayList<Row>();
      var errors = new ArrayList<Problem>();
      var identities = new HashSet<String>();
      for(int index = 1; index < records.size(); index++)
      {
         int line = index + 1;
         try
         {
            var columns = records.get(index);
            if(columns.size() != 8)
            {
               throw new IllegalArgumentException("Expected eight columns");
            }
            String vendor = required(columns.get(1), 200, "vendor");
            String description = required(columns.get(2), 2000, "description");
            String currency = required(columns.get(4), 3, "currency");
            int scale = Currency.getInstance(currency).getDefaultFractionDigits();
            if(scale < 0 || scale > 4)
            {
               throw new IllegalArgumentException("Unsupported currency precision");
            }
            BigDecimal amount = null;
            if(!columns.get(3).isBlank())
            {
               if(!columns.get(3).matches("[0-9]{1,14}(\\.[0-9]{1,4})?"))
               {
                  throw new IllegalArgumentException("Amount must be a nonnegative exact decimal");
               }
               amount = new BigDecimal(columns.get(3)).setScale(scale, java.math.RoundingMode.UNNECESSARY);
            }
            LocalDate date = columns.get(5).isBlank() ? null : LocalDate.parse(columns.get(5));
            String status = columns.get(6);
            if(!Set.of("UNPAID", "PAID_ASSERTED", "DISPUTED", "UNKNOWN").contains(status))
            {
               throw new IllegalArgumentException("Status must be UNPAID, PAID_ASSERTED, DISPUTED, or UNKNOWN");
            }
            String visibility = columns.get(7);
            if(!Set.of("PRIVATE", "FAMILY").contains(visibility))
            {
               throw new IllegalArgumentException("Visibility must be PRIVATE or FAMILY");
            }
            String identity = columns.getFirst().isBlank()
               ? "fallback:" + hash(String.join("\u001f", vendor, description,
                  amount == null ? "" : amount.toPlainString(), currency, date == null ? "" : date.toString()))
               : "source:" + required(columns.getFirst(), 200, "source_id");
            if(!identities.add(identity))
            {
               throw new IllegalArgumentException("Ambiguous duplicate source identity; review before importing");
            }
            rows.add(new Row(line, identity, vendor, description, amount, currency, date, status, visibility));
         }
         catch(RuntimeException invalid)
         {
            String message = invalid instanceof IllegalArgumentException && !(invalid instanceof NumberFormatException)
               ? invalid.getMessage()
               : "Invalid amount or date; use exact decimal and YYYY-MM-DD";
            errors.add(new Problem(line, message));
         }
      }
      return new Preview(hash(source), rows, errors);
   }



   /*******************************************************************************
    * Computes a stable content identity without interpreting source instructions.
    ******************************************************************************/
   public static String hash(String value)
   {
      try
      {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
      }
      catch(NoSuchAlgorithmException impossible)
      {
         throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
   }



   private static String required(String value, int maximum, String field)
   {
      if(value.isBlank() || value.length() > maximum)
      {
         throw new IllegalArgumentException(field + " is required and exceeds no more than " + maximum + " characters");
      }
      return value;
   }



   static List<List<String>> parse(String text)
   {
      var records = new ArrayList<List<String>>();
      var row = new ArrayList<String>();
      var value = new StringBuilder();
      boolean quoted = false;
      boolean closed = false;
      for(int index = 0; index < text.length(); index++)
      {
         char c = text.charAt(index);
         if(quoted)
         {
            if(c == '"')
            {
               if(index + 1 < text.length() && text.charAt(index + 1) == '"')
               {
                  value.append('"');
                  index++;
               }
               else
               {
                  quoted = false;
                  closed = true;
               }
            }
            else
            {
               value.append(c);
            }
         }
         else if(c == ',' || c == '\r' || c == '\n')
         {
            row.add(value.toString());
            value.setLength(0);
            closed = false;
            if(c != ',')
            {
               records.add(List.copyOf(row));
               row.clear();
               if(c == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n')
               {
                  index++;
               }
            }
         }
         else if(c == '"' && value.isEmpty() && !closed)
         {
            quoted = true;
         }
         else if(closed || c == '"')
         {
            throw new IllegalArgumentException("Malformed CSV quoting");
         }
         else
         {
            value.append(c);
         }
      }
      if(quoted)
      {
         throw new IllegalArgumentException("Unterminated CSV quoted field");
      }
      if(!value.isEmpty() || !row.isEmpty() || closed)
      {
         row.add(value.toString());
         records.add(List.copyOf(row));
      }
      return records;
   }
}
