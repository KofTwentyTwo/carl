/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.InitializableViaCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.WidgetDropdownData;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.WidgetDropdownType;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DashboardFacts;


/** Native widgets use explicit filters, current verified identity and read-only financial facts. */
final class CarlDashboards
{
   private CarlDashboards()
   {
   }



   static void register(QInstance instance, CarlService service)
   {
      CarlSavedBalanceSheets.register(instance, service);
      CarlMetadata.choices(instance, "carlDashboardCurrency", Currency.getAvailableCurrencies().stream().filter(currency -> currency.getDefaultFractionDigits() >= 0 && currency.getDefaultFractionDigits() <= 4).map(Currency::getCurrencyCode).sorted().toList());
      add(instance, service, "carlCashFlow", "Cash Flow", List.of(date("from", "From (inclusive)"), date("through", "Through (inclusive)"), selection("carlDashboardCurrency", "Currency")));
      add(instance, service, "carlIncomeExpense", "Income and Expense Flow", List.of(date("from", "From (inclusive)"), date("through", "Through (inclusive)"), selection("carlDashboardCurrency", "Currency")));
      add(instance, service, "carlBalanceSheet", "Balance Sheet", List.of(selection("carlSavedBalanceSheets", "Saved selected balance sheet")));
      add(instance, service, "carlPlanProgress", "Financial Plan and Progress", List.of(selection("carlPlans", "Plan")));
   }



   private static WidgetDropdownData date(String name, String label)
   {
      return new WidgetDropdownData().withType(WidgetDropdownType.DATE_PICKER).withName(name).withLabel(label).withIsRequired(true);
   }



   private static WidgetDropdownData selection(String source, String label)
   {
      return new WidgetDropdownData().withPossibleValueSourceName(source).withLabel(label).withIsRequired(true);
   }



   private static void add(QInstance instance, CarlService service, String name, String label, List<WidgetDropdownData> dropdowns)
   {
      instance.addWidget(new QWidgetMetaData().withName(name).withLabel(label).withType("html").withGridColumns(12).withIsCard(true).withShowReloadButton(true).withShowExportButton(false).withStoreDropdownSelections(false).withMinHeight("160px").withPermissionRules(OperatorPermissions.require(Role.OPERATOR)).withDropdowns(dropdowns).withCodeReference(new ServiceReference(service)));
   }

   private static final class ServiceReference extends QCodeReference
   {
      private final CarlService service;

      private ServiceReference(CarlService service)
      {
         super(Renderer.class);
         this.service = service;
      }
   }



   /** Instantiated by QQQ's code loader using the explicitly bound application service. */
   public static final class Renderer extends AbstractWidgetRenderer implements InitializableViaCodeReference
   {
      private DashboardFacts facts;

      /** QQQ requires a public no-argument renderer constructor. */
      public Renderer()
      {
      }



      @Override
      public void initialize(QCodeReference reference)
      {
         if(!(reference instanceof ServiceReference bound))
         {
            throw new IllegalArgumentException("Carl dashboard requires the application service");
         }
         facts = new DashboardFacts(bound.service);
      }



      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         var scope = CarlService.Scope.privateFor(CarlMetadata.principal());
         facts.requireAccess(scope);
         var metadata = (QWidgetMetaData) input.getWidgetMetaData();
         var output = new RawHTML(metadata.getLabel(), CarlDashboardHtml.selection(metadata.getName()));
         if(input.getQueryParams() == null)
         {
            input.withUrlParams(Map.of());
         }
         if(setupDropdowns(input, metadata, output))
         {
            var parameters = input.getQueryParams();
            String name = metadata.getName();
            try
            {
               Map<String, Object> selected = switch(name)
               {
                  case "carlCashFlow", "carlIncomeExpense" -> facts.cashFlow(scope, LocalDate.parse(parameters.get("from")), LocalDate.parse(parameters.get("through")), parameters.get("carlDashboardCurrency"));
                  case "carlBalanceSheet" -> facts.balanceSheet(scope, Long.parseLong(parameters.get("carlSavedBalanceSheets")));
                  case "carlPlanProgress" -> facts.plan(scope, Long.parseLong(parameters.get("carlPlans")));
                  default -> throw new IllegalArgumentException("Unknown financial dashboard");
               };
               output.withHtml(CarlDashboardHtml.render(name, selected));
            }
            catch(IllegalArgumentException invalid)
            {
               throw new QException("Review the selected dashboard filters: " + invalid.getMessage(), invalid);
            }
         }
         facts.requireAccess(scope);
         return new RenderWidgetOutput(output);
      }
   }
}
