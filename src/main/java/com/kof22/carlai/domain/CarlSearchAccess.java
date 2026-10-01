/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.kof22.agentadmin.qbits.AuthoritativeSearchAccess;


/** Index candidates always rehydrate through current authoritative caller-filtered Carl views. */
public final class CarlSearchAccess implements AuthoritativeSearchAccess
{
   /** Fixed developer-owned tables; no table/SQL expression is supplied by prompts or events. */
   public static final Map<String, String> TABLES = Map.of("carlBills", "carl_bill_view", "carlVendors", "carl_vendor_view", "carlWork", "carl_work_view", "carlAccounts", "carl_account_view", "carlTransactions", "carl_transaction_view", "carlBudgets", "carl_budget_view", "carlProperties", "carl_rental_property_view", "carlCalendar", "carl_calendar_view");
   private final CarlService service;
   private final Supplier<String> principal;

   /** The native composition supplies verified QContext identity, never an HTTP parameter. */
   public CarlSearchAccess(CarlService service, Supplier<String> principal)
   {
      this.service = java.util.Objects.requireNonNull(service);
      this.principal = java.util.Objects.requireNonNull(principal);
   }



   private static String view(String table)
   {
      String view = TABLES.get(table);
      if(view == null)
      {
         throw new IllegalArgumentException("Unconfigured Carl search table");
      }
      return view;
   }



   @Override
   public Set<String> allowedRecordIds(String table)
   {
      String view = view(table);
      String caller = principal.get();
      var member = service.member(caller);
      NativeReadScope.check(member);
      var rows = service.transaction(c -> CarlService.rows(c, "SELECT id FROM " + view + " WHERE principal=? ORDER BY id LIMIT 50001", caller));
      if(rows.size() > 50000)
      {
         throw new IllegalArgumentException("Carl search scope exceeds 50000 records; narrow authoritative scope");
      }
      if(!member.equals(service.member(caller)))
      {
         throw new SecurityException("Carl search access changed");
      }
      NativeReadScope.check(service.member(caller));
      return rows.stream().map(row -> row.get("id").toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
   }



   @Override
   public Optional<SafeRecord> resolve(String table, String id)
   {
      String view = view(table);
      if(id == null || !id.matches("[1-9][0-9]{0,17}"))
      {
         return Optional.empty();
      }
      String caller = principal.get();
      var member = service.member(caller);
      NativeReadScope.check(member);
      var rows = service.transaction(c -> CarlService.rows(c, "SELECT title FROM " + view + " WHERE principal=? AND id=?", caller, Long.parseLong(id)));
      if(!member.equals(service.member(caller)))
      {
         throw new SecurityException("Carl search access changed");
      }
      NativeReadScope.check(service.member(caller));
      return rows.isEmpty() ? Optional.empty() : Optional.of(new SafeRecord(rows.getFirst().get("title").toString(), rows.getFirst().get("title").toString()));
   }



   /** Internal fixed indexing bridge returns a bounded deduplicated title-only page. */
   public List<Map<String, Object>> indexPage(String table, long after)
   {
      String view = view(table);
      if(after < 0)
      {
         throw new IllegalArgumentException("Canonical index cursor required");
      }
      return service.transaction(c -> CarlService.rows(c, "SELECT DISTINCT ON(id) id,title FROM " + view + " WHERE id>? ORDER BY id,length(title) DESC LIMIT 1000", after));
   }
}
