/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.List;

import com.kingsrook.qqq.backend.core.actions.values.ValueBehaviorApplier;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.FieldDisplayBehavior;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kof22.carlai.report.MoneyPresentation;


/** QQQ query/get formatting shares the same numeric fields and permission-scoped currency. */
final class CarlMoneyDisplay implements FieldDisplayBehavior<CarlMoneyDisplay>
{
   @Override
   public void apply(ValueBehaviorApplier.Action action, List<QRecord> records, QInstance instance, QTableMetaData table, QFieldMetaData field)
   {
      for(QRecord record : records)
      {
         Object value = record.getValue(field.getName());
         BigDecimal amount = value == null ? null : value instanceof BigDecimal exact ? exact : new BigDecimal(value.toString());
         record.setDisplayValue(field.getName(), MoneyPresentation.format(amount, record.getValueString("currency")));
      }
   }
}
