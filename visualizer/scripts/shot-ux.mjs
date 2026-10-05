// UI walkthrough screenshots: default view, each drawer page, the injection dialog, a phone-sized view.
// Usage: node scripts/shot-ux.mjs outDir [url]
// Expects a sim-server with --worlds-dir; starts a small "demo" world (kilauea preset) for the tuning pages.
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const out = process.argv[2] ?? 'ux-shots';
const url = process.argv[3] ?? 'http://localhost:8790/?renderer=webgl&debug';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const logs = [];

async function open(width, height) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(300000);
  page.on('console', (m) => m.type() === 'error' && logs.push(`error: ${m.text()}`));
  page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
  await page.goto(url, { waitUntil: 'commit' });
  return page;
}

async function tilesIn(page, n = 60) {
  for (let i = 0; i < 120; i++) {
    await page.waitForTimeout(1000);
    const b = await page.evaluate(() => window.__typhonMessages?.binary ?? 0).catch(() => 0);
    if (b > n) return;
  }
}

const shot = async (page, name) => {
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log('saved', `${out}/${name}.png`);
};
const click = (page, sel, text) => page.locator(sel, { hasText: text }).first().click();

const page = await open(1280, 800);
await page.waitForSelector('dialog.guide', { timeout: 60000 }).catch(() => {});
await tilesIn(page);
await page.waitForTimeout(3000);
await shot(page, '00-first-run-guide');
await click(page, 'button', 'Got it');
await page.waitForTimeout(1500);
await shot(page, '01-default');

for (const [label, name] of [
  ['Monitor', '03-monitor'],
  ['Events', '04-events'],
  ['Section', '05-section'],
  ['View', '06-view'],
  ['Worlds', '02-worlds'],
]) {
  await click(page, '.drawer-buttons button', label);
  await page.waitForTimeout(1500);
  await shot(page, name);
}

// Start a small world from a preset (world-backed, so it is tunable).
const demo = `demo-${Date.now() % 100000}`;
await page.fill('.new-world input[type=text]', demo);
await click(page, '.new-world button', 'Time scale');
await page.locator('.advanced input').first().fill('2000');
await shot(page, '07-new-world-form');
await click(page, '.new-world button[type=submit]', 'Start world');
await page.waitForFunction(() => (d) => document.querySelector('.world-name')?.textContent === d, demo, { timeout: 180000 });
await tilesIn(page, 30);
await page.waitForTimeout(3000);
await shot(page, '08-worlds-two-running');

await click(page, '.drawer-buttons button', 'Settings');
await page.waitForTimeout(2000);
await shot(page, '09-parameters');
// a restart-only change shows the confirmation bar
await page.fill('.params-tools input', 'starting magma');
await page.waitForTimeout(300);
const restartInput = page.locator('.param', { hasText: 'Starting magma temperature' }).locator('input[type=text]');
if (await restartInput.count()) {
  await restartInput.fill('1100');
  await page.waitForTimeout(500);
  await click(page, '.restart-bar button', 'Apply and restart');
  await page.waitForTimeout(500);
  await shot(page, '10-restart-confirm');
  await click(page, '.confirm button', 'Cancel');
  await click(page, '.restart-bar button', 'Discard');
  await page.fill('.params-tools input', '');
}
await click(page, '.drawer-head button', '×');

await click(page, '.action-bar button', 'Add magma');
await page.waitForTimeout(800);
await click(page, '.presets button', 'Dacite');
await page.waitForTimeout(500);
await shot(page, '11-inject-dialog');
await click(page, 'dialog.inject button', 'Cancel');

await click(page, '.world-btn', '');
await page.waitForTimeout(500);
await shot(page, '12-world-switcher');
await page.keyboard.press('Escape');

const phone = await open(390, 844);
await tilesIn(phone, 30);
await phone.waitForTimeout(2000);
await click(phone, 'button', 'Got it').catch(() => {});
await phone.waitForTimeout(1000);
await shot(phone, '13-phone');
await click(phone, '.drawer-buttons button', 'Monitor');
await phone.waitForTimeout(1500);
await shot(phone, '14-phone-drawer');

console.log('console problems:', logs.length ? '\n  ' + logs.slice(0, 12).join('\n  ') : 'none');
await browser.close();
