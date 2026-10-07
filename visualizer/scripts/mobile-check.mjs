// Mobile check: device emulation (touch, DPR, UA) for phones and a tablet.
//  - layout: HUD pieces inside the view and not overlapping, no horizontal page overflow, touch targets ≥ 44 px
//  - screenshots of the main states: world view, Inspector sheet, Settings sheet, Build placement, playback popover
//  - gestures (first device): pinch zooms, two-finger drag pans, twist turns, tap selects, drag does not
//  - perf: PerfHud stats under ×4 CPU throttling
// Usage: node scripts/mobile-check.mjs [url] [outDir]   e.g. url=http://localhost:4174/?server=ws://localhost:8797/ws&renderer=webgl&debug
// Exit code 1 on a layout/target/gesture problem.
import { mkdirSync } from 'node:fs';
import { chromium, devices } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:4174/?server=ws://localhost:8797/ws&renderer=webgl&debug';
const outDir = process.argv[3] ?? 'mobile-shots';
const only = process.env.DEVICES?.split(',');
mkdirSync(outDir, { recursive: true });

const DEVICES = [
  ['iphone14', devices['iPhone 14']],
  ['iphone14-landscape', devices['iPhone 14 landscape']],
  ['pixel7', devices['Pixel 7']],
  ['ipad', devices['iPad Pro 11']],
  ['desktop', { viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1, isMobile: false, hasTouch: false }],
].filter(([n]) => !only || only.includes(n));

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
let failed = false;
const say = (...a) => console.log(...a);
const problem = (msg) => {
  failed = true;
  say('  PROBLEM', msg);
};

async function open(dev) {
  // Chromium cannot emulate WebKit's UA engine, only its metrics/touch: fine for layout and input
  const ctx = await browser.newContext({ ...dev, deviceScaleFactor: Math.min(dev.deviceScaleFactor ?? 2, 2) });
  const page = await ctx.newPage();
  await page.addInitScript(() => window.localStorage.setItem('typhon.guideSeen', '1'));
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(url, { waitUntil: 'commit' });
  await page.waitForSelector('[data-hud]', { timeout: 120000 });
  await page.waitForFunction(() => !!window.__typhon?.getState().world, null, { timeout: 120000 });
  await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
  await page.waitForTimeout(2500);
  return { ctx, page, errors };
}

async function layout(page, name, state) {
  const r = await page.evaluate(() => {
    const out = { problems: [], small: [] };
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const root = document.scrollingElement;
    if (root.scrollWidth > vw + 1) out.problems.push(`page scrolls sideways (${root.scrollWidth} > ${vw})`);
    const view = document.querySelector('[data-hud]')?.getBoundingClientRect();
    const pieces = [...document.querySelectorAll('[data-hud] section, [data-hud] [role="toolbar"], [data-hud] [role="log"] > div')]
      .filter((e) => {
        const b = e.getBoundingClientRect();
        return b.width > 0 && b.height > 0 && !e.parentElement.closest('[data-hud] section, [data-hud] [role="toolbar"]');
      })
      .map((e) => ({ name: e.getAttribute('aria-label') || e.getAttribute('role') || e.tagName, r: e.getBoundingClientRect(), el: e }));
    for (const p of pieces) {
      if (view && (p.r.left < view.left - 1 || p.r.right > view.right + 1 || p.r.top < view.top - 1 || p.r.bottom > view.bottom + 1))
        out.problems.push(`${p.name} leaves the view`);
    }
    for (let i = 0; i < pieces.length; i++)
      for (let j = i + 1; j < pieces.length; j++) {
        const a = pieces[i].r;
        const b = pieces[j].r;
        if (pieces[i].el.contains(pieces[j].el) || pieces[j].el.contains(pieces[i].el)) continue;
        if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1)
          out.problems.push(`${pieces[i].name} overlaps ${pieces[j].name}`);
      }
    // touch targets: visible buttons, tabs, menu items inside the viewport (not covered by a sheet edge)
    for (const e of document.querySelectorAll('[data-slot="button"], [data-slot="tabs-trigger"], [role="menuitem"], [data-sheet-handle]')) {
      const b = e.getBoundingClientRect();
      if (b.width === 0 || b.height === 0 || b.bottom < 0 || b.top > vh || b.right < 0 || b.left > vw) continue;
      const style = getComputedStyle(e);
      if (style.visibility === 'hidden' || style.display === 'none') continue;
      const handle = e.hasAttribute('data-sheet-handle');
      if (b.height < 43.5 || (!handle && b.width < 32))
        out.small.push(`${(e.getAttribute('aria-label') || e.textContent || e.getAttribute('role') || '').trim().slice(0, 24) || '?'} ${Math.round(b.width)}x${Math.round(b.height)}`);
    }
    return out;
  });
  for (const p of r.problems) problem(`${name}/${state}: ${p}`);
  const coarse = await page.evaluate(() => matchMedia('(pointer: coarse)').matches);
  if (coarse && r.small.length) problem(`${name}/${state}: ${r.small.length} touch targets under 44 px: ${r.small.slice(0, 8).join(', ')}`);
}

async function shot(page, name, state) {
  await page.screenshot({ path: `${outDir}/${name}-${state}.png` });
  await layout(page, name, state);
}

async function readout(page) {
  return page.evaluate(() => {
    const r = window.__typhonCamera.getState().readout;
    return r ? { x: r.x, y: r.y, alt: r.altitude, heading: r.heading } : null;
  });
}

async function touch(cdp, type, pts) {
  await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id, radiusX: 4, radiusY: 4, force: 1 })) });
}

async function twoFingerGesture(cdp, from, to, steps = 12) {
  await touch(cdp, 'touchStart', from);
  for (let k = 1; k <= steps; k++) {
    const t = k / steps;
    await touch(
      cdp,
      'touchMove',
      from.map(([x, y], i) => [x + (to[i][0] - x) * t, y + (to[i][1] - y) * t]),
    );
    await new Promise((r) => setTimeout(r, 16));
  }
  await touch(cdp, 'touchEnd', []);
}

async function gestures(page, name) {
  const cdp = await page.context().newCDPSession(page);
  const box = await page.locator('canvas').first().boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height * 0.45;
  const settle = () => page.waitForTimeout(900);
  await page.evaluate(() => window.__typhon.getState().select(null));
  // tap selects
  await touch(cdp, 'touchStart', [[cx, cy]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(500);
  const tapSel = await page.evaluate(() => window.__typhon.getState().selection?.type ?? null);
  if (!tapSel) problem(`${name}: tap did not select`);
  await page.evaluate(() => window.__typhon.getState().select(null));
  await page.waitForTimeout(400);
  // drag (orbit) does not select
  await touch(cdp, 'touchStart', [[cx - 60, cy]]);
  for (let k = 1; k <= 10; k++) await touch(cdp, 'touchMove', [[cx - 60 + k * 12, cy + k * 3]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(500);
  const dragSel = await page.evaluate(() => window.__typhon.getState().selection);
  if (dragSel) problem(`${name}: a drag selected something`);
  await settle();
  // pinch out zooms in (camera altitude drops)
  const a0 = await readout(page);
  await twoFingerGesture(cdp, [[cx - 40, cy], [cx + 40, cy]], [[cx - 140, cy], [cx + 140, cy]]);
  await settle();
  const a1 = await readout(page);
  const pinched = a0 && a1 && a1.alt < a0.alt - 1;
  if (!pinched) problem(`${name}: pinch did not zoom (${a0?.alt?.toFixed(0)} → ${a1?.alt?.toFixed(0)})`);
  const pinchSel = await page.evaluate(() => window.__typhon.getState().selection);
  if (pinchSel) problem(`${name}: the pinch selected something`);
  // two-finger drag pans (camera x/y move)
  await twoFingerGesture(cdp, [[cx - 60, cy], [cx + 60, cy]], [[cx - 60, cy + 120], [cx + 60, cy + 120]]);
  await settle();
  const a2 = await readout(page);
  const panned = a1 && a2 && Math.hypot(a2.x - a1.x, a2.y - a1.y) > 1;
  if (!panned) problem(`${name}: two-finger drag did not pan`);
  // twist turns the heading
  const r = 80;
  const ang = 0.6;
  await twoFingerGesture(
    cdp,
    [[cx - r, cy], [cx + r, cy]],
    [[cx - r * Math.cos(ang), cy - r * Math.sin(ang)], [cx + r * Math.cos(ang), cy + r * Math.sin(ang)]],
  );
  await settle();
  const a3 = await readout(page);
  const turned = a2 && a3 && Math.abs(Math.atan2(Math.sin(a3.heading - a2.heading), Math.cos(a3.heading - a2.heading))) > 0.1;
  if (!turned) problem(`${name}: twist did not turn the heading`);
  say(`  gestures: tap→${tapSel} drag→${dragSel ? 'selected!' : 'none'} pinch ${a0?.alt?.toFixed(0)}→${a1?.alt?.toFixed(0)} m, pan ${a1 && a2 ? Math.hypot(a2.x - a1.x, a2.y - a1.y).toFixed(0) : '?'} m, twist ${a2 && a3 ? (a3.heading - a2.heading).toFixed(2) : '?'} rad`);
}

async function perf(page, name) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  const p0 = await page.evaluate(() => ({ frames: window.__typhonPerf.frames, t: performance.now() }));
  // keep the scene drawing: orbit slowly for 6 s
  const box = await page.locator('canvas').first().boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const hasTouch = await page.evaluate(() => navigator.maxTouchPoints > 0);
  if (hasTouch) await touch(cdp, 'touchStart', [[cx, cy]]);
  else {
    await page.mouse.move(cx, cy);
    await page.mouse.down();
  }
  for (let k = 0; k < 120; k++) {
    if (hasTouch) await touch(cdp, 'touchMove', [[cx + 60 * Math.sin(k / 10), cy]]);
    else await page.mouse.move(cx + 60 * Math.sin(k / 10), cy);
    await new Promise((r) => setTimeout(r, 50));
  }
  if (hasTouch) await touch(cdp, 'touchEnd', []);
  else await page.mouse.up();
  const p1 = await page.evaluate(() => ({ frames: window.__typhonPerf.frames, t: performance.now(), s: { ...window.__typhonPerf } }));
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  const fps = ((p1.frames - p0.frames) * 1000) / (p1.t - p0.t);
  say(`  perf ×4 CPU: ${fps.toFixed(1)} fps, draws ${p1.s.calls ?? '?'}, tris ${p1.s.triangles ?? '?'}, dpr ${p1.s.dpr ?? '?'}, quality ${await page.evaluate(() => window.__typhon.getState().quality)}`);
}

try {
  for (const [name, dev] of DEVICES) {
    say(`${name} (${dev.viewport.width}x${dev.viewport.height})`);
    const { ctx, page, errors } = await open(dev);
    await shot(page, name, 'world');
    // Inspector: select the chamber
    await page.evaluate(() => {
      const s = window.__typhon.getState();
      const v = s.world?.volcanoes?.[0]?.id;
      if (v) s.select({ type: 'entity', id: `chamber:${v}` });
    });
    await page.waitForTimeout(1200);
    await shot(page, name, 'inspector');
    await page.evaluate(() => window.__typhon.getState().select(null));
    // Settings panel
    await page.evaluate(() => window.__typhon.getState().set({ drawer: 'tune' }));
    await page.waitForTimeout(1200);
    await shot(page, name, 'settings');
    // Build placement
    await page.evaluate(() => window.__typhon.getState().set({ drawer: 'build', tool: 'chamber' }));
    await page.waitForTimeout(1200);
    await shot(page, name, 'build');
    await page.evaluate(() => window.__typhon.getState().set({ drawer: null, tool: 'orbit' }));
    await page.waitForTimeout(600);
    // playback popover
    const speed = page.locator('header [data-testid="speed"]').first();
    if (await speed.count()) {
      await speed.tap().catch(() => speed.click());
      await page.waitForTimeout(800);
      await page.screenshot({ path: `${outDir}/${name}-playback.png` });
      await page.keyboard.press('Escape');
      await page.waitForTimeout(400);
    } else problem(`${name}: no speed button in the header`);
    if (dev.hasTouch && (name === DEVICES[0][0] || process.env.GESTURES === 'all')) await gestures(page, name);
    if (name === DEVICES[0][0] || process.env.PERF === 'all') await perf(page, name);
    if (errors.length) problem(`${name}: page errors: ${errors.slice(0, 3).join(' | ')}`);
    await ctx.close();
  }
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
