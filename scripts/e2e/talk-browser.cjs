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
  const existing = process.env.CARL_TALK_EXISTING_ORIGIN;
  const live = process.env.CARL_TALK_EXPECT_LIVE === 'true';
  if(live && !existing) throw new Error('Live checks require the separately authorized existing synthetic preview');
  let fixture;
  let browser;
  let page;
  let diagnostics = '';
  const checks = [];
  try {
    let origin = existing;
    if(!origin) {
      fixture = spawn('java', ['-cp', classpath, 'com.kof22.agentadmin.bootstrap.CarlPackagedVisualFixture', distribution, '--dashboards'], { stdio: ['pipe', 'pipe', 'pipe'] });
      fixture.stderr.on('data', bytes => { diagnostics = (diagnostics + bytes.toString()).slice(-6000); });
      const lines = createInterface({ input: fixture.stdout });
      origin = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Synthetic fixture readiness timeout')), 90000);
        lines.on('line', raw => {
          const line = raw.replace(/\u001b\[[0-9;]*m/g, '');
          if(line.startsWith('PACKAGED_UI=')) { clearTimeout(timer); resolve(line.slice(12)); }
        });
        fixture.once('exit', code => { clearTimeout(timer); reject(new Error('Fixture exited ' + code + ': ' + diagnostics)); });
      });
    }
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } });
    page = await context.newPage();
    page.setDefaultTimeout(20000);
    await page.goto(origin, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: 'Sign in as Alice', exact: true }).click();
    await expect(page).toHaveTitle(/Carl AI/);
    await page.goto(origin + '/app/carlTalkStart');
    const message = page.getByRole('textbox', { name: /^Message to Carl\*?$/ });
    await expect(message).toBeVisible();
    await expect(page.getByLabel(/^Conversation visibility\*?$/)).toContainText('PRIVATE');
    checks.push('native-private-default-conversation-form');
    await page.screenshot({ path: path.join(report, '01-talk-to-carl.png'), fullPage: true });
    await message.fill('Give me a household brief for September 2026 using only the records I can access. Include bills, exact totals by currency, source references and missing or stale information.');
    await page.getByRole('button', { name: /next|continue|submit/i }).last().click();
    const readLink = page.getByRole('link', { name: 'Read this response', exact: true });
    await expect(readLink).toBeVisible();
    const href = await readLink.getAttribute('href');
    const readUrl = new URL(href, origin);
    const defaults = JSON.parse(readUrl.searchParams.get('defaultProcessValues') || '{}');
    if(!/^\/app\/carlTalkRead\/?$/.test(readUrl.pathname) || Object.keys(defaults).join(',') !== 'selected' || !/^[0-9a-f-]{36}\/[0-9a-f-]{36}$/.test(defaults.selected || '')) throw new Error('Saved response link lacks stable request identity');
    let terminal = '';
    const deadline = Date.now() + 150000;
    do {
      terminal = await page.locator('h3').filter({ hasText: /^(Waiting for Carl|Carl's saved response|Response needs review|Carl is unavailable|Response outcome unknown)$/ }).first().innerText();
      if(terminal !== 'Waiting for Carl') break;
      await page.goto(readUrl.href);
      await expect(page.getByLabel(/^Conversation \/ message to read\*?$/)).toBeVisible();
      await page.getByRole('button', { name: /next|continue|submit/i }).last().click();
      await expect(page.getByRole('link', { name: 'Read this response', exact: true })).toBeVisible();
    } while(Date.now() < deadline);
    expect(terminal).not.toBe('Waiting for Carl');
    const body = await page.locator('body').innerText();
    await writeFile(path.join(report, 'synthetic-response.txt'), body);
    if(live) {
      expect(terminal).toMatch(/^(Carl's saved response|Response needs review)$/);
      await expect(page.getByRole('heading', { name: 'Saved facts and source references', exact: true })).toBeVisible();
      const facts = await page.locator('body').innerText();
      expect(facts).toContain('125.25');
      expect(facts).toContain('USD');
      expect(facts).toMatch(/source|provenance/i);
      expect(facts).toMatch(/missing|incomplete|uncertain|stale|not configured/i);
      checks.push('actual-live-model-protected-domain-facts-saved-request');
    } else {
      expect(terminal).toMatch(/^(Carl is unavailable|Response outcome unknown)$/);
      expect(body).toContain('No completed model answer is available');
      await expect(page.getByRole('link', { name: 'Continue this conversation', exact: true })).toHaveCount(0);
      checks.push('disconnected-model-truthful-failure-no-fabricated-answer');
    }
    await page.screenshot({ path: path.join(report, '02-saved-response.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(report, '03-narrow-saved-response.png'), fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    checks.push('narrow-conversation-no-document-overflow');
    const selected = defaults.selected;
    const ownChoice = await context.request.post(origin + '/qqq/v1/possibleValues/carlTalkMessages', { headers: { Origin: origin }, data: { ids: [selected] } });
    expect(ownChoice.status()).toBe(200);
    expect((await ownChoice.json()).options).toHaveLength(1);
    checks.push('saved-response-selector-mounted-and-authorized');
    const bob = await browser.newContext({ ignoreHTTPSErrors: true });
    const bobPage = await bob.newPage();
    await bobPage.goto(origin);
    await bobPage.getByRole('button', { name: 'Sign in as Bob', exact: true }).click();
    await expect.poll(() => new URL(bobPage.url()).pathname).toMatch(/^\/app\/carlOverview\/?$/);
    const denied = await bob.request.post(origin + '/qqq/v1/possibleValues/carlTalkMessages', { headers: { Origin: origin }, data: { ids: [selected] } });
    expect([200, 400, 403, 404]).toContain(denied.status());
    expect(await denied.text()).not.toContain(selected);
    if(denied.status() === 200) expect((await denied.json()).options ?? []).toHaveLength(0);
    checks.push('verified-other-member-cannot-select-private-response');
    await bob.close();
    await context.close();
    await writeFile(path.join(report, 'report.json'), JSON.stringify({ status: 'PASS', checks, liveModel: live, syntheticOnly: true, terminal }, null, 2) + '\n');
  } catch(error) {
    if(page && !page.isClosed()) {
      await page.screenshot({ path: path.join(report, 'failure.png'), fullPage: true });
      await writeFile(path.join(report, 'failure.txt'), await page.locator('body').innerText());
    }
    await writeFile(path.join(report, 'report.json'), JSON.stringify({ status: 'FAIL', checks, error: String(error), syntheticOnly: true }, null, 2) + '\n');
    throw error;
  } finally {
    if(browser) await browser.close();
    if(fixture && fixture.exitCode === null) {
      fixture.stdin.end('close\n');
      await new Promise(resolve => { fixture.once('exit', resolve); const timer = setTimeout(() => { fixture.kill('SIGTERM'); resolve(); }, 10000); timer.unref(); });
    }
  }
}
main().catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; });
