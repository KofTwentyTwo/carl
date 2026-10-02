/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;


/** Bounded, dated, caller-owned CSV snapshots. CSV text is inert display data, not raw source evidence. */
public final class TableExports
{
   public static final String TEXT_POLICY = "SPREADSHEET_SAFE_TEXT_PREFIX_V1";
   public static final String LIMITATION = "Dated authorized snapshot; not current records or proof of household completeness. Text starting with spreadsheet formula/control prefixes receives a leading apostrophe in this CSV only; numbers, dates and stored original evidence are unchanged.";
   public static final Map<String, String> SOURCES = Map.ofEntries(
      Map.entry("carlAccounts", "carl_account_view"),
      Map.entry("carlArtifacts", "carl_artifact_view"),
      Map.entry("carlBalanceSources", "carl_balance_selection_view"),
      Map.entry("carlBills", "carl_bill_view"),
      Map.entry("carlBudgets", "carl_budget_view"),
      Map.entry("carlCalendar", "carl_calendar_view"),
      Map.entry("carlCalendarConnections", "carl_calendar_connection_view"),
      Map.entry("carlCalendarOperations", "carl_native_calendar_operation_view"),
      Map.entry("carlCashPlans", "carl_cash_plan_view"),
      Map.entry("carlDocuments", "carl_document_view"),
      Map.entry("carlDraftVersions", "carl_draft_revision_view"),
      Map.entry("carlExpenseActuals", "carl_expense_actual_view"),
      Map.entry("carlExpenseSettlements", "carl_expense_settlement_view"),
      Map.entry("carlExpenses", "carl_expense_view"),
      Map.entry("carlFinancialGoals", "carl_financial_goal_view"),
      Map.entry("carlFinancingOffers", "carl_financing_offer_view"),
      Map.entry("carlHomeHistory", "carl_home_history_view"),
      Map.entry("carlHomes", "carl_home_view"),
      Map.entry("carlImportReviews", "carl_import_review_view"),
      Map.entry("carlManualTransactions", "carl_manual_transaction_view"),
      Map.entry("carlPlanEffects", "carl_plan_effect_view"),
      Map.entry("carlPlanSteps", "carl_native_plan_step_view"),
      Map.entry("carlPlans", "carl_plan_view"),
      Map.entry("carlPortfolioMoves", "carl_portfolio_move_view"),
      Map.entry("carlPreferences", "carl_preference_view"),
      Map.entry("carlProperties", "carl_rental_property_view"),
      Map.entry("carlReminderObservations", "carl_reminder_observation_view"),
      Map.entry("carlRentApplications", "carl_rent_application_view"),
      Map.entry("carlRentDues", "carl_rent_due_view"),
      Map.entry("carlRentalBaselines", "carl_rental_baseline_selection_view"),
      Map.entry("carlRentalReviewComponents", "carl_rental_review_component_view"),
      Map.entry("carlRentalReviewShares", "carl_rental_review_share_view"),
      Map.entry("carlRentalReviews", "carl_rental_review_view"),
      Map.entry("carlRentalSources", "carl_rental_source_view"),
      Map.entry("carlRentalStress", "carl_rental_shock_view"),
      Map.entry("carlRentalUnits", "carl_rental_unit_view"),
      Map.entry("carlTax", "carl_tax_view"),
      Map.entry("carlTaxAlternatives", "carl_tax_alternative_view"),
      Map.entry("carlTaxProperties", "carl_tax_property_view"),
      Map.entry("carlTaxReferences", "carl_tax_reference_view"),
      Map.entry("carlTransactions", "carl_transaction_view"),
      Map.entry("carlVendors", "carl_vendor_view"),
      Map.entry("carlWork", "carl_work_view"));
   private static final int MAX_ROWS = 50_000;
   private static final int MAX_BYTES = 20_000_000;
   private static final Set<String> PRIVATE_FIELDS = Set.of("principal", "original_content", "extracted_text", "content", "contents", "request_id", "requester_id", "permission_revision", "input_digest", "preview_token", "token", "upload_reference", "storage_reference", "reference", "digest", "transfer_key");
   private final CarlService service;
   private final Map<String, List<Column>> columns;
   private final Clock clock;

   /** A server-owned metadata column; textual cells use the documented inert spreadsheet representation. */
   public record Column(String name, boolean textual)
   {
   }



   /** Humans choose checked records or every record currently authorized in the fixed source view. */
   public enum Scope
   {
      SELECTED, ALL_AUTHORIZED
   }



   private record Snapshot(CarlService.Member actor, long revision, List<Map<String, Object>> rows, List<String> ids, String digest)
   {
   }

   /** Consumes only trusted metadata specs for the fixed application view allowlist. */
   public TableExports(CarlService service, Map<String, List<Column>> columns)
   {
      this(service, columns, Clock.systemUTC());
   }



   /** A clock supplies the explicit snapshot timestamp; source dates are never inferred. */
   public TableExports(CarlService service, Map<String, List<Column>> columns, Clock clock)
   {
      this.service = Objects.requireNonNull(service);
      this.clock = Objects.requireNonNull(clock);
      var configured = new LinkedHashMap<String, List<Column>>();
      for(var source : columns.entrySet())
      {
         if(!SOURCES.containsKey(source.getKey()) || source.getValue().isEmpty() || source.getValue().stream().map(Column::name).distinct().count() != source.getValue().size()
            || source.getValue().stream().anyMatch(column -> !exportable(column.name())) || source.getValue().stream().noneMatch(column -> column.name().equals("id")))
         {
            throw new IllegalArgumentException("Only fixed source views and safe server-owned columns may be exported");
         }
         configured.put(source.getKey(), List.copyOf(source.getValue()));
      }
      this.columns = Map.copyOf(configured);
   }



   /** Trusted metadata may expose only domain fields, never security identities, storage or internal tokens. */
   public static boolean exportable(String field)
   {
      return field != null && field.matches("[a-z][a-z0-9_]{0,99}") && !PRIVATE_FIELDS.contains(field) && !field.endsWith("_token") && !field.contains("password") && !field.contains("secret");
   }



   /** Checks the exact native checkbox set; no inaccessible row is silently skipped. */
   public List<String> selection(String principal, String table, List<String> selected)
   {
      var ids = canonical(selected, 1000);
      source(table);
      return service.transaction(c ->
      {
         var actor = sourceActor(c, principal, table);
         if(!ids.isEmpty())
         {
            requireIds(c, principal, table, ids);
         }
         if(!actor.equals(CarlService.member(c, principal)))
         {
            throw new SecurityException("Export access changed");
         }
         return ids;
      });
   }



   /** Materializes all bounded bytes, then locks the household and rechecks the complete source before publication. */
   public UUID generate(String principal, UUID request, String table, Scope scope, List<String> selected)
   {
      Objects.requireNonNull(request);
      Objects.requireNonNull(scope);
      source(table);
      var ids = canonical(selected, 1000);
      if(scope == Scope.SELECTED && ids.isEmpty())
      {
         throw new IllegalArgumentException("SELECTED export requires checked records");
      }
      String inputDigest = BillCsv.hash(CarlService.json(List.of(table, scope, ids, columns.get(table), TEXT_POLICY)));
      var frozen = service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         var prior = CarlService.rows(c, "SELECT requester_id,input_digest FROM carl_table_export WHERE id=?", request);
         if(!prior.isEmpty())
         {
            if(CarlService.number(prior.getFirst(), "requester_id") != actor.id() || !inputDigest.equals(prior.getFirst().get("input_digest")))
            {
               throw new IllegalArgumentException("Export UUID conflicts with previous input");
            }
            checked(c, principal, request);
            return null;
         }
         if(!ids.isEmpty())
         {
            requireIds(c, principal, table, ids);
         }
         return snapshot(c, principal, table, scope == Scope.SELECTED ? ids : null);
      });
      if(frozen == null)
      {
         return request;
      }
      Instant asOf = clock.instant();
      byte[] content = csv(table, scope, asOf, frozen);
      service.transaction(c ->
      {
         var initial = CarlService.member(c, principal);
         if(initial.householdId() != frozen.actor().householdId())
         {
            throw new SecurityException("Export access changed during generation");
         }
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
         var current = snapshot(c, principal, table, scope == Scope.SELECTED ? ids : null);
         if(!current.actor().equals(frozen.actor()) || !current.digest().equals(frozen.digest()))
         {
            throw new SecurityException("Export source or access changed during generation; retry with current records");
         }
         CarlService.execute(c, "INSERT INTO carl_table_export(id,requester_id,household_id,permission_revision,source_table,selection_scope,input_digest,source_digest,source_ids,source_revision,as_of,row_count,content,content_hash,text_policy) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING", request, current.actor().id(), current.actor().householdId(), current.actor().permissionRevision(), table, scope.name(), inputDigest, current.digest(), c.createArrayOf("text", current.ids().toArray()), current.revision(), java.sql.Timestamp.from(asOf), current.rows().size(), content, hash(content), TEXT_POLICY);
         var stored = checked(c, principal, request);
         if(!inputDigest.equals(stored.get("input_digest")))
         {
            throw new IllegalArgumentException("Export UUID conflicts with previous input");
         }
         return null;
      });
      return request;
   }



   /** Freshly checks the dated export's exact current source set before any original CSV bytes are returned. */
   public byte[] load(String principal, UUID export)
   {
      return service.transaction(c ->
      {
         var actor = CarlService.member(c, principal);
         byte[] content = (byte[]) checked(c, principal, export).get("content");
         NativeReadScope.check(actor);
         return content;
      });
   }



   /** A current-access manifest provides provenance without exposing CSV content or internal identities. */
   public Map<String, Object> manifest(String principal, UUID export)
   {
      return service.transaction(c ->
      {
         var row = checked(c, principal, export);
         return Map.of("id", export.toString(), "sourceTable", row.get("source_table"), "scope", row.get("selection_scope"), "asOf", row.get("as_of"), "rowCount", row.get("row_count"), "byteCount", ((byte[]) row.get("content")).length, "sha256", row.get("content_hash"), "textPolicy", TEXT_POLICY, "limitation", LIMITATION);
      });
   }



   private Map<String, Object> checked(Connection c, String principal, UUID export) throws SQLException
   {
      var initial = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
      var actor = CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM carl_table_export WHERE id=? AND requester_id=? AND household_id=? AND permission_revision=?", export, actor.id(), actor.householdId(), actor.permissionRevision());
      if(rows.size() != 1)
      {
         throw new SecurityException("Table export unavailable");
      }
      var row = rows.getFirst();
      String table = row.get("source_table").toString();
      source(table);
      var savedIds = List.of((String[]) ((java.sql.Array) row.get("source_ids")).getArray());
      var current = snapshot(c, principal, table, savedIds);
      if(current.rows().size() != CarlService.number(row, "row_count") || !current.digest().equals(row.get("source_digest")))
      {
         throw new SecurityException("Export source changed or is unavailable; generate a new dated snapshot");
      }
      return row;
   }



   private Snapshot snapshot(Connection c, String principal, String table, List<String> ids) throws SQLException
   {
      var actor = sourceActor(c, principal, table);
      String projection = columns.get(table).stream().map(Column::name).collect(java.util.stream.Collectors.joining(","));
      String sql = "SELECT " + projection + " FROM " + source(table) + " WHERE principal=?" + (ids == null ? "" : " AND id::text=ANY(?)") + " ORDER BY id LIMIT 50001";
      var rows = new ArrayList<Map<String, Object>>();
      var foundIds = new ArrayList<String>();
      var digest = sha256();
      int sourceBytes = 0;
      try(var statement = c.prepareStatement(sql))
      {
         statement.setQueryTimeout(30);
         statement.setFetchSize(1000);
         statement.setString(1, principal);
         if(ids != null)
         {
            statement.setArray(2, c.createArrayOf("text", ids.toArray()));
         }
         try(var result = statement.executeQuery())
         {
            while(result.next())
            {
               if(rows.size() >= MAX_ROWS)
               {
                  throw new IllegalArgumentException("Export exceeds50000 rows; select a smaller checked set");
               }
               var row = new LinkedHashMap<String, Object>();
               for(var column : columns.get(table))
               {
                  Object value = result.getObject(column.name());
                  if(value instanceof java.sql.Timestamp timestamp)
                  {
                     value = timestamp.toInstant().toString();
                  }
                  else if(value instanceof java.sql.Date || value instanceof UUID)
                  {
                     value = value.toString();
                  }
                  row.put(column.name(), value);
               }
               byte[] encoded = CarlService.json(row).getBytes(StandardCharsets.UTF_8);
               sourceBytes += encoded.length;
               if(sourceBytes > MAX_BYTES)
               {
                  throw new IllegalArgumentException("Export source exceeds20 MB; select a smaller checked set");
               }
               digest.update(encoded);
               digest.update((byte) '\n');
               rows.add(row);
               foundIds.add(row.get("id").toString());
            }
         }
      }
      if(ids != null && !Set.copyOf(foundIds).equals(Set.copyOf(ids)))
      {
         throw new SecurityException("Selected export records are unavailable");
      }
      if(!actor.equals(CarlService.member(c, principal)))
      {
         throw new SecurityException("Export access changed during source reading");
      }
      long revision = CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_household WHERE id=?", actor.householdId()).getFirst(), "revision");
      return new Snapshot(actor, revision, rows, foundIds, HexFormat.of().formatHex(digest.digest()));
   }



   private void requireIds(Connection c, String principal, String table, List<String> ids) throws SQLException
   {
      try(var statement = c.prepareStatement("SELECT id::text AS id FROM " + source(table) + " WHERE principal=? AND id::text=ANY(?)"))
      {
         statement.setQueryTimeout(30);
         statement.setString(1, principal);
         statement.setArray(2, c.createArrayOf("text", ids.toArray()));
         var found = new java.util.HashSet<String>();
         try(var result = statement.executeQuery())
         {
            while(result.next())
            {
               found.add(result.getString("id"));
            }
         }
         if(!found.equals(Set.copyOf(ids)))
         {
            throw new SecurityException("Selected export records are unavailable");
         }
      }
   }



   private static CarlService.Member sourceActor(Connection c, String principal, String table) throws SQLException
   {
      // Import history is owner-only rather than carl_access-backed; preserve its workflow's FINANCE gate. //
      return table.equals("carlImportReviews") ? CarlService.manager(c, principal, "FINANCE") : CarlService.member(c, principal);
   }



   private String source(String table)
   {
      if(!columns.containsKey(table))
      {
         throw new IllegalArgumentException("Export source is not configured");
      }
      return SOURCES.get(table);
   }



   private static List<String> canonical(List<String> selected, int maximum)
   {
      Objects.requireNonNull(selected);
      if(selected.size() > maximum || selected.stream().distinct().count() != selected.size() || selected.stream().anyMatch(id -> id == null || !(id.matches("[1-9][0-9]{0,17}") || uuid(id))))
      {
         throw new IllegalArgumentException("Choose at most1000 unique canonical checkbox IDs");
      }
      return selected.stream().sorted().toList();
   }



   private static boolean uuid(String value)
   {
      try
      {
         return UUID.fromString(value).toString().equals(value);
      }
      catch(IllegalArgumentException invalid)
      {
         return false;
      }
   }



   private byte[] csv(String table, Scope scope, Instant asOf, Snapshot snapshot)
   {
      var bytes = new BoundedOutput();
      try(var writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8); var csv = CSVFormat.RFC4180.print(writer))
      {
         var header = new ArrayList<String>(List.of("_export_row_type", "_export_as_of", "_export_source", "_export_scope", "_export_text_policy", "_export_source_digest"));
         header.addAll(columns.get(table).stream().map(Column::name).toList());
         csv.printRecord(header);
         var metadata = new ArrayList<Object>(List.of("EXPORT_METADATA", asOf.toString(), table, scope.name(), TEXT_POLICY, snapshot.digest()));
         columns.get(table).forEach(column -> metadata.add(""));
         csv.printRecord(metadata);
         for(var row : snapshot.rows())
         {
            var values = new ArrayList<Object>(List.of("RECORD", asOf.toString(), table, scope.name(), TEXT_POLICY, snapshot.digest()));
            for(var column : columns.get(table))
            {
               Object value = row.get(column.name());
               String text = value == null ? "" : value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
               values.add(column.textual() ? inert(text) : text);
            }
            csv.printRecord(values);
         }
      }
      catch(IOException failure)
      {
         throw new IllegalArgumentException("CSV export exceeds20 MB or cannot be materialized", failure);
      }
      return bytes.content.toByteArray();
   }



   private static String inert(String text)
   {
      String stripped = text.stripLeading();
      boolean formula = !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0;
      return formula || !text.isEmpty() && Character.isISOControl(text.charAt(0)) ? "'" + text : text;
   }



   private static String hash(byte[] content)
   {
      return HexFormat.of().formatHex(sha256().digest(content));
   }



   private static MessageDigest sha256()
   {
      try
      {
         return MessageDigest.getInstance("SHA-256");
      }
      catch(NoSuchAlgorithmException impossible)
      {
         throw new IllegalStateException("SHA256 unavailable", impossible);
      }
   }

   private static final class BoundedOutput extends OutputStream
   {
      private final ByteArrayOutputStream content = new ByteArrayOutputStream();
      @Override
      public void write(int value) throws IOException
      {
         bound(1);
         content.write(value);
      }



      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException
      {
         bound(length);
         content.write(bytes, offset, length);
      }



      private void bound(int length) throws IOException
      {
         if((long) content.size() + length > MAX_BYTES)
         {
            throw new IOException("CSV byte bound exceeded");
         }
      }
   }
}
