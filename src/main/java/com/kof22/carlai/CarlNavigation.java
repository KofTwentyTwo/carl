/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.ArrayList;
import java.util.List;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.branding.QBrandingMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/** Native financial groupings keep existing table/process IDs and direct URLs intact. */
final class CarlNavigation
{
   private static final List<String> LABELS = List.of("Overview", "Money", "Plans", "Properties and Tax", "Calendar and Reminders", "Vendors", "Documents and Data", "Settings");
   private static final List<String> NAMES = List.of("carlOverview", "carlMoney", "carlPlanning", "carlPropertyTax", "carlCalendarReminders", "carlVendorWorkspace", "carlDocumentsData", "carlSettings");
   private static final List<String> ICONS = List.of("dashboard", "account_balance_wallet", "route", "real_estate_agent", "event", "handshake", "folder_open", "settings");

   private CarlNavigation()
   {
   }



   static void apply(QInstance instance, QAppMetaData app)
   {
      String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><rect width='64' height='64' rx='14' fill='#203040'/><path d='M44 20H30a12 12 0 0 0 0 24h14' fill='none' stroke='#fff' stroke-width='7'/><path d='M44 31H31' stroke='#40b8a4' stroke-width='7'/></svg>";
      String icon = "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      instance.setBranding(new QBrandingMetaData().withAppName("Carl AI").withCompanyName("Carl AI").withLogo(icon).withIcon(icon).withAccentColor("#2563eb").withAccentColorLight("#dbeafe"));
      app.withIcon(new QIcon().withName("dashboard"));
      var sections = new ArrayList<QAppSection>();
      for(int n = 0; n < LABELS.size(); n++)
      {
         sections.add(new QAppSection().withName(NAMES.get(n)).withLabel(LABELS.get(n)).withIcon(new QIcon().withName(ICONS.get(n))).withTables(new ArrayList<>()).withProcesses(new ArrayList<>()).withReports(new ArrayList<>()).withApps(new ArrayList<>()));
      }
      var originalChildren = List.copyOf(app.getChildren());
      for(var child : originalChildren)
      {
         var section = sections.get(group(child.getName()));
         if(child instanceof QTableMetaData)
         {
            section.withTable(child.getName());
         }
         else if(child instanceof QProcessMetaData)
         {
            section.withProcess(child.getName());
         }
      }
      app.getChildren().clear();
      for(int n = 0; n < LABELS.size(); n++)
      {
         var dashboard = new QAppMetaData().withName(NAMES.get(n)).withLabel(LABELS.get(n)).withIcon(new QIcon().withName(ICONS.get(n))).withChildren(new ArrayList<>()).withSortOrder(n + 1);
         if(n == 0)
         {
            dashboard.withWidgets(List.of("carlCashFlow", "carlPlanProgress"));
         }
         else if(n == 1)
         {
            dashboard.withWidgets(List.of("carlCashFlow", "carlIncomeExpense", "carlBalanceSheet"));
         }
         else if(n == 2)
         {
            dashboard.withWidgets(List.of("carlPlanProgress"));
         }
         for(var child : originalChildren)
         {
            if(group(child.getName()) == n)
            {
               dashboard.withChild(child);
            }
         }
         var original = sections.get(n);
         if(!original.getTables().isEmpty() || !original.getProcesses().isEmpty())
         {
            dashboard.withSections(List.of(new QAppSection().withName(NAMES.get(n) + "Records").withLabel(LABELS.get(n)).withTables(new ArrayList<>(original.getTables())).withProcesses(new ArrayList<>(original.getProcesses())).withReports(new ArrayList<>()).withApps(new ArrayList<>())));
         }
         instance.addApp(dashboard);
         app.withChild(dashboard);
         sections.get(n).withApp(dashboard.getName());
      }
      app.withWidgets(List.of("carlCashFlow", "carlPlanProgress"));
   }



   private static int group(String name)
   {
      if(name.startsWith("carlTalk"))
      {
         return 0;
      }
      if(name.contains("Preference") || name.contains("Member") || name.startsWith("carlSetReport") || name.contains("Connection"))
      {
         return 7;
      }
      if(name.contains("Rental") || name.contains("Rent") || name.contains("Tax") || name.contains("Propert"))
      {
         return 3;
      }
      if(name.contains("Calendar") || name.contains("Agenda") || name.contains("Reminder") || name.contains("Appointment") || name.contains("Availability"))
      {
         return 4;
      }
      if(name.contains("Vendor") || name.contains("Work"))
      {
         return 5;
      }
      if(name.contains("Import") || name.contains("Monarch") || name.contains("Artifact") || name.contains("Report") || name.contains("Copy") || name.contains("Export") || name.contains("Download"))
      {
         return 6;
      }
      if(name.contains("Plan") || name.contains("Goal") || name.contains("Compare") || name.contains("Scenario") || name.contains("Investment") || name.contains("Purchase") || name.contains("Effect") || name.contains("Financing"))
      {
         return 2;
      }
      return 1;
   }
}
