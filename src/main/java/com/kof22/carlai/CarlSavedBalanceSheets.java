/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.kingsrook.qqq.backend.core.actions.values.QCustomPossibleValueProvider;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.InitializableViaCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DashboardFacts;


/** Native choices for selected balance sheets, bound to this application's authoritative service. */
public final class CarlSavedBalanceSheets implements QCustomPossibleValueProvider<Long>, InitializableViaCodeReference
{
   private DashboardFacts facts;

   /** Public constructor required by QQQ's code loader. */
   public CarlSavedBalanceSheets()
   {
   }



   static void register(QInstance instance, CarlService service)
   {
      instance.addPossibleValueSource(new QPossibleValueSource().withName("carlSavedBalanceSheets").withLabel("Saved selected balance sheets").withType(QPossibleValueSourceType.CUSTOM).withIdType(QFieldType.LONG).withCustomCodeReference(new ServiceReference(service)));
   }

   private static final class ServiceReference extends QCodeReference
   {
      private final CarlService service;

      private ServiceReference(CarlService service)
      {
         super(CarlSavedBalanceSheets.class);
         this.service = java.util.Objects.requireNonNull(service);
      }
   }

   @Override
   public void initialize(QCodeReference reference)
   {
      if(!(reference instanceof ServiceReference bound))
      {
         throw new IllegalArgumentException("Carl saved balance sheets require the application service");
      }
      facts = new DashboardFacts(bound.service);
   }



   @Override
   public QPossibleValue<Long> getPossibleValue(Serializable idValue) throws QException
   {
      var scope = CarlService.Scope.privateFor(CarlMetadata.principal());
      var choices = facts.savedBalanceSheets(scope, id(idValue));
      if(choices.size() != 1)
      {
         throw new QException("Selected saved balance sheet unavailable");
      }
      var choice = value(choices.getFirst());
      facts.requireAccess(scope);
      return choice;
   }



   @Override
   public List<QPossibleValue<Long>> search(SearchPossibleValueSourceInput input) throws QException
   {
      var scope = CarlService.Scope.privateFor(CarlMetadata.principal());
      String term = input.getSearchTerm() == null ? "" : input.getSearchTerm();
      int skip = input.getSkip() == null ? 0 : input.getSkip();
      int limit = input.getLimit() == null ? 250 : input.getLimit();
      if(term.length() > 500 || skip < 0 || skip > 1000 || limit < 1 || limit > 250 || input.getDefaultQueryFilter() != null)
      {
         throw new QException("Use a bounded saved balance-sheet search, exact ID or exact label");
      }
      Set<Long> ids = null;
      if(input.getIdList() != null)
      {
         if(input.getIdList().size() > 250)
         {
            throw new QException("At most 250 saved balance-sheet IDs per search");
         }
         ids = input.getIdList().stream().map(CarlSavedBalanceSheets::id).collect(Collectors.toUnmodifiableSet());
      }
      Set<String> labels = null;
      if(input.getLabelList() != null)
      {
         if(input.getLabelList().size() > 250 || input.getLabelList().stream().anyMatch(label -> label == null || label.length() > 500))
         {
            throw new QException("At most 250 bounded saved balance-sheet labels per search");
         }
         labels = Set.copyOf(input.getLabelList());
      }
      var eligible = facts.savedBalanceSheets(scope, ids != null && ids.size() == 1 ? ids.iterator().next() : null);
      Set<Long> exactIds = ids;
      Set<String> exactLabels = labels;
      String query = term.toLowerCase(Locale.ROOT);
      var result = eligible.stream().map(CarlSavedBalanceSheets::value).filter(value -> exactIds == null || exactIds.contains(value.getId())).filter(value -> exactLabels == null || exactLabels.contains(value.getLabel())).filter(value -> value.getLabel().toLowerCase(Locale.ROOT).contains(query)).skip(skip).limit(limit).toList();
      facts.requireAccess(scope);
      // QQQ filters dropdown choices in place after the native provider returns.
      return new java.util.ArrayList<>(result);
   }



   private static long id(Serializable value)
   {
      if(value == null || !value.toString().matches("[1-9][0-9]{0,18}"))
      {
         throw new IllegalArgumentException("Positive canonical saved balance-sheet ID required");
      }
      return Long.parseLong(value.toString());
   }



   private static QPossibleValue<Long> value(DashboardFacts.SavedBalanceSheet saved)
   {
      return new QPossibleValue<>(saved.id(), "Balance sheet #" + saved.id() + " | as of " + saved.asOf() + " | " + (saved.stale() ? "Stale sources" : "Current sources") + " | " + saved.status());
   }
}
