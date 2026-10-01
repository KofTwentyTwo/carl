/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlTalkService;


/** Native human conversation actions use the same persisted workflows as Carl's family clients. */
final class CarlTalkProcesses
{
   private CarlTalkProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlTalkService talk)
   {
      CarlTalkChoices.register(instance, talk);
      CarlTalkChoices.registerParticipants(instance, talk);
      CarlMetadata.choices(instance, "carlChatVisibility", List.of("PRIVATE", "SHARED"));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTalkStart", "Talk to Carl", List.of(
         new QFieldMetaData("message", QFieldType.TEXT).withLabel("Message to Carl").withIsRequired(true),
         new QFieldMetaData("visibility", QFieldType.STRING).withLabel("Conversation visibility").withDefaultValue("PRIVATE").withPossibleValueSourceName("carlChatVisibility").withIsRequired(true),
         new QFieldMetaData("participants", QFieldType.STRING).withLabel("Share with (shared conversations only)").withPossibleValueSourceName("carlTalkParticipants")), (in, out) ->
         {
            String visibility = in.getValueString("visibility");
            if(!Set.of("PRIVATE", "SHARED").contains(visibility))
            {
               throw new IllegalArgumentException("Choose private or explicitly shared");
            }
            String supplied = in.getValueString("participants");
            String principal = CarlMetadata.principal();
            Set<String> participants = supplied == null || supplied.isBlank() ? Set.of() : talk.participants(principal, supplied);
            if(visibility.equals("PRIVATE") && !participants.isEmpty())
            {
               throw new IllegalArgumentException("Private conversations cannot contain shared participants");
            }
            var message = talk.start(principal, UUID.fromString(in.getValueString("requestId")), in.getValueString("message"), visibility.equals("SHARED"), participants);
            out.addValue("result.html", CarlTalkHtml.render(message));
         }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTalkRead", "Read Carl's Response", List.of(selection()), (in, out) ->
      {
         out.addValue("result.html", CarlTalkHtml.render(talk.read(CarlMetadata.principal(), in.getValueString("selected"))));
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlTalkReply", "Continue Talking to Carl", List.of(selection(), new QFieldMetaData("message", QFieldType.TEXT).withLabel("Your message").withIsRequired(true)), (in, out) ->
      {
         out.addValue("result.html", CarlTalkHtml.render(talk.reply(CarlMetadata.principal(), in.getValueString("selected"), UUID.fromString(in.getValueString("requestId")), in.getValueString("message"))));
      }));
   }



   private static QFieldMetaData selection()
   {
      return new QFieldMetaData("selected", QFieldType.STRING).withLabel("Conversation / message to read").withPossibleValueSourceName("carlTalkMessages").withIsRequired(true);
   }
}
