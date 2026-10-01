/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DomainPreferences;


/** Explicit household/member presentation choices; no policy or credential controls. */
final class PreferenceProcesses
{
   private PreferenceProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var preferences = new DomainPreferences(service);
      var table = CarlMetadata.table("carlPreferences", "Preferences", "carl_preference_view", "preference_key:S,value:S,scope:S,member_id:L,revision:L,created_at:I,updated_at:I,updated_by:L");
      instance.addTable(table);
      app.withChild(table);
      CarlMetadata.choices(instance, "carlPreferenceScope", List.of("MEMBER", "HOUSEHOLD"));
      CarlMetadata.choices(instance, "carlReportDetail", List.of("BRIEF", "STANDARD", "DETAILED"));
      CarlMetadata.choices(instance, "carlReportPeriod", List.of("DAILY", "WEEKLY", "MONTHLY"));
      CarlMetadata.choices(instance, "carlDashboardPreference", List.of("DASHBOARD_FROM", "DASHBOARD_THROUGH", "DASHBOARD_CURRENCY", "DASHBOARD_PLAN", "DASHBOARD_BALANCE"));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlSetDashboardDefault", "Choose My Dashboard Default", List.of(new QFieldMetaData("key", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName("carlDashboardPreference"), new QFieldMetaData("value", QFieldType.STRING).withIsRequired(true).withLabel("Date (YYYY-MM-DD), currency code or permitted saved record ID"), new QFieldMetaData("evidence", QFieldType.TEXT).withIsRequired(true).withLabel("Reason for this explicit selection")), (in, out) ->
      {
         preferences.set(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), "MEMBER", in.getValueString("key"), in.getValueString("value"), in.getValueString("evidence"));
         out.addValue("result", "Your dashboard selection is saved. Access to the selected records is rechecked each time.");
      }).withTableName("carlPreferences"));
      for(String key : List.of("REPORT_DETAIL", "REPORT_PERIOD"))
      {
         String process = key.equals("REPORT_DETAIL") ? "carlSetReportDetail" : "carlSetReportPeriod";
         CarlMetadata.add(instance, app, CarlMetadata.process(process, key.equals("REPORT_DETAIL") ? "Choose Report Detail" : "Choose Report Period", List.of(new QFieldMetaData("scope", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName("carlPreferenceScope"), new QFieldMetaData("value", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName(key.equals("REPORT_DETAIL") ? "carlReportDetail" : "carlReportPeriod"), new QFieldMetaData("evidence", QFieldType.TEXT).withIsRequired(true).withLabel("Reason for this explicit preference")), (in, out) ->
         {
            long id = preferences.set(CarlMetadata.principal(), UUID.fromString(in.getValueString("requestId")), in.getValueString("scope"), key, in.getValueString("value"), in.getValueString("evidence"));
            out.addValue("result", "Preference " + id + " saved. It does not schedule delivery or change permissions.");
         }));
      }
   }
}
