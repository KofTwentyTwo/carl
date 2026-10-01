/* Copyright (C) 2026 KofTwentyTwo */
'use strict';
const { chromium, expect } = require('@playwright/test');
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const { mkdir, writeFile } = require('node:fs/promises');
const path = require('node:path');

const TERM = 'Synthetic Checking';
async function search(context, origin) {
  const response = await context.request.post(origin + '/qqq/v1/search', {
    headers: { Origin: origin },
    data: { searchTerm: TERM, tableNames: ['carlAccounts'], limitPerTable: 10 },
  });
  expect(response.status()).toBe(200);
  return response.json();
}

async function qualifyQBits(page, context, origin, report, checks) {
  await expect.poll(async () => {
    const response = await context.request.get(origin + '/kof22/qbits', { headers: { Origin: origin } });
    expect(response.status()).toBe(200);
    return response.json();
  }, { timeout: 60000 }).toEqual({ search: 'READY', broker: 'READY' });
  await expect.poll(async () => (await search(context, origin)).results.map(row => row.recordLabel), { timeout: 60000 }).toContain(TERM);
  await page.goto(origin + '/app/carlOverview');
  const requests = [];
  const observe = request => { if(new URL(request.url()).pathname === '/qqq/v1/search') requests.push(request.postDataJSON()); };
  page.on('request', observe);
  try {
    const input = page.locator('[data-qqq-id="input-header-search"]');
    await input.fill(TERM);
    await expect(page.getByRole('option', { name: /Synthetic Checking/ }).first()).toBeVisible();
    expect(requests.some(body => body.searchTerm === TERM && body.tableNames.includes('carlAccounts'))).toBe(true);
    await page.screenshot({ path: path.join(report, 'qbits-01-native-global-search.png'), fullPage: true });
    checks.push('actual-native-global-search-through-QBit-current-private-account');
    await page.keyboard.press('Escape');
    await input.fill('');
  } finally { page.off('request', observe); }
  await page.goto(origin + '/app/esb');
  await expect(page.locator('[data-qqq-id="widget-esbOverview"]')).toBeVisible();
  await expect(page.getByText('carlBroker', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('carlIndexChanges', { exact: true }).first()).toBeVisible();
  await expect(page.locator('[data-qqq-id="esb-provider-carlBroker"]')).toContainText('Connected');
  await expect(page.getByText('carlRefreshSearchIndex', { exact: true })).toHaveCount(0);
  await page.screenshot({ path: path.join(report, 'qbits-02-native-esb-status.png'), fullPage: true });
  const blocked = await context.request.get(origin + '/qqq/v1/esb/messages/carlIndexChanges', { headers: { Origin: origin } });
  expect(blocked.status()).toBe(403);
  checks.push('actual-native-ESB-connected-provider-destination-hidden-service-trigger-and-denied-payload-route');
}

async function qualifyOtherMember(page, context, origin, report, checks) {
  await page.goto(origin);
  await page.getByRole('button', { name: 'Sign in as Bob', exact: true }).click();
  await expect.poll(() => new URL(page.url()).origin).toBe(origin);
  await expect(page.locator('[data-qqq-id="input-header-search"]')).toBeVisible();
  expect((await search(context, origin)).results).toEqual([]);
  const completed = page.waitForResponse(response => new URL(response.url()).pathname === '/qqq/v1/search' && response.request().postDataJSON()?.searchTerm === TERM);
  await page.locator('[data-qqq-id="input-header-search"]').fill(TERM);
  expect((await (await completed).json()).results).toEqual([]);
  await expect(page.locator('[data-qqq-id="search-results"]')).toHaveAttribute('aria-busy', 'false');
  await expect(page.getByRole('option', { name: /Synthetic Checking/ })).toHaveCount(0);
  await page.screenshot({ path: path.join(report, 'qbits-03-other-member-private-record-denied.png'), fullPage: true });
  checks.push('other-verified-member-cannot-search-private-account');
}

async function qualifyRevokedQBits(context, origin, checks) {
  expect((await search(context, origin)).results).toEqual([]);
  checks.push('existing-cookie-search-rechecks-current-finance-access-after-revocation');
}

async function main() {
  const [distribution, report, classpath] = process.argv.slice(2);
  await mkdir(report, { recursive: true });
  const fixture = spawn('java', ['-cp', classpath, 'com.kof22.agentadmin.bootstrap.CarlPackagedVisualFixture', distribution, '--dashboards'], {
    env: { ...process.env, CARL_PREVIEW_QBITS: 'true', CARL_PREVIEW_LIVE_MODEL: 'false', CARL_QBITS_FIXTURE_REPORT_DIRECTORY: report },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const lines = createInterface({ input: fixture.stdout });
  const messages = [];
  let stdout = '';
  let stderr = '';
  let pipeError;
  let mainFailed = false;
  fixture.stdout.on('data', bytes => { stdout += bytes.toString(); });
  fixture.stderr.on('data', bytes => { stderr += bytes.toString(); mainFailed = /Exception in thread "main"/.test(stderr); });
  fixture.stdin.on('error', error => { pipeError = error.message; });
  lines.on('line', raw => messages.push(raw.replace(/\u001b\[[0-9;]*m/g, '')));
  const send = command => {
    if(fixture.exitCode !== null || fixture.stdin.destroyed || pipeError) throw new Error('Fixture is unavailable: ' + (pipeError || fixture.exitCode) + ': ' + stderr.slice(-6000));
    fixture.stdin.write(command + '\n');
  };
  const next = prefix => new Promise((resolve, reject) => {
    if(fixture.exitCode !== null || pipeError || mainFailed) { reject(new Error('Fixture failed before ' + prefix + ': ' + (pipeError || fixture.exitCode) + ': ' + stderr.slice(-6000))); return; }
    const timer = setTimeout(() => { clearInterval(poll); reject(new Error('Fixture response timed out: ' + prefix + ': ' + stderr.slice(-6000))); }, 180000);
    const poll = setInterval(() => {
      const message = messages.find(line => line.startsWith(prefix));
      if(message) { clearTimeout(timer); clearInterval(poll); resolve(message.slice(prefix.length)); }
      else if(fixture.exitCode !== null || pipeError || mainFailed) { clearTimeout(timer); clearInterval(poll); reject(new Error('Fixture failed before ' + prefix + ': ' + (pipeError || fixture.exitCode) + ': ' + stderr.slice(-6000))); }
    }, 50);
    fixture.once('exit', code => { clearTimeout(timer); clearInterval(poll); reject(new Error('Fixture exited ' + code + ': ' + stderr)); });
  });
  let browser;
  let page;
  const checks = [];
  try {
    const origin = await next('PACKAGED_UI=');
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    page = await context.newPage();
    page.setDefaultTimeout(20000);
    await page.goto(origin);
    await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
    await expect(page.locator('[data-qqq-id="input-header-search"]')).toBeVisible();
    await qualifyQBits(page, context, origin, report, checks);
    send('qualify-qbits-event');
    const eventEvidence = JSON.parse(await next('PACKAGED_QBITS_EVENT='));
    expect(eventEvidence).toMatchObject({ status: 'PASS', destination: 'carlIndexChanges', process: 'carlRefreshSearchIndex', consumedEvents: 2, acknowledgedEvents: 2, titleRestored: true, payloadAuthorityIgnored: true });
    expect(eventEvidence.firstEventMillis).toBeLessThan(15000);
    expect(eventEvidence.nativeTelemetry).toMatchObject({ acknowledged: 2, expectedAcknowledged: 2, added: 2, expectedAdded: 2, consumerCount: 1, remaining: 0, deadLetters: 0, labelMatches: true });
    expect((await search(context, origin)).results.map(row => row.recordLabel)).toContain(TERM);
    await writeFile(path.join(report, 'qbits-event-report.json'), JSON.stringify(eventEvidence, null, 2) + '\n');
    checks.push('actual-Artemis-CloudEvent-fixed-native-trigger-consumed-acknowledged-no-dead-letter-and-index-updated-before-periodic-refresh');
    const other = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    await qualifyOtherMember(await other.newPage(), other, origin, report, checks);
    await other.close();
    send('revoke-dashboard-access');
    await next('PACKAGED_DASHBOARDS_REVOKED');
    await qualifyRevokedQBits(context, origin, checks);
    await writeFile(path.join(report, 'qbits-report.json'), JSON.stringify({ status: 'PASS', checks }, null, 2) + '\n');
  } catch(error) {
    if(page) {
      await page.screenshot({ path: path.join(report, 'qbits-failure.png'), fullPage: true });
      await writeFile(path.join(report, 'qbits-failure.txt'), await page.locator('body').innerText());
    }
    await writeFile(path.join(report, 'qbits-report.json'), JSON.stringify({ status: 'FAIL', checks, error: error.message }, null, 2) + '\n');
    throw error;
  } finally {
    if(browser) await browser.close();
    try {
      if(fixture.exitCode === null && mainFailed) fixture.kill('SIGTERM');
      else if(fixture.exitCode === null && !fixture.stdin.destroyed && !fixture.stdin.writableEnded) {
        fixture.stdin.write('close\n');
        fixture.stdin.end();
      }
      if(fixture.exitCode === null) await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { fixture.kill('SIGTERM'); reject(new Error('QBits fixture cleanup timeout: ' + stderr.slice(-6000))); }, 60000);
        fixture.once('exit', code => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error('QBits fixture cleanup exit ' + code + ': ' + stderr.slice(-6000))); });
      });
    } finally {
      lines.close();
      await writeFile(path.join(report, 'fixture-stdout.log'), stdout);
      await writeFile(path.join(report, 'fixture-stderr.log'), stderr);
    }
  }
}
module.exports = { qualifyQBits, qualifyOtherMember, qualifyRevokedQBits };
if(require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
