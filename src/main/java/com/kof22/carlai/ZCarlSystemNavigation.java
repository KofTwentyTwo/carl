/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;

import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;


/** Native app-output finalizer sorts after the optional QBits multi-output producer and preserves registered app permissions. */
public final class ZCarlSystemNavigation implements MetaDataProducerInterface<QAppMetaData>
{
   /*******************************************************************************
    ** Inherited foundation approval actions that take an approval id and belong on
    ** the approvals table rather than in the System sidebar.
    *******************************************************************************/
   private static final java.util.Set<String> APPROVAL_ACTIONS = java.util.Set.of("denyApproval", "reconcileApproval");

   /** Explicit application factory entry point. */
   public ZCarlSystemNavigation()
   {
   }



   @Override
   public int getSortOrder()
   {
      return Integer.MAX_VALUE;
   }



   @Override
   public QAppMetaData produce(QInstance instance)
   {
      attach(instance);
      // The registered System app is updated in place; native producers allow null for side effects.
      return null;
   }



   static void attach(QInstance instance)
   {
      var system = instance.getApp("carlSystem");
      if(system == null)
      {
         throw new IllegalStateException("Carl System navigation must be defined before finalization");
      }
      var database = instance.getApp("carlDatabase");
      if(database == null)
      {
         database = new QAppMetaData().withName("carlDatabase").withLabel("Database").withIcon(new QIcon().withName("storage")).withWidgets(List.of("carlDatabaseDiagnostics"))
            .withPermissionRules(com.kof22.agentadmin.OperatorPermissions.require(com.kof22.agentcore.security.Role.ADMIN));
         instance.addApp(database);
      }
      for(var app : instance.getApps().values())
      {
         if(app.getChildren() != null)
         {
            for(var child : List.copyOf(app.getChildren()))
            {
               if(child instanceof com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData process && process.getTableName() == null)
               {
                  if(!APPROVAL_ACTIONS.contains(process.getName()) || instance.getTable("approvals") == null)
                  {
                     throw new IllegalStateException("System action needs an explicit registered table: " + process.getName());
                  }
                  process.withTableName("approvals").withMinInputRecords(0).withMaxInputRecords(0);
               }
            }
            app.getChildren().removeIf(child -> child instanceof com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData);
         }
         if(app.getSections() != null)
         {
            for(var section : app.getSections())
            {
               if(section.getProcesses() != null)
               {
                  section.getProcesses().clear();
               }
            }
         }
      }
      for(String name : List.of("operations", "esb", "carlDatabase"))
      {
         var child = instance.getApp(name);
         if(child != null && (system.getChildren() == null || system.getChildren().stream().noneMatch(existing -> existing.getName().equals(name))))
         {
            system.withChild(child);
         }
      }
   }
}
