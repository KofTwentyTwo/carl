/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;


/** Registers only the staged report extension beside current production metadata. */
public final class PlanEffectMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;
   /** Uses identical services in the base and extension metadata. */
   public PlanEffectMetadataFixture(CarlService service)
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
