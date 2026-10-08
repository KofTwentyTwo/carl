/* Copyright (C) 2026 KofTwentyTwo */
'use strict';
const { chromium, expect } = require('@playwright/test');
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const { mkdir, writeFile, readFile, readdir } = require('node:fs/promises');
const { createHash } = require('node:crypto');
const path = require('node:path');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SELECTED = /^[0-9a-f-]{36}\/[0-9a-f-]{36}$/;
const CHAT = 'Chat with Carl';

function walk(nodes, ancestors = [], result = []) {
  for(const node of nodes) {
    result.push({ node, ancestors });
    walk(node.children || [], [...ancestors, node.name], result);
  }
  return result;
}

async function login(page, origin, who) {
  await page.goto(origin, { waitUntil: 'domcontentloaded' });
  const signIn = page.getByRole('button', { name: 'Sign in', exact: true });
  const member = page.getByRole('button', { name: 'Sign in as ' + who, exact: true });
  await expect(signIn.or(member)).toBeVisible();
  if(await signIn.isVisible()) await signIn.click();
  await member.click();
  await expect.poll(() => new URL(page.url()).pathname).toMatch(/^\/app\/carlOverview\/?$/);
  await expect(page.locator('[data-qqq-id="application-name"]')).toHaveText('Carl AI');
  await expect(page).toHaveTitle(/Carl AI/);
}

async function sidebar(page) {
  if(page.viewportSize().width < 768 && !await page.locator('[data-qqq-id="sidebar-mobile-drawer"]').isVisible()) {
    await page.getByRole('button', { name: 'Open navigation menu', exact: true }).click();
  }
  return page.locator('[data-qqq-id="sidebar"]:visible');
}

async function expand(page, name) {
  const toggle = page.locator(`[data-qqq-id="sidebar-toggle-${name}"]:visible`);
  if(await toggle.count() && await toggle.getAttribute('aria-expanded') !== 'true') await toggle.click();
}

async function navigate(page, name) {
  await sidebar(page);
  await expand(page, 'carlAI');
  const route = page.locator(`[data-qqq-id="sidebar-collapse-${name}"]:visible`);
  await expect(route).toBeVisible();
  await route.click();
  await expect.poll(() => new URL(page.url()).pathname).toMatch(new RegExp('^/app/' + name + '/?$'));
}

async function openChat(page) {
  const panel = page.getByRole('region', { name: CHAT, exact: true });
  if(!await panel.isVisible()) await page.getByRole('button', { name: CHAT, exact: true }).click();
  await expect(panel).toBeVisible();
  return panel;
}

function observe(page, origin) {
  const traffic = { start: [], reply: [], reads: 0, statuses: [], pageErrors: [], contractErrors: [] };
  page.on('pageerror', error => traffic.pageErrors.push(error.message.slice(0, 300)));
  page.on('request', request => {
    const url = new URL(request.url());
    if(url.origin !== origin) return;
    if(request.method() === 'POST' && ['/kof22/chat/start', '/kof22/chat/reply'].includes(url.pathname)) {
      let body;
      try { body = request.postDataJSON(); } catch { traffic.contractErrors.push('Chat POST is not valid JSON'); return; }
      if(!body || Object.hasOwn(body, 'principal') || !UUID.test(body.requestId || '')) { traffic.contractErrors.push('Chat POST violates request identity contract'); return; }
      const entry = { requestId: body.requestId, shared: body.shared, participants: body.participants, selected: body.selected };
      traffic[url.pathname.endsWith('/start') ? 'start' : 'reply'].push(entry);
    }
    if(request.method() === 'GET' && url.pathname === '/kof22/chat/response') traffic.reads++;
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if(url.origin === origin && ['/kof22/chat/start', '/kof22/chat/reply', '/kof22/chat/response'].includes(url.pathname) && response.status() === 200) {
      // Retain status only; never dump transcript bodies, cookies or authentication headers.
      response.json().then(body => traffic.statuses.push(body.status)).catch(() => {});
    }
  });
  return traffic;
}

async function headers(context, origin, selected) {
  const response = await context.request.get(origin + '/kof22/chat/history?selected=' + encodeURIComponent(selected), {
    headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' },
  });
  return response;
}

function options(environment = process.env) {
  for(const key of ['CARL_DOCKED_QBITS', 'CARL_DOCKED_REQUIRE_ESB']) {
    if(environment[key] !== undefined && !['true', 'false'].includes(environment[key])) throw new Error(key + ' must be true or false');
  }
  return { ownedQBits: environment.CARL_DOCKED_QBITS === 'true', requireEsb: environment.CARL_DOCKED_REQUIRE_ESB === 'true' };
}

async function qualify(page, context, browser, origin, report, label, checks, mode = options()) {
  const traffic = observe(page, origin);
  await login(page, origin, 'Alice');
  checks.push(label + '-actual-Carl-AI-application-title-and-header');
  let qbits;
  await expect.poll(async () => {
    const response = await context.request.get(origin + '/kof22/qbits', { headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' } });
    expect(response.status()).toBe(200); qbits = await response.json();
    return qbits.broker === 'DISABLED' || qbits.broker === 'READY';
  }, { timeout: 60000 }).toBe(true);
  const esbEnabled = qbits.broker !== 'DISABLED';
  if(mode.requireEsb) expect(qbits).toEqual({ search: 'READY', broker: 'READY' });
  if(!mode.existing) expect(qbits).toEqual(mode.ownedQBits ? { search: 'READY', broker: 'READY' } : { search: 'DISABLED', broker: 'DISABLED' });
  const metadataResponse = await context.request.get(origin + '/qqq/v1/metaData', { headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' } });
  expect(metadataResponse.status()).toBe(200);
  const metadata = await metadataResponse.json();
  const nodes = walk(metadata.appTree || []);
  expect(nodes.length).toBeGreaterThan(0);
  const visible = nodes.filter(({ node }) => !(node.type === 'PROCESS' && metadata.processes?.[node.name]?.isHidden) && !(node.type === 'REPORT' && metadata.reports?.[node.name]?.isHidden) && !(node.type === 'TABLE' && metadata.tables?.[node.name]?.isHidden));
  expect(visible.filter(({ node }) => !['APP', 'TABLE'].includes(node.type))).toEqual([]);
  const system = nodes.find(({ node }) => node.name === 'carlSystem');
  expect(system?.node.type).toBe('APP');
  const requiredSystemApps = esbEnabled ? ['operations', 'esb'] : ['operations'];
  for(const name of requiredSystemApps) {
    const entries = nodes.filter(({ node }) => node.name === name);
    expect(entries.length).toBeGreaterThan(0);
    expect(entries.every(entry => entry.ancestors.includes('carlSystem'))).toBe(true);
  }
  await sidebar(page); await expand(page, 'carlAI'); await expand(page, 'carlSystem');
  for(const name of requiredSystemApps) {
    // Native apps with no sidebar children render as leaf items rather than collapse controls.
    await expect(page.locator(`[data-qqq-id="sidebar-collapse-${name}"]:visible, [data-qqq-id="sidebar-item-${name}"]:visible`)).toHaveCount(1);
  }
  for(const name of Object.keys(metadata.processes || {})) await expect(page.locator(`[data-qqq-id="sidebar-item-${name}"]:visible`)).toHaveCount(0);
  if(!esbEnabled) {
    expect(nodes.filter(({ node }) => node.name === 'esb')).toEqual([]);
    await expect(page.locator('[data-qqq-id="sidebar-collapse-esb"]:visible')).toHaveCount(0);
  }
  checks.push(label + '-actual-visible-app-table-only-sidebar-System-Operations-nesting');
  checks.push(label + (esbEnabled ? '-actual-enabled-ESB-ready-and-nested-under-System' : '-actual-disabled-optional-ESB-absent-from-metadata-and-sidebar'));
  if(page.viewportSize().width < 768) await page.keyboard.press('Escape');
  const font = await context.request.get(origin + '/fonts/material-icons/MaterialIcons-Regular.ttf');
  expect(font.status()).toBe(200); expect((await font.body()).length).toBeGreaterThan(100000);
  checks.push(label + '-actual-packaged-Material-Icons-font-200');

  const panel = await openChat(page), composer = panel.getByRole('textbox', { name: 'Message', exact: true });
  await expect(composer).toBeFocused();
  await expect(panel.getByRole('checkbox', { name: 'Share this conversation', exact: true })).not.toBeChecked();
  await expect(panel.getByRole('button', { name: 'Send message', exact: true })).toBeDisabled();
  checks.push(label + '-docked-chat-focus-private-default-and-empty-composer');
  const draft = 'Synthetic ' + label + ' private draft survives navigation';
  await composer.fill(draft);
  await panel.getByRole('button', { name: 'Expand chat', exact: true }).click();
  await expect(panel.getByRole('button', { name: 'Dock chat', exact: true })).toBeVisible();
  await panel.getByRole('button', { name: 'Dock chat', exact: true }).click();
  await panel.getByRole('button', { name: 'Minimize chat', exact: true }).click();
  await expect(page.getByRole('button', { name: CHAT, exact: true })).toBeFocused();
  await openChat(page); await expect(composer).toHaveValue(draft);
  await navigate(page, 'carlMoney');
  await expect(page.getByRole('region', { name: CHAT, exact: true })).toBeVisible();
  await expect(composer).toHaveValue(draft);
  const bounds = await panel.boundingBox(), viewport = page.viewportSize();
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height + 1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  checks.push(label + '-expand-dock-minimize-focus-draft-persistence-on-real-navigation-and-viewport-fit');
  await page.screenshot({ path: path.join(report, label + '-01-chat-draft.png'), fullPage: true });

  const privateText = 'Synthetic ' + label + ' private question: explain only my accessible September 2026 household records.';
  await composer.fill(privateText); await composer.press('Shift+Enter');
  expect(traffic.start).toHaveLength(0);
  const started = page.waitForResponse(response => new URL(response.url()).pathname === '/kof22/chat/start' && response.request().method() === 'POST');
  await composer.press('Enter');
  const response = await started; expect(response.status()).toBe(200);
  const outcome = await response.json(); expect(outcome.selected).toMatch(SELECTED);
  expect(['PENDING', 'UNKNOWN']).toContain(outcome.status);
  // Without a model the turn ends honestly: an explicit failure (carl#34) or, if the outcome is lost, unknown.
  await expect(panel.getByText(/^(This response failed\. Start a new chat when ready\.|Response status is unknown\. Your message will not be sent again\.)$/)).toBeVisible({ timeout: 90000 });
  await expect(composer).toBeDisabled();
  await expect(panel.getByRole('button', { name: 'Send message', exact: true })).toBeDisabled();
  expect(traffic.start).toHaveLength(1); expect(traffic.start[0]).toMatchObject({ shared: false, participants: [] });
  const statuses = traffic.statuses;
  expect(statuses).not.toContain('COMPLETE'); expect(statuses).not.toContain('PARTIAL');
  await panel.getByRole('button', { name: 'Check status', exact: true }).click();
  await page.waitForTimeout(4500);
  expect(traffic.start).toHaveLength(1); expect(traffic.reply).toHaveLength(0);
  checks.push(label + '-actual-disconnected-UNKNOWN-never-fabricates-completion-or-retries-POST');
  await page.screenshot({ path: path.join(report, label + '-02-private-unknown.png'), fullPage: true });
  const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
  expect(storage).not.toContain(draft); expect(storage).not.toContain(privateText);
  checks.push(label + '-private-draft-and-transcript-never-enter-browser-storage');

  const other = await browser.newContext({ ignoreHTTPSErrors: true, viewport: page.viewportSize() });
  try {
    const bob = await other.newPage(), bobTraffic = observe(bob, origin);
    await login(bob, origin, 'Bob');
    const denied = await headers(other, origin, outcome.selected);
    expect(denied.status()).toBe(403); expect(await denied.text()).not.toContain(privateText);
    const bobThreads = await other.request.get(origin + '/kof22/chat/threads', { headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' } });
    expect(bobThreads.status()).toBe(200); const privateChoices = await bobThreads.json(); expect(Array.isArray(privateChoices)).toBe(true);
    expect(privateChoices.some(choice => choice.id.split('/')[0] === outcome.selected.split('/')[0])).toBe(false);
    await openChat(bob); await expect(bob.getByRole('textbox', { name: 'Message', exact: true })).toHaveValue('');
    expect(await bob.locator('body').innerText()).not.toContain(privateText);
    checks.push(label + '-actual-other-member-denied-private-history-and-thread-selection');

    await panel.getByRole('button', { name: 'New chat', exact: true }).click();
    await panel.getByRole('checkbox', { name: 'Share this conversation', exact: true }).check();
    const membersResponse = await context.request.get(origin + '/kof22/chat/members', { headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' } });
    expect(membersResponse.status()).toBe(200);
    const members = await membersResponse.json(); expect(Array.isArray(members)).toBe(true);
    const memberId = process.env.CARL_DOCKED_SHARED_MEMBER_ID || '2';
    const member = members.find(item => item.id === memberId); expect(member).toBeDefined();
    await panel.getByRole('checkbox', { name: member.label, exact: true }).check();
    const sharedText = 'Synthetic ' + label + ' explicitly shared family question about September 2026 accessible records.';
    await composer.fill(sharedText);
    const sharedStarted = page.waitForResponse(value => new URL(value.url()).pathname === '/kof22/chat/start' && value.request().method() === 'POST');
    await panel.getByRole('button', { name: 'Send message', exact: true }).click();
    const sharedResponse = await sharedStarted; expect(sharedResponse.status()).toBe(200);
    const sharedOutcome = await sharedResponse.json(); expect(sharedOutcome.selected).toMatch(SELECTED);
    await expect(panel.getByRole('checkbox', { name: 'Share this conversation', exact: true })).toHaveCount(0);
    await expect(panel.getByText(/^(This response failed\. Start a new chat when ready\.|Response status is unknown\. Your message will not be sent again\.)$/)).toBeVisible({ timeout: 90000 });
    expect(traffic.start).toHaveLength(2); expect(traffic.start[1].shared).toBe(true); expect(traffic.start[1].participants).toEqual([memberId]);
    checks.push(label + '-explicit-selected-sharing-frozen-on-creation-and-UNKNOWN');
    await bob.reload({ waitUntil: 'domcontentloaded' }); await openChat(bob);
    await bob.getByRole('combobox', { name: 'Conversation', exact: true }).selectOption(sharedOutcome.selected);
    await expect(bob.getByRole('log', { name: 'Messages', exact: true })).toContainText(sharedText);
    await expect(bob.getByRole('textbox', { name: 'Message', exact: true })).toBeDisabled();
    expect(await bob.locator('body').innerText()).not.toContain(privateText);
    expect(bobTraffic.start).toHaveLength(0); expect(bobTraffic.reply).toHaveLength(0);
    expect(bobTraffic.contractErrors).toEqual([]); expect(bobTraffic.pageErrors).toEqual([]);
    checks.push(label + '-selected-member-reads-real-shared-history-without-inference-replay');
    await bob.screenshot({ path: path.join(report, label + '-03-shared-other-member.png'), fullPage: true });
  } finally { await other.close(); }

  await panel.getByRole('button', { name: 'New chat', exact: true }).click();
  const logoutDraft = 'Synthetic ' + label + ' Alice draft must clear on logout';
  await composer.fill(logoutDraft); await panel.getByRole('button', { name: 'Minimize chat', exact: true }).click();
  const nav = await sidebar(page);
  await nav.locator('[data-qqq-id="sidebar-user-button"]').click();
  await page.getByRole('menuitem', { name: 'Log Out', exact: true }).click();
  await expect(page.locator('[data-qqq-id="login-app-name"]')).toHaveText('Carl AI');
  await expect(page.getByRole('region', { name: CHAT, exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: CHAT, exact: true })).toHaveCount(0);
  expect(await page.locator('body').innerText()).not.toContain(logoutDraft);
  const noSession = await context.request.get(origin + '/kof22/chat/threads', { headers: { Origin: origin, 'Sec-Fetch-Site': 'same-origin' } });
  expect(noSession.status()).toBe(401);
  await login(page, origin, 'Bob'); await openChat(page);
  await expect(page.getByRole('textbox', { name: 'Message', exact: true })).toHaveValue('');
  expect(await page.locator('body').innerText()).not.toContain(privateText);
  expect(await page.locator('body').innerText()).not.toContain(logoutDraft);
  checks.push(label + '-actual-logout-revokes-session-and-new-owner-clears-private-chat-state');
  await page.screenshot({ path: path.join(report, label + '-04-new-owner-empty-chat.png'), fullPage: true });
  expect(traffic.pageErrors).toEqual([]); expect(traffic.reply).toHaveLength(0); expect(traffic.contractErrors).toEqual([]);
  return { qbits, esbEnabled, viewport: page.viewportSize(), startRequests: traffic.start.length, replyRequests: traffic.reply.length, pollReads: traffic.reads, statuses: traffic.statuses, pageErrors: traffic.pageErrors, fontSha256: createHash('sha256').update(await font.body()).digest('hex') };
}

async function inventory(directory, relative = '', hashes = {}) {
  const entries = await readdir(path.join(directory, relative), { withFileTypes: true });
  for(const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    const name = path.posix.join(relative, entry.name);
    if(entry.isDirectory()) await inventory(directory, name, hashes);
    else if(entry.isFile()) hashes[name] = createHash('sha256').update(await readFile(path.join(directory, name))).digest('hex');
    else throw new Error('Frozen distribution contains unsupported file type');
  }
  return hashes;
}

async function main() {
  const [distribution, report, classpath] = process.argv.slice(2);
  if(!distribution || !report || (!classpath && !process.env.CARL_DOCKED_EXISTING_ORIGIN)) throw new Error('Usage: node docked-chat-browser.cjs DISTRIBUTION REPORT_DIRECTORY FIXTURE_CLASSPATH');
  const existing = process.env.CARL_DOCKED_EXISTING_ORIGIN;
  const mode = { ...options(), existing: Boolean(existing) };
  if(!existing && mode.requireEsb && !mode.ownedQBits) throw new Error('Owned ESB-required qualification needs CARL_DOCKED_QBITS=true');
  if(existing && process.env.CARL_DOCKED_EXPECT_DISCONNECTED !== 'true') throw new Error('An existing URL requires root-confirmed disconnected synthetic fixture: CARL_DOCKED_EXPECT_DISCONNECTED=true');
  if(existing && !/^https?:\/\/(localhost|127\.0\.0\.1):[0-9]+\/?$/.test(existing)) throw new Error('Only a disposable localhost synthetic fixture is accepted');
  await mkdir(report, { recursive: true });
  let fixture, browser, page, lines, successReport;
  const distributionHashes = await inventory(distribution);
  if(!distributionHashes['app.jar']) throw new Error('Frozen distribution must contain ordinary app.jar');
  const archiveHash = process.env.CARL_DOCKED_DISTRIBUTION_ARCHIVE
    ? createHash('sha256').update(await readFile(process.env.CARL_DOCKED_DISTRIBUTION_ARCHIVE)).digest('hex') : undefined;
  const checks = [], runs = [];
  try {
    let origin = existing?.replace(/\/$/, '');
    if(!origin) {
      const environment = { ...process.env, CARL_PREVIEW_LIVE_MODEL: 'false', CARL_PREVIEW_QBITS: String(mode.ownedQBits) };
      delete environment.KOF22_AGENT_ANTHROPIC_API_KEY;
      delete environment.ANTHROPIC_API_KEY;
      // Optional infrastructure comes only from this disposable fixture, never inherited endpoints.
      for(const key of Object.keys(environment)) if(key.startsWith('CARL_QBITS_')) delete environment[key];
      fixture = spawn('java', ['-cp', classpath, 'com.kof22.agentadmin.bootstrap.CarlPackagedVisualFixture', distribution, '--dashboards'], { env: environment, stdio: ['pipe', 'pipe', 'pipe'] });
      fixture.stderr.resume(); // Do not retain raw provider/database diagnostics or credentials.
      lines = createInterface({ input: fixture.stdout });
      origin = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Disconnected synthetic packaged fixture readiness timeout')), 180000);
        fixture.once('error', error => { clearTimeout(timer); reject(error); });
        fixture.once('exit', code => { clearTimeout(timer); reject(new Error('Fixture exited before readiness: ' + code)); });
        lines.on('line', raw => { const line = raw.replace(/\u001b\[[0-9;]*m/g, ''); if(line.startsWith('PACKAGED_UI=')) { clearTimeout(timer); resolve(line.slice(12).replace(/\/$/, '')); } });
      });
    }
    browser = await chromium.launch({ headless: true });
    for(const [label, viewport] of [['desktop', { width: 1440, height: 1000 }], ['mobile', { width: 390, height: 844 }]]) {
      const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport });
      try { page = await context.newPage(); page.setDefaultTimeout(25000); runs.push(await qualify(page, context, browser, origin, report, label, checks, mode)); }
      catch(error) { if(page && !page.isClosed()) await page.screenshot({ path: path.join(report, label + '-failure.png'), fullPage: true }).catch(() => {}); throw error; }
      finally { await context.close(); }
    }
    expect(await inventory(distribution)).toEqual(distributionHashes);
    successReport = { status: 'PASS', checks, runs, syntheticOnly: true, liveModel: false, expectedOutcome: 'UNKNOWN', apiResponsesMocked: false, mode, existingFixtureLeftRunning: Boolean(existing), nativeQBitsShutdownQualified: false, distribution: path.resolve(distribution), distributionFileCount: Object.keys(distributionHashes).length, distributionFileHashes: distributionHashes, distributionArchiveSha256: archiveHash, distributionUnchanged: true };
  } catch(error) {
    if(page && !page.isClosed()) await page.screenshot({ path: path.join(report, 'docked-chat-failure.png'), fullPage: true }).catch(() => {});
    await writeFile(path.join(report, 'docked-chat-report.json'), JSON.stringify({ status: 'FAIL', checks, error: String(error), syntheticOnly: true, liveModel: false, apiResponsesMocked: false, mode, nativeQBitsShutdownQualified: false }, null, 2) + '\n');
    throw error;
  } finally {
    try {
      if(browser) await browser.close();
      if(fixture && fixture.exitCode === null) {
        fixture.stdin.end('close\n');
        await new Promise((resolve, reject) => { const timer = setTimeout(() => { fixture.kill('SIGTERM'); reject(new Error('Owned fixture cleanup timeout; native shutdown remains unqualified')); }, 60000); fixture.once('exit', code => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error('Owned fixture cleanup exit: ' + code)); }); });
      } else if(fixture && fixture.exitCode !== 0) throw new Error('Owned fixture exited unexpectedly: ' + fixture.exitCode);
    } catch(error) {
      await writeFile(path.join(report, 'docked-chat-report.json'), JSON.stringify({ status: 'FAIL', checks, error: String(error), phase: 'cleanup', syntheticOnly: true, liveModel: false, apiResponsesMocked: false, mode, nativeQBitsShutdownQualified: false }, null, 2) + '\n');
      throw error;
    } finally { lines?.close(); }
  }
  if(successReport) {
    successReport.ownedFixtureStoppedCleanly = Boolean(fixture);
    await writeFile(path.join(report, 'docked-chat-report.json'), JSON.stringify(successReport, null, 2) + '\n');
  }
}
module.exports = { walk, qualify, inventory, options };
if(require.main === module) main().catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 1; });
