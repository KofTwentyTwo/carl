/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.actions.interfaces.QStorageInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import com.kof22.carlai.domain.PlanExports;


/** Read-only protected PDF storage reauthorizes at the actual native download request. */
public final class CarlExportBackend extends QBackendMetaData
{
   static final String TABLE = "carlProtectedPlanExports";
   private final transient PlanExports exports;
   CarlExportBackend(PlanExports exports)
   {
      this.exports = exports;
      withName("carlExportStorage");
      withBackendType(Module.class);
      QBackendModuleDispatcher.registerBackendModule(new Module());
   }

   /** Native storage extension; callers cannot create arbitrary export objects or filesystem paths. */
   public static final class Module implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlExports";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return CarlExportBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               throw new QException("Use the authorized plan export process");
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               if(!TABLE.equals(input.getTableName()) || !(input.getBackend() instanceof CarlExportBackend backend))
               {
                  throw new QException("Plan export unavailable");
               }
               try
               {
                  return new ByteArrayInputStream(backend.exports.load(CarlMetadata.principal(), UUID.fromString(input.getReference())));
               }
               catch(IllegalArgumentException | SecurityException denied)
               {
                  throw new QException("Plan export unavailable");
               }
            }
         };
      }
   }
}
