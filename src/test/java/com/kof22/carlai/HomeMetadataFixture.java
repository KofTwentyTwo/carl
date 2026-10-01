/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;


/** Uses the consumer factory, including its authoritative home metadata hook. */
public final class HomeMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;

   /** Uses one authoritative service for base and staged home metadata. */
   public HomeMetadataFixture(CarlService service)
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
