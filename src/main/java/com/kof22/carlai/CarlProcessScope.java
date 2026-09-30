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
   private static final String CAPTURE = CarlProcessScope.class.getName() + ".scope";
   private CarlService service;
   private QInstance instance;
   private final Cache<String, Scope> scopes = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofHours(24)).build();
   private record Scope(String process, String principal, long member, long household, long epoch)
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
      String principal = CarlMetadata.principal();
      var member = service.member(principal);
      return new Scope(process, principal, member.id(), member.householdId(), member.permissionRevision());
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
         String uuid = context.pathParamMap().get("processUUID");
         if(uuid == null)
         {
            uuid = context.queryParam("processUUID");
         }
         if(uuid != null && !current.equals(scopes.getIfPresent(uuid)))
         {
            deny(context);
            return;
         }
         context.attribute(CAPTURE, current);
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
      try
      {
         if(!initial.equals(current(initial.process())))
         {
            deny(context);
            return;
         }
         if(context.statusCode() != 200 || context.result() == null)
         {
            return;
         }
         var response = JSON.readTree(context.result());
         String uuid = response.path("processUUID").asText();
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
   }



   private static void deny(Context context)
   {
      context.status(403).contentType("application/json").result("{\"error\":\"Process access changed or expired. Reload current records before starting again. A submitted operation may already have completed.\"}");
      context.skipRemainingHandlers();
   }
}
