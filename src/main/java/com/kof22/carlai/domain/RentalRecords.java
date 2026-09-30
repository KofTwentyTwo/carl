/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;


/*******************************************************************************
 ** Carl's persistent rental capabilities over the same authoritative source rows.
 ** Domain processes call mutations; ordinary agent tools call scoped reads only.
 ******************************************************************************/
public final class RentalRecords
{
   /*******************************************************************************
    ** Optional supplied property facts; null ownership/basis stays unknown.
    ******************************************************************************/
   public record PropertyValues(String currency, String locality, BigDecimal ownershipFraction,
      Long assetAccount, Long debtAccount, String legalOwner, BigDecimal marketValue, LocalDate valuationDate,
      LocalDate acquired, BigDecimal acquisitionBasis, BigDecimal landBasis, BigDecimal buildingBasis, String basisEvidence)
   {
   }



   /*******************************************************************************
    ** Explicit unit occupancy and lease/rent evidence without inferred tenants.
    ******************************************************************************/
   public record UnitValues(String label, BigDecimal scheduledRent, LocalDate leaseStart, LocalDate leaseEnd, String occupancy)
   {
   }

   private final CarlService service;
   private static final Set<String> VIEWS = Set.of("carl_rental_property_view", "carl_rental_unit_view", "carl_rental_source_view", "carl_rent_due_view", "carl_rent_application_view");
   private record ReceiptKey(long split, String component, long property)
   {
   }



   private record Snapshot(long epoch, List<Map<String, Object>> properties, List<RentalEconomics.Property> calculatedProperties,
      List<RentalEconomics.Transaction> transactions, List<RentalEconomics.RentDue> rents,
      List<RentalEconomics.RentApplication> applications, Map<Long, Long> sources, List<String> gaps)
   {
   }

   /*******************************************************************************
    ** Uses Carl's authenticated domain and shared transaction boundaries.
    ******************************************************************************/
   public RentalRecords(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /*******************************************************************************
    ** Idempotently records a local property; no title or legal change occurs.
    ******************************************************************************/
   public long createProperty(String principal, UUID request, String title, String visibility, String evidence, PropertyValues values)
   {
      validateProperty(values);
      String digest = digest(Map.of("operation", "PROPERTY_CREATE", "title", title, "visibility", visibility, "evidence", evidence, "values", values));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         validateAccounts(c, principal, values);
         Long prior = CarlService.request(c, actor, request, "RENTAL_PROPERTY", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rental_property_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_property(record_id,locality,ownership_share,currency) VALUES(?,?,?,?)", id, values.locality(), values.ownershipFraction(), values.currency());
         writeProperty(c, id, values);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Local property recorded; no title transfer or external action");
         return id;
      });
   }



   /*******************************************************************************
    ** Preserves original evidence and attributes an optimistic local correction.
    ******************************************************************************/
   public void correctProperty(String principal, UUID request, long property, long expectedRevision, PropertyValues values, String reason)
   {
      validateProperty(values);
      CarlService.bounded(reason, 2000, "property correction reason");
      String digest = digest(Map.of("operation", "PROPERTY_CORRECT", "property", property, "revision", expectedRevision, "values", values, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var before = require(c, principal, "carl_rental_property_view", property);
         validateAccounts(c, principal, values);
         if(CarlService.request(c, actor, request, "RENTAL_PROPERTY_CORRECTION", digest) != null)
         {
            return null;
         }
         if(CarlService.number(before, "revision") != expectedRevision)
         {
            throw new IllegalArgumentException("Property revision changed; review the current record");
         }
         if(!values.currency().equals(before.get("currency")))
         {
            throw new IllegalArgumentException("Currency corrections require a separately reviewed data migration");
         }
         writeProperty(c, property, values);
         CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", property, actor.id(), reason, CarlService.json(before), CarlService.json(values));
         touch(c, property);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, property, "COMPLETE", "Attributed local correction; original evidence retained");
         return null;
      });
   }



   /*******************************************************************************
    ** Records a permission-scoped unit belonging to an accessible property.
    ******************************************************************************/
   public long createUnit(String principal, UUID request, long property, String title, String visibility, String evidence, UnitValues values)
   {
      if(values == null || !Set.of("OCCUPIED", "VACANT", "UNKNOWN").contains(values.occupancy()))
      {
         throw new IllegalArgumentException("Explicit rental-unit occupancy required");
      }
      CarlService.bounded(values.label(), 200, "unit label");
      date(values.leaseStart(), true);
      date(values.leaseEnd(), true);
      if(values.leaseStart() != null && values.leaseEnd() != null && values.leaseEnd().isBefore(values.leaseStart()))
      {
         throw new IllegalArgumentException("Lease end precedes lease start");
      }
      String digest = digest(Map.of("operation", "UNIT_CREATE", "property", property, "title", title, "visibility", visibility, "evidence", evidence, "values", values));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var parent = require(c, principal, "carl_rental_property_view", property);
         if(values.scheduledRent() != null)
         {
            money(values.scheduledRent(), parent.get("currency").toString(), false);
         }
         Long prior = CarlService.request(c, actor, request, "RENTAL_UNIT", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rental_unit_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, title, evidence);
         CarlService.execute(c, "INSERT INTO carl_rental_unit(record_id,property_id,unit_label,scheduled_rent,currency,lease_start,lease_end,occupancy) VALUES(?,?,?,?,?,?,?,?)", id, property, values.label(), values.scheduledRent(), parent.get("currency"), values.leaseStart(), values.leaseEnd(), values.occupancy());
         touch(c, property);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Rental unit recorded with explicit occupancy");
         return id;
      });
   }



   /*******************************************************************************
    ** Stores evidenced source components without rewriting imported transactions.
    ******************************************************************************/
   public long classify(String principal, UUID request, long transaction, List<RentalEconomics.Component> components, String visibility, String evidence)
   {
      return classifyReviewed(principal, request, transaction, null, components, visibility, evidence);
   }



   /** Applies only the exact transaction revision inspected in a saved human review. */
   public long classifyPinned(String principal, UUID request, long transaction, long expectedTransactionRevision, List<RentalEconomics.Component> components, String visibility, String evidence)
   {
      return classifyReviewed(principal, request, transaction, expectedTransactionRevision, components, visibility, evidence);
   }



   private long classifyReviewed(String principal, UUID request, long transaction, Long expectedTransactionRevision, List<RentalEconomics.Component> components, String visibility, String evidence)
   {
      CarlService.bounded(evidence, 4000, "rental classification evidence");
      var identity = new LinkedHashMap<String, Object>(Map.of("operation", "CLASSIFY", "transaction", transaction, "components", componentIdentity(components), "visibility", visibility, "evidence", evidence));
      if(expectedTransactionRevision != null)
      {
         identity.put("expectedTransactionRevision", expectedTransactionRevision);
      }
      String digest = digest(identity);
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var source = sourceTransaction(c, principal, transaction);
         Long prior = CarlService.request(c, actor, request, "RENTAL_CLASSIFICATION", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rental_source_view", prior);
            return prior;
         }
         if(expectedTransactionRevision != null && CarlService.number(source, "revision") != expectedTransactionRevision)
         {
            throw new IllegalArgumentException("Transaction changed after review; inspect a fresh source before applying");
         }
         Set<Long> properties = validateComponents(c, principal, source, components);
         String input = sourceDigest(digest, source);
         var existing = CarlService.rows(c, "SELECT s.record_id,v.input_hash FROM carl_rental_split s JOIN carl_rental_split_version v ON v.split_id=s.record_id AND v.version=s.current_version WHERE s.transaction_id=?", transaction);
         long id;
         if(!existing.isEmpty())
         {
            id = CarlService.number(existing.getFirst(), "record_id");
            require(c, principal, "carl_rental_source_view", id);
            if(!input.equals(existing.getFirst().get("input_hash")))
            {
               throw new IllegalArgumentException("Existing source classification needs explicit versioned revision");
            }
         }
         else
         {
            id = CarlService.record(c, actor, "FINANCE", visibility, "Rental source classification", evidence);
            CarlService.execute(c, "INSERT INTO carl_rental_split(record_id,transaction_id,current_version) VALUES(?,?,1)", id, transaction);
            writeVersion(c, actor, id, 1, source, components, input, evidence);
            for(long property : properties)
            {
               touch(c, property);
            }
            CarlService.bump(c, actor.householdId());
         }
         CarlService.complete(c, request, id, "COMPLETE", "Evidence-linked rental classification; imported transaction unchanged");
         return id;
      });
   }



   /*******************************************************************************
    ** Creates immutable source history after explicit review of dependent applications.
    ******************************************************************************/
   public void reviseClassification(String principal, UUID request, long split, int expectedVersion, List<RentalEconomics.Component> components, String reason)
   {
      reviseReviewed(principal, request, split, expectedVersion, null, components, reason);
   }



   /** Preserves immutable classification history while requiring the reviewed source revision. */
   public void reviseClassificationPinned(String principal, UUID request, long split, int expectedVersion, long expectedTransactionRevision, List<RentalEconomics.Component> components, String reason)
   {
      reviseReviewed(principal, request, split, expectedVersion, expectedTransactionRevision, components, reason);
   }



   private void reviseReviewed(String principal, UUID request, long split, int expectedVersion, Long expectedTransactionRevision, List<RentalEconomics.Component> components, String reason)
   {
      CarlService.bounded(reason, 4000, "classification revision evidence");
      var identity = new LinkedHashMap<String, Object>(Map.of("operation", "REVISE", "split", split, "version", expectedVersion, "components", componentIdentity(components), "reason", reason));
      if(expectedTransactionRevision != null)
      {
         identity.put("expectedTransactionRevision", expectedTransactionRevision);
      }
      String digest = digest(identity);
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var current = require(c, principal, "carl_rental_source_view", split);
         if(CarlService.request(c, actor, request, "RENTAL_CLASSIFICATION_REVISION", digest) != null)
         {
            return null;
         }
         if(CarlService.number(current, "current_version") != expectedVersion)
         {
            throw new IllegalArgumentException("Rental classification version changed");
         }
         if(!CarlService.rows(c, "SELECT record_id FROM carl_rent_application WHERE split_id=? AND active LIMIT 1", split).isEmpty())
         {
            throw new IllegalArgumentException("Remove active receipt applications explicitly before revising their classification");
         }
         var source = sourceTransaction(c, principal, CarlService.number(current, "transaction_id"));
         if(expectedTransactionRevision != null && CarlService.number(source, "revision") != expectedTransactionRevision)
         {
            throw new IllegalArgumentException("Transaction changed after review; inspect a fresh source before applying");
         }
         Set<Long> properties = validateComponents(c, principal, source, components);
         for(var prior : CarlService.rows(c, "SELECT DISTINCT property_id FROM carl_rental_allocation WHERE split_id=? AND version=?", split, expectedVersion))
         {
            properties.add(CarlService.number(prior, "property_id"));
         }
         int next = Math.addExact(expectedVersion, 1);
         writeVersion(c, actor, split, next, source, components, sourceDigest(digest, source), reason);
         CarlService.execute(c, "UPDATE carl_rental_split SET current_version=? WHERE record_id=?", next, split);
         touch(c, split);
         for(long property : properties)
         {
            touch(c, property);
         }
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, split, "COMPLETE", "New immutable classification version; earlier evidence retained");
         return null;
      });
   }



   /*******************************************************************************
    ** Records a documented rent obligation; missing amounts remain unknown.
    ******************************************************************************/
   public long rentDue(String principal, UUID request, long property, Long unit, LocalDate due, BigDecimal amount, String visibility, String evidence)
   {
      date(due, false);
      CarlService.bounded(evidence, 4000, "rent schedule evidence");
      var input = new TreeMap<String, Object>();
      input.put("operation", "RENT_DUE");
      input.put("property", property);
      input.put("unit", unit);
      input.put("date", due);
      input.put("amount", amount);
      input.put("visibility", visibility);
      input.put("evidence", evidence);
      String digest = digest(input);
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var parent = require(c, principal, "carl_rental_property_view", property);
         if(unit != null && CarlService.number(require(c, principal, "carl_rental_unit_view", unit), "property_id") != property)
         {
            throw new IllegalArgumentException("Unit belongs to a different property");
         }
         if(amount != null)
         {
            money(amount, parent.get("currency").toString(), false);
         }
         Long prior = CarlService.request(c, actor, request, "RENT_DUE", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rent_due_view", prior);
            return prior;
         }
         long id = CarlService.record(c, actor, "FINANCE", visibility, "Scheduled rent " + due, evidence);
         CarlService.execute(c, "INSERT INTO carl_rent_due(record_id,property_id,unit_id,due_date,amount) VALUES(?,?,?,?,?)", id, property, unit, due, amount);
         touch(c, property);
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", amount == null ? "Rent schedule saved with missing amount" : "Evidenced rent schedule saved");
         return id;
      });
   }



   /*******************************************************************************
    ** Applies evidenced receipt capacity to a permitted rent period exactly once.
    ******************************************************************************/
   public long applyRent(String principal, UUID request, long due, long split, int version, String component, BigDecimal amount, String evidence)
   {
      CarlService.bounded(component, 150, "receipt component");
      CarlService.bounded(evidence, 4000, "rent application evidence");
      String digest = digest(Map.of("operation", "APPLY_RENT", "due", due, "split", split, "version", version, "component", component, "amount", amount, "evidence", evidence));
      return service.transaction(c ->
      {
         var actor = manager(c, principal);
         var rent = require(c, principal, "carl_rent_due_view", due);
         var source = require(c, principal, "carl_rental_source_view", split);
         Long prior = CarlService.request(c, actor, request, "RENT_APPLICATION", digest);
         if(prior != null)
         {
            require(c, principal, "carl_rent_application_view", prior);
            return prior;
         }
         if(Boolean.TRUE.equals(source.get("source_stale")) || CarlService.number(source, "current_version") != version || rent.get("amount") == null)
         {
            throw new IllegalArgumentException("Known rent and current source classification are required");
         }
         BigDecimal applied = money(amount, rent.get("currency").toString(), false);
         if(applied.signum() <= 0 || !rent.get("currency").equals(source.get("currency")))
         {
            throw new IllegalArgumentException("Application requires a positive amount in one currency");
         }
         // Hidden allocations never become an oracle for another member's private rent period.
         if(!CarlService.rows(c, "SELECT x.record_id FROM carl_rent_application x WHERE x.active AND (x.rent_due_id=? OR x.split_id=?) AND NOT EXISTS(SELECT 1 FROM carl_rent_application_view av WHERE av.id=x.record_id AND av.principal=?) LIMIT 1", due, split, principal).isEmpty())
         {
            throw unavailable();
         }
         var parts = CarlService.rows(c, "SELECT c.kind,c.amount,a.fraction FROM carl_rental_component c JOIN carl_rental_allocation a USING(split_id,version,component_id) WHERE c.split_id=? AND c.version=? AND c.component_id=? AND a.property_id=?", split, version, component, CarlService.number(rent, "property_id"));
         if(parts.size() != 1 || !"RENT_RECEIPT".equals(parts.getFirst().get("kind")))
         {
            throw new IllegalArgumentException("Application must reference this property's rent receipt");
         }
         BigDecimal capacity = allocatedReceipt(c, source, component, CarlService.number(rent, "property_id"));
         BigDecimal receiptApplied = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(x.amount),0) AS amount FROM carl_rent_application x JOIN carl_rent_due d ON d.record_id=x.rent_due_id WHERE x.split_id=? AND x.version=? AND x.component_id=? AND d.property_id=? AND x.active", split, version, component, CarlService.number(rent, "property_id")).getFirst().get("amount");
         BigDecimal dueApplied = (BigDecimal) CarlService.rows(c, "SELECT coalesce(sum(amount),0) AS amount FROM carl_rent_application WHERE rent_due_id=? AND active", due).getFirst().get("amount");
         if(receiptApplied.add(applied).compareTo(capacity) > 0 || dueApplied.add(applied).compareTo((BigDecimal) rent.get("amount")) > 0)
         {
            throw new IllegalArgumentException("Rent application exceeds the evidenced receipt or scheduled amount");
         }
         String visibility = CarlService.rows(c, "SELECT visibility FROM carl_record WHERE id=?", due).getFirst().get("visibility").toString();
         long id = CarlService.record(c, actor, "FINANCE", visibility, "Rent receipt application", evidence);
         CarlService.execute(c, "INSERT INTO carl_rent_application(record_id,rent_due_id,split_id,version,component_id,amount,created_by) VALUES(?,?,?,?,?,?,?)", id, due, split, version, component, applied, actor.id());
         touch(c, due);
         touch(c, split);
         touch(c, CarlService.number(rent, "property_id"));
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, id, "COMPLETE", "Local evidenced rent allocation; no external payment");
         return id;
      });
   }



   /*******************************************************************************
    ** Retains an attributed correction instead of deleting prior application history.
    ******************************************************************************/
   public void unapplyRent(String principal, UUID request, long application, String reason)
   {
      CarlService.bounded(reason, 2000, "rent application correction reason");
      String digest = digest(Map.of("operation", "UNAPPLY_RENT", "application", application, "reason", reason));
      service.transaction(c ->
      {
         var actor = manager(c, principal);
         var current = require(c, principal, "carl_rent_application_view", application);
         if(CarlService.request(c, actor, request, "RENT_APPLICATION_REMOVAL", digest) != null)
         {
            return null;
         }
         if(Boolean.TRUE.equals(current.get("active")))
         {
            CarlService.execute(c, "UPDATE carl_rent_application SET active=false WHERE record_id=?", application);
            CarlService.execute(c, "INSERT INTO carl_correction(record_id,member_id,reason,before_value,after_value) VALUES(?,?,?,?,?)", application, actor.id(), reason, CarlService.json(current), "Inactive application; history preserved");
            touch(c, application);
            touch(c, CarlService.number(current, "rent_due_id"));
            touch(c, CarlService.number(current, "split_id"));
            touch(c, CarlService.number(current, "property_id"));
            CarlService.bump(c, actor.householdId());
         }
         CarlService.complete(c, request, application, "COMPLETE", "Application removed with attribution, never deleted");
         return null;
      });
   }



   /*******************************************************************************
    ** Reads current typed property access intersected across the explicit audience.
    ******************************************************************************/
   public List<Map<String, Object>> properties(CarlService.Scope scope)
   {
      return service.transaction(c ->
      {
         lockScope(c, scope);
         return scoped(c, scope, "carl_rental_property_view", "", List.of(), 100);
      });
   }



   /*******************************************************************************
    ** Reads bounded fixed rental views; caller cannot supply SQL or a table name.
    ******************************************************************************/
   public List<Map<String, Object>> records(CarlService.Scope scope, String kind)
   {
      String view = switch(kind)
      {
         case "units" -> "carl_rental_unit_view";
         case "sources" -> "carl_rental_source_view";
         case "dues" -> "carl_rent_due_view";
         case "applications" -> "carl_rent_application_view";
         default -> throw new IllegalArgumentException("Unsupported rental view");
      };
      return service.transaction(c ->
      {
         lockScope(c, scope);
         return scoped(c, scope, view, "", List.of(), 1000);
      });
   }



   /*******************************************************************************
    ** Persists scoped source-linked cash facts; partial data never becomes whole-household coverage.
    ******************************************************************************/
   public long report(CarlService.Scope scope, UUID request, Set<Long> selectedProperties, LocalDate from, LocalDate through, LocalDate asOf)
   {
      date(from, false);
      date(through, false);
      date(asOf, false);
      if(from.isAfter(through) || ChronoUnit.DAYS.between(from, through) >= 366 || selectedProperties == null || selectedProperties.isEmpty() || selectedProperties.size() > 100 || selectedProperties.stream().anyMatch(id -> id == null || id <= 0))
      {
         throw new IllegalArgumentException("A bounded report interval and explicit permitted properties are required");
      }
      String digest = digest(Map.of("operation", "RENTAL_REPORT", "properties", selectedProperties.stream().sorted().toList(), "from", from, "through", through, "asOf", asOf));
      Long prior = service.claimArtifact(scope, request, "FINANCIAL_PLAN", digest);
      if(prior != null)
      {
         return CarlService.number(service.artifact(scope.principal(), prior), "id");
      }
      Snapshot snapshot = service.transaction(c -> snapshot(c, scope, selectedProperties, from, through, asOf));
      RentalEconomics.Report computed = snapshot.calculatedProperties().isEmpty() ? null : RentalEconomics.reportProjected(from, through, asOf, snapshot.calculatedProperties(), snapshot.transactions(), snapshot.rents(), snapshot.applications(), false);
      var facts = new LinkedHashMap<String, Object>();
      facts.put("calculationVersion", "rental-cash-v1");
      facts.put("from", from);
      facts.put("through", through);
      facts.put("asOf", asOf);
      facts.put("scope", "Selected authorized properties and accessible evidenced classifications only; source coverage is partial. Unseen receipts are not evidence of nonpayment.");
      facts.put("properties", snapshot.properties());
      facts.put("calculation", computed);
      facts.put("exceptions", snapshot.gaps());
      facts.put("evidenceStatus", "Manual classifications and receipt-period links retain attribution; imported activity is not tax treatment or legal proof of a lease obligation.");
      return service.saveArtifact(scope, request, "FINANCIAL_PLAN", from, through, CarlService.json(facts), "", "NOT_REQUESTED",
         "Partial source coverage. Whole-property and proportional ownership results are separate; principal/capital/reserves/deposits are not operating expenses or disposable household cash. No tax calculation, collection or financial action occurs.",
         snapshot.sources(), null, "Incomplete rental report — accessible source coverage only", digest, snapshot.epoch());
   }



   private Snapshot snapshot(Connection c, CarlService.Scope scope, Set<Long> selected, LocalDate from, LocalDate through, LocalDate asOf) throws SQLException
   {
      long epoch = lockScope(c, scope);
      var params = new ArrayList<Object>(selected.stream().sorted().toList());
      String selectedSql = placeholders(selected.size());
      var properties = scoped(c, scope, "carl_rental_property_view", " AND id IN (" + selectedSql + ")", params, 100);
      if(properties.size() != selected.size())
      {
         throw unavailable();
      }
      var sourceIds = new HashMap<Long, Long>();
      var gaps = new ArrayList<String>();
      var known = new HashSet<Long>();
      var calculatedProperties = new ArrayList<RentalEconomics.Property>();
      for(var property : properties)
      {
         long id = CarlService.number(property, "id");
         sourceIds.put(id, CarlService.number(property, "revision"));
         if(property.get("ownership_share") == null)
         {
            gaps.add("Property " + id + ": ownership unresolved; no proportional cash figures computed");
            continue;
         }
         known.add(id);
         calculatedProperties.add(new RentalEconomics.Property(Long.toString(id), property.get("currency").toString(), (BigDecimal) property.get("ownership_share")));
         for(String key : List.of("asset_account_id", "debt_account_id"))
         {
            if(property.get(key) != null)
            {
               long account = CarlService.number(property, key);
               sourceIds.put(account, revision(c, account));
            }
         }
      }
      var sourceParams = new ArrayList<Object>(params);
      sourceParams.add(asOf);
      var sources = scoped(c, scope, "carl_rental_source_view", " AND EXISTS(SELECT 1 FROM carl_rental_allocation x WHERE x.split_id=id AND x.version=current_version AND x.property_id IN (" + selectedSql + ")) AND source_date<=?", sourceParams, 10_000);
      var transactions = new ArrayList<RentalEconomics.Transaction>();
      var includedSplits = new HashMap<Long, Integer>();
      var receiptComponents = new HashMap<ReceiptKey, String>();
      long projectedComponents = 0;
      for(var source : sources)
      {
         long id = CarlService.number(source, "id");
         sourceIds.put(id, CarlService.number(source, "revision"));
         long transaction = CarlService.number(source, "transaction_id");
         sourceIds.put(transaction, revision(c, transaction));
         long account = CarlService.number(sourceTransaction(c, scope.principal(), transaction), "account_id");
         sourceIds.put(account, revision(c, account));
         if(Boolean.TRUE.equals(source.get("source_stale")))
         {
            gaps.add("Classification " + id + " is stale after source changes; excluded until reviewed");
            continue;
         }
         List<RentalEconomics.Component> parts = readComponents(c, source);
         var filtered = new ArrayList<RentalEconomics.Component>();
         int componentIndex = 0;
         for(var part : parts)
         {
            var fullAllocation = RentalEconomics.allocateComponent(source.get("currency").toString(), part);
            BigDecimal retained = BigDecimal.ZERO;
            for(long property : known.stream().sorted().toList())
            {
               BigDecimal amount = fullAllocation.getOrDefault(Long.toString(property), BigDecimal.ZERO);
               if(amount.signum() > 0)
               {
                  String projectedId = "c" + componentIndex + ":p" + property;
                  filtered.add(new RentalEconomics.Component(projectedId, part.kind(), amount, Map.of(Long.toString(property), BigDecimal.ONE), BigDecimal.ZERO));
                  receiptComponents.put(new ReceiptKey(id, part.id(), property), projectedId);
                  retained = retained.add(amount);
               }
            }
            BigDecimal outside = part.amount().subtract(retained);
            if(outside.signum() > 0)
            {
               filtered.add(new RentalEconomics.Component("c" + componentIndex + ":outside", part.kind(), outside, Map.of(), BigDecimal.ONE));
            }
            componentIndex++;
         }
         projectedComponents += filtered.size();
         if(projectedComponents > 100_000)
         {
            throw new IllegalArgumentException("Rental projection bound exceeded; narrow the report interval or scope");
         }
         if(filtered.stream().anyMatch(part -> !part.propertyFractions().isEmpty()))
         {
            transactions.add(new RentalEconomics.Transaction(Long.toString(id), LocalDate.parse(source.get("source_date").toString()), source.get("currency").toString(), (BigDecimal) source.get("source_amount"), filtered, Long.toString(id)));
            includedSplits.put(id, Math.toIntExact(CarlService.number(source, "current_version")));
         }
      }
      var rents = new ArrayList<RentalEconomics.RentDue>();
      var dueIds = new HashSet<Long>();
      for(var rent : scoped(c, scope, "carl_rent_due_view", " AND property_id IN (" + selectedSql + ")", params, 20_000))
      {
         long id = CarlService.number(rent, "id");
         sourceIds.put(id, CarlService.number(rent, "revision"));
         if(rent.get("unit_id") != null)
         {
            long unit = CarlService.number(rent, "unit_id");
            sourceIds.put(unit, revision(c, unit));
         }
         if(!known.contains(CarlService.number(rent, "property_id")))
         {
            continue;
         }
         dueIds.add(id);
         rents.add(new RentalEconomics.RentDue(Long.toString(id), rent.get("property_id").toString(), LocalDate.parse(rent.get("due_date").toString()), (BigDecimal) rent.get("amount"), Long.toString(id)));
      }
      var applications = new ArrayList<RentalEconomics.RentApplication>();
      for(var application : scoped(c, scope, "carl_rent_application_view", " AND property_id IN (" + selectedSql + ") AND active", params, 20_000))
      {
         long id = CarlService.number(application, "id");
         sourceIds.put(id, CarlService.number(application, "revision"));
         long split = CarlService.number(application, "split_id");
         long due = CarlService.number(application, "rent_due_id");
         if(!dueIds.contains(due) || !java.util.Objects.equals(includedSplits.get(split), Math.toIntExact(CarlService.number(application, "version"))))
         {
            gaps.add("Application " + id + " has unavailable/stale source or rent evidence; excluded");
            continue;
         }
         String component = receiptComponents.get(new ReceiptKey(split, application.get("component_id").toString(), CarlService.number(application, "property_id")));
         if(component == null)
         {
            gaps.add("Application " + id + " has no selected allocated receipt; excluded");
            continue;
         }
         applications.add(new RentalEconomics.RentApplication(Long.toString(due), Long.toString(split), component, (BigDecimal) application.get("amount")));
      }
      return new Snapshot(epoch, properties, calculatedProperties, transactions, rents, applications, sourceIds, gaps);
   }



   private BigDecimal allocatedReceipt(Connection c, Map<String, Object> source, String component, long property) throws SQLException
   {
      var part = readComponents(c, source).stream().filter(value -> value.id().equals(component)).findFirst().orElseThrow();
      return RentalEconomics.allocateComponent(source.get("currency").toString(), part).getOrDefault(Long.toString(property), BigDecimal.ZERO);
   }



   private static List<RentalEconomics.Component> readComponents(Connection c, Map<String, Object> source) throws SQLException
   {
      long id = CarlService.number(source, "id");
      int version = Math.toIntExact(CarlService.number(source, "current_version"));
      var parts = new ArrayList<RentalEconomics.Component>();
      var rows = CarlService.rows(c, "SELECT * FROM carl_rental_component WHERE split_id=? AND version=? ORDER BY component_id LIMIT 101", id, version);
      if(rows.size() > 100)
      {
         throw new IllegalArgumentException("Rental component bound exceeded");
      }
      for(var part : rows)
      {
         var shares = new TreeMap<String, BigDecimal>();
         var allocations = CarlService.rows(c, "SELECT property_id,fraction FROM carl_rental_allocation WHERE split_id=? AND version=? AND component_id=? ORDER BY property_id LIMIT 101", id, version, part.get("component_id"));
         if(allocations.size() > 100)
         {
            throw new IllegalArgumentException("Rental allocation bound exceeded");
         }
         for(var allocation : allocations)
         {
            shares.put(allocation.get("property_id").toString(), (BigDecimal) allocation.get("fraction"));
         }
         parts.add(new RentalEconomics.Component(part.get("component_id").toString(), RentalEconomics.Kind.valueOf(part.get("kind").toString()), (BigDecimal) part.get("amount"), shares, (BigDecimal) part.get("outside_fraction")));
      }
      return parts;
   }



   static Set<Long> validateComponents(Connection c, String principal, Map<String, Object> source, List<RentalEconomics.Component> components) throws SQLException
   {
      if(components == null || components.isEmpty() || components.size() > 100)
      {
         throw new IllegalArgumentException("Bounded evidenced source components are required");
      }
      var properties = new HashSet<Long>();
      var ids = new HashSet<String>();
      BigDecimal sum = BigDecimal.ZERO;
      String currency = source.get("currency").toString();
      for(var part : components)
      {
         if(part == null || part.id() == null || part.id().isBlank() || part.id().length() > 150 || !ids.add(part.id()) || part.kind() == null || part.propertyFractions().size() > 100)
         {
            throw new IllegalArgumentException("Source components need unique bounded IDs and explicit kinds");
         }
         BigDecimal amount = money(part.amount(), currency, false);
         if(amount.signum() <= 0)
         {
            throw new IllegalArgumentException("Components require positive magnitudes");
         }
         sum = sum.add(Set.of(RentalEconomics.Kind.RENT_RECEIPT, RentalEconomics.Kind.DEPOSIT_RECEIPT).contains(part.kind()) ? amount : amount.negate());
         fraction(part.outsideScopeFraction(), true);
         BigDecimal total = part.outsideScopeFraction();
         for(var share : part.propertyFractions().entrySet())
         {
            long id;
            try
            {
               id = Long.parseLong(share.getKey());
            }
            catch(NumberFormatException invalid)
            {
               throw new IllegalArgumentException("Property allocation identities must be persisted record IDs", invalid);
            }
            var property = require(c, principal, "carl_rental_property_view", id);
            if(!currency.equals(property.get("currency")))
            {
               throw new IllegalArgumentException("Property allocation currency differs from source");
            }
            fraction(share.getValue(), false);
            total = total.add(share.getValue());
            properties.add(id);
         }
         if(total.compareTo(BigDecimal.ONE) != 0)
         {
            throw new IllegalArgumentException("Property/outside fractions must sum to one");
         }
      }
      if(sum.compareTo((BigDecimal) source.get("amount")) != 0)
      {
         throw new IllegalArgumentException("Components do not reconcile to the imported source amount");
      }
      if(properties.isEmpty())
      {
         throw new IllegalArgumentException("At least one permitted property allocation is required");
      }
      return properties;
   }



   private static void writeVersion(Connection c, CarlService.Member actor, long split, int version, Map<String, Object> source, List<RentalEconomics.Component> components, String hash, String evidence) throws SQLException
   {
      long transaction = CarlService.number(source, "id");
      CarlService.execute(c, "INSERT INTO carl_rental_split_version(split_id,version,transaction_revision,source_amount,source_date,currency,input_hash,evidence,created_by) VALUES(?,?,?,?,?,?,?,?,?)", split, version, revision(c, transaction), source.get("amount"), LocalDate.parse(source.get("effective_date").toString()), source.get("currency"), hash, evidence, actor.id());
      for(var part : components)
      {
         CarlService.execute(c, "INSERT INTO carl_rental_component(split_id,version,component_id,kind,amount,outside_fraction) VALUES(?,?,?,?,?,?)", split, version, part.id(), part.kind().name(), part.amount(), part.outsideScopeFraction());
         for(var share : part.propertyFractions().entrySet())
         {
            CarlService.execute(c, "INSERT INTO carl_rental_allocation(split_id,version,component_id,property_id,fraction) VALUES(?,?,?,?,?)", split, version, part.id(), Long.parseLong(share.getKey()), share.getValue());
         }
      }
   }



   private static List<Object> componentIdentity(List<RentalEconomics.Component> components)
   {
      if(components == null || components.isEmpty() || components.size() > 100 || components.stream().anyMatch(part -> part == null || part.id() == null))
      {
         throw new IllegalArgumentException("Bounded explicit source components are required");
      }
      var identities = new ArrayList<Object>();
      for(var part : components.stream().sorted(java.util.Comparator.comparing(RentalEconomics.Component::id)).toList())
      {
         var input = new TreeMap<String, Object>();
         input.put("id", part.id());
         input.put("kind", part.kind());
         input.put("amount", part.amount());
         input.put("shares", new TreeMap<>(part.propertyFractions()));
         input.put("outside", part.outsideScopeFraction());
         identities.add(input);
      }
      return identities;
   }



   private static String sourceDigest(String digest, Map<String, Object> source)
   {
      return BillCsv.hash(digest + ":" + source.get("id") + ":" + source.get("revision") + ":" + source.get("amount") + ":" + source.get("effective_date"));
   }



   private static String digest(Map<String, ?> input)
   {
      return BillCsv.hash(CarlService.json(new TreeMap<>(input)));
   }



   private static CarlService.Member manager(Connection c, String principal) throws SQLException
   {
      var initial = CarlService.member(c, principal);
      CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
      var current = CarlService.manager(c, principal, "FINANCE");
      if(current.householdId() != initial.householdId())
      {
         throw unavailable();
      }
      return current;
   }



   private static long lockScope(Connection c, CarlService.Scope scope) throws SQLException
   {
      var initial = CarlService.member(c, scope.principal());
      long epoch = CarlService.number(CarlService.rows(c, "SELECT permission_revision FROM carl_household WHERE id=? FOR SHARE", initial.householdId()).getFirst(), "permission_revision");
      for(String principal : scope.audience())
      {
         if(CarlService.member(c, principal).householdId() != initial.householdId())
         {
            throw unavailable();
         }
      }
      return epoch;
   }



   private static List<Map<String, Object>> scoped(Connection c, CarlService.Scope scope, String view, String clause, List<Object> values, int maximum) throws SQLException
   {
      if(!VIEWS.contains(view))
      {
         throw new IllegalArgumentException("Unregistered rental view");
      }
      Map<Long, Map<String, Object>> allowed = null;
      for(String principal : scope.audience().stream().sorted().toList())
      {
         var parameters = new ArrayList<Object>();
         parameters.add(principal);
         parameters.addAll(values);
         var found = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=?" + clause + " ORDER BY id LIMIT " + (maximum + 1), parameters.toArray());
         if(found.size() > maximum)
         {
            throw new IllegalArgumentException("Narrow the rental query; bounded record count exceeded");
         }
         var current = new LinkedHashMap<Long, Map<String, Object>>();
         for(var row : found)
         {
            row.remove("principal");
            current.put(CarlService.number(row, "id"), row);
         }
         if(allowed == null)
         {
            allowed = current;
         }
         else
         {
            allowed.entrySet().removeIf(entry -> !entry.getValue().equals(current.get(entry.getKey())));
         }
      }
      return allowed == null ? List.of() : List.copyOf(allowed.values());
   }



   private static Map<String, Object> require(Connection c, String principal, String view, long id) throws SQLException
   {
      if(!VIEWS.contains(view))
      {
         throw new IllegalArgumentException("Unregistered rental view");
      }
      var rows = CarlService.rows(c, "SELECT * FROM " + view + " WHERE principal=? AND id=?", principal, id);
      if(rows.size() != 1)
      {
         throw unavailable();
      }
      return rows.getFirst();
   }



   private static Map<String, Object> sourceTransaction(Connection c, String principal, long transaction) throws SQLException
   {
      var rows = CarlService.rows(c, "SELECT * FROM carl_transaction_view WHERE principal=? AND id=?", principal, transaction);
      if(rows.size() != 1)
      {
         throw unavailable();
      }
      rows.getFirst().put("revision", revision(c, transaction));
      return rows.getFirst();
   }



   private static long revision(Connection c, long id) throws SQLException
   {
      return CarlService.number(CarlService.rows(c, "SELECT revision FROM carl_record WHERE id=?", id).getFirst(), "revision");
   }



   private static void touch(Connection c, long id) throws SQLException
   {
      CarlService.execute(c, "UPDATE carl_record SET revision=revision+1 WHERE id=?", id);
   }



   private static String placeholders(int count)
   {
      return String.join(",", java.util.Collections.nCopies(count, "?"));
   }



   private static SecurityException unavailable()
   {
      return new SecurityException("Carl rental record or operation unavailable");
   }



   private static void validateProperty(PropertyValues values)
   {
      if(values == null || values.currency() == null)
      {
         throw new IllegalArgumentException("Explicit property currency is required");
      }
      Currency.getInstance(values.currency());
      CarlService.bounded(values.locality(), 200, "property locality");
      if(values.ownershipFraction() != null)
      {
         fraction(values.ownershipFraction(), false);
      }
      if(values.legalOwner() != null)
      {
         CarlService.bounded(values.legalOwner(), 500, "supplied legal owner");
      }
      if(values.marketValue() != null)
      {
         money(values.marketValue(), values.currency(), false);
      }
      if((values.marketValue() == null) != (values.valuationDate() == null))
      {
         throw new IllegalArgumentException("Market estimate requires its valuation date; missing value is not zero");
      }
      date(values.valuationDate(), true);
      date(values.acquired(), true);
      for(var amount : java.util.Arrays.asList(values.acquisitionBasis(), values.landBasis(), values.buildingBasis()))
      {
         if(amount != null)
         {
            money(amount, values.currency(), false);
         }
      }
      if(values.acquisitionBasis() != null || values.landBasis() != null || values.buildingBasis() != null)
      {
         CarlService.bounded(values.basisEvidence(), 4000, "supplied basis evidence");
      }
      if(values.acquisitionBasis() != null && values.landBasis() != null && values.buildingBasis() != null && values.landBasis().add(values.buildingBasis()).compareTo(values.acquisitionBasis()) != 0)
      {
         throw new IllegalArgumentException("Supplied land/building basis must reconcile to acquisition basis");
      }
   }



   private static void validateAccounts(Connection c, String principal, PropertyValues values) throws SQLException
   {
      for(var account : java.util.Arrays.asList(values.assetAccount(), values.debtAccount()))
      {
         if(account == null)
         {
            continue;
         }
         var found = CarlService.rows(c, "SELECT currency,kind FROM carl_account_view WHERE principal=? AND id=?", principal, account);
         if(found.size() != 1 || !values.currency().equals(found.getFirst().get("currency")))
         {
            throw unavailable();
         }
         String kind = found.getFirst().get("kind").toString();
         if(account.equals(values.assetAccount()) && !"OTHER_ASSET".equals(kind) || account.equals(values.debtAccount()) && !Set.of("LOAN", "CREDIT_CARD").contains(kind))
         {
            throw new IllegalArgumentException("Property assets and financing must use appropriately typed source accounts");
         }
      }
   }



   private static void writeProperty(Connection c, long id, PropertyValues values) throws SQLException
   {
      CarlService.execute(c, "UPDATE carl_property SET locality=?,ownership_share=?,asset_account_id=?,debt_account_id=?,legal_owner=?,market_value=?,valuation_date=?,acquisition_date=?,acquisition_basis=?,land_basis=?,building_basis=?,basis_evidence=? WHERE record_id=?",
         values.locality(), values.ownershipFraction(), values.assetAccount(), values.debtAccount(), values.legalOwner(), values.marketValue(), values.valuationDate(), values.acquired(), values.acquisitionBasis(), values.landBasis(), values.buildingBasis(), values.basisEvidence(), id);
   }



   private static void fraction(BigDecimal value, boolean zeroAllowed)
   {
      if(value == null || value.precision() > 18 || Math.abs((long) value.scale()) > 10 || value.signum() < 0 || !zeroAllowed && value.signum() == 0 || value.compareTo(BigDecimal.ONE) > 0)
      {
         throw new IllegalArgumentException("Explicit bounded fractions from zero/positive through one are required");
      }
   }



   private static BigDecimal money(BigDecimal value, String currency, boolean signed)
   {
      int scale = Currency.getInstance(currency).getDefaultFractionDigits();
      if(value == null || !signed && value.signum() < 0 || value.precision() > 18 || Math.abs((long) value.scale()) > 18 || value.abs().compareTo(new BigDecimal("100000000000000")) > 0 || scale < 0 || scale > 4)
      {
         throw new IllegalArgumentException("Explicit bounded exact amounts are required");
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



   private static void date(LocalDate value, boolean optional)
   {
      if(value == null && optional)
      {
         return;
      }
      if(value == null || value.getYear() < 1900 || value.getYear() > 2300)
      {
         throw new IllegalArgumentException("Explicit bounded dates are required");
      }
   }
}
