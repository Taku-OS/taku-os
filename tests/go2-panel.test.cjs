// Contract checks use synthetic telemetry and intercepted HTTP, never a real robot.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../tooling/go2-lab/web');

(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 393, height: 700 }, locale: 'en-US' });
    const errors = [], calls = [];
    let serviceOffline = false;
    let state = { connected: false, phase: 'idle', source: 'real', motion: { armed: false } };
    page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript(() => {
      window.testPages = [];
      window.Go2App = {
        openPage: name => window.testPages.push(name),
        settings: () => { window.settingsOpened = true; },
        isConnecting: () => Boolean(window.testStarting),
      };
    });
    await page.route('http://panel.test/**', async route => {
      const request = route.request(), pathname = new URL(request.url()).pathname;
      if (pathname.startsWith('/api/')) {
        if (serviceOffline) return route.abort();
        if (request.method() === 'POST') calls.push(pathname);
        return route.fulfill({ json: state });
      }
      const name = pathname === '/panel' ? 'panel.html' : pathname.slice(1);
      const contentType = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : name.endsWith('.png') ? 'image/png' : 'text/html';
      await route.fulfill({ body: await fs.readFile(path.join(root, name)), contentType });
    });
    await page.goto('http://panel.test/panel');
    await page.waitForFunction(() => document.getElementById('connection-note').textContent.includes('same Wi-Fi'));
    assert.equal(await page.locator('.feature').count(), 6);
    assert.equal(await page.evaluate(() => { const ids = [...document.querySelectorAll('[id]')].map(e => e.id); return ids.length === new Set(ids).size; }), true, 'SVG and interactive element IDs must not collide');
    assert.equal(await page.locator('#battery-value').innerText(), '—');
    await page.locator('#skills').click();
    assert.equal(await page.locator('#sheet .skill-link').count(), 7);
    await page.locator('#sheet .skill-link').first().click();
    assert.deepEqual(await page.evaluate(() => window.testPages), ['follow_view']);
    assert.equal(await page.locator('#sheet').evaluate(e => e.open), false);
    await page.locator('#settings').click();
    assert.equal(await page.evaluate(() => window.settingsOpened), true);
    await page.locator('#language').click();
    assert.equal(await page.locator('h1').innerText(), '我的设备');
    await page.locator('#language').click();
    await page.evaluate(() => { window.testStarting = true; render(); });
    assert.equal(await page.locator('#power').isDisabled(), true);
    assert.equal(await page.locator('#settings').isDisabled(), true);
    await page.evaluate(() => { window.testStarting = false; render(); });
    state = { ...state, connected: true, phase: 'connected', telemetry: { 'rt/lf/lowstate': { fresh: true, ageMs: 0, values: { batteryPercent: 72 } } } };
    await page.evaluate(() => refresh());
    assert.equal(await page.locator('#battery-value').innerText(), '72%');
    await page.locator('#power').click();
    assert.equal(calls.length, 0, 'Disconnect requires a second explicit action');
    await page.locator('#sheet-actions button').first().click();
    assert.equal(calls.length, 0);
    state.telemetry['rt/lf/lowstate'].ageMs = 6000;
    await page.evaluate(() => refresh());
    assert.equal(await page.locator('#battery-value').innerText(), '—');
    assert.equal(await page.locator('#connection-label').innerText(), 'No updates');
    serviceOffline = true;
    await page.evaluate(() => refresh());
    assert.match(await page.locator('#connection-note').innerText(), /service is unavailable/);
    await page.evaluate(() => { window.settingsOpened = false; });
    await page.locator('#power').click();
    assert.equal(await page.evaluate(() => window.settingsOpened), true);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(calls, [], 'Browsing and offline recovery must not send robot commands');
    assert.deepEqual(errors, []);
    console.log('Robot panel: navigation, unique IDs, translations, busy state, fresh/stale telemetry, disconnect confirmation and offline recovery passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
