/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.io.Serializable;
import java.util.List;

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
import com.kof22.carlai.domain.CarlTalkService;


/** Native saved-message selectors recheck conversation membership and protected sources. */
public final class CarlTalkChoices implements QCustomPossibleValueProvider<String>, InitializableViaCodeReference
{
   private CarlTalkService talk;
   private boolean participants;

   /** Native QQQ code loader entry point. */
   public CarlTalkChoices()
   {
   }



   static void register(QInstance instance, CarlTalkService talk)
   {
      instance.addPossibleValueSource(new QPossibleValueSource().withName("carlTalkMessages").withType(QPossibleValueSourceType.CUSTOM).withIdType(QFieldType.STRING).withCustomCodeReference(new Reference(talk, false)));
   }



   static void registerParticipants(QInstance instance, CarlTalkService talk)
   {
      instance.addPossibleValueSource(new QPossibleValueSource().withName("carlTalkParticipants").withType(QPossibleValueSourceType.CUSTOM).withIdType(QFieldType.STRING).withCustomCodeReference(new Reference(talk, true)));
   }

   private static final class Reference extends QCodeReference
   {
      private final CarlTalkService talk;
      private final boolean participants;
      private Reference(CarlTalkService talk, boolean participants)
      {
         super(CarlTalkChoices.class);
         this.talk = talk;
         this.participants = participants;
      }
   }

   @Override
   public void initialize(QCodeReference reference)
   {
      if(!(reference instanceof Reference bound))
      {
         throw new IllegalArgumentException("Carl message choices require the application's conversation service");
      }
      talk = bound.talk;
      participants = bound.participants;
   }



   @Override
   public QPossibleValue<String> getPossibleValue(Serializable id) throws QException
   {
      String principal = CarlMetadata.principal();
      if(participants)
      {
         var choice = talk.participantChoices(principal).stream().filter(value -> value.id().equals(id.toString())).findFirst().orElseThrow(() -> new QException("Current sharing selection unavailable"));
         return new QPossibleValue<>(choice.id(), choice.label());
      }
      var message = talk.read(principal, id.toString());
      return new QPossibleValue<>(id.toString(), "Saved message | " + message.result().status() + " | " + message.request());
   }



   @Override
   public List<QPossibleValue<String>> search(SearchPossibleValueSourceInput input) throws QException
   {
      String principal = CarlMetadata.principal();
      String term = input.getSearchTerm() == null ? "" : input.getSearchTerm();
      int skip = input.getSkip() == null ? 0 : input.getSkip();
      int limit = input.getLimit() == null ? 250 : input.getLimit();
      if(term.length() > 500 || skip < 0 || skip > 1000 || limit < 1 || limit > 250 || input.getDefaultQueryFilter() != null || input.getIdList() != null && input.getIdList().size() > 250 || input.getLabelList() != null && input.getLabelList().size() > 250)
      {
         throw new QException("Use a bounded saved-message selection");
      }
      var choices = (participants ? talk.participantChoices(principal) : talk.choices(principal)).stream().filter(choice -> input.getIdList() == null || input.getIdList().contains(choice.id()))
         .filter(choice -> input.getLabelList() == null || input.getLabelList().contains(choice.label()))
         .filter(choice -> choice.label().toLowerCase(java.util.Locale.ROOT).contains(term.toLowerCase(java.util.Locale.ROOT))).skip(skip).limit(limit)
         .map(choice -> new QPossibleValue<>(choice.id(), choice.label())).toList();
      return new java.util.ArrayList<>(choices);
   }
}
