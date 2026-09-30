# Acceptance evidence

Carl0.1.0-SNAPSHOT has passed the final **299-test** integrated gate with no failures/errors/skips, Java21, copyright/Spotless/Checkstyle/ErrorProne/convergence and unchanged80%line/60%branch floors. Exact foundation0.4.0 is pinned. The packaged browser passed **31 checks**, including narrow repeated two-file upload, protected PDF/text export, supported native source selection and logout/revoked-session replay. PDFs and main review screenshots were visually inspected. Dependency/container scans each inventoried240 Java packages with no HIGH/CRITICAL findings; source secret scan found none. See [sealed local evidence](evidence/local-qualification.json).

**Local controlled build/package qualification: PASS. Hosted release qualification: BLOCKED** until authorized private-package access and successful hosted checks. **Integration qualified: BLOCKED** pending real Synology/model/identity and data-policy acceptance. **Deployed: NOT APPLICABLE — deployment not requested.** This does not label the whole live product complete. Foundation0.4.0/source`bb25a2a1f3ca562b054400a321773852ae839f39` is independently remotely published. Carl tested recovered exact release bytes matching all nine signed publication hashes; it has not proven a fresh credentialed consumer Maven fetch or hosted package access.

## Baseline scenario evidence

PASS below means the stated controlled synthetic application behavior passed. It never means live model/provider acceptance, complete family data, or universal model correctness.

| Scenario | Status | Actual evidence and remaining limits |
| --- | --- | --- |
| AT-01 | PASS | `CarlServiceTest.at01at02ImportExactCurrenciesAndDuplicateIdentity` verifies exactUSD200 and separateEUR against PostgreSQL. |
| AT-02 | PASS | Same test plus `BillCsvTest.invalidRowsAreExplicitAndBatchCannotCommit`, source identity/retry tests. |
| AT-03 | PASS | `at03at09at17MissingBillsAndUntrustedTextRemainFactsAfterAttributedCorrection`: null amount/date stays missing; paid assertion requires evidence and remains unverified. |
| AT-04 | PASS | `at04at13SavedReportRechecksSourceAccessAndRevocation`, typed account/calendar restrictions, family/native HTTP and protected export negatives. |
| AT-05 | PASS | `AgendaDecoderTest` fixed recurrence/exceptions/cancellation/all-day/DST and private occurrence fixtures; PostgreSQL agenda ingestion. |
| AT-06 | PASS | `CalendarAgendaServiceTest.expiredReadRetainsRecordsLastSuccessAndTruthfulCoverage`, bounded controlledHTTP failures. LiveSynology separately blocked. |
| AT-07 | PASS | `VendorRecordsTest` immutable grounded drafts/versions/current-source export; `CarlVendorNativeTest` actual authenticated download. |
| AT-08 | PASS | `CarlReadToolsTest` closed read catalog; `AgentApplicationTest` governed SDK tool loop; provider types expose no financial/vendor writes. Calendar authority is the explicit later exception. |
| AT-09 | BLOCKED | Imported instructions remain inert source text and typed authorization denies caller spoofing; representative live-model adversarial evaluation is still unqualified. |
| AT-10 | PASS | `FocusedReportsTest.calendarFailureAndVendorReportsPersistWithoutClaimingCompleteness`, household coverage/freshness and missing-data facts. |
| AT-11 | PASS | `CarlServiceTest.at11NarrationFailurePreservesFacts` and focused report failure fixtures preserve deterministic facts with failed narration. |
| AT-12 | PASS | `ReportRecoveryTest.interruptedWorkerIsFencedWithoutRepeatingNarration`, committed-artifact recovery, durable request retries and conservativeUNKNOWN; no blind replay. |
| AT-13 | PASS | Source/member/field/linked-account denial across stored artifacts, family get and native/PDF/vendor exports. |
| AT-14 | PASS | `workflowDeadlineCancelsSlowDatabaseReadAndLeavesServiceResponsive`, bounded workflow queue and controlled provider/recurrence/input limits. Provider timeout adds its own bound; not an exact30s wall-clock guarantee. |
| AT-15 | PASS |31 final packaged browser checks, relocated TLS/OIDC, restricted reader, native records/processes, protected downloads and logout/revoked replay pass on exact stable foundation bytes; PDFs/screenshots visually inspected. |
| AT-16 | PASS | Actual application/read-tool fixtures run withSlack disabled, bounded read catalogs and no prohibited write capability. |
| AT-17 | PASS | `at03at09at17MissingBillsAndUntrustedTextRemainFactsAfterAttributedCorrection`: original evidence retained, attributed correction and matching generated facts. |
| AT-18 | NOT APPLICABLE — deployment not requested | Production backup/restore qualification requires approved operating scope. |
| AT-19 | BLOCKED | `AgentApplicationTest.actualApplicationPersistsConversationsAndServesAuthenticatedAdministration` and `CarlFamilyHttpTest.familyConversationRequestsPersistSourceGroundedReportsAndDraftsWithCurrentAccess` prove controlled SDK/family/native continuity; live chosen entry point/model remains unqualified. |

## Expanded scope and evidence

`PurchaseAssessmentsTest`, `PortfolioPlansTest`, `ExpenseRecordsTest`, `RentalRecordsTest`, `RentalAllocationReviewsTest`, `BudgetPreferencesTest`, `TaxPlanningRecordsTest`, `BalanceSheetsTest`, `ReminderObservationsTest`, `CalendarAvailabilityTest` and `ReportRecoveryTest` contain real PostgreSQL requirement fixtures, including shared/private/revoked boundaries. Their pure calculators additionally assert independently fixed cents, dates and shortfalls. No calculator-only test substitutes for a domain workflow.

The complete individual [numbered requirement map](REQUIREMENT-EVIDENCE.md) records implementation, final-gate and live prerequisites separately. All scoped implementation handoffs and local exact-source gates are complete; remaining blockers are explicitly identified live/hosted prerequisites. Tax rules remain undetermined without tested applicable rules; supplied government metadata is not archival custody or professional approval. All reports label selected/currently accessible scope and missing coverage.

Source and artifact/image scans pass for final application bytes. Release policy tests pass seven cases using an isolated synthetic GPG key. Repository environments/tag immutability are installed; hosted development/RC/main delivery remains blocked until package access and required checks qualify. See [CI/CD](CI-CD.md), [session state](SESSION-STATE.md) and [remaining work](TODO.md).
