/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit disposable synthetic preview loader. This is not an application import tool. */
public final class CarlPublicHouseholdSeed
{
   // Deliberate v1 public recipe identity; update only with reviewed from-scratch generator changes.
   private static final String PUBLIC_V1_SHA256 = "4b763e87f08af36d67a07ffd692bc3033d9cd8dd6c58f33c837e6223eabee953";
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String EVIDENCE = "Fictional public household v1; independently authored; no external financial action";
   private CarlPublicHouseholdSeed() {}

   /** Requires the fixture's prepared empty synthetic household, uses allocated IDs, and returns persisted selections. */
   public static Map<String, Object> seed(CarlService service, Path directory) throws Exception
   {
      String manifestText = read(directory, "household.json");
      String transactions = read(directory, "transactions.csv"), balances = read(directory, "balances.csv");
      String calendarText = read(directory, "calendar.ics"), reminderText = read(directory, "reminders.ics");
      String digest = BillCsv.hash("household.json:" + manifestText.length() + ":" + manifestText
         + "transactions.csv:" + transactions.length() + ":" + transactions
         + "balances.csv:" + balances.length() + ":" + balances
         + "calendar.ics:" + calendarText.length() + ":" + calendarText
         + "reminders.ics:" + reminderText.length() + ":" + reminderText);
      if(!PUBLIC_V1_SHA256.equals(digest)) throw new SecurityException("Canonical public fictional v1 bundle required; modified files cannot enter the fixture");
      JsonNode manifest = JSON.readTree(manifestText);
      if(!"public-synthetic".equals(manifest.path("mode").asText()) || !manifest.path("fixtureOnly").asBoolean()
         || manifest.path("schemaVersion").asInt() != 1 || manifest.path("accounts").size() != 36)
         throw new IllegalArgumentException("Explicit public synthetic fixture manifest required");

      UUID request = id("complete-seed");
      var prior = service.transaction(c ->
      {
         var alice = CarlService.manager(c, "alice", "FINANCE");
         var bob = CarlService.manager(c, "bob", "FINANCE");
         var households = CarlService.rows(c, "SELECT name FROM carl_household WHERE id=?", alice.householdId());
         if(alice.householdId() != bob.householdId() || !households.getFirst().get("name").equals("Synthetic Household")
            || CarlService.rows(c, "SELECT id FROM carl_member WHERE household_id=? AND active", alice.householdId()).size() != 2)
            throw new SecurityException("Only the prepared two-member Synthetic Household fixture may be seeded");
         var old = CarlService.rows(c, "SELECT * FROM carl_request WHERE id=?", request);
         if(!old.isEmpty())
         {
            if(!digest.equals(old.getFirst().get("digest")) || !old.getFirst().get("status").equals("COMPLETE"))
               throw new IllegalStateException("Changed or interrupted fixture seed; create a fresh owned disposable database");
            return old.getFirst().get("detail").toString();
         }
         if(!CarlService.rows(c, "SELECT id FROM carl_record WHERE household_id=? LIMIT 1", alice.householdId()).isEmpty())
            throw new SecurityException("Public fixture requires an empty prepared synthetic target; never mixes ordinary seeds");
         CarlService.request(c, alice, request, "PUBLIC_SYNTHETIC_FIXTURE", digest);
         return null;
      });
      if(prior != null) return JSON.readValue(prior, JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
      var result = new LinkedHashMap<String, Object>();
      var accounts = new LinkedHashMap<String, Long>();
      var finance = new FinancialRecords(service);
      var mapping = new MonarchImportWorkflow(service);
      for(var account : manifest.path("accounts"))
      {
         String label = account.path("label").asText();
         if(!label.startsWith("Fictional ")) throw new IllegalArgumentException("Fictional account labels required");
         long allocated = finance.createAccount("alice", label, account.path("kind").asText(), account.path("currency").asText(),
            account.path("liquid").asBoolean(), number(account, "ownershipShare"), account.path("visibility").asText(), EVIDENCE + (label.equals("Fictional APR Card") ? "; explicitly supplied USD credit limit 60000.00; available credit 47721.38 is borrowing capacity, never cash" : label.equals("Fictional Expired Promo Card") ? "; explicitly supplied zero-rate promotion expired 2025-12-31; current APR 20.99%" : ""));
         accounts.put(label, allocated);
         mapping.mapAccount("alice", label, allocated);
      }
      System.out.println("PUBLIC_FIXTURE_STAGE=imports");
      long transactionBatch = finance.importTransactions("alice", id("transactions"), transactions, accounts, false);
      long balanceBatch = finance.importBalances("alice", id("balances"), balances, accounts, false);
      var sources = service.transaction(c -> CarlService.rows(c, "SELECT t.*,s.tags FROM carl_transaction_view t JOIN carl_transaction_source s ON s.transaction_id=t.id AND s.version=1 WHERE principal='alice' AND source_id LIKE 'fictional-tx-%' ORDER BY id"));
      if(sources.size() != manifest.path("expected").path("transactionCount").asInt()) throw new IllegalStateException("Exact imported transaction count mismatch");
      System.out.println("PUBLIC_FIXTURE_STAGE=classifications");
      var pairs = new LinkedHashMap<String, List<Map<String, Object>>>();
      for(var source : sources)
      {
         long transaction = CarlService.number(source, "id");
         String sourceId = source.get("source_id").toString();
         var meta = manifest.path("transactionMetadata").path(sourceId);
         if(meta.isMissingNode()) throw new IllegalStateException("Missing explicit fictional classification");
         if("TRANSFER".equals(meta.path("classification").asText()))
            pairs.computeIfAbsent(source.get("tags").toString(), key -> new ArrayList<>()).add(source);
         else finance.classify("alice", transaction, meta.path("classification").asText(), meta.path("category").asText(), EVIDENCE);
      }
      for(var legs : pairs.values())
      {
         if(legs.size() != 2) throw new IllegalStateException("Exactly two transfer legs required");
         var outgoing = ((BigDecimal) legs.getFirst().get("amount")).signum() < 0 ? legs.getFirst() : legs.getLast();
         var incoming = outgoing == legs.getFirst() ? legs.getLast() : legs.getFirst();
         finance.pairTransfer("alice", CarlService.number(outgoing, "id"), CarlService.number(incoming, "id"), EVIDENCE);
      }
      System.out.println("PUBLIC_FIXTURE_STAGE=properties");
      var properties = new LinkedHashMap<String, Long>();
      var units = new LinkedHashMap<String, Long>();
      var rentals = new RentalRecords(service);
      var homes = new HomeRecords(service);
      LocalDate asOf = LocalDate.parse(manifest.path("asOf").asText());
      for(var property : manifest.path("properties"))
      {
         String key = property.path("key").asText();
         long propertyId = rentals.createProperty("alice", id("property-" + key), property.path("title").asText(), "FAMILY", property.path("evidence").asText(),
            new RentalRecords.PropertyValues("USD", property.path("locality").asText(), BigDecimal.ONE, accounts.get(property.path("asset").asText()), accounts.get(property.path("debt").asText()),
               property.path("legalOwner").asText(), number(property, "marketValue"), asOf, LocalDate.parse(property.path("acquired").asText()), number(property, "costBasis"), number(property, "landBasis"), number(property, "buildingBasis"), EVIDENCE));
         properties.put(key, propertyId);
         for(var unit : property.path("units"))
         {
            String unitKey = unit.path("key").asText();
            long unitId = rentals.createUnit("alice", id("unit-" + unitKey), propertyId, unit.path("label").asText(), "FAMILY", EVIDENCE,
               new RentalRecords.UnitValues(unit.path("label").asText(), number(unit, "rent"), date(unit, "leaseStart"), date(unit, "leaseEnd"), unit.path("occupancy").asText()));
            units.put(unitKey, unitId);
         }
         long revision = CarlService.number(homes.get("alice", propertyId), "revision");
         homes.save("alice", id("home-" + key), propertyId, revision, new HomeRecords.ProfileValues(HomeRecords.Use.valueOf(property.path("use").asText()), "USD",
            new HomeRecords.Mortgage(asOf, number(property, "principalAsOf").abs(), number(property, "apr"), number(property, "mortgagePayment"), date(property, "firstContractPayment"), date(property, "maturity"), property.path("amortizationMonths").asInt(), HomeRecords.RateKind.FIXED, BigDecimal.ZERO, BigDecimal.ZERO, property.path("contractAssumptions").asText()), EVIDENCE));
      }
      var dueIds = new LinkedHashMap<String, Long>();
      var rentalSources = new ArrayList<Long>();
      for(var source : sources)
      {
         String tag = source.get("tags").toString(), category = source.get("category").toString();
         if(!source.get("effective_date").toString().startsWith("2026-09") && !category.equals("Capital improvement")) continue;
         String key = tag.startsWith("property:") ? tag.substring(9) : tag.startsWith("rent:") ? "courtyard" : "";
         if(key.isEmpty() || key.equals("home")) continue;
         var kind = tag.startsWith("rent:") ? RentalEconomics.Kind.RENT_RECEIPT : category.equals("Capital improvement") ? RentalEconomics.Kind.CAPITAL_EXPENDITURE : RentalEconomics.Kind.OPERATING_EXPENSE;
         long transaction = CarlService.number(source, "id");
         var component = new RentalEconomics.Component("fictional-component", kind, ((BigDecimal) source.get("amount")).abs(), Map.of(Long.toString(properties.get(key)), BigDecimal.ONE), BigDecimal.ZERO);
         long split = rentals.classify("alice", id("rental-split-" + source.get("source_id")), transaction, List.of(component), "FAMILY", EVIDENCE);
         rentalSources.add(split);
         if(tag.startsWith("rent:"))
         {
            String unitKey = tag.substring(5);
            var unit = manifest.path("properties").get(1).path("units").get(unitKey.endsWith("1") ? 0 : 1);
            long due = rentals.rentDue("alice", id("due-" + unitKey), properties.get(key), units.get(unitKey), LocalDate.of(2026, 9, 1), number(unit, "rent"), "FAMILY", EVIDENCE);
            rentals.applyRent("alice", id("rent-application-" + unitKey), due, split, 1, "fictional-component", ((BigDecimal) source.get("amount")).abs(), EVIDENCE);
            dueIds.put(unitKey, due);
         }
      }
      for(String key : List.of("courtyard", "orchard"))
      {
         var payment = sources.stream().filter(row -> row.get("effective_date").toString().equals("2026-09-10") && row.get("tags").toString().startsWith("pair:" + key + "-mortgage:") && ((BigDecimal) row.get("amount")).signum() < 0).findFirst().orElseThrow();
         long accountId = accounts.get(key.equals("courtyard") ? "Fictional Courtyard Mortgage" : "Fictional Orchard Mortgage");
         BigDecimal interest = sources.stream().filter(row -> row.get("effective_date").toString().equals("2026-09-09") && CarlService.number(row, "account_id") == accountId && row.get("category").equals("Interest")).map(row -> ((BigDecimal) row.get("amount")).abs()).findFirst().orElseThrow();
         var components = List.of(new RentalEconomics.Component("fictional-principal", RentalEconomics.Kind.DEBT_PRINCIPAL, ((BigDecimal) payment.get("amount")).abs().subtract(interest), Map.of(Long.toString(properties.get(key)), BigDecimal.ONE), BigDecimal.ZERO),
            new RentalEconomics.Component("fictional-interest", RentalEconomics.Kind.DEBT_INTEREST, interest, Map.of(Long.toString(properties.get(key)), BigDecimal.ONE), BigDecimal.ZERO));
         rentalSources.add(rentals.classify("alice", id("rental-mortgage-" + key), CarlService.number(payment, "id"), components, "FAMILY", EVIDENCE));
      }
      var agenda = new CalendarAgendaService(service, "fictional-events", "alice", BillCsv.hash("fictional-offline-calendar-v1"), Set.of(service.member("alice").id(), service.member("bob").id()), () -> new CalendarAgendaService.Provider()
      {
         public Set<String> components() { return Set.of("VEVENT"); }
         public List<com.kof22.carlai.calendar.CalDavClient.Resource> query(Instant from, Instant through) { return List.of(new com.kof22.carlai.calendar.CalDavClient.Resource(java.net.URI.create("https://fictional-calendar.invalid/shared.ics"), "fictional-v1", calendarText)); }
         public void close() {}
      });
      var calendarState = agenda.synchronize("alice", id("calendar-sync"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
      result.put("calendarSync", calendarState);
      var debtPlans = new DebtPlans(service);
      for(var account : manifest.path("accounts"))
      {
         if(!Set.of("LOAN", "CREDIT_CARD").contains(account.path("kind").asText())) continue;
         String label = account.path("label").asText();
         BigDecimal rate = label.contains("APR Card") ? new BigDecimal("0.2399") : label.contains("Expired Promo") ? new BigDecimal("0.2099")
            : label.contains("Home Mortgage") ? new BigDecimal("0.0425") : label.contains("Courtyard") ? new BigDecimal("0.0525") : new BigDecimal("0.0475");
         BigDecimal minimum = label.contains("APR Card") ? new BigDecimal("450.00") : label.contains("Expired Promo") ? new BigDecimal("100.00") : new BigDecimal(label.contains("Home Mortgage") ? "1500.00" : label.contains("Courtyard") ? "1300.00" : "1100.00");
         debtPlans.terms("alice", accounts.get(label), asOf, new BigDecimal(manifest.path("expected").path("closingByAccount").path(label).asText()).abs(), minimum, BigDecimal.ZERO, rate, BigDecimal.ZERO, EVIDENCE);
         debtPlans.paymentProfile("alice", accounts.get(label), asOf, LocalDate.of(2026, 10, 10), minimum, minimum, FinancialPlanning.Strategy.AVALANCHE, EVIDENCE);
      }
      var expenses = new ExpenseRecords(service);
      var expenseIds = new ArrayList<Long>();
      for(var entry : properties.entrySet())
      {
         for(var charge : Map.of("tax", "250.00", "insurance", "110.00", "utilities", "120.00", "maintenance", "85.00", "CAPEX reserve", "100.00").entrySet())
         {
            expenseIds.add(expenses.create("alice", id("expense-" + entry.getKey() + "-" + charge.getKey()), "Fictional " + entry.getKey() + " " + charge.getKey(), "FAMILY", EVIDENCE,
               new ExpenseRecords.Schedule("USD", ExpenseForecast.Cadence.MONTHLY, LocalDate.of(2026, 10, 18), null, new BigDecimal(charge.getValue()), Map.of(), charge.getKey().equals("CAPEX reserve") ? ExpenseForecast.Kind.RESERVE_EARMARK : ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.ESTIMATED, entry.getValue())));
         }
      }
      expenseIds.add(expenses.create("alice", id("expense-unknown"), "Fictional future maintenance quote pending", "FAMILY", EVIDENCE,
         new ExpenseRecords.Schedule("USD", ExpenseForecast.Cadence.ONCE, LocalDate.of(2026, 10, 20), null, null, Map.of(), ExpenseForecast.Kind.EXPENSE, ExpenseForecast.Basis.ESTIMATED, null)));
      service.importBills("alice", id("bills"), "Fictional public bills", billCsv(manifest));
      long vendor = service.createVendor("alice", "Fictional Meadow Plumbing", "PLUMBING", "quote@fictional-meadow.invalid", true, "FAMILY", EVIDENCE);
      long work = service.createWorkItem("alice", vendor, "Fictional inspect Courtyard tap", "VENDOR_RESPONSE", LocalDate.of(2026, 10, 3), "Fictional quote requested; no price agreed", "FAMILY");
      new BudgetRecords(service).create("alice", id("budget"), "Fictional October grocery budget", "FAMILY", "Groceries", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal("700.00"), "USD", EVIDENCE);
      new FinancialGoals(service).create("alice", "Fictional family debt freedom", "DEBT_FREEDOM", 1, "FAMILY", EVIDENCE);
      var cash = new CashPlans(service);
      long cashPlan = cash.create("alice", "Fictional October conditional household forecast", "FAMILY", EVIDENCE, new CashPlans.Assumptions("USD", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 31),
         number(manifest.path("expected"), "liquidSharedUSD"), new BigDecimal("20000.00"), new BigDecimal("350.00"), true, false, false, true, true, true));
      expenses.attachCashPlan("alice", id("cash-expenses"), cashPlan, Set.copyOf(expenseIds), Set.of(), EVIDENCE);
      for(int month = 0; month < 6; month++)
      {
         LocalDate day = LocalDate.of(2026, 10, 1).plusMonths(month);
         cash.event("alice", cashPlan, id("cash-income-" + month), day, new BigDecimal("6200.00"), "Fictional supported salary", EVIDENCE);
         cash.event("alice", cashPlan, id("cash-debts-" + month), day.withDayOfMonth(10), new BigDecimal("-4450.00"), "Fictional explicit current debt payments", EVIDENCE);
      }
      var offer = new FinancingScenarios.Offer("fictional-store", "USD", new BigDecimal("400.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"), 4,
         List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(5, new BigDecimal("0.24"))), new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 4, BigDecimal.ZERO, true), FinancingScenarios.Evidence.VERIFIED_TERMS);
      long storeOffer = new FinancingOffers(service).create("alice", "Fictional four-month furniture terms", "FAMILY", "PURCHASE_FINANCE", offer, asOf, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15), "Fictional published terms; approval never claimed", EVIDENCE);
      var transfer = new FinancingScenarios.Offer("fictional-transfer", "USD", new BigDecimal("3000.00"), new BigDecimal("90.00"), BigDecimal.ZERO, new BigDecimal("270.00"), 12,
         List.of(new FinancialPlanning.Rate(1, BigDecimal.ZERO), new FinancialPlanning.Rate(13, new BigDecimal("0.2499"))), new FinancingScenarios.Promotion(FinancingScenarios.PromotionKind.TRUE_ZERO, 12, BigDecimal.ZERO, true), FinancingScenarios.Evidence.HYPOTHETICAL);
      long transferOffer = new FinancingOffers(service).create("alice", "Fictional promotional balance transfer assumption", "FAMILY", "BALANCE_TRANSFER", transfer, asOf, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 10), "Hypothetical limit and eligibility; no application or transfer", EVIDENCE);
      System.out.println("PUBLIC_FIXTURE_STAGE=artifacts");
      var shared = new CarlService.Scope("alice", Set.of("alice", "bob"));
      long draft = service.generateDraft(shared, id("draft"), work, "QUOTE_REQUEST");
      long comparison = debtPlans.compare(shared, id("comparison"), asOf, "USD", new BigDecimal("6500.00"), 240, "Fictional explicit maximum allocation; conditional assumptions");
      var plans = new PlanLifecycle(service);
      long plan = plans.create("alice", id("plan"), comparison, "Fictional shared debt review plan", EVIDENCE);
      UUID task = id("task-bob");
      int version = plans.step("alice", plan, 1, task, "Fictional review expired promotional card statement", service.member("bob").id(), LocalDate.of(2026, 10, 5), "Fictional household desk", null, EVIDENCE);
      plans.checkIn("bob", plan, version, task, "BLOCKED", "Fictional awaiting clarification of issuer terms; no financial action", null);
      var sharedAccountIds = accounts.entrySet().stream().filter(e -> !Set.of("Fictional Aster Wallet", "Fictional Unvalued Collectible").contains(e.getKey())).map(Map.Entry::getValue).toList();
      long balance = new BalanceSheets(service, Clock.fixed(Instant.parse("2026-09-30T23:59:59Z"), ZoneOffset.UTC)).report(shared, id("balance"), asOf, 31, sharedAccountIds,
         properties.values().stream().map(p -> new BalanceSheets.PropertyChoice(p, BalanceSheets.Valuation.LINKED_ACCOUNT)).toList());
      long rentalReport = rentals.report(shared, id("rental-report"), Set.of(properties.get("courtyard"), properties.get("orchard")), LocalDate.of(2026, 9, 1), asOf, asOf);
      long report = service.generateReport(shared, id("report"), LocalDate.of(2026, 9, 1), asOf, facts -> "Controlled fictional seed report; inspect authoritative stored facts. No live model acceptance or financial execution.");
      result.putAll(Map.of("mode", "public-synthetic", "asOf", asOf.toString(), "reportFrom", "2026-09-01", "reportThrough", asOf.toString(), "currency", "USD", "liveAcceptance", "NOT_RUN"));
      result.put("memberIds", Map.of("alice", service.member("alice").id(), "bob", service.member("bob").id()));
      result.put("accountIds", accounts); result.put("propertyIds", properties); result.put("unitIds", units); result.put("rentDueIds", dueIds); result.put("rentalSourceIds", rentalSources);
      result.put("transactionBatchId", transactionBatch); result.put("balanceBatchId", balanceBatch); result.put("expenseIds", expenseIds); result.put("cashPlanId", cashPlan);
      result.put("planId", plan); result.put("taskIds", List.of(task.toString())); result.put("balanceId", balance); result.put("comparisonId", comparison); result.put("rentalReportId", rentalReport); result.put("reportId", report);
      result.put("vendorId", vendor); result.put("workId", work); result.put("draftId", draft); result.put("storeOfferId", storeOffer); result.put("transferOfferId", transferOffer);
      result.put("expected", JSON.convertValue(manifest.path("expected"), Map.class)); result.put("calendar", JSON.convertValue(manifest.path("calendar"), Map.class));
      String receipt = JSON.writeValueAsString(result);
      service.transaction(c -> { CarlService.complete(c, request, plan, "COMPLETE", receipt); return null; });
      return result;
   }

   private static String billCsv(JsonNode manifest)
   {
      var csv = new StringBuilder("source_id,vendor,description,amount,currency,due_date,status,visibility\n");
      for(var bill : manifest.path("bills")) csv.append(bill.path("sourceId").asText()).append(',').append(bill.path("vendor").asText()).append(',').append(bill.path("description").asText()).append(',')
         .append(bill.path("amount").isNull() ? "" : bill.path("amount").asText()).append(',').append(bill.path("currency").asText()).append(',').append(bill.path("due").isNull() ? "" : bill.path("due").asText()).append(",UNPAID,").append(bill.path("visibility").asText()).append('\n');
      return csv.toString();
   }
   private static String read(Path directory, String name) throws Exception { var file = directory.resolve(name); if(!Files.isRegularFile(file) || Files.size(file) > 20_000_000) throw new IllegalArgumentException("Bounded fixture file required"); return Files.readString(file); }
   private static BigDecimal number(JsonNode node, String field) { return new BigDecimal(node.path(field).asText()); }
   private static LocalDate date(JsonNode node, String field) { return LocalDate.parse(node.path(field).asText()); }
   private static UUID id(String name) { return UUID.nameUUIDFromBytes(("carl-public-fictional-v1:" + name).getBytes(StandardCharsets.UTF_8)); }
}
