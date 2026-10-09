# Carl table actions

Open an application area, then its table. Use the table or record Actions menu. Select rows with the table checkboxes before opening a selected-set action. A single-record process can launch from the table with explicit record selection or from one selected row with its target prefilled. Create/import/readiness actions require no selected rows: clear the selection before launching them. They remain in the table Actions menu, even though they are absent from record-specific menus. Refresh the page after a runtime update so the browser reloads action metadata.

This map reflects current-source contextual actions, including selected-transaction classification, protected documents and governed dated CSV exports. The running package and its actual acceptance checks are recorded in SESSION-STATE.md. It describes supported actions and selection bounds, not successful real-data acceptance of every operation. Required reviewed records, supplied facts, current permissions and stale-source checks still apply.

## Bulk and core operations

Classify Selected Transactions accepts 1–100 explicitly checked Transactions, displays current records and proposed corrections, then requires confirmation. It supports reviewed INCOME, EXPENSE or UNCLASSIFIED treatment; transfer pairing and debt/capital treatment use their separate operations. It authorizes every selected ID, rejects stale previews and commits all corrections together with attribution. Original imported source evidence remains immutable. General filter/select-all submissions are rejected; use explicit row checkboxes. This action does not infer classification rules from imported category names.

Compare Selected Transactions with Plan accepts 1–100 selected Transactions. Review Selected Accounts and Properties accepts 1–200 selected Accounts and Properties for Review. Bill CSV and Monarch imports perform audited batch work without a row selection. Other zero-or-one processes are not bulk mutations.

Carl's protected administrative tables are views over authoritative domain records. Generic insert, update and delete on these views are disabled; use the listed domain operations to preserve validation, permissions and provenance. Protected report/plan/draft copy and download operations recheck access. Core audit and approval history remains read-only. These restrictions are separate from the repaired contextual-action selection metadata.

## Documents and dated CSV exports

Household Evidence Documents provides **Upload and Review Household Document** with no selection and **Download Household Document Original** with zero or one row. Supply the original UTF-8 text or PDF, its source identity and known dates, review the immutable preview, then confirm. Documents remain `SUPPLIED_UNVERIFIED`; historical notes do not automatically become current account, debt or rental facts. Extraction is bounded; scanned or encrypted PDFs may retain their originals without readable extracted text.

The 43 approved business tables provide **Export Records** for zero through 1,000 explicitly checked rows. Choose `SELECTED` to export exactly those rows or `ALL_AUTHORIZED` for the currently permitted table scope. Browser filters are not implicit export criteria. Exports are complete dated CSV snapshots, bounded to 50,000 rows and 20 MB, with scope, source hash and spreadsheet text-safety metadata. Open **Dated CSV Export Downloads**, select the saved export, then use **Download Saved Table CSV**. Access and source freshness are rechecked; an old export is not a current household total. Core operational history, member/security tables and protected binary storage are excluded.

These actions are in the running563-test/V62 package. Actual native imports/downloads for ten private historical originals and selected-two/all23,610 transaction CSVs pass, including independent source-field checks and anonymous denial. Download revocation and committed-mutation receipt regressions pass. Other table exports and domain processes retain their separate prerequisite and acceptance limits.

## Process placement

| Table | Action | Selection | Process identifier |
| --- | --- | --- | --- |
| Household Evidence Documents | Upload and Review Household Document | No selection | `carlRegisterDocument` |
| Household Evidence Documents | Download Household Document Original | Zero or one row | `carlDownloadDocument` |
| Dated CSV Export Downloads | Download Saved Table CSV | Zero or one row | `carlDownloadTableExport` |
| Approved business tables (43) | Export Records | Zero to 1,000 checked rows; explicit scope | `carlExportRecords` plus table suffix |
| Rental Properties | Record Rental Property Facts | No selection | `carlAddRentalProperty` |
| Rental Units | Record Rental Unit | No selection | `carlAddRentalUnit` |
| Vendors | Add Vendor | No selection | `carlAddVendor` |
| Vendor Work | Add Vendor Work Item | No selection | `carlAddWork` |
| Agreed Plans | Agree to Plan | Zero or one row | `carlAgreePlan` |
| Rent Receipt Applications | Apply Evidenced Rent Receipt | No selection | `carlApplyRentReceipt` |
| Rental Allocation Reviews | Apply or Reconcile Reviewed Allocation | Zero or one row | `carlApplyRentalReview` |
| Cash Forecasts | Assess Household Purchase | Zero or one row | `carlAssessPurchase` |
| Budgets | Review Budget Versus Actual | Zero or one row | `carlBudgetVariance` |
| Calendar Connections | Shared Calendar Connection Status | No selection | `carlCalendarSetupStatus` |
| Cash Forecasts | Add Dated Cash Commitment | Zero or one row | `carlCashMovement` |
| Evidenced Expense Payments | Use Imported Expense Payment | No selection | `carlClassifyExpenseActual` |
| Rental Source Classifications | Classify One-Property Rental Transaction | No selection | `carlClassifyRentalSource` |
| Transactions | Classify Reviewed Transaction | Zero or one row | `carlClassifyTransaction` |
| Transactions | Classify Selected Transactions | 1–100 checked rows | `carlClassifyTransactions` |
| Bills | Compare Bill Periods | No selection | `carlCompareBillPeriods` |
| Accounts and Debts | Compare Debt Payoff Plans | No selection | `carlCompareDebt` |
| Financing Offers | Compare Financing Offer Costs | No selection | `carlCompareOffers` |
| Transactions | Compare Selected Transactions with Plan | 1–100 rows | `carlComparePlanEffect` |
| Accounts and Debts | Compare Selected Debt Portfolio | No selection | `carlComparePortfolio` |
| Plan Effect Expectations | Compare Current Principal Statement with Plan | Zero or one row | `carlComparePrincipalEffect` |
| Cash Forecasts | Compare Cash, Card and Store Financing | Zero or one row | `carlComparePurchaseOptions` |
| Accounts and Properties for Review | Review Selected Accounts and Properties | 1–200 rows | `carlConsolidatedBalanceSheet` |
| Reports, Plans and Drafts | Review and Copy Saved Report | Zero or one row | `carlCopyReport` |
| Reports, Plans and Drafts | Review and Copy Vendor Draft | Zero or one row | `carlCopyVendorDraft` |
| Bills | Correct Local Bill | Zero or one row | `carlCorrectBill` |
| Budgets | Correct Category Budget Amount | Zero or one row | `carlCorrectBudget` |
| Expense Schedules | Correct Expense Schedule | Zero or one row | `carlCorrectExpense` |
| Rental Properties | Correct Rental Property Facts | Zero or one row | `carlCorrectRentalProperty` |
| Vendors | Correct Vendor and Contact | Zero or one row | `carlCorrectVendor` |
| Accounts and Debts | Add Financial Account | No selection | `carlCreateAccount` |
| Budgets | Create Category Budget | No selection | `carlCreateBudget` |
| Cash Forecasts | Review Cash Forecast Assumptions | No selection | `carlCreateCashPlan` |
| Expense Schedules | Record Expense Schedule | No selection | `carlCreateExpense` |
| Financial Goals and Priorities | Record Financial Priority | No selection | `carlCreateGoal` |
| Agreed Plans | Propose Plan from Comparison | No selection | `carlCreatePlan` |
| Accounts and Debts | Record Current and Proposed Debt Payments | Zero or one row | `carlDebtPayments` |
| Accounts and Debts | Record Future Debt Rate or Fee | Zero or one row | `carlDebtRateChange` |
| Accounts and Debts | Record Debt Statement Terms | Zero or one row | `carlDebtTerms` |
| Reports, Plans and Drafts | Download Saved Report PDF | Zero or one row | `carlDownloadReportPdf` |
| Reports, Plans and Drafts | Download Saved Report TEXT | Zero or one row | `carlDownloadReportText` |
| Reports, Plans and Drafts | Download Vendor Draft — Not Sent | Zero or one row | `carlDownloadVendorDraft` |
| Reviewed Rental Components | Add or Edit Reviewed Component | No selection | `carlEditRentalReviewComponent` |
| Reviewed Property Shares | Add or Edit Property Share | No selection | `carlEditRentalReviewShare` |
| Reports, Plans and Drafts | Edit Saved Vendor Draft | Zero or one row | `carlEditVendorDraft` |
| Expense Schedules | Prepare Expense Forecast | Zero or one row | `carlExpenseReport` |
| Agreed Plans | Download Plan PDF | Zero or one row | `carlExportPlan` |
| Reports, Plans and Drafts | Generate Focused Report | No selection | `carlFocusedReport` |
| Agreed Plans | Generate Financial Readiness Plan | No selection | `carlGenerateReadinessPlan` |
| Reports, Plans and Drafts | Generate Household Report | No selection | `carlHouseholdReport` |
| Bills | Apply Reviewed Bill Import | No selection | `carlImportBills` |
| Monarch Import History | Import from Monarch | No selection | `carlImportMonarch` |
| Reports, Plans and Drafts | Inspect Report Request | No selection | `carlInspectReportRequest` |
| Financial Goals and Priorities | Record Investment Context | Zero or one row | `carlInvestmentContext` |
| Financial Goals and Priorities | Explore Investment Assumptions | Zero or one row | `carlInvestmentScenario` |
| Vendor Work | Update Vendor Work and Follow-up | Zero or one row | `carlMaintainVendorWork` |
| Bills | Add Bill | No selection | `carlManualBill` |
| Human-entered Transactions | Record Human-entered Transaction | No selection | `carlManualTransaction` |
| Monarch Import History | Map Monarch Account | No selection | `carlMapMonarch` |
| Transactions | Match Internal Transfer | No selection | `carlPairTransfer` |
| Plan Tasks | Record Plan Check-in | Zero or one row | `carlPlanCheckIn` |
| Plan Effect Expectations | Record Agreed Plan Expectation | No selection | `carlPlanExpectation` |
| Plan Tasks | Assign Plan Task | Zero or one row | `carlPlanTask` |
| Bills | Preview Bill Import | No selection | `carlPreviewBills` |
| Rental Allocation Reviews | Preview Rental Allocation Review | Zero or one row | `carlPreviewRentalReview` |
| Reports, Plans and Drafts | Reconcile or Terminate Report Request | No selection | `carlReconcileReportRequest` |
| Evidenced Expense Payments | Record Evidenced Expense Payment | No selection | `carlRecordExpenseActual` |
| Financing Offers | Record Financing Offer Assumptions | No selection | `carlRecordOffer` |
| Scheduled Rent | Record Scheduled Rent | No selection | `carlRecordRentDue` |
| Rental Stress Assumptions | Record Hypothetical Rental Stress | No selection | `carlRecordRentalStress` |
| Conditional Ownership Alternatives | Record Conditional Ownership Alternative | No selection | `carlRecordTaxAlternative` |
| Dated Tax Sources | Record Dated Primary Source | No selection | `carlRecordTaxReference` |
| Evidenced Expense Payments | Review Changed Expense Payment | Zero or one row | `carlRefreshExpenseActual` |
| Monarch Import History | Register Source Accounts for Review | Zero or one row | `carlRegisterMonarchSources` |
| Reviewed Rental Components | Remove Draft Component | Zero or one row | `carlRemoveRentalReviewComponent` |
| Current Rental Reports | Prepare Rental Property Report | No selection | `carlRentalReport` |
| Rental Stress Assumptions | Calculate Conditional Rental Stress | Zero or one row | `carlRentalStressReport` |
| Agreed Plans | Replan with Current Comparison | Zero or one row | `carlReplan` |
| Monarch Import History | Resolve Balance Observation | Zero or one row | `carlResolveMonarchBalance` |
| Monarch Import History | Resume Monarch Review | Zero or one row | `carlResumeMonarch` |
| Accounts and Debts | Review Financial Account | Zero or one row | `carlReviewAccount` |
| Reviewed Financing Allocations | Review Financing Allocation (up to four sources) | No selection | `carlReviewPortfolioMove` |
| Reminder Completion Review | Review Remote Reminder Completion | Zero or one row | `carlReviewReminder` |
| Homes and Properties | Record Home Use and Mortgage Terms | Zero or one row | `carlSaveHomeProfile` |
| Cash Forecasts | Replace Cash Forecast Expense Selection | Zero or one row | `carlSelectCashExpenses` |
| Preferences | Choose My Dashboard Default | No selection | `carlSetDashboardDefault` |
| Preferences | Choose Report Detail | No selection | `carlSetReportDetail` |
| Preferences | Choose Report Period | No selection | `carlSetReportPeriod` |
| Expense Payment Applications | Apply Payment to Expense Due | No selection | `carlSettleExpense` |
| Dated Tax Sources | Prepare Tax and Ownership Discussion | No selection | `carlSourceGroundedTaxPacket` |
| Rental Allocation Reviews | Start Rental Allocation Review | No selection | `carlStartRentalReview` |
| Calendar | Suggest Possible Appointment Windows | No selection | `carlSuggestAppointmentWindows` |
| Reports, Plans and Drafts | Read Carl's Response | No selection | `carlTalkRead` |
| Reports, Plans and Drafts | Continue Talking to Carl | No selection | `carlTalkReply` |
| Reports, Plans and Drafts | Talk to Carl | No selection | `carlTalkStart` |
| Tax Documents | Record Supplied Tax Evidence | No selection | `carlTaxDocument` |
| Tax Documents | Prepare Property Tax Checklist | No selection | `carlTaxPacket` |
| Tax Preparation Properties | Record Tax Preparation Context | Zero or one row | `carlTaxPropertyContext` |
| Dated Tax Sources | Activate or Deactivate Tax Source | Zero or one row | `carlTaxReferenceStatus` |
| Rent Receipt Applications | Correct Rent Receipt Application | Zero or one row | `carlUnapplyRentReceipt` |
| Transactions | Review and Unpair Transfer | Zero or one row | `carlUnpairTransfer` |
| Expense Payment Applications | Correct Expense Payment Application | Zero or one row | `carlUnsettleExpense` |
| Vendor Work | Prepare Vendor Draft | Zero or one row | `carlVendorDraft` |
| Reports, Plans and Drafts | Review Draft Version History | Zero or one row | `carlVendorDraftHistory` |
