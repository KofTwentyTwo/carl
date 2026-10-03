/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import com.kingsrook.qqq.backend.core.model.metadata.QSupplementalInstanceMetaData;


/** Carl's consumer-owned visual properties for the native Next/QQQ theme contract. */
public final class CarlTheme implements QSupplementalInstanceMetaData
{
   // Native middleware reads allow-listed visual getters under this shared metadata key.
   public static final String NAME = "com.kingsrook.qqq.frontend.materialdashboard.model.metadata.MaterialDashboardThemeMetaData";

   @Override
   public String getName()
   {
      return NAME;
   }



   public String getPrimaryColor()
   {
      return "#0066cc";
   }



   public String getSecondaryColor()
   {
      return "#69b0ff";
   }



   public String getBackgroundColor()
   {
      return "#1b1b1b";
   }



   public String getSurfaceColor()
   {
      return "#25282c";
   }



   public String getTextPrimary()
   {
      return "#f5f5f5";
   }



   public String getTextSecondary()
   {
      return "#b8bdc5";
   }



   public String getErrorColor()
   {
      return "#ff5b50";
   }



   public String getWarningColor()
   {
      return "#ffc857";
   }



   public String getSuccessColor()
   {
      return "#62c99e";
   }



   public String getInfoColor()
   {
      return "#0066cc";
   }



   public String getFontFamily()
   {
      return "Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
   }



   public String getHeaderFontFamily()
   {
      return "Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
   }



   public String getBorderRadiusGlobal()
   {
      return "3px";
   }



   public String getSidebarBackgroundColor()
   {
      return "#25282c";
   }



   public String getSidebarTextColor()
   {
      return "#f5f5f5";
   }



   public String getSidebarIconColor()
   {
      return "#b8bdc5";
   }



   public String getSidebarSelectedBackgroundColor()
   {
      return "#163e69";
   }



   public String getSidebarSelectedTextColor()
   {
      return "#ffffff";
   }



   public String getSidebarHoverBackgroundColor()
   {
      return "#34383d";
   }



   public String getSidebarDividerColor()
   {
      return "#41464d";
   }



   public String getTableHeaderBackgroundColor()
   {
      return "#34383d";
   }



   public String getTableHeaderTextColor()
   {
      return "#f5f5f5";
   }



   public String getTableRowHoverColor()
   {
      return "#34383d";
   }



   public String getTableRowSelectedColor()
   {
      return "#163e69";
   }



   public String getTableBorderColor()
   {
      return "#41464d";
   }



   public String getDividerColor()
   {
      return "#41464d";
   }



   public String getBorderColor()
   {
      return "#41464d";
   }



   public String getCardBorderColor()
   {
      return "#41464d";
   }



   public String getCustomCss()
   {
      return """
         body.qqq-themed {
            color-scheme: dark;
            --color-secondary: #34383d;
            --color-muted: #34383d;
            --color-accent: #34383d;
            --color-primary-foreground: #ffffff;
            --color-ring: #69b0ff;
            --qqq-link-color: #69b0ff;
            --qqq-tooltip-background-color: #34383d;
            --qqq-tooltip-text-color: #f5f5f5;
            --qqq-switch-track-color: #41464d;
            --carl-background-image: url("data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHZpZXdCb3g9JzAgMCAxNjAwIDEwMDAnPjxyZWN0IHdpZHRoPScxNjAwJyBoZWlnaHQ9JzEwMDAnIGZpbGw9JyMxYjFiMWInLz48ZyBmaWxsPScjZmZmZmZmJyBvcGFjaXR5PScuMDI4Jz48cGF0aCBkPSdNMCAwaDUwMEwyNDAgMzAweicvPjxwYXRoIGQ9J001MDAgMGg2NDBMNzgwIDI4MHonLz48cGF0aCBkPSdNMTE0MCAwaDQ2MHYzNjB6Jy8+PHBhdGggZD0nTTAgMGwyNDAgMzAwTDAgNTcweicvPjxwYXRoIGQ9J00yNDAgMzAwbDU0MC0yMC0zMzAgMjUweicvPjxwYXRoIGQ9J003ODAgMjgwbDUyMCAxNjAtNTEwIDcweicvPjxwYXRoIGQ9J00xMzAwIDQ0MGwzMDAtODB2MzYweicvPjxwYXRoIGQ9J00wIDU3MGw0NTAtNDAtMjEwIDIzMHonLz48cGF0aCBkPSdNNDUwIDUzMGwzNDAtMjAtMTYwIDMzMHonLz48cGF0aCBkPSdNNzkwIDUxMGw1MTAtNzAtMjYwIDM3MHonLz48cGF0aCBkPSdNMjQwIDc2MGwzOTAgODAtMTMwIDE2MEgweicvPjxwYXRoIGQ9J00xMDQwIDgxMGw1NjAtOTB2MjgweicvPjwvZz48ZyBmaWxsPScjZmZmZmZmJyBvcGFjaXR5PScuMDE4Jz48cGF0aCBkPSdNNTAwIDBsMjgwIDI4MC01NDAgMjB6Jy8+PHBhdGggZD0nTTExNDAgMGwxNjAgNDQwLTUyMC0xNjB6Jy8+PHBhdGggZD0nTTI0MCAzMDBsMjEwIDIzMEwwIDU3MHonLz48cGF0aCBkPSdNNDUwIDUzMGwxODAgMzEwLTM5MC04MHonLz48cGF0aCBkPSdNNzkwIDUxMGwyNTAgMzAwLTQxMCAzMHonLz48L2c+PC9zdmc+");
            background-image: var(--carl-background-image);
            background-size: cover;
            background-attachment: fixed;
         }
         body.qqq-themed #main-content {
            background-image: var(--carl-background-image);
            background-size: cover;
            background-attachment: fixed;
         }
         body.qqq-themed [data-qqq-id="header"] {
            background: #25282c;
            color: #f5f5f5;
            border-top: 3px solid #0066cc;
         }
         body.qqq-themed [data-qqq-id="application-name"] { font-size: 1.125rem; }
         body.qqq-themed [data-qqq-id="sidebar"] { border-right: 1px solid #41464d; }
         body.qqq-themed [data-qqq-id^="widget-grid-item-"] > div {
            border-top: 3px solid #0066cc;
            background-color: #25282c;
         }
         body.qqq-themed section[aria-label="Chat with Carl"] {
            border-top: 3px solid #0066cc;
         }
         body.qqq-themed :is(input, select, textarea)::placeholder { color: #b8bdc5; }
         body.qqq-themed :focus-visible {
            outline: 3px solid #69b0ff;
            outline-offset: 2px;
         }
         @media (forced-colors: active) {
            body.qqq-themed, body.qqq-themed #main-content { background-image: none; }
            body.qqq-themed :focus-visible { outline-color: Highlight; }
         }
         """;
   }
}
