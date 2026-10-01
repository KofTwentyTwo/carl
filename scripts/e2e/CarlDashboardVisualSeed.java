/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin.bootstrap;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kof22.carlai.domain.BalanceSheets;
import com.kof22.carlai.domain.BudgetRecords;
import com.kof22.carlai.domain.CarlService;
import com.kof22.carlai.domain.DashboardFacts;
import com.kof22.carlai.domain.DebtPlans;
import com.kof22.carlai.domain.FinancialGoals;
import com.kof22.carlai.domain.FinancialPlanning;
import com.kof22.carlai.domain.FinancialRecords;
import com.kof22.carlai.domain.PlanEffects;
import com.kof22.carlai.domain.PlanLifecycle;


/** Optional synthetic dashboard evidence; historical acceptance keeps its original seed unchanged. */
final class CarlDashboardVisualSeed
{
   private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
   private static final LocalDate THROUGH = LocalDate.of(2026, 8, 31);
   private final CarlService service;
   private final FinancialRecords records;
   private final BudgetRecords budget;

   private CarlDashboardVisualSeed(CarlService service)
   {
      this.service = service;
      records = new FinancialRecords(service);
      budget = new BudgetRecords(service);
   }



   static Map<String, Object> seed(CarlService service)
   {
      return new CarlDashboardVisualSeed(service).create();
   }



   private Map<String, Object> create()
   {
      long cash = account("UI Cash Account", "CASH", "USD", true);
      long secondCash = account("UI Second Cash Account", "CASH", "USD", true);
      long card = account("UI Card Account", "CREDIT_CARD", "USD", false);
      long loan = account("UI Loan Account", "LOAN", "USD", false);
      long investment = account("UI Investment Account", "INVESTMENT", "USD", false);
      long euro = account("UI Euro Account", "CASH", "EUR", true);
      long missing = account("UI Missing Valuation", "OTHER_ASSET", "USD", false);
      movement(cash, "1500.25", "INCOME", "UI Salary");
      movement(cash, "25.25", "EXPENSE", "UI Groceries");
      movement(cash, "400.40", "CAPITAL", "UI Capital Proceeds");
      movement(cash, "300.30", "DEBT_PRINCIPAL", "UI Borrowing");
      var cardPair = pair(cash, card, "200.25", "UI Card Payment");
      pair(cash, loan, "100.10", "UI Loan Payment");
      pair(cash, investment, "50.05", "UI Investment Contribution");
      pair(cash, secondCash, "75.25", "UI Internal Cash Transfer");
      movement(cash, "-125.25", "EXPENSE", "UI Groceries");
      movement(cash, "-10.10", "DEBT_INTEREST", "UI Interest");
      movement(cash, "-50.00", "CAPITAL", "UI Capital Purchase");
      movement(cash, "-15.15", "UNCLASSIFIED", "UI Unreviewed");
      movement(card, "-1000.25", "EXPENSE", "UI Card Purchase");
      movement(card, "25.25", "EXPENSE", "UI Card Purchase");
      movement(euro, "10.10", "INCOME", "UI Euro Income");
      long goal = new FinancialGoals(service).create("alice", "UI priority: debt freedom", "DEBT_FREEDOM", 1, "PRIVATE", "Synthetic explicit human priority");
      var debt = new DebtPlans(service);
      LocalDate asOf = LocalDate.of(2026, 9, 1);
      for(long account : List.of(card, loan))
      {
         debt.terms("alice", account, asOf, new BigDecimal(account == card ? "2000.25" : "1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("0.24"), BigDecimal.ZERO, "Synthetic statement terms");
         debt.paymentProfile("alice", account, asOf, asOf.plusDays(14), new BigDecimal("100.00"), new BigDecimal("200.00"), FinancialPlanning.Strategy.AVALANCHE, "Synthetic declared monthly schedule");
      }
      long comparison = debt.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), asOf, "USD", new BigDecimal("500.00"), 12, "Synthetic explicit budget after reserves; no execution");
      var lifecycle = new PlanLifecycle(service);
      long plan = lifecycle.create("alice", UUID.randomUUID(), comparison, "UI Debt Freedom Plan", "Synthetic human selection");
      UUID step = UUID.randomUUID();
      lifecycle.step("alice", plan, 1, step, "UI Review a supplied card transfer", 1, THROUGH, "Synthetic statement review", null, "Synthetic assigned review");
      lifecycle.agree("alice", plan, 2, "Synthetic explicit agreement; no financial execution");
      var effects = new PlanEffects(service);
      long expected = effects.expect(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), plan, 3, step, PlanEffects.Kind.TRANSFER, cash, new BigDecimal("200.25"), FROM, THROUGH, "Synthetic expectation for the selected matched transfer");
      long observed = effects.compare(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), expected, cardPair, "Synthetic explicit selection of both supplied legs");
      lifecycle.checkIn("alice", plan, 3, step, "REPORTED_COMPLETE", "Synthetic human report; no independent verification", null);
      records.importBalances("alice", UUID.randomUUID(), "Date,Balance,Account\n2026-08-31,500.25,UI Cash\n2026-08-31,-2000.25,UI Card\n2026-08-31,100.10,UI Euro\n", Map.of("UI Cash", cash, "UI Card", card, "UI Euro", euro), false);
      var sheets = new BalanceSheets(service);
      var selected = List.of(cash, card, euro, missing);
      long sheet = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), THROUGH, 30, selected, List.of());
      long duplicate = sheets.report(CarlService.Scope.privateFor("alice"), UUID.randomUUID(), THROUGH, 31, selected, List.of());
      var facts = new DashboardFacts(service);
      var flow = facts.cashFlow(CarlService.Scope.privateFor("alice"), FROM, THROUGH, "USD");
      exact(flow, "inflows", "2226.20");
      exact(flow, "outflows", "550.90");
      exact(flow, "netMovement", "1675.30");
      exact(flow, "classifiedSpendingAllAccountKinds", "1125.50");
      exact(flow, "classifiedExpenseRefundsAllAccountKinds", "50.50");
      exact(flow, "classifiedNetSpendingAllAccountKinds", "1075.00");
      if(!Integer.valueOf(2).equals(flow.get("excludedTransferLegs")))
      {
         throw new IllegalStateException("Synthetic fixture must exclude exactly the matched CASH/CASH legs");
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("from", FROM.toString());
      result.put("through", THROUGH.toString());
      result.put("balanceSheet", sheet);
      result.put("duplicateBalanceSheet", duplicate);
      result.put("debtArtifact", comparison);
      result.put("plan", plan);
      result.put("goal", goal);
      result.put("expectation", expected);
      result.put("observation", observed);
      result.put("cashFlow", flow);
      result.put("balanceFacts", facts.balanceSheet(CarlService.Scope.privateFor("alice"), sheet));
      result.put("planFacts", facts.plan(CarlService.Scope.privateFor("alice"), plan));
      return result;
   }



   private long account(String title, String kind, String currency, boolean liquid)
   {
      return records.createAccount("alice", title, kind, currency, liquid, BigDecimal.ONE, "PRIVATE", "Synthetic packaged dashboard source");
   }



   private long movement(long account, String amount, String classification, String category)
   {
      return budget.manualTransaction("alice", UUID.randomUUID(), account, FROM.plusDays(14), new BigDecimal(amount), classification, category, category, "Synthetic supplied ledger classification");
   }



   private List<Long> pair(long outgoing, long incoming, String amount, String category)
   {
      long out = movement(outgoing, "-" + amount, "UNCLASSIFIED", category);
      long in = movement(incoming, amount, "UNCLASSIFIED", category);
      records.pairTransfer("alice", out, in, "Synthetic human matched equal opposite legs");
      return List.of(out, in);
   }



   private static void exact(Map<String, Object> facts, String field, String expected)
   {
      if(new BigDecimal(expected).compareTo(new BigDecimal(facts.get(field).toString())) != 0)
      {
         throw new IllegalStateException("Synthetic dashboard fixture did not reconcile " + field);
      }
   }
}
