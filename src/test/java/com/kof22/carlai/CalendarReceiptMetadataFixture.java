/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CalendarWorkflows;
import com.kof22.carlai.domain.CarlService;


/** Controlled calendar reads use the production metadata and native process factory. */
public final class CalendarReceiptMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;
   private final CalendarWorkflows calendars;

   /** Supplies controlled calendar providers to the unchanged consumer metadata factory. */
   public CalendarReceiptMetadataFixture(CarlService service, CalendarWorkflows calendars)
   {
      this.service = service;
      this.calendars = calendars;
   }



   @Override
   public int getSortOrder()
   {
      return 600;
   }



   @Override
   public QAppMetaData produce(QInstance instance)
   {
      return new CarlMetadata(service, calendars).produce(instance);
   }
}
