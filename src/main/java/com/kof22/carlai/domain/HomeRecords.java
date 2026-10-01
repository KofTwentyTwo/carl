/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/** Supplied home use and mortgage evidence over existing property/account identities, never duplicated balances. */
public final class HomeRecords
{
   /** Missing legacy profiles remain unknown until an authorized human supplies their use. */
   public enum Use
   {
      UNKNOWN, PRIMARY_RESIDENCE, RENTAL, SECOND_HOME, OTHER_REAL_ESTATE
   }



   /** Supplied contract rate behavior, separate from an inferred payment forecast. */
   public enum RateKind
   {
      UNKNOWN, FIXED, VARIABLE
   }



   /** Every optional term remains unknown when absent; payment and escrow are separate supplied figures. */
   public record Mortgage(LocalDate asOf, BigDecimal principal, BigDecimal annualRate, BigDecimal payment,
      LocalDate firstPayment, LocalDate maturity, Integer amortizationMonths, RateKind rateKind,
      BigDecimal escrow, BigDecimal fee, String assumptions)
   {
   }



   /** Currency must match the authoritative property and linked mortgage account. */
   public record ProfileValues(Use use, String currency, Mortgage mortgage, String evidence)
   {
   }
   private final CarlService service;

   /** Shares the consumer's actual permission-filtered records and transaction boundary. */
   public HomeRecords(CarlService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
   }



   /** Writes only current human-reviewed terms, preserving immutable attributed prior profiles. */
   public long save(String principal, UUID request, long property, long expectedPropertyRevision, ProfileValues values)
   {
      validate(values);
      String digest = BillCsv.hash(CarlService.json(Map.of("property", property, "revision", expectedPropertyRevision, "values", values)));
      return service.transaction(c ->
      {
         var initial = CarlService.member(c, principal);
         CarlService.rows(c, "SELECT id FROM carl_household WHERE id=? FOR UPDATE", initial.householdId());
         var actor = CarlService.manager(c, principal, "FINANCE");
         if(actor.householdId() != initial.householdId())
         {
            throw new SecurityException("Home household changed");
         }
         var row = require(c, principal, property);
         Long prior = CarlService.request(c, actor, request, "HOME_PROFILE", digest);
         if(prior != null)
         {
            return prior;
         }
         if(!row.get("currency").equals(values.currency()))
         {
            throw new IllegalArgumentException("Home currency must match the property");
         }
         Mortgage mortgage = values.mortgage();
         if(mortgage != null)
         {
            if(row.get("debt_account_id") == null)
            {
               throw new IllegalArgumentException("Mortgage terms require an explicitly linked loan account");
            }
            var account = CarlService.rows(c, "SELECT kind,currency FROM carl_account_view WHERE principal=? AND id=?", principal, row.get("debt_account_id"));
            if(account.size() != 1 || !account.getFirst().get("kind").equals("LOAN") || !account.getFirst().get("currency").equals(values.currency()))
            {
               throw new IllegalArgumentException("Mortgage terms require the linked same-currency loan");
            }
         }
         var advanced = CarlService.rows(c, "UPDATE carl_record SET revision=revision+1 WHERE id=? AND revision=? RETURNING revision", property, expectedPropertyRevision);
         if(advanced.size() != 1)
         {
            throw new IllegalArgumentException("Property changed; reload its current revision");
         }
         long revision = CarlService.number(advanced.getFirst(), "revision");
         CarlService.execute(c, "INSERT INTO carl_home_profile(property_id,property_use,mortgage_as_of,mortgage_principal,annual_rate,payment_amount,first_payment,maturity_date,amortization_months,rate_kind,escrow_amount,fee_amount,assumptions,profile_evidence,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(property_id) DO UPDATE SET property_use=EXCLUDED.property_use,mortgage_as_of=EXCLUDED.mortgage_as_of,mortgage_principal=EXCLUDED.mortgage_principal,annual_rate=EXCLUDED.annual_rate,payment_amount=EXCLUDED.payment_amount,first_payment=EXCLUDED.first_payment,maturity_date=EXCLUDED.maturity_date,amortization_months=EXCLUDED.amortization_months,rate_kind=EXCLUDED.rate_kind,escrow_amount=EXCLUDED.escrow_amount,fee_amount=EXCLUDED.fee_amount,assumptions=EXCLUDED.assumptions,profile_evidence=EXCLUDED.profile_evidence,updated_by=EXCLUDED.updated_by,updated_at=now()", property, values.use().name(), mortgage == null ? null : mortgage.asOf(),
            mortgage == null ? null : mortgage.principal(), mortgage == null ? null : mortgage.annualRate(), mortgage == null ? null : mortgage.payment(), mortgage == null ? null : mortgage.firstPayment(), mortgage == null ? null : mortgage.maturity(), mortgage == null ? null : mortgage.amortizationMonths(), mortgage == null ? "UNKNOWN" : mortgage.rateKind().name(), mortgage == null ? null : mortgage.escrow(), mortgage == null ? null : mortgage.fee(), mortgage == null ? "Mortgage terms not supplied" : mortgage.assumptions(), values.evidence(), actor.id());
         var current = require(c, principal, property);
         CarlService.execute(c, "INSERT INTO carl_home_history(property_id,property_revision,actor_id,asset_account_id,debt_account_id,snapshot,evidence) VALUES(?,?,?,?,?,?,?)", property, revision, actor.id(), row.get("asset_account_id"), row.get("debt_account_id"), CarlService.json(current), values.evidence());
         CarlService.bump(c, actor.householdId());
         CarlService.complete(c, request, revision, "COMPLETE", "Home profile retained; no title, loan, rent or external action changed");
         return revision;
      });
   }



   /** Rechecks current property and linked account access, including for retained versions. */
   public Map<String, Object> get(String principal, long property)
   {
      return service.transaction(c -> require(c, principal, property));
   }



   /** Returns only profiles common to the explicitly authorized audience. */
   public List<Map<String, Object>> records(CarlService.Scope scope)
   {
      return service.transaction(c ->
      {
         var actor = CarlService.member(c, scope.principal());
         StringBuilder predicate = new StringBuilder("principal=?");
         var parameters = new java.util.ArrayList<Object>(List.of(scope.principal()));
         for(String recipient : scope.audience())
         {
            if(CarlService.member(c, recipient).householdId() != actor.householdId())
            {
               throw new SecurityException("Home audience unavailable");
            }
            predicate.append(" AND EXISTS(SELECT 1 FROM carl_home_view common WHERE common.id=carl_home_view.id AND common.principal=?)");
            parameters.add(recipient);
         }
         return CarlService.rows(c, "SELECT * FROM carl_home_view WHERE " + predicate + " ORDER BY id LIMIT 1000", parameters.toArray());
      });
   }



   /** Historical linked account permissions are rechecked, not inherited from a later replacement link. */
   public List<Map<String, Object>> history(String principal, long property)
   {
      return service.transaction(c ->
      {
         require(c, principal, property);
         return CarlService.rows(c, "SELECT * FROM carl_home_history_view WHERE principal=? AND property_id=? ORDER BY property_revision DESC LIMIT 100", principal, property).reversed();
      });
   }



   private static Map<String, Object> require(Connection c, String principal, long property) throws SQLException
   {
      CarlService.member(c, principal);
      var rows = CarlService.rows(c, "SELECT * FROM carl_home_view WHERE principal=? AND id=?", principal, property);
      if(rows.size() != 1)
      {
         throw new SecurityException("Home unavailable");
      }
      return rows.getFirst();
   }



   private static void validate(ProfileValues values)
   {
      if(values == null || values.use() == null)
      {
         throw new IllegalArgumentException("Explicit property use required");
      }
      Currency currency = Currency.getInstance(values.currency());
      CarlService.bounded(values.evidence(), 4000, "Home evidence");
      var mortgage = values.mortgage();
      if(mortgage == null)
      {
         return;
      }
      if(mortgage.rateKind() == null || mortgage.annualRate() != null && (mortgage.annualRate().signum() < 0 || mortgage.annualRate().compareTo(BigDecimal.TEN) > 0 || mortgage.annualRate().stripTrailingZeros().scale() > 8)
         || mortgage.amortizationMonths() != null && (mortgage.amortizationMonths() < 1 || mortgage.amortizationMonths() > 1200)
         || mortgage.firstPayment() != null && mortgage.maturity() != null && mortgage.firstPayment().isAfter(mortgage.maturity()))
      {
         throw new IllegalArgumentException("Invalid explicit mortgage rate, duration or dates");
      }
      for(BigDecimal amount : new BigDecimal[]{mortgage.principal(), mortgage.payment(), mortgage.escrow(), mortgage.fee()})
      {
         if(amount != null && (amount.signum() < 0 || amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits() || amount.abs().compareTo(new BigDecimal("10000000000000000")) >= 0))
         {
            throw new IllegalArgumentException("Mortgage amount exceeds exact currency precision or bound");
         }
      }
      for(LocalDate date : new LocalDate[]{mortgage.asOf(), mortgage.firstPayment(), mortgage.maturity()})
      {
         if(date != null && (date.getYear() < 1900 || date.getYear() > 2200))
         {
            throw new IllegalArgumentException("Mortgage dates outside supported range");
         }
      }
      CarlService.bounded(mortgage.assumptions(), 4000, "Mortgage assumptions");
   }
}
