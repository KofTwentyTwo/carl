/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.report;


import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;


/*******************************************************************************
 * Renders already-authorized immutable report data as inert PDF text. This component
 * performs no authorization, persistence, HTML interpretation or caller-selected resource loading.
 * The caller must recheck access immediately before exporting the returned bytes.
 ******************************************************************************/
public final class PlanPdfRenderer
{
   public static final int MAX_PAGES = 24;
   private static final int MAX_TEXT = 400_000;
   private static final float LEFT = 48;
   private static final float WIDTH = 516;
   private static final Color INK = new Color(28, 43, 59);
   private static final Color MUTED = new Color(76, 91, 107);
   private static final Color ACCENT = new Color(22, 96, 114);
   private static final Color LINE = new Color(208, 219, 226);

   /** All audience and source filtering has already been performed by Carl's domain service. */
   public record Report(String title, String planId, long version, Instant generatedAt,
      String scope, String asOf, String progress, List<Figure> figures, List<Task> tasks,
      List<Source> sources, List<String> limitations)
   {
      /** Freezes caller-supplied collections for a consistent export snapshot. */
      public Report
      {
         figures = List.copyOf(figures);
         tasks = List.copyOf(tasks);
         sources = List.copyOf(sources);
         limitations = List.copyOf(limitations);
      }
   }



   /** A nullable value is explicitly unavailable; currencies are never converted or summed here. */
   public record Figure(String label, BigDecimal amount, String currency, String evidence)
   {
   }



   /** Describes a recorded plan task, never an authorization to execute it. */
   public record Task(String title, String assignee, LocalDate dueDate, TaskStatus status, String notes)
   {
   }



   /** Recorded progress labels supplied by the authorized plan service. */
   public enum TaskStatus
   {
      OPEN, IN_PROGRESS, COMPLETE, BLOCKED
   }



   /** References are printable evidence labels, not fetchable URLs or PDF links. */
   public record Source(String reference, String title, String provenance)
   {
   }

   /** Returns a complete bounded PDF or throws; oversized reports are never silently truncated. */
   public byte[] render(Report report) throws IOException
   {
      validate(report);
      try(var document = new PDDocument(); var bytes = new ByteArrayOutputStream())
      {
         document.getDocumentInformation().setTitle("Carl AI - " + safe(report.title()));
         document.getDocumentInformation().setCreator("Carl AI PlanPdfRenderer v1");
         try(var layout = new Layout(document))
         {
            layout.paragraph(report.title(), 20, true, INK, 8);
            layout.paragraph(report.planId() + " | Version " + report.version(), 10, true, ACCENT, 4);
            layout.paragraph("Generated " + report.generatedAt() + " (UTC)", 9, false, MUTED, 14);
            layout.section("Authorized scope");
            layout.paragraph(report.scope(), 10, false, INK, 6);
            layout.paragraph("Source as of: " + fallback(report.asOf(), "Not supplied"), 9, false, MUTED, 12);
            layout.section(report.planId().startsWith("report-") ? "Report status" : "Progress");
            layout.paragraph(fallback(report.progress(), "No progress update supplied."), 10, false, INK, 12);
            layout.section("Figures and assumptions");
            if(report.figures().isEmpty())
            {
               layout.paragraph("No figures supplied.", 10, false, MUTED, 8);
            }
            for(Figure figure : report.figures())
            {
               layout.keep(66);
               layout.paragraph(figure.label(), 10, true, INK, 2);
               layout.paragraph(figure.amount() == null ? figure.currency() + " - Not available" : figure.currency() + " " + figure.amount().setScale(Math.max(figure.amount().scale(), java.util.Currency.getInstance(figure.currency()).getDefaultFractionDigits())).toPlainString(), 17, true, ACCENT, 3);
               layout.paragraph(fallback(figure.evidence(), "Supporting evidence not supplied."), 9, false, MUTED, 12);
            }
            if(!report.planId().startsWith("report-") || !report.tasks().isEmpty())
            {
               layout.section("Plan tasks");
               if(report.tasks().isEmpty())
               {
                  layout.paragraph("No tasks recorded.", 10, false, MUTED, 8);
               }
            }
            int index = 0;
            for(Task task : report.tasks())
            {
               layout.keep(72);
               layout.paragraph(++index + ". " + task.title(), 11, true, INK, 3);
               layout.paragraph("Status: " + task.status().name().replace('_', ' ') + " | Who: " + fallback(task.assignee(), "Not assigned") + " | Due: " + (task.dueDate() == null ? "Not scheduled" : task.dueDate()), 9, true, ACCENT, 4);
               layout.paragraph(fallback(task.notes(), "No additional notes."), 10, false, INK, 13);
            }
            layout.section("Supporting sources");
            if(report.sources().isEmpty())
            {
               layout.paragraph("No supporting sources supplied; verify before relying on this report.", 10, false, MUTED, 8);
            }
            for(Source source : report.sources())
            {
               layout.keep(48);
               layout.paragraph(source.reference() + " - " + source.title(), 10, true, INK, 3);
               layout.paragraph(fallback(source.provenance(), "Provenance not supplied."), 9, false, MUTED, 10);
            }
            layout.section("Limitations and human review");
            if(report.limitations().isEmpty())
            {
               layout.paragraph("No limitations were supplied. This does not establish complete source coverage.", 10, false, INK, 8);
            }
            for(String limitation : report.limitations())
            {
               layout.paragraph("- " + limitation, 10, false, INK, 7);
            }
         }
         for(int i = 0; i < document.getNumberOfPages(); i++)
         {
            try(var footer = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true))
            {
               footer.setStrokingColor(LINE);
               footer.moveTo(LEFT, 52);
               footer.lineTo(LEFT + WIDTH, 52);
               footer.stroke();
               String identity = safe(report.planId() + " | Version " + report.version());
               if(width(identity, PDType1Font.HELVETICA, 8) <= WIDTH - 100)
               {
                  text(footer, identity, LEFT, 37, 8, PDType1Font.HELVETICA, MUTED);
               }
               else
               {
                  text(footer, report.planId(), LEFT, 39, 8, PDType1Font.HELVETICA, MUTED);
                  text(footer, "Version " + report.version(), LEFT, 27, 8, PDType1Font.HELVETICA, MUTED);
               }
               String pages = "Page " + (i + 1) + " of " + document.getNumberOfPages();
               text(footer, pages, LEFT + WIDTH - width(pages, PDType1Font.HELVETICA, 8), 37, 8, PDType1Font.HELVETICA, MUTED);
            }
         }
         document.save(bytes);
         return bytes.toByteArray();
      }
   }



   private static void validate(Report report)
   {
      Objects.requireNonNull(report, "report");
      if(report.planId() == null || !report.planId().matches("[A-Za-z0-9:_-]{1,48}") || report.version() < 1 || report.generatedAt() == null || report.figures().size() > 50 || report.tasks().size() > 100 || report.sources().size() > 100 || report.limitations().size() > 50)
      {
         throw new IllegalArgumentException("Report identity, time and bounded collection sizes are required");
      }
      int total = bounded(report.title(), 2000, true) + bounded(report.planId(), 48, true) + bounded(report.scope(), 8000, true) + bounded(report.asOf(), 2000, false) + bounded(report.progress(), 8000, false);
      for(Figure f : report.figures())
      {
         if(f.currency() == null || !f.currency().matches("[A-Z]{3}") || (f.amount() != null && (f.amount().precision() > 30 || Math.abs((long) f.amount().scale()) > 8)))
         {
            throw new IllegalArgumentException("Figures require explicit currency and bounded exact decimals");
         }
         total += bounded(f.label(), 2000, true) + bounded(f.evidence(), 4000, false);
      }
      for(Task task : report.tasks())
      {
         if(task.status() == null)
         {
            throw new IllegalArgumentException("Task status is required");
         }
         total += bounded(task.title(), 2000, true) + bounded(task.assignee(), 500, false) + bounded(task.notes(), 8000, false);
      }
      for(Source source : report.sources())
      {
         total += bounded(source.reference(), 200, true) + bounded(source.title(), 2000, true) + bounded(source.provenance(), 8000, false);
      }
      for(String limitation : report.limitations())
      {
         total += bounded(limitation, 8000, true);
      }
      if(total > MAX_TEXT)
      {
         throw new IllegalArgumentException("Report exceeds the bounded text budget");
      }
   }



   private static int bounded(String value, int maximum, boolean required)
   {
      if((required && (value == null || value.isBlank())) || (value != null && value.length() > maximum))
      {
         throw new IllegalArgumentException("Report text is missing or exceeds its bound");
      }
      return value == null ? 0 : value.length();
   }



   private static String fallback(String value, String absent)
   {
      return value == null || value.isBlank() ? absent : value;
   }



   private static String safe(String value) throws IOException
   {
      var result = new StringBuilder();
      for(int i = 0; i < value.length();)
      {
         int code = value.codePointAt(i);
         i += Character.charCount(code);
         String glyph = new String(Character.toChars(code));
         if(code == '\n')
         {
            result.append('\n');
            continue;
         }
         if(code == '\r' || code == '\t')
         {
            result.append(' ');
            continue;
         }
         try
         {
            if(Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)
            {
               throw new IllegalArgumentException();
            }
            PDType1Font.HELVETICA.getStringWidth(glyph);
            result.append(glyph);
         }
         catch(IllegalArgumentException unsupported)
         {
            result.append(String.format(Locale.ROOT, "[U+%04X]", code));
         }
      }
      return result.toString();
   }



   private static float width(String text, PDFont font, float size) throws IOException
   {
      return font.getStringWidth(text) / 1000 * size;
   }



   private static void text(PDPageContentStream stream, String value, float x, float y, float size, PDFont font, Color color) throws IOException
   {
      stream.beginText();
      stream.setFont(font, size);
      stream.setNonStrokingColor(color);
      stream.newLineAtOffset(x, y);
      stream.showText(value);
      stream.endText();
   }

   private static final class Layout implements AutoCloseable
   {
      private final PDDocument document;
      private PDPageContentStream stream;
      private float y;
      private Layout(PDDocument document) throws IOException
      {
         this.document = document;
         page();
      }



      private void page() throws IOException
      {
         if(stream != null)
         {
            stream.close();
            stream = null;
         }
         if(document.getNumberOfPages() >= MAX_PAGES)
         {
            throw new IllegalArgumentException("Report exceeds " + MAX_PAGES + " pages; narrow its scope");
         }
         var page = new PDPage(PDRectangle.LETTER);
         document.addPage(page);
         stream = new PDPageContentStream(document, page);
         text(stream, "Carl AI", LEFT, 756, 13, PDType1Font.HELVETICA_BOLD, ACCENT);
         text(stream, "HOUSEHOLD PLANNING", 350, 756, 9, PDType1Font.HELVETICA, MUTED);
         stream.setStrokingColor(LINE);
         stream.moveTo(LEFT, 738);
         stream.lineTo(LEFT + WIDTH, 738);
         stream.stroke();
         y = 714;
      }



      private void keep(float height) throws IOException
      {
         if(y - height < 72)
         {
            page();
         }
      }



      private void section(String title) throws IOException
      {
         keep(60);
         y -= 8;
         paragraph(title, 13, true, ACCENT, 8);
      }



      private void paragraph(String value, float size, boolean bold, Color color, float gap) throws IOException
      {
         PDFont font = bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
         float leading = size * 1.4f;
         for(String line : wrap(safe(value), font, size))
         {
            keep(leading);
            text(stream, line, LEFT, y, size, font, color);
            y -= leading;
         }
         y -= gap;
      }



      private static List<String> wrap(String value, PDFont font, float size) throws IOException
      {
         var lines = new ArrayList<String>();
         for(String paragraph : value.split("\\n", -1))
         {
            var line = new StringBuilder();
            float used = 0;
            for(String word : paragraph.strip().split("\\s+"))
            {
               if(word.isEmpty())
               {
                  continue;
               }
               float wordWidth = width(word, font, size);
               float separator = line.isEmpty() ? 0 : width(" ", font, size);
               if(!line.isEmpty() && used + separator + wordWidth > WIDTH)
               {
                  lines.add(line.toString());
                  line.setLength(0);
                  used = 0;
                  separator = 0;
               }
               if(separator > 0)
               {
                  line.append(' ');
                  used += separator;
               }
               for(int i = 0; i < word.length(); i++)
               {
                  String character = word.substring(i, i + 1);
                  float characterWidth = width(character, font, size);
                  if(used + characterWidth > WIDTH && !line.isEmpty())
                  {
                     lines.add(line.toString());
                     line.setLength(0);
                     used = 0;
                  }
                  line.append(character);
                  used += characterWidth;
               }
            }
            lines.add(line.toString());
         }
         return lines;
      }



      @Override
      public void close() throws IOException
      {
         if(stream != null)
         {
            stream.close();
         }
      }
   }
}
