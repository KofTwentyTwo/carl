/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.InitializableViaCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kof22.agentadmin.OperatorPermissions;
import com.kof22.agentcore.security.Role;
import com.kof22.carlai.domain.CarlDatabaseDiagnostics;
import com.kof22.carlai.domain.CarlService;


/** Native owner/admin diagnostics share the Carl service; ordinary family members receive no storage details. */
final class CarlSystemMetadata
{
   private CarlSystemMetadata()
   {
   }



   static void register(QInstance instance, CarlService service)
   {
      instance.addWidget(new QWidgetMetaData().withName("carlDatabaseDiagnostics").withLabel("Database storage").withType("html").withGridColumns(12).withIsCard(true).withShowReloadButton(true).withShowExportButton(false)
         .withPermissionRules(OperatorPermissions.require(Role.ADMIN)).withCodeReference(new Reference(service)));
   }

   private static final class Reference extends QCodeReference
   {
      private final CarlService service;

      private Reference(CarlService service)
      {
         super(Renderer.class);
         this.service = service;
      }
   }



   /** Instantiated through QQQ's supported code loader with the explicitly bound application service. */
   public static final class Renderer extends AbstractWidgetRenderer implements InitializableViaCodeReference
   {
      private CarlDatabaseDiagnostics diagnostics;

      /** Native code loader constructor. */
      public Renderer()
      {
      }



      @Override
      public void initialize(QCodeReference reference)
      {
         if(!(reference instanceof Reference bound))
         {
            throw new IllegalArgumentException("Carl diagnostics require the application service");
         }
         diagnostics = new CarlDatabaseDiagnostics(bound.service);
      }



      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         OperatorPermissions.check(Role.ADMIN);
         try
         {
            var snapshot = diagnostics.read(CarlMetadata.principal());
            return new RenderWidgetOutput(new RawHTML("Database storage", html(snapshot)));
         }
         catch(SecurityException denied)
         {
            throw new com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException("Current Carl household settings authority is required");
         }
      }
   }

   static String html(CarlDatabaseDiagnostics.Snapshot snapshot)
   {
      var html = new StringBuilder("<section aria-label='Database storage'><h2>Database storage</h2><dl><dt>Connection</dt><dd>");
      html.append(escape(snapshot.connectionLabel())).append("</dd><dt>Database</dt><dd>").append(escape(snapshot.database())).append("</dd><dt>Schema</dt><dd>").append(escape(snapshot.schema()));
      html.append("</dd></dl><p>Read-only PostgreSQL storage sizes at query time, in bytes. Includes table, index and auxiliary storage; no row counts or connection credentials.</p><div role='region' aria-label='Database tables and storage sizes' tabindex='0' style='overflow-x:auto'><table><caption>Tables in the active Carl schema</caption><thead><tr><th scope='col'>Schema</th><th scope='col'>Table</th><th scope='col'>Table bytes</th><th scope='col'>Index bytes</th><th scope='col'>Total bytes</th></tr></thead><tbody>");
      for(var table : snapshot.tables())
      {
         html.append("<tr><td>").append(escape(table.schema())).append("</td><td>").append(escape(table.table())).append("</td><td>").append(table.tableBytes()).append("</td><td>").append(table.indexBytes()).append("</td><td>").append(table.totalBytes()).append("</td></tr>");
      }
      html.append("</tbody></table></div>");
      if(snapshot.tables().isEmpty())
      {
         html.append("<p>No tables were found in the active schema.</p>");
      }
      if(snapshot.truncated())
      {
         html.append("<p>Showing the first 200 tables in schema and table order; additional tables are omitted.</p>");
      }
      return html.append("</section>").toString();
   }



   private static String escape(String value)
   {
      return value == null ? "Unavailable" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
   }
}
