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
import com.kof22.carlai.domain.DocumentRecords;


/** Native original-file downloads recheck the actual current caller, details and permission epoch. */
public final class DocumentDownloadBackend extends QBackendMetaData
{
   static final String TABLE = "carlProtectedDocuments";
   private final transient DocumentRecords documents;
   DocumentDownloadBackend(DocumentRecords documents)
   {
      this.documents = documents;
      withName("carlDocumentStorage");
      withBackendType(Module.class);
      QBackendModuleDispatcher.registerBackendModule(new Module());
   }

   /** Storage-only extension: no path, URL, generic write or public content endpoint. */
   public static final class Module implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlDocuments";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return DocumentDownloadBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               throw new QException("Use the authorized document review process");
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               if(!TABLE.equals(input.getTableName()) || !(input.getBackend() instanceof DocumentDownloadBackend backend))
               {
                  throw new QException("Document unavailable");
               }
               try
               {
                  return new ByteArrayInputStream(backend.documents.load(CarlMetadata.principal(), UUID.fromString(input.getReference())));
               }
               catch(IllegalArgumentException | SecurityException | NullPointerException denied)
               {
                  throw new QException("Document unavailable");
               }
            }
         };
      }
   }
}
