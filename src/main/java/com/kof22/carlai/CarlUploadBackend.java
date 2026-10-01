/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.kingsrook.qqq.backend.core.actions.interfaces.QStorageInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import com.kof22.carlai.domain.MonarchImportWorkflow;


/** A private native QQQ storage extension; uploads stay in Carl's database under verified ownership. */
public final class CarlUploadBackend extends QBackendMetaData
{
   static final String TABLE = "carlPrivateUploads";
   private final transient MonarchImportWorkflow workflow;
   CarlUploadBackend(MonarchImportWorkflow workflow)
   {
      this.workflow = workflow;
      withName("carlUploadStorage");
      withBackendType(Module.class);
      QBackendModuleDispatcher.registerBackendModule(new Module());
   }
   /** Storage-only backend: no generic query, CRUD, public URL or filesystem target is exposed. */
   public static final class Module implements QBackendModuleInterface
   {
      @Override
      public String getBackendType()
      {
         return "carlUploads";
      }



      @Override
      public Class<? extends QBackendMetaData> getBackendMetaDataClass()
      {
         return CarlUploadBackend.class;
      }



      @Override
      public QStorageInterface getStorageInterface()
      {
         return new QStorageInterface()
         {
            @Override
            public OutputStream createOutputStream(StorageInput input) throws QException
            {
               var backend = backend(input);
               String principal = CarlMetadata.principal();
               backend.workflow.uploadPermission(principal);
               return new OutputStream()
               {
                  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                  private boolean closed;
                  private boolean failed;
                  @Override
                  public void write(int value) throws IOException
                  {
                     bound(1);
                     bytes.write(value);
                  }



                  @Override
                  public void write(byte[] values, int offset, int length) throws IOException
                  {
                     bound(length);
                     bytes.write(values, offset, length);
                  }



                  private void bound(int length) throws IOException
                  {
                     if(closed || length < 0 || (long) bytes.size() + length > 20_000_000)
                     {
                        failed = true;
                        throw new IOException("Upload exceeds20,000,000 bytes or stream is closed");
                     }
                  }



                  @Override
                  public void close() throws IOException
                  {
                     if(!closed && !failed)
                     {
                        closed = true;
                        try
                        {
                           backend.workflow.storeUpload(principal, input.getReference(), bytes.toByteArray());
                        }
                        catch(RuntimeException failure)
                        {
                           throw new IOException("Upload could not be stored");
                        }
                     }
                  }
               };
            }



            @Override
            public InputStream getInputStream(StorageInput input) throws QException
            {
               try
               {
                  return new ByteArrayInputStream(backend(input).workflow.upload(CarlMetadata.principal(), input.getReference()));
               }
               catch(SecurityException denied)
               {
                  throw new QException("Upload unavailable");
               }
            }
         };
      }



      private static CarlUploadBackend backend(StorageInput input) throws QException
      {
         if(!TABLE.equals(input.getTableName()) || !(input.getBackend() instanceof CarlUploadBackend backend)
            || input.getReference() == null || input.getReference().length() > 1000)
         {
            throw new QException("Carl upload storage unavailable");
         }
         return backend;
      }
   }
}
