/* Copyright (C) 2026 KofTwentyTwo */
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
import com.kof22.carlai.domain.TableExports;


/** A single read-only native storage backend reauthorizes every dated CSV snapshot retrieval. */
public final class CarlTableExportBackend extends QBackendMetaData
{
   static final String TABLE = "carlProtectedTableExports";
   private final transient TableExports exports;
   CarlTableExportBackend(TableExports exports)
   {
      this.exports = exports;
      withName("carlTableExportStorage");
      withBackendType(Module.class);
      QBackendModuleDispatcher.registerBackendModule(new Module());
   }

   /** Storage-only extension: clients cannot choose paths, source views or arbitrary output objects. */
   public static final class Module implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlTableExports";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return CarlTableExportBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               throw new QException("Use the governed table export process");
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               if(!TABLE.equals(input.getTableName()) || !(input.getBackend() instanceof CarlTableExportBackend backend))
               {
                  throw new QException("Table export unavailable");
               }
               try
               {
                  return new ByteArrayInputStream(backend.exports.load(CarlMetadata.principal(), UUID.fromString(input.getReference())));
               }
               catch(IllegalArgumentException | SecurityException | NullPointerException denied)
               {
                  throw new QException("Table export unavailable");
               }
            }
         };
      }
   }
}
