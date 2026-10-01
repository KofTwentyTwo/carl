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
      return components((source, service) -> com.kof22.carlai.domain.CalendarWorkflows.configured(source, System.getenv()));
   }



   /** Production and controlled tests share composition, replacing only trusted calendar transport factories. */
   public static NativeAgentApplication.Components components(java.util.function.BiFunction<javax.sql.DataSource, com.kof22.carlai.domain.CarlService, com.kof22.carlai.domain.CalendarWorkflows> calendarFactory)
   {
      return components(calendarFactory, System.getenv());
   }



   /** Controlled tests supply trusted provider environment while retaining the production composition. */
   public static NativeAgentApplication.Components components(java.util.function.BiFunction<javax.sql.DataSource, com.kof22.carlai.domain.CarlService, com.kof22.carlai.domain.CalendarWorkflows> calendarFactory, java.util.Map<String, String> environment)
   {
      java.util.Map<String, String> trustedEnvironment = java.util.Map.copyOf(environment);
      return new NativeAgentApplication.Components()
      {
         private com.kof22.carlai.domain.CarlService service;
         private com.kof22.carlai.domain.CalendarWorkflows calendars;
         private com.kof22.agentcore.runtime.AgentRuntime inference;
         private com.kof22.carlai.domain.CarlClientWorkflows workflows;
         private com.kof22.carlai.domain.CarlTalkService talk;
         private CarlQBits qbits;
         private com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration;
         @Override
         public void validate(com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration)
         {
            super.validate(configuration);
            this.configuration = configuration;
            var database = configuration.database();
            service = new com.kof22.carlai.domain.CarlService(
               com.kof22.agentadmin.bootstrap.NativeDatabases.source(database.url(), database.username(), database.password()), java.time.Clock.systemUTC());
            talk = new com.kof22.carlai.domain.CarlTalkService(service);
            qbits = new CarlQBits(service, trustedEnvironment);
            calendars = calendarFactory.apply(com.kof22.agentadmin.bootstrap.NativeDatabases.source(database.url(), database.username(), database.password()), service);
         }



         @Override
         public com.kof22.agentcore.runtime.AgentRuntime runtime(com.kof22.agentadmin.configuration.NativeAgentConfiguration configuration)
         {
            inference = com.kof22.carlai.domain.CarlConversation.admittedRuntime(super.runtime(configuration), configuration.core().getLimits().validated().maxConcurrentTurns());
            return inference;
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
         public synchronized java.util.Map<String, com.kof22.agentadmin.client.ClientWorkflow> clientWorkflows(com.kof22.agentadmin.bootstrap.NativeStores stores)
         {
            if(workflows == null)
            {
               workflows = new com.kof22.carlai.domain.CarlClientWorkflows(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(calendars), inference == null ? null : new com.kof22.carlai.domain.CarlConversation(service, inference, configuration, stores, calendars));
            }
            return workflows.handlers();
         }



         @Override
         public void clientServiceReady(com.kof22.agentadmin.client.ClientService clients)
         {
            java.util.Objects.requireNonNull(talk).ready(clients);
         }



         @Override
         public java.util.Optional<com.kof22.agentadmin.NativeChat> chat()
         {
            return configuration.clientApi().enabled()
               ? java.util.Optional.of(new CarlNativeChat(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(talk)))
               : java.util.Optional.empty();
         }



         @Override
         public com.kof22.agentadmin.qbits.NativeQBits administrationExtensions(com.kof22.agentadmin.bootstrap.NativeStores stores)
         {
            return java.util.Objects.requireNonNull(qbits).extensions();
         }



         @Override
         public java.util.Optional<String> landingApp()
         {
            return java.util.Optional.of("carlOverview");
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
            return java.util.List.of(new CarlMetadata(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(calendars), java.util.Objects.requireNonNull(talk)), java.util.Objects.requireNonNull(qbits), new ZCarlSystemNavigation());
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
