/* Copyright (C) 2026 KofTwentyTwo */
'use strict';
const { chromium, expect } = require('@playwright/test');
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const { mkdir, writeFile } = require('node:fs/promises');
const path = require('node:path');

async function main() {
  const [distribution, report, classpath] = process.argv.slice(2);
  await mkdir(report, { recursive: true });
  const fixture = spawn('java', ['-cp', classpath, 'com.kof22.agentadmin.bootstrap.CarlPackagedVisualFixture', distribution, '--dashboards'], { stdio: ['pipe', 'pipe', 'pipe'] });
  const lines = createInterface({ input: fixture.stdout });
  let seed;
  let stderr = '';
  fixture.stderr.on('data', bytes => { stderr = (stderr + bytes.toString()).slice(-6000); });
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Packaged dashboard fixture readiness timeout')), 90000);
    lines.on('line', raw => {
      const line = raw.replace(/\u001b\[[0-9;]*m/g, '');
      if(line.startsWith('PACKAGED_DASHBOARDS=')) seed = JSON.parse(line.slice(20));
      if(line.startsWith('PACKAGED_UI=')) { clearTimeout(timer); resolve(line.slice(12)); }
    });
    fixture.once('exit', code => { clearTimeout(timer); reject(new Error('Packaged fixture exited ' + code + ': ' + stderr)); });
  });
  let browser;
  let page;
  const checks = [];
  const observedDateRequests = [];
  const screenshot = async name => page.screenshot({ path: path.join(report, name + '.png'), fullPage: true });
  const widget = name => page.locator(`[data-qqq-id="widget-content-${name}"]`).last();
  const choose = async (name, source, id) => {
    const input = page.locator(`[data-qqq-id="widget-dropdown-${name}-${source}"]`);
    await input.click();
    await page.locator(`[data-qqq-id="widget-dropdown-option-${name}-${source}-${id}"]`).click();
  };
  const dates = async name => {
    // Interact with the actual native controls. Their wire values must already be ISO dates.
    await page.locator(`[data-qqq-id="widget-dropdown-${name}-from"]`).fill(seed.from);
    await page.locator(`[data-qqq-id="widget-dropdown-${name}-through"]`).fill(seed.through);
    await choose(name, 'carlDashboardCurrency', 'USD');
  };
  try {
    const origin = await ready;
    expect(seed).toBeTruthy();
    await writeFile(path.join(report, 'synthetic-source-facts.json'), JSON.stringify(seed, null, 2) + '\n');
    browser = await chromium.launch({ headless: true });
    for(const locale of ['en-US', 'en-GB']) {
      const context = await browser.newContext({ ignoreHTTPSErrors: true, locale, viewport: { width: 1440, height: 1000 } });
      page = await context.newPage();
      page.setDefaultTimeout(20000);
      page.on('request', request => {
        const url = new URL(request.url());
        if(url.pathname.includes('/widget/carlCashFlow') && url.searchParams.has('carlDashboardCurrency')) observedDateRequests.push({ locale, from: url.searchParams.get('from'), through: url.searchParams.get('through') });
      });
      await page.goto(origin, { waitUntil: 'domcontentloaded' });
      await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
      await expect.poll(() => new URL(page.url()).origin).toBe(origin);
      await expect.poll(() => new URL(page.url()).pathname).toMatch(/^\/app\/carlOverview\/?$/);
      await expect(page.locator('[data-qqq-id="widget-carlCashFlow"]')).toBeVisible();
      await expect(page.locator('[data-qqq-id="widget-carlPlanProgress"]')).toBeVisible();
      await expect(page).toHaveTitle(/Carl AI/);
      const favicon = await page.locator('link[rel="icon"]').last().getAttribute('href');
      expect(favicon).toMatch(/^data:image\/svg\+xml;base64,/);
      expect(Buffer.from(favicon.split(',')[1], 'base64').toString()).toContain("viewBox='0 0 64 64'");
      await screenshot(locale + '-00-verified-overview-landing');
      const moneyGroup = page.locator('[data-qqq-id="sidebar-collapse-carlMoney"]');
      if(!await moneyGroup.isVisible()) await page.locator('[data-qqq-id="sidebar-collapse-carlAI"]').click();
      for(const group of ['carlMoney', 'carlPlanning', 'carlPropertyTax', 'carlCalendarReminders', 'carlVendorWorkspace', 'carlDocumentsData', 'carlSettings']) await expect(page.locator(`[data-qqq-id="sidebar-collapse-${group}"]`)).toBeVisible();
      const accountRoute = page.locator('[data-qqq-id="sidebar-item-carlAccounts"]');
      if(!await accountRoute.isVisible()) await moneyGroup.click();
      await expect(accountRoute).toBeVisible();
      checks.push(locale + '-native-nested-groups-and-stable-account-route');
      await screenshot(locale + '-01-default-home');
      checks.push(locale + '-actual-default-home-title-favicon');
      await page.goto(origin + '/app/carlMoney');
      for(const name of ['carlCashFlow', 'carlIncomeExpense', 'carlBalanceSheet']) {
        await expect(page.locator(`[data-qqq-id="widget-needs-selection-${name}"]`)).toBeVisible();
        await expect(widget(name)).not.toContainText('$1,675.30 USD');
      }
      await screenshot(locale + '-02-required-selection-empty-state');
      await dates('carlCashFlow');
      await expect(widget('carlCashFlow')).toContainText('$2,226.20 USD');
      await expect(widget('carlCashFlow')).toContainText('$550.90 USD');
      await expect(widget('carlCashFlow')).toContainText('$1,675.30 USD');
      await expect(widget('carlCashFlow')).toContainText('$1,125.50 USD');
      await expect(widget('carlCashFlow')).toContainText('$1,075.00 USD');
      await expect(widget('carlCashFlow')).toContainText('principal/interest allocation');
      await expect(widget('carlCashFlow').getByRole('table', { name: 'Exact accessible account movement totals' })).toBeVisible();
      await expect(widget('carlCashFlow').getByRole('table', { name: /Classified spending across all account kinds/ })).toBeVisible();
      await dates('carlIncomeExpense');
      await expect(widget('carlIncomeExpense').locator('svg[role="img"]')).toBeVisible();
      await expect(widget('carlIncomeExpense').locator('svg path')).not.toHaveCount(0);
      await expect(widget('carlIncomeExpense').getByRole('table', { name: /Directional classified movements/ })).toBeVisible();
      await screenshot(locale + '-03-cash-spending-sankey-exact-tables');
      checks.push(locale + '-native-ISO-controls-cash-and-card-spending-accessible-SVG-table');
      await choose('carlCashFlow', 'carlDashboardCurrency', 'EUR');
      await expect(widget('carlCashFlow')).toContainText('€10.10 EUR');
      await expect(widget('carlCashFlow')).not.toContainText('$2,226.20 USD');
      await choose('carlCashFlow', 'carlDashboardCurrency', 'USD');
      await expect(widget('carlCashFlow')).toContainText('$2,226.20 USD');
      const selected = page.locator('[data-qqq-id="widget-dropdown-carlBalanceSheet-carlSavedBalanceSheets"]');
      await selected.click();
      for(const id of [seed.balanceSheet, seed.duplicateBalanceSheet]) await expect(page.locator(`[data-qqq-id="widget-dropdown-option-carlBalanceSheet-carlSavedBalanceSheets-${id}"]`)).toContainText('#' + id);
      await expect(page.locator(`[data-qqq-id="widget-dropdown-option-carlBalanceSheet-carlSavedBalanceSheets-${seed.debtArtifact}"]`)).toHaveCount(0);
      await page.locator(`[data-qqq-id="widget-dropdown-option-carlBalanceSheet-carlSavedBalanceSheets-${seed.duplicateBalanceSheet}"]`).click();
      await expect(widget('carlBalanceSheet')).toContainText('-$1,500.00 USD');
      await expect(widget('carlBalanceSheet')).toContainText('€100.10 EUR');
      await expect(widget('carlBalanceSheet')).toContainText('UI Missing Valuation');
      await expect(widget('carlBalanceSheet')).toContainText('currencies are never combined');
      await screenshot(locale + '-04-distinct-balance-choices-currencies-gaps');
      checks.push(locale + '-distinct-saved-balance-choice-and-explicit-currency-gaps');
      for(const name of ['carlCashFlow', 'carlIncomeExpense', 'carlBalanceSheet']) {
        const cells = widget(name).locator('td').filter({ has: page.locator('span[data-carl-money]') });
        const count = await cells.count();
        expect(count).toBeGreaterThan(0);
        for(let index = 0; index < count; index++) {
          await expect(cells.nth(index)).toHaveCSS('text-align', 'right');
          await expect(cells.nth(index)).toHaveCSS('font-variant-numeric', 'tabular-nums');
        }
      }
      checks.push(locale + '-currency-symbols-grouping-and-computed-monetary-alignment');
      await page.goto(origin + '/app/carlPlanning');
      await choose('carlPlanProgress', 'carlPlans', seed.plan);
      await expect(widget('carlPlanProgress')).toContainText('UI priority: debt freedom');
      await expect(widget('carlPlanProgress')).toContainText('REPORTED_COMPLETE');
      await expect(widget('carlPlanProgress')).toContainText('TRANSFER');
      await expect(widget('carlPlanProgress')).toContainText('$200.25 USD');
      await expect(widget('carlPlanProgress')).toContainText('MATCH');
      await expect(widget('carlPlanProgress')).toContainText('Stale');
      await expect(widget('carlPlanProgress').getByRole('table', { name: /Saved selected observations/ })).toContainText('false');
      await screenshot(locale + '-05-current-tasks-stale-projections-observed-effects');
      checks.push(locale + '-goal-priority-current-plan-and-saved-observation-distinctions');
      await page.setViewportSize({ width: 390, height: 844 });
      await screenshot(locale + '-06-narrow-plan-and-progress');
      await page.goto(origin + '/app/carlMoney');
      await dates('carlCashFlow');
      await dates('carlIncomeExpense');
      await choose('carlBalanceSheet', 'carlSavedBalanceSheets', seed.balanceSheet);
      await expect(widget('carlIncomeExpense').locator('svg[role="img"]')).toBeVisible();
      await expect(widget('carlBalanceSheet')).toContainText('500.25');
      for(const [name, suffix] of [['carlCashFlow', 'cash-flow'], ['carlIncomeExpense', 'income-expense'], ['carlBalanceSheet', 'balance-sheet']]) {
        await widget(name).scrollIntoViewIfNeeded();
        await screenshot(locale + '-07-narrow-' + suffix);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
      await screenshot(locale + '-07-narrow-financial-dashboards');
      checks.push(locale + '-narrow-native-controls-SVG-and-table-scroll');
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto(origin + '/app/carlBalanceSources');
      await page.getByRole('row').filter({ has: page.getByText('UI Cash Account', { exact: true }) }).getByRole('checkbox').check();
      await page.getByRole('button', { name: 'Actions', exact: true }).click();
      await expect(page.getByRole('menuitem', { name: 'Review Selected Accounts and Properties', exact: true })).toBeVisible();
      await screenshot(locale + '-08-native-record-context-action');
      await page.keyboard.press('Escape');
      await page.goto(origin + '/app/carlAccounts');
      await page.getByRole('button', { name: /^Configure columns(?: \(\d+\))?$/ }).click();
      await page.locator('[data-qqq-id="column-config-hide-all"]').click();
      for(const field of ['title', 'balance', 'currency', 'ownership_share']) await page.locator('[data-qqq-id="column-toggle-' + field + '"]').click();
      await page.getByRole('button', { name: 'Close column configuration', exact: true }).click();
      const negativeMoney = page.locator('td[data-qqq-id="grid-cell-balance"]').filter({ hasText: '-$2,000.25 USD' });
      await expect(negativeMoney).toHaveCount(1);
      await expect(negativeMoney).toHaveCSS('text-align', 'right');
      await expect(negativeMoney).toHaveCSS('font-variant-numeric', 'tabular-nums');
      const balanceHeader = page.getByRole('columnheader').filter({ has: page.getByRole('button', { name: /^Balance/ }) });
      await expect(balanceHeader).toHaveCSS('text-align', 'right');
      await expect(page.locator('td[data-qqq-id="grid-cell-ownership_share"]').first()).not.toContainText('$');
      await screenshot(locale + '-09-native-money-grid');
      await negativeMoney.click();
      await expect(page.locator('[data-qqq-id="field-value-balance"]')).toHaveText('-$2,000.25 USD');
      await expect(page.locator('[data-qqq-id="field-value-balance"]')).toHaveCSS('text-align', 'right');
      const detailLayout = await page.locator('[data-qqq-id="record-tab-panel-overview"]').evaluate(panel => {
        const card = [...panel.children].find(child => child.querySelector('[data-qqq-id="field-value-balance"]'));
        const field = card.querySelector('[data-qqq-id="field-value-balance"]');
        const cardBox = card.getBoundingClientRect();
        const fieldBox = field.getBoundingClientRect();
        return { cardWidth: cardBox.width, cardLeft: cardBox.left, cardRight: cardBox.right, fieldLeft: fieldBox.left, fieldRight: fieldBox.right };
      });
      expect(detailLayout.cardWidth).toBeGreaterThanOrEqual(400);
      expect(detailLayout.fieldLeft).toBeGreaterThanOrEqual(detailLayout.cardLeft);
      expect(detailLayout.fieldRight).toBeLessThanOrEqual(detailLayout.cardRight);

      await screenshot(locale + '-10-native-money-detail');
      await page.goto(origin + '/app/carlManualBill');
      const moneyInput = page.locator('input[name="amount"]');
      await moneyInput.fill('8100.00');
      await expect(moneyInput).toHaveValue('8100.00');
      await expect(moneyInput).toHaveCSS('text-align', 'right');
      await screenshot(locale + '-11-exact-money-input');
      checks.push(locale + '-native-grid-header-detail-and-input-money-alignment');
      const choiceResponse = await context.request.post(origin + '/qqq/v1/possibleValues/carlSavedBalanceSheets', { headers: { Origin: origin }, data: { ids: [String(seed.balanceSheet)] } });
      expect(choiceResponse.status()).toBe(200);
      const exact = await choiceResponse.json();
      expect(exact.options).toHaveLength(1);
      expect(String(exact.options[0].id)).toBe(String(seed.balanceSheet));
      checks.push(locale + '-actual-v1-POST-exact-saved-choice');
      await page.locator('[data-qqq-id="sidebar-user-button"]').click();
      await page.getByRole('menuitem', { name: 'Log Out', exact: true }).click();
      await expect(page.locator('[data-qqq-id="login-app-name"]')).toHaveText('Carl AI');
      await expect(page.locator('[data-qqq-id="login-logo"]')).toBeVisible();
      await expect(page).toHaveTitle(/Carl AI/);
      await screenshot(locale + '-09-native-login-branding');
      checks.push(locale + '-public-native-login-branding');
      await context.close();
    }
    expect(observedDateRequests.length).toBeGreaterThanOrEqual(2);
    for(const request of observedDateRequests) {
      expect(request.from).toBe(seed.from);
      expect(request.through).toBe(seed.through);
    }
    const bob = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    page = await bob.newPage();
    await page.goto(origin);
    await page.getByRole('button', { name: 'Sign in as Bob', exact: true }).click();
    await expect.poll(() => new URL(page.url()).pathname).toMatch(/^\/app\/carlOverview\/?$/);
    await page.goto(origin + '/app/carlMoney');
    await dates('carlCashFlow');
    await expect(widget('carlCashFlow')).not.toContainText('$2,226.20 USD');
    await expect(widget('carlCashFlow')).not.toContainText('UI Salary');
    const privateChoices = await bob.request.post(origin + '/qqq/v1/possibleValues/carlSavedBalanceSheets', { headers: { Origin: origin }, data: { ids: [String(seed.balanceSheet)] } });
    expect(privateChoices.status()).toBe(200);
    const privateChoiceBody = await privateChoices.json();
    expect(privateChoiceBody.options ?? []).toHaveLength(0);
    expect(JSON.stringify(privateChoiceBody)).not.toContain('Balance sheet #');
    const privateGuess = await bob.request.post(origin + '/qqq/v1/widget/carlBalanceSheet?carlSavedBalanceSheets=' + seed.balanceSheet, { headers: { Origin: origin }, data: {} });
    expect(privateGuess.status()).toBeGreaterThanOrEqual(400);
    expect(privateGuess.status()).not.toBe(404);
    expect(await privateGuess.text()).not.toContain('500.25');
    await screenshot('10-current-Bob-private-empty-state');
    checks.push('verified-Bob-private-report-guess-and-cash-privacy');
    await bob.close();
    const alice = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 390, height: 844 } });
    page = await alice.newPage();
    await page.goto(origin);
    await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
    await expect.poll(() => new URL(page.url()).pathname).toMatch(/^\/app\/carlOverview\/?$/);
    await page.goto(origin + '/app/carlMoney');
    await dates('carlCashFlow');
    await expect(widget('carlCashFlow')).toContainText('$2,226.20 USD');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Synthetic revocation acknowledgement timeout')), 10000);
      lines.on('line', line => { if(line.includes('PACKAGED_DASHBOARDS_REVOKED')) { clearTimeout(timer); resolve(); } });
      fixture.stdin.write('revoke-dashboard-access\n');
    });
    await page.locator('[data-qqq-id="button-widget-reload-carlCashFlow"]').click();
    await expect(page.locator('[data-qqq-id="widget-error-carlCashFlow"]')).toBeVisible();
    await expect(page.locator('[data-qqq-id="widget-carlCashFlow"]')).not.toContainText('$2,226.20 USD');
    const revoked = await alice.request.post(origin + '/qqq/v1/possibleValues/carlSavedBalanceSheets', { headers: { Origin: origin }, data: { ids: [String(seed.balanceSheet)] } });
    expect(revoked.status()).toBeGreaterThanOrEqual(400);
    expect(revoked.status()).not.toBe(404);
    expect(await revoked.text()).not.toContain('Balance sheet #');
    await screenshot('11-revoked-current-native-widget-and-choices');
    checks.push('current-revocation-removes-rendered-facts-and-choices');
    await alice.close();
    await writeFile(path.join(report, 'report.json'), JSON.stringify({ status: 'PASS', checks, syntheticOnly: true, dateRequests: observedDateRequests }, null, 2) + '\n');
  } catch(error) {
    if(page && !page.isClosed()) { await screenshot('failure'); await writeFile(path.join(report, 'failure.txt'), await page.locator('body').innerText()); }
    await writeFile(path.join(report, 'report.json'), JSON.stringify({ status: 'FAIL', checks, error: String(error), syntheticOnly: true }, null, 2) + '\n');
    throw error;
  } finally {
    if(browser) await browser.close();
    fixture.stdin.write('close\n');
    fixture.stdin.end();
    await new Promise(resolve => { if(fixture.exitCode !== null) return resolve(); fixture.once('exit', resolve); setTimeout(() => { fixture.kill('SIGTERM'); resolve(); }, 10000); });
  }
}
main().catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; });
