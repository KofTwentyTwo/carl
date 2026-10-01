/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kof22.agentadmin.client.ClientWorkflow;
import com.kof22.carlai.domain.CarlTalkService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class CarlTalkHtmlTest
{
   @Test
   void savedResponseAndProtectedArtifactLinksUseActualFrontendProcessRoutesAndPrefillContract() throws Exception
   {
      var selected = new CarlTalkService.Message(UUID.randomUUID(), UUID.randomUUID(), new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, "123", "Saved report", null));
      String html = CarlTalkHtml.render(selected);
      assertNativeLink(html, "carlTalkRead", "selected", selected.conversation() + "/" + selected.request());
      assertNativeLink(html, "carlTalkReply", "selected", selected.conversation() + "/" + selected.request());
      assertNativeLink(html, "carlCopyReport", "reportId", "123");
      assertNativeLink(html, "carlDownloadReportPdf", "reportId", "123");
      assertThat(html).contains("href='/app/carlTalkStart'").doesNotContain("href='/process/");
      var artifact = new ObjectMapper().createObjectNode();
      artifact.putObject("savedReport").put("kind", "VENDOR_DRAFT");
      String draft = CarlTalkHtml.render(message(new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, "456", "Draft — not sent", artifact)));
      assertNativeLink(draft, "carlCopyVendorDraft", "draftId", "456");
      assertNativeLink(draft, "carlDownloadVendorDraft", "draftId", "456");
      String untrusted = CarlTalkHtml.render(message(new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, "123' onclick='disclose()", "Untrusted artifact identifier", artifact)));
      assertThat(untrusted).doesNotContain("carlCopyVendorDraft", "carlDownloadVendorDraft", "onclick");
   }



   @Test
   void rendersReadableFactsAndEscapesImportedHtmlRatherThanExecutingIt()
   {
      var artifact = new ObjectMapper().createObjectNode().put("message", "Bills total USD 200.00").put("narrative", "<script>disclose()</script>");
      artifact.putObject("facts").put("amount", "200.00").put("currency", "USD").put("asOf", "2026-09-30");
      artifact.putObject("export").put("contentBase64", "PRIVATE_BINARY").put("notice", "Protected PDF");
      String html = CarlTalkHtml.render(message(new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, "123", "Bills total USD 200.00", artifact)));
      assertThat(html).contains("Carl&#39;s saved response", "<dt style='font-weight:600'>as Of</dt>", "200.00", "USD", "&lt;script&gt;disclose()&lt;/script&gt;", "Continue this conversation");
      assertThat(html).doesNotContain("<script>", "PRIVATE_BINARY", "<pre", "contentBase64");
   }



   @Test
   void pendingAndUnknownReadTheSameMessageWithoutPretendingAnAnswerOrAutomaticReplay() throws Exception
   {
      var id = UUID.randomUUID();
      var conversation = UUID.randomUUID();
      var pending = new CarlTalkService.Message(conversation, id, new ClientWorkflow.Result(ClientWorkflow.Status.PENDING, null, "", null));
      String first = CarlTalkHtml.render(pending);
      assertThat(first).contains("Waiting for Carl", "do not submit another copy");
      assertNativeLink(first, "carlTalkRead", "selected", conversation + "/" + id);
      String unknown = CarlTalkHtml.render(new CarlTalkService.Message(conversation, id, new ClientWorkflow.Result(ClientWorkflow.Status.UNKNOWN, null, "", null)));
      assertThat(unknown).contains("Response outcome unknown", "do not replay with a new request ID", "No completed model answer").doesNotContain("Continue this conversation", "Carl&#39;s saved response");
      assertNativeLink(unknown, "carlTalkRead", "selected", conversation + "/" + id);
   }



   @Test
   void boundedFactsRetainTruthfulPartialDisplayAndNoUntrustedLinks()
   {
      var artifact = new ObjectMapper().createObjectNode().put("url", "javascript:alert(1)").put("message", "x".repeat(16001));
      String html = CarlTalkHtml.render(message(new ClientWorkflow.Result(ClientWorkflow.Status.PARTIAL, null, "Facts saved; narration failed", artifact)));
      assertThat(html).contains("Response needs review", "Facts saved; narration failed", "Additional saved detail omitted").doesNotContain("href='javascript:");
   }



   @Test
   void totalEscapedResponseIsBoundedEvenWhenHundredsOfFieldsAreLarge()
   {
      var artifact = new ObjectMapper().createObjectNode();
      for(int i = 0; i < 400; i++)
      {
         artifact.put("large" + i, "&".repeat(16000));
      }
      String html = CarlTalkHtml.render(message(new ClientWorkflow.Result(ClientWorkflow.Status.COMPLETE, null, "Bounded saved response", artifact)));
      assertThat(html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(524288);
      assertThat(html).contains("Additional saved detail", "Read this response");
   }



   private static CarlTalkService.Message message(ClientWorkflow.Result result)
   {
      return new CarlTalkService.Message(UUID.randomUUID(), UUID.randomUUID(), result);
   }



   private static void assertNativeLink(String html, String process, String field, String value) throws Exception
   {
      var matches = java.util.regex.Pattern.compile("href='([^']*)'").matcher(html);
      while(matches.find())
      {
         var uri = URI.create(matches.group(1));
         if(uri.getPath().equals("/app/" + process))
         {
            assertThat(uri.getScheme()).isNull();
            assertThat(uri.getRawQuery()).startsWith("defaultProcessValues=");
            String json = URLDecoder.decode(uri.getRawQuery().substring("defaultProcessValues=".length()), StandardCharsets.UTF_8);
            var values = new ObjectMapper().readTree(json);
            assertThat(values.size()).isEqualTo(1);
            assertThat(values.path(field).asText()).isEqualTo(value);
            return;
         }
      }
      throw new AssertionError("Missing actual frontend process link for " + process);
   }
}
