/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai;


import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.RentalAllocationReviews;
import com.kof22.carlai.domain.RentalEconomics;


/** Saved, typed human allocation review over the same rental capabilities used by Carl. */
final class RentalReviewProcesses
{
   private RentalReviewProcesses()
   {
   }



   static void register(QInstance instance, QAppMetaData app, CarlService service)
   {
      var reviews = new RentalAllocationReviews(service);
      for(var table : List.of(
         CarlMetadata.table("carlRentalReviews", "Rental Allocation Reviews", "carl_rental_review_view", "transaction_id:L,transaction_revision:L,review_version:L,state:S,classification_id:L,classification_version:L,result_id:L,source_amount:M,currency:S,source_date:D,source_stale:B,classification_stale:B"),
         CarlMetadata.table("carlRentalReviewComponents", "Reviewed Rental Components", "carl_rental_review_component_view", "review_id:L,component_id:S,kind:S,amount:M,outside_fraction:R,review_version:L,state:S,currency:S"),
         CarlMetadata.table("carlRentalReviewShares", "Reviewed Property Shares", "carl_rental_review_share_view", "review_id:L,component_id:S,property_id:L,property_label:S,fraction:R,review_version:L,state:S")))
      {
         instance.addTable(table);
         app.withChild(table);
         instance.addPossibleValueSource(QPossibleValueSource.newForTable(table.getName()));
      }
      CarlMetadata.choices(instance, "carlRentalReviewKind", Arrays.stream(RentalEconomics.Kind.values()).map(Enum::name).toList());
      CarlMetadata.add(instance, app, CarlMetadata.process("carlStartRentalReview", "Start Rental Allocation Review", List.of(pick("transaction", "carlTransactions"), f("title", QFieldType.STRING), f("visibility", QFieldType.STRING).withPossibleValueSourceName("carlVisibility"), evidence()), (in, out) ->
      {
         long id = reviews.create(CarlMetadata.principal(), request(in), id(in, "transaction"), in.getValueString("visibility"), in.getValueString("title"), in.getValueString("evidence"));
         out.addValue("result", "Saved review " + id + ", version 1. Add components and property shares, preview, then explicitly apply. No transaction was changed.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlEditRentalReviewComponent", "Add or Edit Reviewed Component", List.of(review(), version(), f("component", QFieldType.STRING).withLabel("Stable component name"), f("kind", QFieldType.STRING).withPossibleValueSourceName("carlRentalReviewKind"), f("amount", QFieldType.DECIMAL), f("outsideFraction", QFieldType.DECIMAL).withLabel("Outside rental scope fraction (0 means none)"), evidence()), (in, out) ->
      {
         long next = reviews.setComponent(CarlMetadata.principal(), request(in), id(in, "review"), id(in, "expectedVersion"), in.getValueString("component"), RentalEconomics.Kind.valueOf(in.getValueString("kind")), decimal(in, "amount"), decimal(in, "outsideFraction"), in.getValueString("evidence"));
         out.addValue("result", "Draft component saved at review version " + next + ". Source classification unchanged.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlEditRentalReviewShare", "Add or Edit Property Share", List.of(review(), version(), pick("component", "carlRentalReviewComponents"), pick("property", "carlProperties"), f("fraction", QFieldType.DECIMAL).withLabel("Property fraction (0.5 means half; 0 removes it)"), evidence()), (in, out) ->
      {
         String principal = CarlMetadata.principal();
         long review = id(in, "review");
         String key = reviews.componentKey(principal, review, id(in, "component"));
         long next = reviews.setShare(principal, request(in), review, id(in, "expectedVersion"), key, id(in, "property"), decimal(in, "fraction"), in.getValueString("evidence"));
         out.addValue("result", "Property share saved at review version " + next + ". Fractions cannot exceed 100%; incomplete shares remain a draft.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlRemoveRentalReviewComponent", "Remove Draft Component", List.of(review(), version(), pick("component", "carlRentalReviewComponents"), evidence()), (in, out) ->
      {
         String principal = CarlMetadata.principal();
         long review = id(in, "review");
         long next = reviews.removeComponent(principal, request(in), review, id(in, "expectedVersion"), reviews.componentKey(principal, review, id(in, "component")), in.getValueString("evidence"));
         out.addValue("result", "Draft component and its shares removed with attribution; review version " + next + ". Imported evidence retained.");
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlPreviewRentalReview", "Preview Rental Allocation Review", List.of(review()), (in, out) ->
      {
         var preview = reviews.preview(CarlService.Scope.privateFor(CarlMetadata.principal()), id(in, "review"));
         var text = new StringBuilder("Review ").append(preview.id()).append(" / version ").append(preview.version()).append(" / ").append(preview.state()).append("\nSource: ").append(com.kof22.carlai.report.MoneyPresentation.format(preview.sourceAmount(), preview.currency()));
         for(var part : preview.components())
         {
            text.append("\n").append(part.id()).append(" / ").append(part.kind()).append(" / ").append(com.kof22.carlai.report.MoneyPresentation.format(part.amount(), preview.currency())).append(" / unallocated: ").append(part.remainingFraction().multiply(new BigDecimal("100")).toPlainString()).append('%');
            part.propertyAmounts().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> text.append("\n  Allocation ").append(entry.getKey()).append(": ").append(com.kof22.carlai.report.MoneyPresentation.format(entry.getValue(), preview.currency())));
         }
         for(String gap : preview.gaps())
         {
            text.append("\nNeeds attention: ").append(gap);
         }
         text.append(preview.canApply() ? "\nReady for explicit application. No classification has been changed by this preview." : "\nCannot newly apply this review. Applied classifications remain immutable; corrections use a fresh review.");
         out.addValue("result", text.toString());
      }));
      CarlMetadata.add(instance, app, CarlMetadata.process("carlApplyRentalReview", "Apply or Reconcile Reviewed Allocation", List.of(review(), version(), evidence()), (in, out) ->
      {
         long result = reviews.apply(CarlMetadata.principal(), request(in), id(in, "review"), id(in, "expectedVersion"), in.getValueString("evidence"));
         out.addValue("result", "Confirmed classification " + result + " saved. Prior classification versions and original transaction evidence remain available. No payment or external tax action occurred.");
      }));
   }



   private static QFieldMetaData review()
   {
      return pick("review", "carlRentalReviews");
   }



   private static QFieldMetaData version()
   {
      return f("expectedVersion", QFieldType.LONG).withLabel("Review version shown in Rental Allocation Reviews");
   }



   private static QFieldMetaData evidence()
   {
      return f("evidence", QFieldType.TEXT);
   }



   private static QFieldMetaData pick(String name, String table)
   {
      return f(name, QFieldType.LONG).withPossibleValueSourceName(table);
   }



   private static QFieldMetaData f(String name, QFieldType type)
   {
      return new QFieldMetaData(name, type).withIsRequired(true);
   }



   private static long id(RunBackendStepInput in, String name)
   {
      return Long.parseLong(in.getValueString(name));
   }



   private static BigDecimal decimal(RunBackendStepInput in, String name)
   {
      return new BigDecimal(in.getValueString(name));
   }



   private static UUID request(RunBackendStepInput in)
   {
      return UUID.fromString(in.getValueString("requestId"));
   }
}
