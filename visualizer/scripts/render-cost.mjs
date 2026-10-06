// Debug helper: GPU cost of each part of the scene. Renders the scene synchronously (render +
// 1-pixel readback to wait for the GPU) with everything, then with each visible drawable group hidden
// in turn, and prints the time each part adds per frame. Needs `?debug`.
// Usage: node scripts/render-cost.mjs [url] [--wait S] [--reps N] [--view top]
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const url = argv.find((a) => a.startsWith('http')) ?? 'http://localhost:8790/?renderer=webgl&debug';
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(600000);
await page.goto(url, { waitUntil: 'commit' });
await page.waitForTimeout(Number(opt('--wait', 30)) * 1000);
if (opt('--view', null) === 'top') {
  await page.evaluate(() => {
    const w = window.__typhon.getState().world;
    const v = w.volcanoes[0].vents[0].at;
    window.__typhonCamera.getState().requestCamera({ kind: 'pose', instant: true, pose: { mode: 'orbit', position: [v[0], 6500, -v[1] + 600], heading: 0, pitch: -1.45 } });
  });
  await page.waitForTimeout(8000);
}
const r = await page.evaluate((reps) => {
  const gl = window.__typhonGl;
  const scene = window.__typhonScene;
  const cam = window.__typhonCam;
  const ctx = gl.getContext();
  const px = new Uint8Array(4);
  const time = () => {
    gl.render(scene, cam);
    ctx.readPixels(0, 0, 1, 1, ctx.RGBA, ctx.UNSIGNED_BYTE, px);
    const t0 = performance.now();
    for (let k = 0; k < reps; k++) {
      gl.render(scene, cam);
      ctx.readPixels(0, 0, 1, 1, ctx.RGBA, ctx.UNSIGNED_BYTE, px);
    }
    return (performance.now() - t0) / reps;
  };
  // groups: by object type + material type + name, over visible drawables
  const groups = new Map();
  scene.traverse((o) => {
    if (!(o.isMesh || o.isPoints || o.isLine) || !o.visible) return;
    for (let p = o.parent; p; p = p.parent) if (!p.visible) return;
    const mat = Array.isArray(o.material) ? o.material[0] : o.material;
    const k = `${o.isInstancedMesh ? 'Instanced' : o.type}/${o.geometry?.type}/${mat?.type}${mat?.transparent ? '+t' : ''}${mat?.vertexColors ? '+vc' : ''} ro${o.renderOrder}`;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(o);
  });
  const base = time();
  const out = [{ part: 'ALL', ms: base, n: 0 }];
  for (const [k, objs] of groups) {
    for (const o of objs) o.visible = false;
    const t = time();
    for (const o of objs) o.visible = true;
    out.push({ part: k, n: objs.length, ms: base - t });
  }
  // shadows / lights summary
  return { out: out.sort((a, b) => b.ms - a.ms), size: [ctx.drawingBufferWidth, ctx.drawingBufferHeight] };
}, Number(opt('--reps', 3)));
console.log('drawing buffer', r.size.join('x'));
for (const o of r.out) console.log(o.ms.toFixed(1).padStart(8), 'ms', String(o.n).padStart(5), o.part);
await browser.close();
