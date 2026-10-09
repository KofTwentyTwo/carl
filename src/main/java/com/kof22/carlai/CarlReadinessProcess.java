/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.ReadinessPlans;


/** Creates the same source-linked private readiness plan available to Carl's financial workflow. */
final class CarlReadinessProcess
{
   private CarlReadinessProcess()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var readiness = new ReadinessPlans(service);
      var process = CarlMetadata.process("carlGenerateReadinessPlan", "Generate Financial Readiness Plan", List.of(new QFieldMetaData("title", QFieldType.STRING).withLabel("Plan title").withIsRequired(true)), (in, out) ->
      {
         var saved = readiness.generate(CarlService.Scope.privateFor(CarlMetadata.principal()), UUID.fromString(in.getValueString("requestId")), in.getValueString("title"));
         out.addValue("result", "Saved private draft plan " + saved.plan() + " and source-linked readiness report " + saved.artifact() + ". Review the missing facts and next preparation steps in Plans and Reports, Plans and Drafts. This records planning gaps; it creates no assumed payment schedule and performs no financial action.");
      }).withTableName("carlPlans").withMinInputRecords(0).withMaxInputRecords(0);
      CarlMetadata.add(instance, app, process);
   }
}
