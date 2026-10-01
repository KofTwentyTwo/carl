/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.CarlTalkService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/** Pure presentation boundaries supplement actual PostgreSQL conversation/authorization tests. */
class CarlNativeChatTest
{
   @Test
   void boundedHistoryUsesLatestDisplayedRequestAndDoesNotCopyAnUnboundedTranscript() throws Exception
   {
      var talk = mock(CarlTalkService.class);
      var chat = new CarlNativeChat(mock(CarlService.class), talk);
      UUID conversation = UUID.randomUUID();
      var turns = new ArrayList<CarlTalkService.Transcript>();
      for(int n = 0; n < 50; n++)
      {
         UUID request = UUID.randomUUID();
         String selected = conversation + "/" + request;
         turns.add(new CarlTalkService.Transcript(selected, "\"\\\n".repeat(3000), new CarlTalkService.Message(conversation, request, new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, null, "Completed synthetic response".repeat(500), null))));
      }
      String first = turns.getFirst().selected();
      when(talk.history("alice", first)).thenReturn(List.copyOf(turns));
      var history = chat.history("alice", first);
      assertThat(history.selected()).isEqualTo(turns.getLast().selected());
      assertThat(history.messages()).hasSizeLessThan(100);
      assertThat(history.messages().getFirst().text()).contains("Earlier messages");
      assertThat(history.messages().getLast().selected()).isEqualTo(history.selected());
      assertThat(new ObjectMapper().writeValueAsBytes(history).length).isLessThan(201000);
   }



   @Test
   void sourceChangesAndSavedSnapshotTimeRemainVisibleInPlainText()
   {
      var talk = mock(CarlTalkService.class);
      var chat = new CarlNativeChat(mock(CarlService.class), talk);
      UUID conversation = UUID.randomUUID();
      UUID request = UUID.randomUUID();
      String selected = conversation + "/" + request;
      var artifact = new ObjectMapper().createObjectNode();
      artifact.putObject("savedReport").put("narrative", "Synthetic previous total USD 200.00").put("stale", true).put("created_at", "2026-09-30T12:00:00Z").put("limitations", "Calendar source missing");
      when(talk.read("alice", selected)).thenReturn(new CarlTalkService.Message(conversation, request, new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, "42", "Saved", artifact)));
      assertThat(chat.read("alice", selected).text()).contains("source records have changed", "USD 200.00", "2026-09-30T12:00:00Z", "Calendar source missing");
   }
}
