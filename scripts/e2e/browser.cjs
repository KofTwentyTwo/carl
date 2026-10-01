/*
 * Copyright (C) 2026 KofTwentyTwo
 */
'use strict';
const { chromium, expect } = require('@playwright/test');
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const { mkdir, writeFile, rm } = require('node:fs/promises');
const path = require('node:path');
const { cleanupOwnedFixture } = require('./live-evaluation-assertions.cjs');
async function main() {
  const [distribution, report, classpath] = process.argv.slice(2);
  await mkdir(report, { recursive: true });
  const fixture = spawn('java', ['-cp', classpath, 'com.kof22.agentadmin.bootstrap.CarlPackagedVisualFixture', distribution], { stdio: ['pipe', 'pipe', 'pipe'] });
  const lines = createInterface({ input: fixture.stdout });
  let planSeed;
  let restartResolve;
  lines.on('line', raw => {
    const line = raw.replace(/\u001b\[[0-9;]*m/g, '');
    if (line.startsWith('PACKAGED_PLAN_SEED=')) planSeed = JSON.parse(line.slice('PACKAGED_PLAN_SEED='.length));
    if (line.startsWith('PACKAGED_PROCESS_RESTART=')) restartResolve?.(JSON.parse(line.slice('PACKAGED_PROCESS_RESTART='.length)));
  });
  let stderr = '';
  fixture.stderr.on('data', bytes => { stderr = (stderr + bytes.toString()).slice(-6000); });
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Packaged fixture readiness timeout')), 90000);
    lines.on('line', raw => { const line = raw.replace(/\u001b\[[0-9;]*m/g, ''); if (line.startsWith('PACKAGED_UI=')) { clearTimeout(timer); resolve(line.slice(12)); } });
    fixture.once('exit', code => { clearTimeout(timer); reject(new Error('Packaged fixture exited ' + code + ': ' + stderr)); });
  });
  let browser;
  let page;
  const checks = [];
  let outcome = { status: 'FAIL', checks, syntheticOnly: true };
  try {
    const origin = await ready;
    browser = await chromium.launch({ headless: true });
    let context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    page = await context.newPage();
    page.setDefaultTimeout(20000);
    await page.goto(origin, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
    await expect(page.getByText('Carl AI', { exact: true }).first()).toBeVisible();
    checks.push('packaged-relocated-TLS-OIDC-login');
    await page.screenshot({ path: path.join(report, '01-carl-home.png'), fullPage: true });
    const accounts = page.locator('[data-qqq-id="sidebar-item-carlAccounts"]');
    if(!await accounts.isVisible()) {
      const money = page.locator('[data-qqq-id="sidebar-collapse-carlMoney"]');
      if(!await money.isVisible()) await page.locator('[data-qqq-id="sidebar-collapse-carlAI"]').click();
      if(!await accounts.isVisible()) await money.click();
    }
    await accounts.click();
    await expect(page.getByText('Synthetic Checking', { exact: true }).first()).toBeVisible();
    checks.push('native-authoritative-financial-account');
    await page.screenshot({ path: path.join(report, '02-accounts.png'), fullPage: true });
    await page.goto(origin + '/app/carlImportMonarch');
    await expect(page.locator('input[type=file]').first()).toBeAttached();
    const csv = 'Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed,Id\n2026-09-02,Synthetic grocery,Food,Checking,Synthetic,,-12.30,,,Reviewed,100000000000000002\n';
    await page.locator('input[type=file]').first().setInputFiles({ name: 'Transactions_synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await page.locator('input[type=file]').nth(1).setInputFiles({ name: 'Balances_synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from('Date,Balance,Account\n2026-09-02,1987.70,Checking\n') });
    await page.getByRole('button', { name: /next|continue|submit/i }).last().click();
    await expect(page.getByText(/Transactions: 1 source rows/)).toBeVisible();
    await page.screenshot({ path: path.join(report, '03-import-preview.png'), fullPage: true });
    await page.getByLabel('I reviewed these imports and account mappings').check();
    await page.getByRole('button', { name: /next|continue|submit/i }).last().click();
    await expect(page.getByText(/Transaction batch.*committed/)).toBeVisible();
    checks.push('native-two-file-upload-preview-confirm-apply');
    await page.screenshot({ path: path.join(report, '04-import-complete.png'), fullPage: true });
    await page.setViewportSize({width:390,height:844});
    await page.goto(origin + '/app/carlImportMonarch');
    await page.locator('input[type=file]').first().setInputFiles({name:'Transactions_synthetic.csv',mimeType:'text/csv',buffer:Buffer.from(csv)});
    await page.locator('input[type=file]').nth(1).setInputFiles({name:'Balances_synthetic.csv',mimeType:'text/csv',buffer:Buffer.from('Date,Balance,Account\n2026-09-02,1987.70,Checking\n')});
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Transactions: 1 source rows/)).toBeVisible();
    await page.screenshot({path:path.join(report,'04b-narrow-repeat-import-preview.png'),fullPage:true});
    await page.getByLabel('I reviewed these imports and account mappings').check();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Transaction batch.*committed/)).toBeVisible();
    checks.push('native-narrow-two-file-repeat-import');
    await page.setViewportSize({width:1440,height:1000});
    await page.goto(origin + '/app/carlTransactions');
    await expect(page.getByText('Synthetic grocery', { exact: true }).first()).toBeVisible();
    checks.push('imported-record-visible-in-QQQ');
    await page.getByRole('button', {name: /^Configure columns(?: \(\d+\))?$/}).click();
    await page.locator('[data-qqq-id="column-config-hide-all"]').click();
    for (const field of ['title','amount','currency','effective_date']) await page.locator('[data-qqq-id="column-toggle-' + field + '"]').click();
    const titleHandle=page.getByRole('button', {name: 'Drag to reorder Title', exact: true});
    for(let move=0;move<20;move++) await titleHandle.press('ArrowUp');
    await page.getByRole('button', {name: 'Close column configuration', exact: true}).click();
    checks.push('native-user-column-preference');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(report, '05-narrow-review.png'), fullPage: true });
    await page.getByText('Synthetic grocery', {exact: true}).first().click();
    await expect(page.getByRole('heading', {name:'Details',exact:true})).toBeVisible();
    await page.screenshot({path:path.join(report,'05b-narrow-record-detail.png'),fullPage:true});
    await page.locator('[data-qqq-id="accordion-trigger-section-sources"]').click();
    await expect(page.getByRole('heading', {name:'Source evidence',exact:true})).toBeVisible();
    await page.getByRole('heading', {name:'Source evidence',exact:true}).scrollIntoViewIfNeeded();
    await page.screenshot({path:path.join(report,'05c-narrow-source-evidence.png'),fullPage:true});
    checks.push('native-narrow-record-detail');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(origin + '/app/carlPlans');
    await expect(page.getByText('Synthetic debt plan', { exact: true }).first()).toBeVisible();
    await page.screenshot({ path: path.join(report, '06-plan-records.png'), fullPage: true });
    checks.push('persisted-versioned-plan-visible');
    expect(planSeed && Number.isSafeInteger(planSeed.planId) && /^[0-9a-f-]{36}$/.test(planSeed.taskId)).toBeTruthy();
    const readPlanRecord = async (table, id) => {
      const response = await context.request.get(origin + '/qqq/v1/table/' + table + '/' + encodeURIComponent(id));
      expect(response.status(), await response.text()).toBe(200);
      const values = (await response.json()).record?.values;
      expect(values && String(values.id)).toBe(String(id));
      return values;
    };
    const beforePlan = await readPlanRecord('carlPlans', planSeed.planId);
    const beforeTask = await readPlanRecord('carlPlanSteps', planSeed.taskId);
    const restarted = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { restartResolve = null; reject(new Error('Bounded packaged restart timeout')); }, 60000);
      restartResolve = value => { clearTimeout(timer); restartResolve = null; resolve(value); };
      fixture.stdin.write('restart\n');
    });
    expect(restarted.previousTerminal && restarted.ready).toBe(true);
    expect(Number.isSafeInteger(restarted.previousPid) && restarted.previousPid > 0).toBe(true);
    expect(Number.isSafeInteger(restarted.currentPid) && restarted.currentPid > 0).toBe(true);
    expect(restarted.currentPid).not.toBe(restarted.previousPid);
    await context.close();
    context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    page = await context.newPage();
    page.setDefaultTimeout(20000);
    await page.goto(origin);
    await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
    await expect(page.getByText('Carl AI', { exact: true }).first()).toBeVisible();
    const afterPlan = await readPlanRecord('carlPlans', planSeed.planId);
    const afterTask = await readPlanRecord('carlPlanSteps', planSeed.taskId);
    expect(afterPlan).toEqual(beforePlan);
    expect(afterTask).toEqual(beforeTask);
    await page.goto(origin + '/app/carlPlanSteps/' + planSeed.taskId);
    await expect(page.locator('[data-qqq-id="field-value-status"]')).toBeVisible();
    await page.screenshot({ path: path.join(report, '25-plan-task-after-process-restart.png'), fullPage: true });
    await writeFile(path.join(report, 'plan-process-restart.json'), JSON.stringify({ syntheticOnly: true, process: restarted, records: { planId: planSeed.planId, taskId: planSeed.taskId, beforePlan, afterPlan, beforeTask, afterTask }, limitation: 'SIGTERM process replacement and durable records are verified; no native state-provider shutdown API or live model qualification is implied.' }, null, 2) + '\n');
    checks.push('packaged-process-restart-retains-exact-plan-and-task-records');
    await page.goto(origin + '/app/carlExportPlan');
    const planChoice = page.locator('[data-qqq-id="planId"]');
    await expect(planChoice).toBeVisible();
    await planChoice.click();
    await expect(planChoice).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('option', { name: /Synthetic debt plan/ }).click();
    await page.getByRole('button', { name: /next|continue|submit/i }).last().click();
    const downloadLink = page.locator('[data-qqq-id="link-process-download"]');
    await expect(downloadLink).toBeVisible();
    await page.screenshot({ path: path.join(report, '07-plan-pdf-download.png'), fullPage: true });
    const [download] = await Promise.all([page.waitForEvent('download'), downloadLink.click()]);
    try {
      await download.saveAs(path.join(report, 'synthetic-plan.pdf'));
    } catch (error) {
      const href = await downloadLink.getAttribute('href');
      const direct = await context.request.get(new URL(href, origin).toString(), { headers: { Origin: origin, Referer: page.url() } });
      await writeFile(path.join(report, 'download-diagnostic.json'), JSON.stringify({ path: new URL(href, origin).pathname, status: direct.status(), contentType: direct.headers()['content-type'], contentDisposition: direct.headers()['content-disposition'], bytes: (await direct.body()).length, pdfSignature: (await direct.body()).subarray(0,5).toString() === '%PDF-', error: await download.failure() }, null, 2));
      throw error;
    }
    checks.push('native-permission-checked-plan-pdf-download');

    for (const [route, title, file] of [
      ['carlCashPlans', 'Synthetic September cash forecast', '08-cash-forecast.png'],
      ['carlFinancialGoals', 'Synthetic debt freedom goal', '09-owner-goals.png'],
      ['carlFinancingOffers', 'Synthetic financing assumption', '10-financing-offers.png'],
      ['carlProperties','Synthetic rental house','11-rental-property.png'],
      ['carlRentalUnits','Synthetic unit','12-rental-unit.png'],
      ['carlExpenses','Synthetic power schedule','14-expense-schedule.png']]) {
      await page.goto(origin + '/app/' + route);
      await expect(page.getByText(title, { exact: true }).first()).toBeVisible();
      await page.screenshot({ path: path.join(report, file), fullPage: true });
      checks.push('native-scoped-' + route);
    }
    await page.goto(origin + '/app/carlTaxPacket');
    await page.getByLabel('Property', {exact:false}).click();
    await page.getByRole('option',{name:/Synthetic rental house/}).click();
    await page.locator('input[name="taxYear"]').fill('2026');
    await page.locator('input[name="asOf"]').fill('2026-09-30');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/No tax liability, filing or entity choice has been calculated/)).toBeVisible();
    await page.screenshot({path:path.join(report,'13-tax-preparation-checklist.png'),fullPage:true});
    checks.push('native-tax-evidence-checklist');
    await page.goto(origin + '/app/carlSyncAgenda');
    await page.getByLabel('Collection', {exact:false}).click();
    await page.getByRole('option', {name:'events', exact:true}).click();
    await page.locator('input[name="from"]').fill('2026-09-30');
    await page.locator('input[name="through"]').fill('2026-09-30');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Connection: CURRENT/)).toBeVisible();
    await page.screenshot({path:path.join(report,'15-calendar-refresh.png'),fullPage:true});
    checks.push('native-bounded-calendar-refresh-over-controlled-HTTPS');
    await page.goto(origin + '/app/carlCalendar');
    await expect(page.getByText('Synthetic household review', {exact:true}).first()).toBeVisible();
    await page.getByRole('button', {name: /^Configure columns(?: \(\d+\))?$/}).click();
    await page.locator('[data-qqq-id="column-config-hide-all"]').click();
    for (const field of ['title','start_at','end_at','sync_state']) await page.locator('[data-qqq-id="column-toggle-' + field + '"]').click();
    const agendaTitle = page.getByRole('button', {name:'Drag to reorder Title', exact:true});
    for(let move=0;move<20;move++) await agendaTitle.press('ArrowUp');
    await page.getByRole('button', {name:'Close column configuration', exact:true}).click();
    await page.screenshot({path:path.join(report,'16-calendar-agenda.png'),fullPage:true});
    checks.push('native-authoritative-calendar-agenda');
    await page.goto(origin + '/app/carlSuggestAppointmentWindows');
    await page.locator('input[name="date"]').fill('2026-09-30');
    await page.locator('input[name="startTime"]').fill('09:00');
    await page.locator('input[name="endTime"]').fill('17:00');
    await page.locator('input[name="minimumMinutes"]').fill('60');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Suggestion — not scheduled/)).toBeVisible();
    await expect(page.getByText(/America\/Chicago/).last()).toBeVisible();
    await page.screenshot({path:path.join(report,'23-appointment-window-suggestions.png'),fullPage:true});
    checks.push('native-scoped-appointment-window-suggestions');
    await page.goto(origin + '/app/carlComparePortfolio');
    await page.locator('input[name="asOf"]').fill('2026-09-01');
    await page.locator('input[name="currency"]').fill('USD');
    await page.locator('input[name="monthlyBudget"]').fill('100.00');
    await page.locator('input[name="horizonMonths"]').fill('24');
    await page.getByLabel('Rollover', {exact:false}).click();
    await page.getByRole('option', {name:'AVALANCHE', exact:true}).click();
    await page.locator('[name="budgetEvidence"]').fill('Synthetic assumed budget; available cash not qualified');
    await page.getByLabel('Account 1', {exact:false}).click();
    await page.getByRole('option', {name:/Synthetic Card/}).click();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Budget and affordability are not yet qualified/)).toBeVisible();
    await page.screenshot({path:path.join(report,'17-portfolio-comparison.png'),fullPage:true});
    checks.push('native-selected-portfolio-comparison');
    await page.goto(origin + '/app/carlComparePurchaseOptions');
    await page.getByLabel('Cash Plan', {exact:false}).click();
    await page.getByRole('option', {name:/Synthetic purchase alternatives forecast/}).click();
    await page.locator('input[name="purchaseDate"]').fill('2026-09-30');
    await page.locator('input[name="allInPrice"]').fill('400.00');
    await page.locator('input[name="purpose"]').fill('Kitchen table and chairs');
    await page.getByLabel('All In Costs Known', {exact:false}).check();
    await page.getByLabel('Optional purchase financing offer 1', {exact:false}).click();
    await page.getByRole('option', {name:/Synthetic zero-interest furniture terms/}).click();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByRole('heading',{name:'Purchase payment choices',exact:true})).toBeVisible();
    await expect(page.getByText(/Conditionally prefer CASH/)).toBeVisible();
    await expect(page.getByText(/Maximum supported cash budget: 500.00/)).toBeVisible();
    await page.screenshot({path:path.join(report,'18-purchase-payment-options.png'),fullPage:true});
    checks.push('native-cash-versus-store-finance-purchase-guidance');
    await page.goto(origin + '/app/carlManualTransaction');
    await page.locator('[data-qqq-id=\"account\"]').click();
    await page.getByRole('option', {name:/Synthetic Checking/}).click();
    await page.locator('input[name="date"]').fill('2026-09-29');
    await page.locator('input[name="amount"]').fill('-25.00');
    await page.locator('[data-qqq-id=\"classification\"]').click();
    await page.getByRole('option', {name:'EXPENSE',exact:true}).click();
    await page.locator('[name="category"]').fill('Groceries');
    await page.locator('[name="title"]').fill('Synthetic manually recorded groceries');
    await page.locator('[name="evidence"]').fill('Synthetic receipt reviewed by household member');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/saved with original evidence; no external transaction occurred/)).toBeVisible();
    checks.push('native-human-transaction-with-source-evidence');
    await page.goto(origin + '/app/carlBudgetVariance');
    await page.locator('[data-qqq-id=\"budget\"]').click();
    await page.getByRole('option',{name:/Synthetic grocery budget/}).click();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Actual expenses less refunds: 25.00/)).toBeVisible();
    await page.screenshot({path:path.join(report,'19-budget-actuals.png'),fullPage:true});
    checks.push('native-category-budget-actuals');
    await page.goto(origin + '/app/carlDownloadVendorDraft');
    await page.locator('[data-qqq-id=\"draftId\"]').click();
    await page.getByRole('option',{name:/VENDOR DRAFT/}).click();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(downloadLink).toBeVisible();
    const [draftDownload] = await Promise.all([page.waitForEvent('download'),downloadLink.click()]);
    await draftDownload.saveAs(path.join(report,'synthetic-vendor-draft.txt'));
    await page.screenshot({path:path.join(report,'20-vendor-draft-download.png'),fullPage:true});
    checks.push('native-protected-vendor-draft-download');
    for(const action of ['PUBLISH','SYNCHRONIZE']) {
      await page.goto(origin + '/app/carlPublishCalendar');
      await page.locator('[data-qqq-id="planId"]').click();
      await page.getByRole('option',{name:/Synthetic debt plan/}).click();
      await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
      await page.locator('[data-qqq-id=\"collection\"]').click();
      await page.getByRole('option',{name:'reminders',exact:true}).click();
      await page.locator('[data-qqq-id=\"operation\"]').click();
      await page.getByRole('option',{name:action,exact:true}).click();
      await page.locator('[data-qqq-id="stepId"]').click();
      await page.getByRole('option',{name:/Review statement/}).click();
      await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
      await expect(page.getByText(action==='PUBLISH' ? /state=COMPLETE/ : /reminderObservation=/)).toBeVisible();
    }
    await page.goto(origin + '/app/carlReviewReminder');
    await page.locator('[data-qqq-id=\"observation\"]').click();
    await page.getByRole('option',{name:/Synthetic debt plan/}).click();
    await page.locator('[name="expectedVersion"]').fill('3');
    await page.locator('[data-qqq-id=\"decision\"]').click();
    await page.getByRole('option',{name:'ACCEPT REPORTED COMPLETE',exact:true}).click();
    await page.locator('[name="note"]').fill('Human reviewed shared reminder; no payment was verified');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Reported completion is not verified financial execution/)).toBeVisible();
    await page.screenshot({path:path.join(report,'21-reminder-human-review.png'),fullPage:true});
    checks.push('native-shared-reminder-sync-and-human-reported-completion');
    await page.goto(origin + '/app/carlSetReportDetail');
    await page.locator('[data-qqq-id=\"scope\"]').click();
    await page.getByRole('option',{name:'MEMBER',exact:true}).click();
    await page.locator('[data-qqq-id=\"value\"]').click();
    await page.getByRole('option',{name:'BRIEF',exact:true}).click();
    await page.locator('[name="evidence"]').fill('Explicit synthetic member presentation choice');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/It does not schedule delivery or change permissions/)).toBeVisible();
    await page.screenshot({path:path.join(report,'22-explicit-member-preference.png'),fullPage:true});
    checks.push('native-explicit-private-presentation-preference');
    await page.goto(origin + '/app/carlStartRentalReview');
    await page.locator('[data-qqq-id="transaction"]').click();
    await page.getByRole('option',{name:/Synthetic manually recorded groceries/}).click();
    await page.locator('input[name="title"]').fill('Synthetic shared rental allocation review');
    await page.locator('[data-qqq-id="visibility"]').click();
    await page.getByRole('option',{name:'PRIVATE',exact:true}).click();
    await page.locator('[name="evidence"]').fill('Synthetic reviewed operating allocation');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Add components and property shares/)).toBeVisible();
    await page.goto(origin + '/app/carlEditRentalReviewComponent');
    await page.locator('[data-qqq-id="review"]').click();
    await page.getByRole('option',{name:/Synthetic shared rental allocation review/}).click();
    await page.locator('input[name="expectedVersion"]').fill('1');
    await page.locator('input[name="component"]').fill('operating');
    await page.locator('[data-qqq-id="kind"]').click();
    await page.getByRole('option',{name:'OPERATING EXPENSE',exact:true}).click();
    await page.locator('input[name="amount"]').fill('25.00');
    await page.locator('input[name="outsideFraction"]').fill('0');
    await page.locator('[name="evidence"]').fill('Synthetic operating expense');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/review version 2/)).toBeVisible();
    for(const [version,property] of [[2,'Synthetic rental house'],[3,'Synthetic second rental house']]) {
      await page.goto(origin + '/app/carlEditRentalReviewShare');
      await page.locator('[data-qqq-id="review"]').click();
      await page.getByRole('option',{name:/Synthetic shared rental allocation review/}).click();
      await page.locator('input[name="expectedVersion"]').fill(String(version));
      await page.locator('[data-qqq-id="component"]').click();
      await page.getByRole('option',{name:/Synthetic shared rental allocation review.*operating/}).click();
      await page.locator('[data-qqq-id="property"]').click();
      await page.getByRole('option',{name:property,exact:true}).click();
      await page.locator('input[name="fraction"]').fill('0.5');
      await page.locator('[name="evidence"]').fill('Synthetic explicit half share');
      await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
      await expect(page.getByText(new RegExp('review version '+(version+1)))).toBeVisible();
    }
    await page.goto(origin + '/app/carlPreviewRentalReview');
    await page.locator('[data-qqq-id="review"]').click();
    await page.getByRole('option',{name:/Synthetic shared rental allocation review/}).click();
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Ready for explicit application/)).toBeVisible();
    await page.screenshot({path:path.join(report,'24-multi-property-allocation-review.png'),fullPage:true});
    await page.goto(origin + '/app/carlApplyRentalReview');
    await page.locator('[data-qqq-id="review"]').click();
    await page.getByRole('option',{name:/Synthetic shared rental allocation review/}).click();
    await page.locator('input[name="expectedVersion"]').fill('4');
    await page.locator('[name="evidence"]').fill('Human confirms the reviewed allocation');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Confirmed classification/)).toBeVisible();
    checks.push('native-saved-multi-property-allocation-preview-apply');
    await page.goto(origin + '/app/carlFocusedReport');
    await page.locator('[data-qqq-id="focus"]').click();
    await page.getByRole('option',{name:'BILLS',exact:true}).click();
    await page.locator('input[name="from"]').fill('2026-09-01');
    await page.locator('input[name="through"]').fill('2026-09-30');
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Incomplete focused report/)).toBeVisible();
    await page.screenshot({path:path.join(report,'25-focused-report.png'),fullPage:true});
    checks.push('native-focused-report-with-source-coverage');
    for(const [route,file] of [['carlDownloadReportPdf','synthetic-household-report.pdf'],['carlDownloadReportText','synthetic-household-report.txt']]) {
      await page.goto(origin + '/app/' + route);
      await page.locator('[data-qqq-id="reportId"]').click();
      await page.getByRole('option',{name:'BILL REPORT',exact:true}).first().click();
      await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
      await expect(downloadLink).toBeVisible();
      const [saved] = await Promise.all([page.waitForEvent('download'),downloadLink.click()]);
      await saved.saveAs(path.join(report,file));
    }
    await page.screenshot({path:path.join(report,'26-protected-report-download.png'),fullPage:true});
    checks.push('native-protected-report-pdf-and-text-downloads');
    await page.goto(origin + '/app/carlBalanceSources');
    for(const title of ['Synthetic Checking','Synthetic rental house']) {
      await page.getByRole('row').filter({has:page.getByText(title,{exact:true})}).getByRole('checkbox').check();
    }
    await page.getByRole('button',{name:'Actions',exact:true}).click();
    await page.getByRole('menuitem',{name:'Review Selected Accounts and Properties',exact:true}).click();
    await expect(page.getByText('Synthetic Checking',{exact:true}).first()).toBeVisible();
    await expect(page.getByText('Synthetic rental house',{exact:true}).first()).toBeVisible();
    await page.locator('input[name="asOf"]').fill('2026-09-30');
    await page.locator('input[name="maximumAgeDays"]').fill('30');
    await page.locator('[data-qqq-id="propertyPolicy"]').click();
    await page.getByRole('option',{name:"Use each property's supplied valuation",exact:true}).click();
    await page.screenshot({path:path.join(report,'27-selected-balance-input.png'),fullPage:true});
    await page.getByRole('button',{name:/next|continue|submit/i}).last().click();
    await expect(page.getByText(/Saved selected balance sheet/)).toBeVisible();
    await page.screenshot({path:path.join(report,'28-selected-balance-result.png'),fullPage:true});
    checks.push('native-selected-account-property-balance-review');
    const oldSession = (await context.cookies()).find(cookie => cookie.name === 'sessionUUID');
    expect(oldSession && oldSession.httpOnly && oldSession.secure && oldSession.sameSite === 'Strict').toBeTruthy();
    await page.locator('[data-qqq-id="sidebar-user-button"]').click();
    await page.getByRole('menuitem',{name:'Log Out',exact:true}).click();
    await expect.poll(async () => (await context.request.get(origin + '/metaData')).status()).toBe(401);
    expect((await context.cookies()).some(cookie => cookie.name === 'sessionUUID')).toBe(false);
    const replay = await browser.newContext({ignoreHTTPSErrors:true});
    try {
      await replay.addCookies([oldSession]);
      expect((await replay.request.get(origin + '/metaData')).status()).toBe(401);
    } finally { await replay.close(); }
    checks.push('native-logout-and-revoked-session-replay');
    await Promise.all(['failure.png','failure.txt','download-diagnostic.json'].map(file => rm(path.join(report,file), {force:true})));
    outcome = { status: 'PASS', checks, syntheticOnly: true };
  } catch (error) {
    outcome = { status: 'FAIL', checks, syntheticOnly: true, error: error.message };
    try {
      if (page && !page.isClosed()) { await page.screenshot({ path: path.join(report, "failure.png"), fullPage: true }); await writeFile(path.join(report, "failure.txt"), await page.locator("body").innerText()); }
    } catch (diagnosticError) { outcome.diagnosticError = diagnosticError.message; }
    throw error;
  } finally {
    if (browser) {
      try { await browser.close(); }
      catch (error) { outcome.status = 'FAIL'; outcome.browserCleanupError = String(error); }
    }
    outcome.cleanup = await cleanupOwnedFixture(fixture);
    if (!outcome.cleanup.terminal || outcome.cleanup.errors.length || outcome.cleanup.disposition !== 'GRACEFUL') outcome.status = 'FAIL';
    if (outcome.status !== 'PASS') process.exitCode = 1;
    await writeFile(path.join(report, 'report.json'), JSON.stringify(outcome, null, 2) + '\n');
  }
}
main().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
