/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.InitializableViaCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.NativeMutationReceipt;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;


/** Native Carl process state must never outlive its verified household permission scope. */
public final class CarlProcessScope implements QJavalinRouteProviderInterface, InitializableViaCodeReference
{
   // These explicit human edits intentionally invalidate the household scope and return only fixed receipts.
   // Native HTTP regressions pin the receipt text and keys; adding retrieved facts requires a different flow.
   private static final java.util.Set<String> RECEIPT_ONLY_MUTATIONS = java.util.Set.of(
      "carlStartRentalReview", "carlEditRentalReviewComponent", "carlEditRentalReviewShare", "carlRemoveRentalReviewComponent", "carlApplyRentalReview", "carlTaxReferenceStatus");
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String RECEIPT = CarlProcessScope.class.getName() + ".receipt";
   private static final String STATUS = CarlProcessScope.class.getName() + ".status";
   private static final String CAPTURE = CarlProcessScope.class.getName() + ".scope";
   private CarlService service;
   private QInstance instance;
   private final Cache<String, Scope> scopes = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofHours(24)).build();
   private record Scope(String process, CarlService.Member member)
   {
   }
   /** QQQ initializes the service through the registered code reference. */
   public CarlProcessScope()
   {
   }
   private static final class ServiceReference extends QCodeReference
   {
      private final CarlService service;
      private ServiceReference(CarlService service)
      {
         super(CarlProcessScope.class);
         this.service = service;
      }
   }
   @Override
   public void initialize(QCodeReference reference)
   {
      if(!(reference instanceof ServiceReference bound))
      {
         throw new IllegalArgumentException("Carl process scope requires the application service");
      }
      service = bound.service;
   }



   static void register(QInstance instance, CarlService service)
   {
      QJavalinMetaData.ofOrWithNew(instance).withAdditionalRouteProviderReference(new ServiceReference(service));
   }



   @Override
   public void setQInstance(QInstance instance)
   {
      this.instance = instance;
   }



   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      config.routes.beforeMatched(this::authorize);
      config.routes.after(this::capture);
   }



   private Scope current(String process) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      return current(process, CarlMetadata.principal());
   }



   private Scope current(String process, String principal)
   {
      var member = service.member(principal);
      return new Scope(process, member);
   }



   private void authorize(Context context)
   {
      String process = context.pathParamMap().get("processName");
      if(process == null || !process.startsWith("carl") || instance.getProcess(process) == null || RECEIPT_ONLY_MUTATIONS.contains(process))
      {
         return;
      }
      try
      {
         Scope current = current(process);
         if(NativeMutationReceipt.supports(process) && context.path().endsWith("/run"))
         {
            deny(context);
            return;
         }
         String uuid = context.pathParamMap().get("processUUID");
         if(uuid == null)
         {
            uuid = context.queryParam("processUUID");
         }
         Scope initial = uuid == null ? current : scopes.getIfPresent(uuid);
         String job = context.pathParamMap().get("jobUUID");
         NativeMutationReceipt.State status = uuid != null && job != null && context.method() == io.javalin.http.HandlerType.GET
            ? NativeMutationReceipt.forStatus(instance, process, uuid, job)
            : null;
         if(status != null && initial != null && initial.process().equals(process))
         {
            if(!status.permitsStatus(current.member()))
            {
               if(status.owns(current.member()))
               {
                  scopes.invalidate(uuid);
                  NativeMutationReceipt.close(status);
               }
               deny(context);
               return;
            }
            context.attribute(RECEIPT, status);
            context.attribute(STATUS, Boolean.TRUE);
         }
         else if(!current.equals(initial))
         {
            deny(context);
            return;
         }
         context.attribute(CAPTURE, initial);
         String step = context.pathParamMap().get("stepName");
         if(step == null)
         {
            step = context.pathParamMap().get("step");
         }
         if(uuid != null && context.method() == io.javalin.http.HandlerType.POST && NativeMutationReceipt.supportsStep(process, step) && !"true".equals(context.queryParam("isStepBack")))
         {
            context.attribute(RECEIPT, NativeMutationReceipt.open(instance, process, uuid, current.member()));
         }
      }
      catch(Exception denied)
      {
         deny(context);
      }
   }



   private void capture(Context context)
   {
      Scope initial = context.attribute(CAPTURE);
      if(initial == null)
      {
         return;
      }
      NativeMutationReceipt.State receipt = context.attribute(RECEIPT);
      boolean retain = false;
      try
      {
         var current = current(initial.process(), initial.member().principal());
         boolean unchanged = initial.equals(current);
         if(context.statusCode() != 200 || context.result() == null)
         {
            if(!unchanged)
            {
               deny(context);
            }
            return;
         }
         var response = JSON.readTree(context.result());
         String uuid = response.path("processUUID").asText();
         String type = response.path("type").asText();
         boolean clean = !response.hasNonNull("error");
         boolean status = Boolean.TRUE.equals(context.attribute(STATUS));
         // The pinned legacy handler has no type field: its completed step has values/nextStep,
         // and its completed status additionally has jobStatus.state=COMPLETE.
         boolean legacyComplete = !context.path().startsWith("/qqq/v1/") && type.isBlank() && response.has("values")
            && "result".equals(response.path("nextStep").asText()) && (!status || "COMPLETE".equals(response.path("jobStatus").path("state").asText()));
         boolean complete = clean && ("COMPLETE".equals(type) || legacyComplete);
         boolean sameProcess = uuid.equals(context.pathParamMap().get("processUUID"));
         boolean pending = status && clean && ("RUNNING".equals(type) || "RUNNING".equals(response.path("jobStatus").path("state").asText()));
         String job = response.path("jobUUID").asText();
         boolean started = !status && clean && !job.isBlank() && ("JOB_STARTED".equals(type) || type.isBlank() && !response.has("values"));
         if(receipt != null && sameProcess && (started || pending) && receipt.permitsStatus(current.member()))
         {
            var safe = JSON.createObjectNode();
            safe.put("processUUID", uuid);
            if(started)
            {
               receipt.bindJob(job);
               safe.put("jobUUID", job).put("type", "JOB_STARTED");
            }
            else
            {
               safe.put("jobUUID", context.pathParamMap().get("jobUUID")).put("type", "RUNNING");
               safe.putObject("jobStatus").put("state", "RUNNING");
            }
            context.result(JSON.writeValueAsString(safe));
            retain = true;
            return;
         }
         boolean fixedReceipt = receipt != null && complete && sameProcess && "result".equals(response.path("nextStep").asText())
            && receipt.message().equals(response.path("values").path("result").asText()) && receipt.acknowledge(current.member());
         if(!unchanged && !fixedReceipt || receipt != null && !fixedReceipt && (complete || status || started))
         {
            deny(context);
            return;
         }
         if(fixedReceipt)
         {
            String text = receipt.message().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
            var safe = JSON.createObjectNode();
            safe.put("processUUID", uuid);
            safe.put("nextStep", "result");
            safe.put("type", "COMPLETE");
            safe.putObject("values").put("result", receipt.message()).put("result.html", "<div style=\"white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6\">" + text + "</div>");
            context.result(JSON.writeValueAsString(safe));
            scopes.invalidate(uuid);
            return;
         }
         if(!uuid.isBlank())
         {
            java.util.UUID.fromString(uuid);
            Scope existing = scopes.asMap().putIfAbsent(uuid, initial);
            if(existing != null && !initial.equals(existing))
            {
               deny(context);
            }
         }
      }
      catch(Exception denied)
      {
         deny(context);
      }
      finally
      {
         if(!retain)
         {
            NativeMutationReceipt.close(receipt);
         }
      }
   }



   private static void deny(Context context)
   {
      context.status(403).contentType("application/json").result("{\"error\":\"Process access changed or expired. Reload current records before starting again. A submitted operation may already have completed.\"}");
      context.skipRemainingHandlers();
   }
}
