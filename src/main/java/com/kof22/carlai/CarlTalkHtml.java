/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kof22.carlai.domain.CarlTalkService;


/** Escaped native response cards distinguish saved answers from pending or indeterminate execution. */
final class CarlTalkHtml
{
   private CarlTalkHtml()
   {
   }



   static String render(CarlTalkService.Message message)
   {
      var result = message.result();
      String heading = switch(result.status())
      {
         case PENDING -> "Waiting for Carl";
         case COMPLETE -> "Carl's saved response";
         case PARTIAL -> "Response needs review";
         case FAILED -> "Carl is unavailable";
         case UNKNOWN -> "Response outcome unknown";
      };
      String explanation = switch(result.status())
      {
         case PENDING -> "Your message is saved. Read this same response again while Carl finishes; do not submit another copy.";
         case UNKNOWN -> "Outcome requires reconciliation; do not replay with a new request ID. No completed model answer is available.";
         case FAILED -> "No completed model answer is available. You can ask a new question when the connection is available.";
         case COMPLETE, PARTIAL -> result.summary() == null ? "Saved result" : result.summary();
      };
      String selected = message.conversation() + "/" + message.request();
      StringBuilder html = new StringBuilder("<section style='max-width:60rem;overflow-wrap:anywhere;line-height:1.6'><h3>").append(escape(heading)).append("</h3><p style='white-space:pre-wrap'>").append(escape(explanation.substring(0, Math.min(16000, explanation.length())))).append("</p>");
      if(result.artifact() != null)
      {
         html.append("<section aria-label='Saved facts and source references'><h4>Saved facts and source references</h4>");
         int[] budget = new int[]{0, 0};
         fields(html, com.kof22.carlai.report.MoneyPresentation.humanFacts(result.artifact()), 0, budget);
         if(budget[0] >= 400 || budget[1] >= 64000)
         {
            html.append("<p>Additional saved detail omitted from this bounded view; inspect Carl's protected records.</p>");
         }
         html.append("</section>");
      }
      if(result.artifactId() != null && result.artifactId().matches("[1-9][0-9]{0,17}"))
      {
         boolean draft = result.artifact() != null && result.artifact().path("savedReport").path("kind").asText().contains("DRAFT");
         String id = result.artifactId();
         html.append("<p><a href='").append(escape(processLink(draft ? "carlCopyVendorDraft" : "carlCopyReport", draft ? "draftId" : "reportId", id))).append("'>Review and copy saved ").append(draft ? "draft" : "report").append("</a> | <a href='").append(escape(processLink(draft ? "carlDownloadVendorDraft" : "carlDownloadReportPdf", draft ? "draftId" : "reportId", id))).append("'>Download saved ").append(draft ? "draft — not sent" : "report PDF").append("</a></p>");
      }
      html.append("<p><a href='").append(escape(processLink("carlTalkRead", "selected", selected))).append("'>Read this response</a> | <a href='/app/carlTalkStart'>Ask a new question</a></p>");
      if(result.status() == com.kof22.agentadmin.client.ClientWorkflow.Status.COMPLETE || result.status() == com.kof22.agentadmin.client.ClientWorkflow.Status.PARTIAL)
      {
         html.append("<p><a href='").append(escape(processLink("carlTalkReply", "selected", selected))).append("'>Continue this conversation</a></p>");
      }
      return html.append("<p>Read-only financial guidance and local reports/drafts. Carl does not pay, borrow, buy, or send vendor messages.</p></section>").toString();
   }



   /** Matching Next UI accepts process defaults as JSON, not arbitrary field query parameters. */
   private static String processLink(String process, String field, String value)
   {
      String defaults = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put(field, value).toString();
      return "/app/" + process + "?defaultProcessValues=" + java.net.URLEncoder.encode(defaults, java.nio.charset.StandardCharsets.UTF_8);
   }



   private static void fields(StringBuilder html, com.fasterxml.jackson.databind.JsonNode value, int depth, int[] count)
   {
      if(depth > 7 || count[0] >= 400 || count[1] >= 64000)
      {
         html.append("<p>Additional saved detail is available through Carl's protected records.</p>");
         return;
      }
      if(value.isObject())
      {
         html.append("<dl style='margin:8px 0'>");
         value.fields().forEachRemaining(field ->
         {
            if(java.util.Set.of("contentBase64", "thinking", "signature").contains(field.getKey()) || count[0] >= 400 || count[1] >= 64000)
            {
               return;
            }
            count[0]++;
            String key = field.getKey().substring(0, Math.min(256, field.getKey().length()));
            boolean money = com.kof22.carlai.report.MoneyPresentation.isMoneyField(key) || key.matches("[A-Z]{3}(:[A-Z_]+)?");
            String label = key.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
            label = label.substring(0, Math.min(label.length(), 64000 - count[1]));
            count[1] += label.length();
            html.append("<dt style='font-weight:600'>").append(escape(label)).append("</dt><dd style='margin:0 0 12px;white-space:pre-wrap").append(money ? ";text-align:right;font-variant-numeric:tabular-nums" : "").append("'>");
            fields(html, field.getValue(), depth + 1, count);
            html.append("</dd>");
         });
         html.append("</dl>");
      }
      else if(value.isArray())
      {
         html.append("<ol style='padding-left:24px'>");
         for(int i = 0; i < Math.min(50, value.size()) && count[0] < 400 && count[1] < 64000; i++)
         {
            count[0]++;
            html.append("<li>");
            fields(html, value.get(i), depth + 1, count);
            html.append("</li>");
         }
         html.append("</ol>");
         if(value.size() > 50)
         {
            html.append("<p>Showing the first 50 saved entries; inspect protected records for additional detail.</p>");
         }
      }
      else
      {
         String text = value.isNull() ? "Not supplied" : value.asText();
         int emitted = Math.min(Math.min(16000, text.length()), 64000 - count[1]);
         count[1] += emitted;
         html.append(escape(text.substring(0, emitted)));
         if(text.length() > emitted)
         {
            html.append(" [Additional saved detail omitted from this view]");
         }
      }
   }



   private static String escape(String text)
   {
      return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }
}
