/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.InitializableViaCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.NativeReadScope;
import io.javalin.config.JavalinConfig;


/** Rejects whole native search/dropdown responses when their first verified domain scope has changed. */
public final class CarlNativeReadScope implements QJavalinRouteProviderInterface, InitializableViaCodeReference
{
   private static final String KEY = CarlNativeReadScope.class.getName();
   private CarlService service;

   /** QQQ code loader entry point. */
   public CarlNativeReadScope()
   {
   }

   private static final class Reference extends QCodeReference
   {
      private final CarlService service;
      private Reference(CarlService service)
      {
         super(CarlNativeReadScope.class);
         this.service = service;
      }
   }

   static void register(QInstance instance, CarlService service)
   {
      QJavalinMetaData.ofOrWithNew(instance).withAdditionalRouteProviderReference(new Reference(service));
   }



   @Override
   public void initialize(QCodeReference reference)
   {
      if(!(reference instanceof Reference bound))
      {
         throw new IllegalArgumentException("Carl native response scope requires the application service");
      }
      service = bound.service;
   }



   @Override
   public void setQInstance(QInstance instance)
   {
      java.util.Objects.requireNonNull(instance);
   }



   @Override
   public void acceptJavalinConfig(JavalinConfig configuration)
   {
      // Native route providers register before the host's authentication and scoped-search before hooks.
      // Allocate only an empty holder here; caller-aware capabilities bind it after authentication.
      configuration.routes.before(context -> context.attribute(KEY, NativeReadScope.begin()));
      configuration.routes.after(context ->
      {
         NativeReadScope.State state = context.attribute(KEY);
         try
         {
            if(state != null && state.member() != null && !state.member().equals(service.member(state.member().principal())))
            {
               deny(context);
            }
         }
         catch(RuntimeException denied)
         {
            deny(context);
         }
         finally
         {
            NativeReadScope.end();
         }
      });
   }



   private static void deny(io.javalin.http.Context context)
   {
      context.status(403).contentType("application/json").result("{\"error\":\"Carl access changed during this request. Reload current records.\"}");
   }
}
