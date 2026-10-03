/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;


/** Real production export metadata factory for isolated native qualification. */
public final class TableExportMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;
   /** Uses the same consumer service. */
   public TableExportMetadataFixture(CarlService service)
   {
      this.service = service;
   }



   @Override
   public int getSortOrder()
   {
      return 600;
   }



   @Override
   public QAppMetaData produce(QInstance instance)
   {
      return new CarlMetadata(service).produce(instance);
   }
}
