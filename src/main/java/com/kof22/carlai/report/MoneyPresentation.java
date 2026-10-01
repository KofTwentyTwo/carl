/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.report;


import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kingsrook.qqq.backend.core.model.metadata.fields.DisplayFormat;


/** Human presentation only: exact calculation/import values are never replaced. */
public final class MoneyPresentation
{
   private static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   private static final Set<String> MONEY_FIELDS = Set.of("acquisitionbasis", "actualspending", "budget", "remainingbudget", "actualexpenses", "actualreserveearmarks", "additionalrepair", "additionalsimpleinterest", "afteramount", "afterreturnbeforefees", "agreedpurchasecap", "allinprice", "allocatedprincipal", "amount", "annualcost", "appliedamount", "appliedrent", "assumednetgrowth", "assumedreturn", "availablebudget", "balance", "balloondue", "baseamount", "baselinecashafterreservetransfers", "baselineknownscheduledrent", "baselinepayment", "beforeamount", "buildingbasis", "candidateminusbaselinecost", "candidateminusbaselineinterestandfees", "candidateminusbaselineupfrontcash", "candidatepayment", "capacity", "capitalspending", "cashafterdebtandreserves", "cashafterreservetransfers", "cashbeforereserves", "cashfee", "cashoperatingincome", "cashpaid", "cashreserve", "classifiedexpenserefundsallaccountkinds", "classifiednetspendingallaccountkinds",
      "classifiedspendingallaccountkinds", "closingbalance", "closingcash",
      "contribution", "contributions", "cumulativereleasedcash", "currentpayment", "debtafterfinancedfees", "debtinterest", "debtprincipal", "deferredinterestcharged", "difference", "differenceobservedminusexpected", "discretionarycap", "endinghypotheticalvalue", "endingvalue", "escrow", "escrowamount", "expectedamount", "expectedrent", "fee", "feeamount", "fees", "financecost", "financedfee", "financedfees", "financedprincipal", "fixedmonthlyfee", "hypotheticalcashafterreservetransfers", "hypotheticalvacancyloss", "inflows", "initialcapital", "initialfee", "interest", "knownduerent", "knownscheduledexpenses", "knownscheduledrent", "landbasis", "marketvalue", "maximumcashbudget", "minimum", "minimumamount", "minimumcash", "minimumcashafterpayments", "monthlybudget", "monthlycontribution", "monthlyfee", "monthlyfees", "monthlypayment", "mortgageprincipal", "netmovement", "netoperatingincome", "observedamount", "openingbalance", "openingcash", "openingexpensereserveearmarks",
      "openingprincipal", "openingreserveearmarks", "openingvalue", "operatingexpenses", "originalamount", "originalprincipal", "outflows", "ownedpropertyvalue", "ownedsignedbalance", "ownershipattributedadversecashimpact", "ownershipattributedbaselinecash", "ownershipattributedhypotheticalcash", "ownershipattributedimpact", "ownershipattributedinterest", "ownershipattributedrepair", "ownershipattributedvacancy", "payment", "paymentamount", "pendingdeferredinterest", "principal", "principalbalance", "proposed", "proposedpayment", "protectedreserve", "releasedcash", "remainingamount", "remainingbalance", "remainingdebt", "remainingexpenses", "remainingreserveearmarks", "rentcollected", "repair", "repairamount", "required", "requiredminimum", "reserve", "reservefloor", "reserveneed", "reservetransfers", "restricteddepositcash", "scheduledrent", "setupcost", "signedamount", "sourceamount", "supportedcashbudget", "totaladversecashimpact", "totalcashoutlay", "totaldebtafterfees",
      "totalfees", "totalinterest", "unappliedreceipts", "unpaidrent", "unusedbudget", "upfrontcashfee", "upfrontcashfees");

   private MoneyPresentation()
   {
   }



   /** Uses QQQ display-format syntax, explicit grouping locale and the record's currency. */
   public static String format(BigDecimal amount, String code)
   {
      if(amount == null)
      {
         return "Not supplied";
      }
      Currency currency;
      try
      {
         currency = Currency.getInstance(code == null ? "" : code);
         if(currency.getDefaultFractionDigits() < 0)
         {
            throw new IllegalArgumentException("Currency has no defined monetary precision");
         }
      }
      catch(IllegalArgumentException invalid)
      {
         return number(amount, 2) + " — currency not supplied";
      }
      String symbol = currency.getSymbol(Locale.US);
      String value = number(amount.abs(), currency.getDefaultFractionDigits());
      String sign = amount.signum() < 0 ? "-" : "";
      return symbol.equals(code) ? sign + code + " " + value : sign + symbol + value + " " + code;
   }



   /** Recognizes formal monetary fields only; rates, fractions and IDs remain numeric. */
   public static boolean isMoneyField(String name)
   {
      return MONEY_FIELDS.contains(name.replace("_", "").toLowerCase(Locale.ROOT));
   }



   /** A derived human view; machine-readable stored facts and model tool data stay exact. */
   public static JsonNode humanFacts(JsonNode facts)
   {
      return humanFacts(facts, null, "", 0);
   }



   /** Formats typed preview facts, including ISO dates, without replacing their source records. */
   public static JsonNode humanFactsOf(Object facts)
   {
      return humanFacts(JSON.valueToTree(facts));
   }



   /** Parses exact saved decimals before producing a display-only copy. */
   public static String humanFacts(String facts)
   {
      try
      {
         return humanFacts(JSON.readTree(facts)).toPrettyString();
      }
      catch(com.fasterxml.jackson.core.JsonProcessingException invalid)
      {
         throw new IllegalArgumentException("Saved financial facts are not valid structured data", invalid);
      }
   }



   private static JsonNode humanFacts(JsonNode node, String currency, String field, int depth)
   {
      if(depth > 32)
      {
         throw new IllegalArgumentException("Financial display exceeds the nested facts bound");
      }
      if(node.isObject())
      {
         String current = currency(node, currency);
         var result = JsonNodeFactory.instance.objectNode();
         node.fields().forEachRemaining(entry ->
         {
            String key = entry.getKey();
            String code = key.matches("[A-Z]{3}(:[A-Z_]+)?") ? key.substring(0, 3) : current;
            boolean amountMap = Set.of("totals", "selectedCurrencyStatusDifferences", "knownSelectedNetWorthByCurrency", "knownSelectedLiquidBalancesByCurrency", "remainingBalances", "propertyAmounts", "allocations", "maturityResiduals").contains(field);
            result.set(key, amountMap && entry.getValue().isNumber() ? JsonNodeFactory.instance.textNode(format(entry.getValue().decimalValue(), code)) : humanFacts(entry.getValue(), code, key, depth + 1));
         });
         return result;
      }
      if(node.isArray())
      {
         var result = JsonNodeFactory.instance.arrayNode();
         node.forEach(value -> result.add(humanFacts(value, currency, field, depth + 1)));
         return result;
      }
      return node.isNumber() && isMoneyField(field) ? JsonNodeFactory.instance.textNode(format(node.decimalValue(), currency)) : node.deepCopy();
   }



   private static String currency(JsonNode node, String inherited)
   {
      for(JsonNode value : java.util.List.of(node.path("currency"), node.path("inputs").path("currency"), node.path("terms").path("currency"), node.path("budget").path("currency"), node.path("sourcePlan").path("currency"), node.path("offer").path("currency")))
      {
         if(value.isTextual() && !value.asText().isBlank())
         {
            return value.asText();
         }
      }
      return inherited;
   }



   private static String number(BigDecimal value, int currencyDigits)
   {
      int digits = Math.max(currencyDigits, Math.max(0, value.stripTrailingZeros().scale()));
      String pattern = digits == 2 ? DisplayFormat.DECIMAL2_COMMAS : "%,." + digits + "f";
      return String.format(Locale.US, pattern, value);
   }
}
