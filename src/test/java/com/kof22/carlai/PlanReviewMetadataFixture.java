/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.Map;

import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CalendarWorkflows;
import com.kof22.carlai.domain.CarlService;


/** Registers production plan and calendar workflows without opening provider transports. */
public final class PlanReviewMetadataFixture implements MetaDataProducerInterface<QAppMetaData>
{
   private final CarlService service;

   /** Shares the native domain and local calendar status services. */
   public PlanReviewMetadataFixture(CarlService service)
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
      var calendars = new CalendarWorkflows(Map.of("reminders", ignored ->
      {
         throw new AssertionError("Local native status and rejected input must not connect to a provider");
      }), Map.of(), service);
      return new CarlMetadata(service, calendars).produce(instance);
   }
}
