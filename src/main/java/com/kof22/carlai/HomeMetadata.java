/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.HomeRecords;


/** Native property profiles over Carl's authoritative home/property/account records. */
public final class HomeMetadata
{
   private HomeMetadata()
   {
   }



   /** Consumer factory hook; no independent household or house application is created. */
   public static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var homes = new HomeRecords(service);
      for(var table : List.of(
         CarlMetadata.table("carlHomes", "Homes and Properties", "carl_home_view", "revision:L,property_use:S,currency:S,locality:S,ownership_share:R,asset_account_id:L,debt_account_id:L,market_value:M,valuation_date:D,mortgage_as_of:D,mortgage_principal:M,annual_rate:R,payment_amount:M,first_payment:D,maturity_date:D,amortization_months:L,rate_kind:S,escrow_amount:M,fee_amount:M,assumptions:T,profile_evidence:T", false),
         CarlMetadata.table("carlHomeHistory", "Home Profile History", "carl_home_history_view", "property_id:L,property_revision:L,actor_id:L,snapshot:T,created_at:I", false)))
      {
         if(table.getName().equals("carlHomes"))
         {
            table.getField("asset_account_id").withPossibleValueSourceName("carlAccounts");
            table.getField("debt_account_id").withPossibleValueSourceName("carlAccounts");
         }
         else
         {
            table.getField("property_id").withPossibleValueSourceName("carlHomes");
         }
         instance.addTable(table);
         app.withChild(table);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      }
      CarlMetadata.choices(instance, "carlHomeUse", Arrays.stream(HomeRecords.Use.values()).map(Enum::name).toList());
      CarlMetadata.choices(instance, "carlMortgageRateKind", Arrays.stream(HomeRecords.RateKind.values()).map(Enum::name).toList());
      var review = new QFrontendStepMetaData().withName("review").withLabel("Review supplied home terms")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)).withViewField(field("summary", QFieldType.TEXT, false))
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM));
      var fields = List.of(field("propertyUse", QFieldType.STRING, true).withPossibleValueSourceName("carlHomeUse"), field("mortgageSupplied", QFieldType.BOOLEAN, true).withLabel("Record supplied mortgage terms; otherwise clear the profile terms as unknown"),
         field("asOf", QFieldType.DATE, false), field("principal", QFieldType.DECIMAL, false), field("annualRate", QFieldType.DECIMAL, false).withLabel("Annual rate as fraction; 0.0425 means 4.25%"), field("payment", QFieldType.DECIMAL, false),
         field("firstPayment", QFieldType.DATE, false), field("maturity", QFieldType.DATE, false), field("amortizationMonths", QFieldType.INTEGER, false), field("rateKind", QFieldType.STRING, false).withPossibleValueSourceName("carlMortgageRateKind"),
         field("escrow", QFieldType.DECIMAL, false), field("fee", QFieldType.DECIMAL, false), field("assumptions", QFieldType.TEXT, false), field("evidence", QFieldType.TEXT, true));
      fields.forEach(review::withFormField);
      var process = new QProcessMetaData().withName("carlSaveHomeProfile").withTableName("carlHomes").withLabel("Record Home Use and Mortgage Terms").withPermissionRules(OperatorPermissions.require(Role.OPERATOR))
         .withStep(new QFrontendStepMetaData().withName("choose").withLabel("Choose property").withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)).withFormField(field("property", QFieldType.LONG, true).withPossibleValueSourceName("carlHomes")))
         .withStep(new QBackendStepMetaData().withName("load").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            var current = homes.get(CarlMetadata.principal(), Long.parseLong(in.getValueString("property")));
            out.addValue("requestId", UUID.randomUUID().toString());
            out.addValue("expectedRevision", (java.io.Serializable) current.get("revision"));
            out.addValue("currency", (java.io.Serializable) current.get("currency"));
            out.addValue("propertyUse", (java.io.Serializable) current.get("property_use"));
            out.addValue("rateKind", (java.io.Serializable) current.get("rate_kind"));
            out.addValue("mortgageSupplied", current.get("profile_evidence") != null && current.get("debt_account_id") != null && !"Mortgage terms not supplied".equals(current.get("assumptions")));
            for(String[] mapping : new String[][]{{"asOf", "mortgage_as_of"}, {"principal", "mortgage_principal"}, {"annualRate", "annual_rate"}, {"payment", "payment_amount"}, {"firstPayment", "first_payment"}, {"maturity", "maturity_date"}, {"amortizationMonths", "amortization_months"}, {"escrow", "escrow_amount"}, {"fee", "fee_amount"}, {"assumptions", "assumptions"}, {"evidence", "profile_evidence"}})
            {
               out.addValue(mapping[0], (java.io.Serializable) current.get(mapping[1]));
            }
            out.addValue("summary", current.get("title") + " — current property revision " + current.get("revision") + ". Optional terms stay unknown. Payment, escrow and fees are separate supplied assumptions. This profile changes no account balance, rent, title or external obligation.");
         }))).withStep(review)
         .withStep(new QBackendStepMetaData().withName("save").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
         {
            HomeRecords.Mortgage mortgage = null;
            if(Boolean.parseBoolean(in.getValueString("mortgageSupplied")))
            {
               mortgage = new HomeRecords.Mortgage(date(in.getValueString("asOf")), decimal(in.getValueString("principal")), decimal(in.getValueString("annualRate")), decimal(in.getValueString("payment")), date(in.getValueString("firstPayment")), date(in.getValueString("maturity")), integer(in.getValueString("amortizationMonths")), HomeRecords.RateKind.valueOf(in.getValueString("rateKind") == null ? "UNKNOWN" : in.getValueString("rateKind")), decimal(in.getValueString("escrow")), decimal(in.getValueString("fee")), in.getValueString("assumptions"));
            }
            long revision = homes.save(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), Long.parseLong(in.getValueString("property")), Long.parseLong(in.getValueString("expectedRevision")), new HomeRecords.ProfileValues(HomeRecords.Use.valueOf(in.getValueString("propertyUse")), in.getValueString("currency"), mortgage, in.getValueString("evidence")));
            out.addValue("result", "Supplied home profile retained at property revision " + revision + ". No financial or property action was executed.");
         })))
         .withStep(new QFrontendStepMetaData().withName("result").withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)).withViewField(field("result", QFieldType.TEXT, false)));
      instance.addProcess(process);
      app.withChild(process);
   }



   private static QFieldMetaData field(String name, QFieldType type, boolean required)
   {
      return new QFieldMetaData(name, type).withIsRequired(required);
   }



   private static BigDecimal decimal(String value)
   {
      return value == null || value.isBlank() ? null : new BigDecimal(value);
   }



   private static LocalDate date(String value)
   {
      return value == null || value.isBlank() ? null : LocalDate.parse(value);
   }



   private static Integer integer(String value)
   {
      return value == null || value.isBlank() ? null : Integer.valueOf(value);
   }
}
