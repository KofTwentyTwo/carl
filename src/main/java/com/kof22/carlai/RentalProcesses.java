/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.RentalEconomics;
import com.kof22.carlai.domain.RentalRecords;


/** Native human workflows over Carl's permission-scoped rental records. */
final class RentalProcesses
{
   private RentalProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var rentals = new RentalRecords(service);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("carlProperties"));
      for(var table : List.of(
         CarlMetadata.table("carlRentalUnits", "Rental Units", "carl_rental_unit_view", "property_id:L,unit_label:S,scheduled_rent:M,currency:S,lease_start:D,lease_end:D,occupancy:S"),
         CarlMetadata.table("carlRentalSources", "Rental Source Classifications", "carl_rental_source_view", "transaction_id:L,current_version:L,source_amount:M,source_date:D,currency:S,source_stale:B"),
         CarlMetadata.table("carlRentDues", "Scheduled Rent", "carl_rent_due_view", "property_id:L,unit_id:L,due_date:D,amount:M,currency:S"),
         CarlMetadata.table("carlRentApplications", "Rent Receipt Applications", "carl_rent_application_view", "rent_due_id:L,split_id:L,version:L,component_id:S,amount:M,active:B,property_id:L")))
      {
         instance.addTable(table);
         app.withChild(table);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      }
      CarlMetadata.choices(instance, "carlOccupancy", List.of("OCCUPIED", "VACANT", "UNKNOWN"));
      CarlMetadata.choices(instance, "carlRentalKind", Arrays.stream(RentalEconomics.Kind.values()).map(Enum::name).toList());
      var propertyFields = new ArrayList<QFieldMetaData>(List.of(f("title", QFieldType.STRING), visibility(), f("evidence", QFieldType.TEXT)));
      propertyFields.addAll(propertyFields());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlAddRentalProperty", "Record Rental Property Facts", propertyFields, (in, out) ->
      {
         long id = rentals.createProperty(CarlMetadata.principal(), request(in), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("evidence"), propertyValues(in));
         out.addValue("result", "Property " + id + " saved. Unknown ownership and basis remain unknown. No legal ownership change occurred.");
      }));
      var correctionFields = new ArrayList<QFieldMetaData>(List.of(property(), f("expectedRevision", QFieldType.LONG), f("reason", QFieldType.TEXT)));
      correctionFields.addAll(propertyFields());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlCorrectRentalProperty", "Correct Rental Property Facts", correctionFields, (in, out) ->
      {
         rentals.correctProperty(CarlMetadata.principal(), request(in), id(in, "property"), id(in, "expectedRevision"), propertyValues(in), in.getValueString("reason"));
         out.addValue("result", "Current facts corrected with attribution; original evidence and prior values retained.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlAddRentalUnit", "Record Rental Unit", List.of(property(), f("title", QFieldType.STRING), visibility(), f("evidence", QFieldType.TEXT), f("scheduledRent", QFieldType.DECIMAL).withIsRequired(false), f("leaseStart", QFieldType.DATE).withIsRequired(false), f("leaseEnd", QFieldType.DATE).withIsRequired(false), f("occupancy", QFieldType.STRING).withPossibleValueSourceName("carlOccupancy")), (in, out) ->
      {
         long id = rentals.createUnit(CarlMetadata.principal(), request(in), id(in, "property"), in.getValueString("title"), in.getValueString("visibility"), in.getValueString("evidence"), new RentalRecords.UnitValues(in.getValueString("title"), money(in, "scheduledRent"), in.getValueLocalDate("leaseStart"), in.getValueLocalDate("leaseEnd"), in.getValueString("occupancy")));
         out.addValue("result", "Rental unit " + id + " saved with supplied occupancy and lease evidence.");
      }));
      var classificationFields = new ArrayList<QFieldMetaData>(List.of(property(), pick("transaction", "carlTransactions"), visibility(), f("evidence", QFieldType.TEXT)));
      for(int n = 1; n <= 3; n++)
      {
         classificationFields.add(f("kind" + n, QFieldType.STRING).withPossibleValueSourceName("carlRentalKind").withIsRequired(n == 1));
         classificationFields.add(f("amount" + n, QFieldType.DECIMAL).withIsRequired(n == 1));
      }
      CarlMetadata.add(instance, app, CarlMetadata.process("carlClassifyRentalSource", "Classify One-Property Rental Transaction", classificationFields, (in, out) ->
      {
         var components = new ArrayList<RentalEconomics.Component>();
         for(int n = 1; n <= 3; n++)
         {
            String kind = in.getValueString("kind" + n);
            BigDecimal amount = money(in, "amount" + n);
            if((kind == null) != (amount == null))
            {
               throw new IllegalArgumentException("Each component requires both kind and amount");
            }
            if(kind != null)
            {
               components.add(new RentalEconomics.Component("component-" + n, RentalEconomics.Kind.valueOf(kind), amount, Map.of(Long.toString(id(in, "property")), BigDecimal.ONE), BigDecimal.ZERO));
            }
         }
         long id = rentals.classify(CarlMetadata.principal(), request(in), id(in, "transaction"), components, in.getValueString("visibility"), in.getValueString("evidence"));
         out.addValue("result", "Classification " + id + " saved. Components must reconcile exactly to the original transaction. This form allocates the whole source to one property; it does not infer shared-property splits.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRecordRentDue", "Record Scheduled Rent", List.of(property(), pick("unit", "carlRentalUnits").withIsRequired(false), f("due", QFieldType.DATE), f("amount", QFieldType.DECIMAL).withIsRequired(false), visibility(), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = rentals.rentDue(CarlMetadata.principal(), request(in), id(in, "property"), optionalId(in, "unit"), in.getValueLocalDate("due"), money(in, "amount"), in.getValueString("visibility"), in.getValueString("evidence"));
         out.addValue("result", "Scheduled rent " + id + " saved. A due date does not establish receipt.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlApplyRentReceipt", "Apply Evidenced Rent Receipt", List.of(pick("due", "carlRentDues"), pick("source", "carlRentalSources"), f("version", QFieldType.INTEGER), f("component", QFieldType.STRING).withLabel("Receipt component identifier (for example component-1)"), f("amount", QFieldType.DECIMAL), f("evidence", QFieldType.TEXT)), (in, out) ->
      {
         long id = rentals.applyRent(CarlMetadata.principal(), request(in), id(in, "due"), id(in, "source"), Integer.parseInt(in.getValueString("version")), in.getValueString("component"), money(in, "amount"), in.getValueString("evidence"));
         out.addValue("result", "Receipt application " + id + " saved with source evidence. No money was moved.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlUnapplyRentReceipt", "Correct Rent Receipt Application", List.of(pick("application", "carlRentApplications"), f("reason", QFieldType.TEXT)), (in, out) ->
      {
         rentals.unapplyRent(CarlMetadata.principal(), request(in), id(in, "application"), in.getValueString("reason"));
         out.addValue("result", "Application retired with attribution; source receipt and history retained.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRentalReport", "Prepare Rental Property Report", List.of(property(), f("from", QFieldType.DATE), f("through", QFieldType.DATE), f("asOf", QFieldType.DATE)), (in, out) ->
      {
         long report = rentals.report(CarlService.Scope.privateFor(CarlMetadata.principal()), request(in), Set.of(id(in, "property")), in.getValueLocalDate("from"), in.getValueLocalDate("through"), in.getValueLocalDate("asOf"));
         out.addValue("result", service.artifact(CarlMetadata.principal(), report).get("facts").toString() + "\nSaved report " + report + " covers supplied records only. Missing ownership, rent applications and source coverage remain explicit.");
      }));
   }



   private static List<QFieldMetaData> propertyFields()
   {
      return List.of(f("currency", QFieldType.STRING), f("locality", QFieldType.STRING), f("ownership", QFieldType.DECIMAL).withLabel("Evidenced ownership fraction (0.5 means half)").withIsRequired(false), pick("assetAccount", "carlAccounts").withIsRequired(false), pick("debtAccount", "carlAccounts").withIsRequired(false), f("legalOwner", QFieldType.STRING).withIsRequired(false), f("marketValue", QFieldType.DECIMAL).withIsRequired(false), f("valuationDate", QFieldType.DATE).withIsRequired(false), f("acquired", QFieldType.DATE).withIsRequired(false), f("acquisitionBasis", QFieldType.DECIMAL).withIsRequired(false), f("landBasis", QFieldType.DECIMAL).withIsRequired(false), f("buildingBasis", QFieldType.DECIMAL).withIsRequired(false), f("basisEvidence", QFieldType.TEXT).withIsRequired(false));
   }



   private static RentalRecords.PropertyValues propertyValues(RunBackendStepInput in)
   {
      return new RentalRecords.PropertyValues(in.getValueString("currency"), in.getValueString("locality"), money(in, "ownership"), optionalId(in, "assetAccount"), optionalId(in, "debtAccount"), in.getValueString("legalOwner"), money(in, "marketValue"), in.getValueLocalDate("valuationDate"), in.getValueLocalDate("acquired"), money(in, "acquisitionBasis"), money(in, "landBasis"), money(in, "buildingBasis"), in.getValueString("basisEvidence"));
   }



   private static BigDecimal money(RunBackendStepInput in, String key)
   {
      String value = in.getValueString(key);
      return value == null || value.isBlank() ? null : new BigDecimal(value);
   }



   private static Long optionalId(RunBackendStepInput in, String key)
   {
      String value = in.getValueString(key);
      return value == null || value.isBlank() ? null : Long.valueOf(value);
   }



   private static long id(RunBackendStepInput in, String key)
   {
      return Long.parseLong(in.getValueString(key));
   }



   private static UUID request(RunBackendStepInput in)
   {
      return UUID.fromString(in.getValueString("requestId"));
   }



   private static QFieldMetaData property()
   {
      return pick("property", "carlProperties");
   }



   private static QFieldMetaData visibility()
   {
      return f("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility");
   }



   private static QFieldMetaData pick(String name, String table)
   {
      return f(name, QFieldType.LONG).withPossibleValueSourceName(table);
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }
}
