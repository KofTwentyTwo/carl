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
import com.kof22.carlai.domain.ArtifactExports;


/** Read-only protected PDF storage reauthorizes at the actual native download request. */
public final class ArtifactExportBackend extends QBackendMetaData
{
   static final String PDF_TABLE = "carlProtectedReportPdfs";
   static final String TEXT_TABLE = "carlProtectedReportTexts";
   private final transient ArtifactExports exports;
   ArtifactExportBackend(ArtifactExports exports)
   {
      this.exports = exports;
      withName("carlArtifactExportStorage");
      withBackendType(Module.class);
      QBackendModuleDispatcher.registerBackendModule(new Module());
   }

   /** Native storage extension; callers cannot create arbitrary export objects or filesystem paths. */
   public static final class Module implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlArtifactExports";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return ArtifactExportBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               throw new QException("Use the authorized report export process");
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               if(!java.util.Set.of(PDF_TABLE, TEXT_TABLE).contains(input.getTableName()) || !(input.getBackend() instanceof ArtifactExportBackend backend))
               {
                  throw new QException("Report export unavailable");
               }
               try
               {
                  return new ByteArrayInputStream(backend.exports.load(CarlMetadata.principal(), UUID.fromString(input.getReference()), PDF_TABLE.equals(input.getTableName()) ? ArtifactExports.Format.PDF : ArtifactExports.Format.TEXT));
               }
               catch(IllegalArgumentException | SecurityException denied)
               {
                  throw new QException("Report export unavailable");
               }
            }
         };
      }
   }
}
