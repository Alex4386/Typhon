// Debug probe of chamber placement on an empty world: tool → map click → dialog, plus why a click misses
// (DOM path, R3F dispatch, tile rebuild backlog). Usage: node scripts/place-probe.mjs out.png  (server on :8797)
import { chromium } from 'playwright';
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const logs = [];
page.on('console', (m) => (m.type() === 'error' || m.type() === 'warning') && logs.push(`${m.type()}: ${m.text()}`));
page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`));
await page.goto('http://127.0.0.1:8797/?renderer=webgl&debug', { waitUntil: 'commit' });
await page.waitForSelector('[aria-label="Empty world"]', { timeout: 180000 });
const gotIt = page.getByRole('button', { name: 'Got it' });
if (await gotIt.isVisible().catch(() => false)) await gotIt.click();
for (let i = 0; i < 30; i++) {
  const n = await page.evaluate(() => window.__typhonMessages?.binary ?? 0);
  if (n > 20) break;
  await page.waitForTimeout(1000);
}
const st = () => page.evaluate(() => { const s = window.__typhon.getState(); return { tool: s.tool, placeAt: s.placeAt, hasSchema: !!s.schema, place: s.schema?.commands?.placeChamber?.length }; });
console.log('world', JSON.stringify(await page.evaluate(() => { const w = window.__typhon.getState().world; return { er: w.elevationRange, sea: w.seaLevel, hasSea: w.hasSea, tiles: w.tiles, cell: w.cellSize, tileSize: w.tileSize, origin: w.origin, lod: w.lod?.extent }; })));
console.log('cam', JSON.stringify(await page.evaluate(() => { const c = window.__typhonScene?.userData; const cam = window.__typhonCamera?.getState(); return { mode: cam?.mode, readout: cam?.readout }; })));
await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false })); await page.waitForTimeout(500);
console.log('before', JSON.stringify(await st()));
console.log('element at centre', await page.evaluate(() => { let e = document.elementFromPoint(640, 424); const chain = []; while (e && chain.length < 8) { chain.push(`${e.tagName}.${(e.className && e.className.toString().slice(0, 50)) || ''}#${e.id || ''} pe=${getComputedStyle(e).pointerEvents}`); e = e.parentElement; } return chain.join(' < '); }));
{ const c0 = await page.locator('canvas').first().boundingBox(); await page.mouse.click(c0.x + c0.width / 2, c0.y + c0.height / 2); await page.waitForTimeout(1200);
  console.log('orbit click selection', JSON.stringify(await page.evaluate(() => window.__typhon.getState().selection))); await page.keyboard.press('Escape'); }
await page.evaluate(() => { window.__clicks = []; window.__typhonScene.traverse((o) => { const h = o.__r3f?.handlers; if (o.isMesh && h?.onClick) { const orig = h.onClick; h.onClick = (e) => { window.__clicks.push({ vis: o.visible, delta: e.delta, z: Math.round(e.point.y) }); return orig(e); }; } }); });
await page.evaluate(() => { window.__ev = []; const cv = document.querySelector('canvas'); const div = cv.parentElement; for (const t of ['pointerdown', 'pointerup', 'click']) { window.addEventListener(t, (e) => window.__ev.push(`win-capture:${t}`), true); div.addEventListener(t, (e) => window.__ev.push(`div-bubble:${t}`)); cv.addEventListener(t, (e) => window.__ev.push(`canvas:${t}`)); } });
await page.getByRole('button', { name: 'Place magma chamber' }).click();
await page.evaluate(() => { window.__ev = []; });
console.log('after button', JSON.stringify(await st()));
const canvas = page.locator('canvas').first();
const box = await canvas.boundingBox();
console.log('canvas', JSON.stringify(box));
await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
await page.waitForTimeout(1500);
console.log('after map click', JSON.stringify(await st()));
console.log('r3f cam', JSON.stringify(await page.evaluate(() => { const c = window.__typhonCam; return { pos: [c.position.x, c.position.y, c.position.z].map(Math.round), type: c.type, near: c.near, far: c.far, uuidSame: true }; })));
console.log('canvas keys', JSON.stringify(await page.evaluate(() => Object.keys(document.querySelector('canvas')).filter(k => k.startsWith('__')))));
console.log('dom events', JSON.stringify(await page.evaluate(() => window.__ev)));
console.log('mesh matrices', JSON.stringify(await page.evaluate(() => { const out = []; window.__typhonScene.traverse((o) => { if (o.isMesh && o.visible && o.__r3f?.handlers?.onClick && out.length < 2) { const chain = []; let p = o; while (p) { chain.push({ t: p.type, auto: p.matrixAutoUpdate, s: [p.scale.x, p.scale.y, p.scale.z], pos: [p.position.x, p.position.y, p.position.z].map(Math.round), mw13: Math.round(p.matrixWorld.elements[13]), mw5: p.matrixWorld.elements[5] }); p = p.parent; } out.push(chain); } }); return out; })));
console.log('perf', JSON.stringify(await page.evaluate(() => { const p = window.__typhonPerf; return { rebuildQueue: p.rebuildQueue, rebuildMs: p.rebuildMs, fps: p.fps }; })));
console.log('tiles', JSON.stringify(await page.evaluate(() => { let vis = 0, inv = 0; const visXY = []; window.__typhonScene.traverse((o) => { if (o.isMesh && o.__r3f?.handlers?.onClick && o.geometry.attributes.position?.count === 1089) { if (o.visible) { vis++; if (visXY.length < 60) visXY.push([Math.round(o.geometry.boundingSphere.center.x/320), Math.round(-o.geometry.boundingSphere.center.z/320)]); } else inv++; } }); return { vis, inv, visXY }; })));
console.log('clicks seen', JSON.stringify(await page.evaluate(() => window.__clicks)));
console.log('dialog visible', await page.getByRole('dialog').isVisible().catch(() => false));
const hits = await page.evaluate(() => { const sc = window.__typhonScene; if (!sc) return 'no scene'; let n = 0, vis = 0; sc.traverse((o) => { if (o.isMesh && o.__r3f?.handlers?.onClick) { n++; if (o.visible) vis++; } }); return { clickable: n, visible: vis }; });
console.log('mesh detail', JSON.stringify(await page.evaluate(() => { const out = []; window.__typhonScene.traverse((o) => { if (o.isMesh && o.__r3f?.handlers?.onClick && out.length < 6) { const g = o.geometry; const bs = g.boundingSphere; const pos = g.attributes.position; out.push({ vis: o.visible, parentVis: o.parent?.visible, n: pos?.count, bs: bs ? [Math.round(bs.center.x), Math.round(bs.center.y), Math.round(bs.center.z), Math.round(bs.radius)] : null, p0: pos ? [pos.getX(0), pos.getY(0), pos.getZ(0)].map(Math.round) : null, draw: g.drawRange?.count }); } }); return out; })));
console.log('clickable meshes', JSON.stringify(hits));
await page.screenshot({ path: process.argv[2] });
console.log('console', logs.slice(0, 10).join('\n'));
await browser.close();
