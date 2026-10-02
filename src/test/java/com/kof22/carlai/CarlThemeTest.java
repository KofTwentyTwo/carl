/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.util.Map;

import com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.specs.v1.responses.components.SupplementalInstanceMetaData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CarlThemeTest
{
   @Test
   void actualNativeMetadataPublishesCarlVisualPropertiesWithoutMaterialUiDependency()
   {
      var instance = new QInstance();
      new CarlMetadata(new com.kof22.carlai.domain.CarlService(new org.postgresql.ds.PGSimpleDataSource(), java.time.Clock.systemUTC())).produce(instance);
      assertNotNull(instance.getSupplementalMetaData(CarlTheme.NAME));
      var output = new MetaDataOutput();
      output.setSupplementalInstanceMetaData(instance.getSupplementalMetaData());
      var visual = SupplementalInstanceMetaData.of(output).getMaterialDashboardTheme();
      assertEquals("#0066cc", visual.get("primaryColor"));
      assertEquals("#25282c", visual.get("surfaceColor"));
      assertEquals("#f5f5f5", visual.get("textPrimary"));
      assertEquals("#163e69", visual.get("tableRowSelectedColor"));
      assertEquals("#41464d", visual.get("tableBorderColor"));
      assertTrue(visual.keySet().stream().allMatch(SupplementalInstanceMetaData.THEME_PROPERTY_NAMES::contains));
      String css = (String) visual.get("customCss");
      assertTrue(css.contains("data:image/svg+xml;base64,"));
      assertTrue(css.contains("--color-primary-foreground: #ffffff"));
      assertTrue(css.contains("color-scheme: dark"));
      assertTrue(css.contains("@media (forced-colors: active)"));
      assertFalse(css.contains("@import"));
      assertFalse(css.contains("https://"));
      assertFalse(css.contains("<script"));
   }



   @Test
   void financialHtmlUsesMatchingDarkSurfaceAndPreservesUnknownFacts()
   {
      String html = CarlDashboardHtml.render("carlCashFlow", Map.of("currency", "USD"));
      assertTrue(html.contains("color:#f5f5f5;background:#25282c"));
      assertTrue(html.contains("color:#b8bdc5"));
      assertFalse(html.contains("background:#fff"));
      assertTrue(html.contains("Not supplied / excluded"));
      assertTrue(html.contains("text-align:right"));
   }
}
