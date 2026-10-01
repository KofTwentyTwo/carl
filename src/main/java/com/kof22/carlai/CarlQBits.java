/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kingsrook.qbits.quicksearch.QuickSearchQBitConfig;
import com.kingsrook.qbits.quicksearch.SearchableFieldConfig;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceLambda;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.esb.model.EsbDestinationType;
import com.kingsrook.qqq.esb.model.EsbInstanceMetaData;
import com.kingsrook.qqq.esb.model.EsbProcessMetaData;
import com.kingsrook.qqq.esb.model.EsbProviderType;
import com.kingsrook.qqq.esb.model.EsbTrigger;
import com.kingsrook.qqq.esb.model.QEsbDestinationMetaData;
import com.kingsrook.qqq.esb.model.QEsbProviderMetaData;
import com.kof22.agentadmin.qbits.NativeQBits;
import com.kof22.agentadmin.qbits.NativeQuickSearch;
import com.kof22.agentadmin.qbits.NativeServiceIdentity;
import com.kof22.carlai.domain.CarlSearchAccess;
import com.kof22.carlai.domain.CarlService;


/** Explicit optional real Quick Search and fixed ESB index invalidation, disabled without configuration. */
public final class CarlQBits implements MetaDataProducerInterface<MetaDataProducerMultiOutput>
{
   private final Map<String, String> environment;
   private final CarlSearchAccess access;
   private final NativeQBits nativeQBits;
   private final boolean search;
   private final boolean broker;
   private final CarlIndexRefresh refresh;

   /** Trusted operator environment supplies bounded hosts and credentials; payloads never configure providers. */
   public CarlQBits(CarlService service, Map<String, String> environment)
   {
      this.environment = Map.copyOf(environment);
      search = enabled(environment, "CARL_QBITS_SEARCH_ENABLED");
      broker = enabled(environment, "CARL_QBITS_ESB_ENABLED");
      if(broker && !search)
      {
         throw new IllegalArgumentException("Carl ESB index invalidation requires configured Quick Search");
      }
      access = new CarlSearchAccess(service, () ->
      {
         try
         {
            return CarlMetadata.principal();
         }
         catch(QException denied)
         {
            throw new SecurityException("Verified Carl search principal required");
         }
      });
      refresh = search ? new CarlIndexRefresh(access) : null;
      if(search)
      {
         var configuration = new QuickSearchQBitConfig().withBackendName("carlSearchControl").withTableNamePrefix("carl")
            .withOpensearchHost(required(environment, "CARL_QBITS_OPENSEARCH_HOST")).withOpensearchPort(port(environment))
            .withOpensearchIndexName(required(environment, "CARL_QBITS_OPENSEARCH_INDEX"))
            .withUseSsl(!"false".equals(environment.get("CARL_QBITS_OPENSEARCH_SSL")))
            .withOpensearchUsername(environment.get("CARL_QBITS_OPENSEARCH_USERNAME")).withOpensearchPassword(environment.get("CARL_QBITS_OPENSEARCH_PASSWORD"))
            .withEnableScheduledProcesses(false).withEnableRealTimeIndexing(false);
         if(!configuration.getOpensearchIndexName().matches("[a-z0-9][a-z0-9_-]{0,63}"))
         {
            throw new IllegalArgumentException("Carl search index name must be a bounded lowercase identifier");
         }
         for(String table : CarlSearchAccess.TABLES.keySet().stream().sorted().toList())
         {
            configuration.withSearchableTable(table, List.of(new SearchableFieldConfig("title")));
         }
         nativeQBits = new NativeQBits(Optional.of(new NativeQuickSearch(configuration, access)), broker ? Set.of("carlRefreshSearchIndex") : Set.of());
      }
      else
      {
         nativeQBits = NativeQBits.disabled();
      }
   }



   /** One extension owner is passed to the foundation host. */
   public NativeQBits extensions()
   {
      return nativeQBits;
   }



   @Override
   public int getSortOrder()
   {
      return 700;
   }



   @Override
   public MetaDataProducerMultiOutput produce(QInstance instance)
   {
      if(search)
      {
         instance.addBackend(new QBackendMetaData().withName("carlSearchControl").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
         instance.withRuntimeService(new QCodeReferenceLambda<com.kingsrook.qqq.backend.core.instances.QRuntimeServiceInterface>(refresh));
      }
      if(broker)
      {
         var process = new QProcessMetaData().withName("carlRefreshSearchIndex").withLabel("Refresh Carl search index")
            .withPermissionRules(NativeQBits.indexerPermission()).withStep(new QBackendStepMetaData().withName("refresh").withCode(new QCodeReferenceLambda<BackendStep>((in, out) ->
            {
               if(!QContext.getQSession().getUser().getIdReference().equals("carl-index-service") || !QContext.getQSession().hasPermission("agent.qbits.indexer.hasAccess"))
               {
                  throw new QException("Trusted index service required");
               }
               refresh.refresh();
            })));
         EsbProcessMetaData.ofOrWithNew(process).withTrigger(new EsbTrigger().withDestinationName("carlIndexChanges").withMaxAttempts(3)
            .withRunAsSessionSupplier(new QCodeReferenceLambda<java.util.function.Supplier<com.kingsrook.qqq.backend.core.model.session.QSession>>(() -> new NativeServiceIdentity("carl-index-service", Set.of("agent.qbits.indexer.hasAccess"), Map.of()).get())));
         instance.addProcess(process);
         String url = required(environment, "CARL_QBITS_BROKER_URL");
         if(!url.startsWith("tcp://") || url.contains("@") || url.length() > 500)
         {
            throw new IllegalArgumentException("Carl broker requires an operator-configured TCP URL without embedded credentials");
         }
         EsbInstanceMetaData.of(instance).withInstanceName("carl")
            .withProvider(new QEsbProviderMetaData().withName("carlBroker").withType(EsbProviderType.ACTIVEMQ_ARTEMIS).withUrl(url).withUsername(environment.get("CARL_QBITS_BROKER_USERNAME")).withPassword(environment.get("CARL_QBITS_BROKER_PASSWORD")))
            .withDestination(new QEsbDestinationMetaData().withName("carlIndexChanges").withType(EsbDestinationType.QUEUE).withProviderName("carlBroker"));
      }
      return new MetaDataProducerMultiOutput();
   }



   private static boolean enabled(Map<String, String> environment, String name)
   {
      String value = environment.getOrDefault(name, "false");
      if(!Set.of("true", "false").contains(value))
      {
         throw new IllegalArgumentException(name + " must be true or false");
      }
      return value.equals("true");
   }



   private static String required(Map<String, String> environment, String name)
   {
      String value = environment.get(name);
      if(value == null || value.isBlank())
      {
         throw new IllegalArgumentException(name + " is required");
      }
      return value;
   }



   private static int port(Map<String, String> environment)
   {
      int port = Integer.parseInt(required(environment, "CARL_QBITS_OPENSEARCH_PORT"));
      if(port < 1 || port > 65535)
      {
         throw new IllegalArgumentException("Carl search port must be 1–65535");
      }
      return port;
   }
}
