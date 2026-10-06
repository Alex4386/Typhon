// Checks the HUD over the 3D view at several window sizes: panels must not overlap, must stay inside
// the view, and long content must scroll inside its panel. Fills the HUD worst-case first (toasts,
// an open Inspector on the chamber, a map tool hint, the minimap).
// Usage: node scripts/layout-check.mjs [url] [outDir]   (exit code 1 on a problem)
import { mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:8787/?renderer=webgl&debug';
const outDir = process.argv[3] ?? 'layout-shots';
mkdirSync(outDir, { recursive: true });
const SIZES = [
  [1280, 800],
  [1280, 600],
  [1024, 480],
  [800, 420],
];

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
let failed = false;
try {
  for (const [w, h] of SIZES) {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    await page.addInitScript(() => window.localStorage.setItem('typhon.guideSeen', '1')); // no first-visit guide
    await page.goto(url, { waitUntil: 'commit' });
    await page.waitForSelector('[data-hud] [aria-label="Volcano status"]', { timeout: 120000 });
    const gotIt = page.getByRole('button', { name: 'Got it' });
    if (await gotIt.isVisible().catch(() => false)) await gotIt.click(); // the first-visit guide
    await page.evaluate(() => {
      const store = window.__typhon.getState();
      const long = 'Applied 1 change. Warning: Magma supply rate: Long-term supply above ~10 m³/s exceeds any active volcano (Kīlauea, among the highest, ~3–6 m³/s = 0.1–0.2 km³/yr; Etna ~1); the chamber sits at its rupture limit and keeps opening dikes.';
      store.toast(long, 'warn');
      store.toast('New fissure at 1.2 km NE', 'warn', { label: 'Show', onClick: () => {} });
      store.toast('Eruption started', 'alert');
      const v = window.__typhon.getState().world?.volcanoes?.[0]?.id;
      if (v) store.select({ type: 'entity', id: `chamber:${v}` });
      store.set({ tool: 'dig', showMinimap: true });
    });
    await page.waitForTimeout(1500);
    const report = await page.evaluate(() => {
      const view = document.querySelector('[data-hud]').getBoundingClientRect();
      const panels = [...document.querySelectorAll('[data-hud] > div > div > *')]
        .filter((e) => e.getBoundingClientRect().height > 0)
        .map((e) => ({ name: e.getAttribute('aria-label') || e.getAttribute('role') || e.tagName, r: e.getBoundingClientRect(), el: e }));
      const problems = [];
      const eps = 1;
      for (const p of panels) {
        if (p.r.left < view.left - eps || p.r.right > view.right + eps || p.r.top < view.top - eps || p.r.bottom > view.bottom + eps) {
          problems.push(`${p.name} leaves the view (${Math.round(p.r.left)},${Math.round(p.r.top)})-(${Math.round(p.r.right)},${Math.round(p.r.bottom)})`);
        }
      }
      for (let i = 0; i < panels.length; i++) {
        for (let j = i + 1; j < panels.length; j++) {
          const a = panels[i].r;
          const b = panels[j].r;
          if (panels[i].el.contains(panels[j].el) || panels[j].el.contains(panels[i].el)) continue;
          const overlap = Math.min(a.right, b.right) - Math.max(a.left, b.left) > eps && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > eps;
          if (overlap) problems.push(`${panels[i].name} overlaps ${panels[j].name}`);
        }
      }
      // content cut off without a way to scroll to it
      for (const el of document.querySelectorAll('[data-hud] *')) {
        const cs = getComputedStyle(el);
        if (el.scrollHeight > el.clientHeight + 2 && cs.overflowY === 'visible' && el.clientHeight > 0 && cs.display !== 'contents') {
          const parentClips = el.parentElement && getComputedStyle(el.parentElement).overflowY === 'hidden';
          if (parentClips) problems.push(`${el.tagName}.${(el.className || '').toString().slice(0, 40)} is clipped without scrolling`);
        }
      }
      return { panels: panels.map((p) => `${p.name}:${Math.round(p.r.width)}x${Math.round(p.r.height)}`), problems };
    });
    const shot = `${outDir}/hud-${w}x${h}.png`;
    await page.screenshot({ path: shot });
    console.log(`${w}x${h}: ${report.panels.join(' ')}`);
    for (const p of report.problems) console.log(`  PROBLEM ${p}`);
    if (report.problems.length) failed = true;
    await page.close();
  }
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
