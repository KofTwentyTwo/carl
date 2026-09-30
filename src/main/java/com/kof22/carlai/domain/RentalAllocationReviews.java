/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/** Human review drafts over rental source allocations; applying retains immutable classification history. */
public final class RentalAllocationReviews
{
   /** Exact property amounts are available only after the entire component is allocated. */
   public record ComponentPreview(String id, RentalEconomics.Kind kind, BigDecimal amount, BigDecimal outsideFraction,
      BigDecimal remainingFraction, Map<String, BigDecimal> propertyAmounts)
   {
      /** Keeps the reviewed split immutable. */
      public ComponentPreview
      {
         propertyAmounts = Map.copyOf(propertyAmounts);
      }
   }



   /** Current saved review and truthful validation gaps, independent of conversation history. */
   public record Preview(long id, long version, String state, String currency, BigDecimal sourceAmount,
      boolean sourceStale, List<ComponentPreview> components, List<String> gaps)
   {
      /** Keeps calculated rows and limitations immutable. */
      public Preview
      {
         components = List.copyOf(components);
         gaps = List.copyOf(gaps);
      }



      /** A partial, changed or already applied draft cannot be newly applied. */
      public boolean canApply()
      {
         return state.equals("DRAFT") && !sourceStale && gaps.isEmpty();
      }
   }



   private record Frozen(Map<String, Object> review, List<RentalEconomics.Component> components, UUID child, String evidence, Long result)
   {
   }

   private final CarlService service;
   private static final Set<String> VIEWS = Set.of("carl_rental_review_view", "carl_rental_review_component_view", "carl_rental_review_share_view", "carl_transaction_view", "carl_rental_property_view", "carl_rental_source_view");
   /** Shares Carl's verified callers, source records and authoritative classifiers. */
   public RentalAllocationReviews(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** Starts a source-pinned local review; incomplete shares are never silently classified. */
   public long create(String principal, UUID request, long transaction, String visibility, String title, String evidence)
   {
      CarlService.bounded(evidence, 4000, "allocation review evidence");
      String digest = digest(Map.of("op", "RENTAL_REVIEW_CREATE", "transaction", transaction, "visibility", visibility, "title", title, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         require(c, principal, "carl_transaction_view", transaction);
         Long prior = CarlService.request(c, actor, request, "RENTAL_REVIEW_CREATE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rental_review_view", prior);
            return prior;
         }
         var existing = CarlService.rows(c, "SELECT record_id,current_version FROM carl_rental_split WHERE transaction_id=?", transaction);
         Long split = existing.isEmpty() ? null : CarlService.number(existing.getFirst(), "record_id");
         Integer splitVersion = existing.isEmpty() ? null : Math.toIntExact(CarlService.number(existing.getFirst(), "current_version"));
         if(split != null)
         {
            require(c, principal, "carl_rental_source_view", split);
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_rental_review(record_id,transaction_id,transaction_revision,classification_id,classification_version,created_by) VALUES(?,?,?,?,?,?)", id, transaction, revision(c, transaction), split, splitVersion, actor.id());
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Saved allocation review; source transaction unchanged");
         return id;
      });
   }



   /** Edits one explicitly supplied component, preserving attributed prior values. */
   public long setComponent(String principal, UUID request, long review, long expectedVersion, String component,
      RentalEconomics.Kind kind, BigDecimal amount, BigDecimal outsideFraction, String evidence)
   {
      CarlService.bounded(component, 150, "component name");
      fraction(outsideFraction);
      if(kind == null)
      {
         throw new IllegalArgumentException("An explicit component category is required");
      }
      String digest = digest(Map.of("op", "RENTAL_REVIEW_COMPONENT", "review", review, "version", expectedVersion, "component", component, "kind", kind, "amount", amount, "outside", outsideFraction, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var row = require(c, principal, "carl_rental_review_view", review);
         if(CarlService.request(c, actor, request, "RENTAL_REVIEW_COMPONENT", digest) != null)
         {
            return CarlService.number(row, "review_version");
         }
         editable(row, expectedVersion);
         BigDecimal normalized = money(amount, row.get("currency").toString());
         var before = CarlService.rows(c, "SELECT * FROM carl_rental_review_component WHERE review_id=? AND component_id=?", review, component);
         if(before.isEmpty() && CarlService.rows(c, "SELECT id FROM carl_rental_review_component WHERE review_id=? LIMIT 101", review).size() >= 100)
         {
            throw new IllegalArgumentException("One review supports at most 100 components");
         }
         BigDecimal assigned = before.isEmpty() ? BigDecimal.ZERO : (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(fraction),0) AS assigned FROM carl_rental_review_share WHERE component_id=?", before.getFirst().get("id")).getFirst().get("assigned");
         if(assigned.add(outsideFraction).compareTo(BigDecimal.ONE) > 0)
         {
            throw new IllegalArgumentException("Property and outside shares cannot exceed the whole component");
         }
         CarlService.execute(c, "INSERT INTO carl_rental_review_component(review_id,component_id,kind,amount,outside_fraction) VALUES(?,?,?,?,?) ON CONFLICT(review_id,component_id) DO UPDATE SET kind=EXCLUDED.kind,amount=EXCLUDED.amount,outside_fraction=EXCLUDED.outside_fraction", review, component, kind.name(), normalized, outsideFraction);
         changed(c, actor, review, evidence, before, Map.of("component", component, "kind", kind, "amount", normalized, "outsideFraction", outsideFraction));
         CarlService.complete(c, request, review, "COMPLETE", "Component edited; source unchanged");
         return Math.addExact(expectedVersion, 1);
      });
   }



   /** Adds, replaces or explicitly removes one share; zero means remove, never an inferred allocation. */
   public long setShare(String principal, UUID request, long review, long expectedVersion, String component,
      long property, BigDecimal share, String evidence)
   {
      fraction(share);
      String digest = digest(Map.of("op", "RENTAL_REVIEW_SHARE", "review", review, "version", expectedVersion, "component", component, "property", property, "share", share, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var row = require(c, principal, "carl_rental_review_view", review);
         var permitted = require(c, principal, "carl_rental_property_view", property);
         if(!row.get("currency").equals(permitted.get("currency")))
         {
            throw new IllegalArgumentException("Source and property currencies must match");
         }
         if(CarlService.request(c, actor, request, "RENTAL_REVIEW_SHARE", digest) != null)
         {
            return CarlService.number(row, "review_version");
         }
         editable(row, expectedVersion);
         var parts = CarlService.rows(c, "SELECT * FROM carl_rental_review_component WHERE review_id=? AND component_id=?", review, component);
         if(parts.size() != 1)
         {
            throw new IllegalArgumentException("Add the reviewed component first");
         }
         var part = parts.getFirst();
         long partId = CarlService.number(part, "id");
         var before = CarlService.rows(c, "SELECT * FROM carl_rental_review_share WHERE component_id=? AND property_id=?", partId, property);
         int count = CarlService.rows(c, "SELECT id FROM carl_rental_review_share WHERE component_id=? LIMIT 101", partId).size();
         if(share.signum() > 0 && before.isEmpty() && count >= 100)
         {
            throw new IllegalArgumentException("One component supports at most 100 property shares");
         }
         BigDecimal other = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(fraction),0) AS assigned FROM carl_rental_review_share WHERE component_id=? AND property_id<>?", partId, property).getFirst().get("assigned");
         if(other.add(share).add((BigDecimal) part.get("outside_fraction")).compareTo(BigDecimal.ONE) > 0)
         {
            throw new IllegalArgumentException("Property and outside shares cannot exceed the whole component");
         }
         if(share.signum() == 0)
         {
            CarlService.execute(c, "DELETE FROM carl_rental_review_share WHERE component_id=? AND property_id=?", partId, property);
         }
         else
         {
            CarlService.execute(c, "INSERT INTO carl_rental_review_share(component_id,property_id,fraction) VALUES(?,?,?) ON CONFLICT(component_id,property_id) DO UPDATE SET fraction=EXCLUDED.fraction", partId, property, share);
         }
         changed(c, actor, review, evidence, before, Map.of("component", component, "property", property, "share", share));
         CarlService.complete(c, request, review, "COMPLETE", "Share reviewed; no classification applied");
         return Math.addExact(expectedVersion, 1);
      });
   }



   /** Previews identical authorized records for every intended viewer. */
   public Preview preview(CarlService.Scope scope, long review)
   {
      return service.transaction(c ->
      {
         var row = scoped(c, scope, review);
         return preview(c, scope.principal(), row);
      });
   }



   /** Explicitly applies or reconciles the frozen review, using a stable child request across interruption. */
   public long apply(String principal, UUID request, long review, long expectedVersion, String evidence)
   {
      CarlService.bounded(evidence, 4000, "confirmed allocation evidence");
      String digest = digest(Map.of("op", "RENTAL_REVIEW_APPLY", "review", review, "version", expectedVersion, "evidence", evidence));
      var frozen = service.transaction(c ->
      {
         var actor = manager(c, principal);
         var row = require(c, principal, "carl_rental_review_view", review);
         Long prior = CarlService.request(c, actor, request, "RENTAL_REVIEW_APPLY", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rental_source_view", prior);
            return new Frozen(row, List.of(), null, evidence, prior);
         }
         if(CarlService.number(row, "review_version") != expectedVersion)
         {
            throw new IllegalArgumentException("Review changed; inspect the current version before applying");
         }
         if(row.get("state").equals("APPLIED"))
         {
            long result = CarlService.number(row, "result_id");
            require(c, principal, "carl_rental_source_view", result);
            CarlService.complete(c, request, result, "COMPLETE", "Previously applied review; no duplicate classification");
            return new Frozen(row, List.of(), null, evidence, result);
         }
         if(row.get("state").equals("FAILED"))
         {
            throw new IllegalStateException("This review failed before classification; inspect the source and start a fresh review");
         }
         if(row.get("state").equals("DRAFT"))
         {
            var preview = preview(c, principal, row);
            if(!preview.canApply())
            {
               throw new IllegalArgumentException("Review is incomplete or stale: " + String.join("; ", preview.gaps()));
            }
            UUID child = UUID.nameUUIDFromBytes(("rental-review:" + review + ":" + expectedVersion).getBytes(StandardCharsets.UTF_8));
            CarlService.execute(c, "UPDATE carl_rental_review SET state='APPLYING',child_request=?,apply_evidence=? WHERE record_id=?", child, evidence, review);
            row.put("child_request", child.toString());
            row.put("apply_evidence", evidence);
         }
         else
         {
            var stored = CarlService.rows(c, "SELECT child_request,apply_evidence FROM carl_rental_review WHERE record_id=?", review).getFirst();
            row.putAll(stored);
         }
         row.put("visibility", CarlService.rows(c, "SELECT visibility FROM carl_record WHERE id=?", review).getFirst().get("visibility"));
         return new Frozen(row, components(c, review), UUID.fromString(row.get("child_request").toString()), row.get("apply_evidence").toString(), null);
      });
      if(frozen.result() != null)
      {
         return frozen.result();
      }
      try
      {
         var rentals = new RentalRecords(service);
         long result;
         if(frozen.review().get("classification_id") == null)
         {
            result = rentals.classifyPinned(principal, frozen.child(), CarlService.number(frozen.review(), "transaction_id"), CarlService.number(frozen.review(), "transaction_revision"), frozen.components(), frozen.review().get("visibility").toString(), frozen.evidence());
         }
         else
         {
            result = CarlService.number(frozen.review(), "classification_id");
            rentals.reviseClassificationPinned(principal, frozen.child(), result, Math.toIntExact(CarlService.number(frozen.review(), "classification_version")), CarlService.number(frozen.review(), "transaction_revision"), frozen.components(), frozen.evidence());
         }
         return finish(principal, request, review, result);
      }
      catch(RuntimeException failure)
      {
         Long completed = service.transaction(c ->
         {
            manager(c, principal);
            require(c, principal, "carl_rental_review_view", review);
            var child = CarlService.rows(c, "SELECT status,result_id FROM carl_request WHERE id=?", frozen.child());
            if(!child.isEmpty() && Set.of("COMPLETE", "PARTIAL").contains(child.getFirst().get("status")))
            {
               return CarlService.number(child.getFirst(), "result_id");
            }
            CarlService.execute(c, "UPDATE carl_rental_review SET state=? WHERE record_id=?", child.isEmpty() ? "FAILED" : "UNKNOWN", review);
            return null;
         });
         if(completed != null)
         {
            return finish(principal, request, review, completed);
         }
         throw failure;
      }
   }



   private long finish(String principal, UUID request, long review, long result)
   {
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var row = require(c, principal, "carl_rental_review_view", review);
         require(c, principal, "carl_rental_source_view", result);
         if(!row.get("state").equals("APPLIED"))
         {
            CarlService.execute(c, "UPDATE carl_rental_review SET state='APPLIED',result_id=? WHERE record_id=?", result, review);
            CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", review);
            CarlService.bump(c, actor.householdId());
         }
         else if(CarlService.number(row, "result_id") != result)
         {
            throw new IllegalStateException("Review already applied to another classification");
         }
         CarlService.complete(c, request, result, "COMPLETE", "Confirmed immutable classification; imported source retained");
         return result;
      });
   }



   private static Preview preview(Connection c, String principal, Map<String, Object> row) throws SQLException
   {
      long id = CarlService.number(row, "id");
      var components = components(c, id);
      var gaps = new ArrayList<String>();
      boolean stale = Boolean.TRUE.equals(row.get("source_stale"));
      if(stale)
      {
         gaps.add("Transaction changed after this review; start a fresh review");
      }
      if(Boolean.TRUE.equals(row.get("classification_stale")))
      {
         gaps.add("Classification changed after this review; start a fresh review");
      }
      var source = new LinkedHashMap<String, Object>();
      source.put("amount", row.get("source_amount"));
      source.put("currency", row.get("currency"));
      try
      {
         RentalRecords.validateComponents(c, principal, source, components);
      }
      catch(IllegalArgumentException incomplete)
      {
         gaps.add(incomplete.getMessage());
      }
      var rows = new ArrayList<ComponentPreview>();
      for(var component : components)
      {
         BigDecimal remaining = BigDecimal.ONE.subtract(component.outsideScopeFraction()).subtract(component.propertyFractions().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
         Map<String, BigDecimal> amounts = remaining.signum() == 0 ? RentalEconomics.allocateComponent(row.get("currency").toString(), component) : Map.of();
         rows.add(new ComponentPreview(component.id(), component.kind(), component.amount(), component.outsideScopeFraction(), remaining, amounts));
      }
      return new Preview(id, CarlService.number(row, "review_version"), row.get("state").toString(), row.get("currency").toString(), (BigDecimal) row.get("source_amount"), stale, rows, gaps);
   }



   /** Reads the selected component key only through its currently authorized review. */
   public String componentKey(String principal, long review, long component)
   {
      return service.transaction(c ->
      {
         var row = require(c, principal, "carl_rental_review_component_view", component);
         if(CarlService.number(row, "review_id") != review)
         {
            throw new IllegalArgumentException("Selected component belongs to another review");
         }
         return row.get("component_id").toString();
      });
   }



   /** Removes a draft component and its shares with attributed evidence. */
   public long removeComponent(String principal, UUID request, long review, long expectedVersion, String component, String evidence)
   {
      CarlService.bounded(component, 150, "component name");
      String digest = digest(Map.of("op", "RENTAL_REVIEW_REMOVE", "review", review, "version", expectedVersion, "component", component, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var row = require(c, principal, "carl_rental_review_view", review);
         if(CarlService.request(c, actor, request, "RENTAL_REVIEW_REMOVE", digest) != null)
         {
            return CarlService.number(row, "review_version");
         }
         editable(row, expectedVersion);
         var before = CarlService.rows(c, "SELECT * FROM carl_rental_review_component WHERE review_id=? AND component_id=?", review, component);
         if(before.isEmpty())
         {
            throw new IllegalArgumentException("Selected component is unavailable");
         }
         CarlService.execute(c, "DELETE FROM carl_rental_review_component WHERE review_id=? AND component_id=?", review, component);
         changed(c, actor, review, evidence, before, Map.of("removedComponent", component));
         CarlService.complete(c, request, review, "COMPLETE", "Draft component removed; source unchanged");
         return Math.addExact(expectedVersion, 1);
      });
   }



   private static List<RentalEconomics.Component> components(Connection c, long review) throws SQLException
   {
      var parts = CarlService.rows(c, "SELECT * FROM carl_rental_review_component WHERE review_id=? ORDER BY id LIMIT 101", review);
      if(parts.size() > 100)
      {
         throw new IllegalArgumentException("Review exceeds the component limit");
      }
      var result = new ArrayList<RentalEconomics.Component>();
      for(var part : parts)
      {
         var shares = CarlService.rows(c, "SELECT property_id,fraction FROM carl_rental_review_share WHERE component_id=? ORDER BY property_id LIMIT 101", part.get("id"));
         if(shares.size() > 100)
         {
            throw new IllegalArgumentException("Component exceeds the property-share limit");
         }
         var fractions = new LinkedHashMap<String, BigDecimal>();
         for(var share : shares)
         {
            fractions.put(share.get("property_id").toString(), ((BigDecimal) share.get("fraction")).stripTrailingZeros());
         }
         result.add(new RentalEconomics.Component(part.get("component_id").toString(), RentalEconomics.Kind.valueOf(part.get("kind").toString()), (BigDecimal) part.get("amount"), fractions, ((BigDecimal) part.get("outside_fraction")).stripTrailingZeros()));
      }
      return List.copyOf(result);
   }



   private static Map<String, Object> scoped(Connection c, CarlService.Scope scope, long review) throws SQLException
   {
      var initial = CarlService.member(c, scope.principal());
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR SHARE", initial.householdId());
      Map<String, Object> result = null;
      for(String principal : scope.audience())
      {
         if(CarlService.member(c, principal).householdId() != initial.householdId())
         {
            throw new SecurityException("Allocation review unavailable for this audience");
         }
         var row = require(c, principal, "carl_rental_review_view", review);
         row.remove("principal");
         if(result != null && !result.equals(row))
         {
            throw new SecurityException("Allocation review unavailable for this audience");
         }
         result = row;
      }
      return java.util.Objects.requireNonNull(result);
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      if(!VIEWS.contains(view))
      {
         throw new IllegalArgumentException("Unregistered allocation review view");
      }
      CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(rows.size() != 1)
      {
         throw new SecurityException("Allocation review or source unavailable");
      }
      return rows.getFirst();
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var initial = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
      var current = CarlService.manager(c, principal, "FINANCE");
      if(current.householdId() != initial.householdId())
      {
         throw new SecurityException("Allocation review unavailable");
      }
      return current;
   }



   private static long revision(Connection c, long id) throws SQLException
   {
      return CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", id).getFirst(), "revision");
   }



   private static void editable(Map<String, Object> row, long expectedVersion)
   {
      if(!row.get("state").equals("DRAFT"))
      {
         throw new IllegalStateException("Only an unapplied draft can be edited");
      }
      if(CarlService.number(row, "review_version") != expectedVersion)
      {
         throw new IllegalArgumentException("Review changed; refresh before editing");
      }
   }



   private static BigDecimal money(BigDecimal value, String currency)
   {
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(value == null || value.signum() <= 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18 || value.compareTo(new BigDecimal("100000000000000")) > 0 || scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Explicit bounded positive amounts are required");
      }
      try
      {
         return value.setScale(scale, RoundingMode.UNNECESSARY);
      }
      catch(ArithmeticException invalid)
      {
         throw new IllegalArgumentException("Amount exceeds currency precision", invalid);
      }
   }



   private static void fraction(BigDecimal value)
   {
      if(value == null || value.precision() > 18 || Math.abs((long) value.scale()) > 10 || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)
      {
         throw new IllegalArgumentException("Explicit fraction from zero through one is required");
      }
   }



   private static String digest(Map<String, ?> values)
   {
      return BillCsv.hash(CarlService.json(values));
   }



   private static void changed(Connection c, CarlService.Member actor, long review, String evidence, Object before, Object after) throws SQLException
   {
      CarlService.bounded(evidence, 4000, "review correction evidence");
      CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", review, actor.id(), evidence, CarlService.json(before), CarlService.json(after));
      CarlService.execute(c, "UPDATE carl_rental_review SET review_version=review_version+1 WHERE record_id=?", review);
      CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", review);
      CarlService.bump(c, actor.householdId());
   }
}
