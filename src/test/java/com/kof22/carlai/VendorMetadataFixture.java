/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;


/** Native fixture delegates to the integrated production Carl metadata. */
public final class VendorMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;
   /** Uses the same domain service in base and extension metadata. */
   public VendorMetadataFixture(CarlService service)
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
      var app = new CarlMetadata(service).produce(instance);
      return app;
   }
}
