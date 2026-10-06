// Debug helper: counts the visible drawable objects of the 3D scene by type (draw-call sources) and
// the entities by kind, against a running sim-server.
// Usage: node scripts/scene-breakdown.mjs [url]
import { chromium } from 'playwright';

const url = process.argv[2] ?? 'http://localhost:8790/?renderer=webgl&debug&quality=medium';
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(600000);
await page.goto(url, { waitUntil: 'commit' });
for (let i = 0; i < 240; i++) {
  await page.waitForTimeout(1000);
  const t = await page.evaluate(() => Object.keys(window.__typhon?.getState().tileRevision ?? {}).length).catch(() => 0);
  if (t > 20) break;
}
await page.waitForTimeout(10000);
const r = await page.evaluate(() => {
  const by = {};
  const ents = {};
  window.__typhonScene.traverse((o) => {
    if (!(o.isMesh || o.isPoints || o.isLine || o.isSprite)) return;
    for (let p = o; p; p = p.parent) if (!p.visible) return;
    const k = `${o.isInstancedMesh ? 'instanced ' : ''}${o.type}:${o.geometry?.type ?? ''}:${o.material?.type ?? ''}`;
    by[k] = (by[k] ?? 0) + 1;
  });
  for (const e of Object.values(window.__typhon.getState().entities)) ents[e.kind] = (ents[e.kind] ?? 0) + 1;
  return { drawables: by, entities: ents, events: window.__typhon.getState().events?.length };
});
console.log(JSON.stringify(r, null, 1));
await browser.close();
