/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import com.kof22.agentadmin.bootstrap.NativeAgentApplication;


/*******************************************************************************
 * Native QQQ main; business metadata and tools extend the deferred components below.
 ******************************************************************************/
public final class AgentApplication
{
   private AgentApplication()
   {
   }



   /*******************************************************************************
    * The application test uses this same composition, replacing only external boundaries.
    ******************************************************************************/
   public static NativeAgentApplication.Components components()
   {
      return new NativeAgentApplication.Components()
      {
         private com.kof22.carlai.domain.CarlService service;
         private com.kof22.carlai.domain.CalendarWorkflows calendars;
         @Override
         public void validate(com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration)
         {
            super.validate(configuration);
            var database = configuration.database();
            service = new com.kof22.carlai.domain.CarlService(
               com.kof22.agentadmin.bootstrap.NativeDatabases.source(database.url(), database.username(), database.password()), java.time.Clock.systemUTC());
            calendars = com.kof22.carlai.domain.CalendarWorkflows.configured(com.kof22.agentadmin.bootstrap.NativeDatabases.source(database.url(), database.username(), database.password()), System.getenv());
         }



         @Override
         public java.util.List<com.kof22.agentcore.runtime.ToolBinding> tools(com.kof22.agentadmin.bootstrap.NativeStores stores)
         {
            return CarlTools.bind(java.util.Objects.requireNonNull(service));
         }



         @Override
         public com.kof22.agentadmin.client.FamilyAccess familyAccess()
         {
            return new com.kof22.carlai.domain.CarlFamilyAccess(java.util.Objects.requireNonNull(service));
         }



         @Override
         public java.util.Map<String, com.kof22.agentadmin.client.ClientWorkflow> clientWorkflows(com.kof22.agentadmin.bootstrap.NativeStores stores)
         {
            return new com.kof22.carlai.domain.CarlClientWorkflows(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(calendars)).handlers();
         }



         @Override
         public com.kof22.agentadmin.NativeDownloadPolicy downloadPolicy()
         {
            return new com.kof22.agentadmin.NativeDownloadPolicy(java.util.Map.of("carlProtectedPlanExports", "carlExportPlan", "carlProtectedVendorDrafts", "carlDownloadVendorDraft", "carlProtectedReportPdfs", "carlDownloadReportPdf", "carlProtectedReportTexts", "carlDownloadReportText"));
         }



         @Override
         public java.util.Optional<com.kof22.agentadmin.bootstrap.NativeUploadPolicy> uploadPolicy()
         {
            return java.util.Optional.of(new com.kof22.agentadmin.bootstrap.NativeUploadPolicy(20_000_000, 40_000_000, 34, 2, 65_536));
         }



         @Override
         public java.util.List<com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface<?>> metadata()
         {
            return java.util.List.of(new CarlMetadata(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(calendars)));
         }
      };
   }



   /*******************************************************************************
    * Starts the shared runtime with this application's registrations.
    ******************************************************************************/
   public static void main(String[] args)
   {
      NativeAgentApplication.run(args, components());
   }
}
