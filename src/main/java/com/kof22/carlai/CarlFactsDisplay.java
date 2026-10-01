/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;

import com.kingsrook.qqq.backend.core.actions.values.ValueBehaviorApplier;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.FieldDisplayBehavior;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kof22.carlai.report.MoneyPresentation;


/** Native administration presents protected report facts without modifying their stored schema. */
final class CarlFactsDisplay implements FieldDisplayBehavior<CarlFactsDisplay>
{
   @Override
   public void apply(ValueBehaviorApplier.Action action, List<QRecord> records, QInstance instance, QTableMetaData table, QFieldMetaData field)
   {
      for(QRecord record : records)
      {
         String facts = record.getValueString(field.getName());
         if(facts != null)
         {
            record.setDisplayValue(field.getName(), MoneyPresentation.humanFacts(facts));
         }
      }
   }
}
