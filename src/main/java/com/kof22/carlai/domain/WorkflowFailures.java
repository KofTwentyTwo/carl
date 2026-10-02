/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.util.Locale;

import com.kof22.agentcore.runtime.AgentRuntimeException;


/** Public-safe failure codes and owner guidance; exception messages are never exposed. */
final class WorkflowFailures
{
   private WorkflowFailures()
   {
   }



   static String code(Throwable failure)
   {
      if(failure instanceof AgentRuntimeException stopped)
      {
         return stopped.reason().name().toLowerCase(Locale.ROOT);
      }
      if(failure instanceof InvalidModelOutput)
      {
         return "invalid_model_output";
      }
      if(failure instanceof SecurityException)
      {
         return "access_changed";
      }
      if(failure instanceof IllegalArgumentException)
      {
         return "invalid_request";
      }
      return failure instanceof IllegalStateException ? "unavailable" : "execution";
   }



   /** Guidance for failures that ended before any record could be saved. */
   static String message(String code)
   {
      return switch(code)
      {
         case "context_budget", "output_budget", "tool_budget", "iteration_limit" -> "Carl stopped before finishing because answering needed more records than one request can review. Nothing was saved. Try a narrower question, such as one month, one account or one category.";
         case "deadline" -> "Carl ran out of time before finishing. Nothing was saved. You can ask again or narrow the question.";
         case "cancelled" -> "Carl stopped because the request was interrupted. Nothing was saved.";
         case "provider", "incomplete_response" -> "The model service did not return a usable answer. Nothing was saved. You can ask again.";
         case "capacity" -> "Carl is busy with other requests. Nothing was saved. Please ask again shortly.";
         case "invalid_model_output" -> "Carl could not interpret the model's plan for this request. Nothing was saved. You can ask again or rephrase it.";
         default -> "Carl could not complete this request. Nothing was saved.";
      };
   }
}
