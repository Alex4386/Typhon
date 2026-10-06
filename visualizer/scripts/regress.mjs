// Screenshot regression set: one deterministic scenario, fixed named views, perf stats per view.
//
//   node scripts/regress.mjs setup [ws-url]          build the scenario on a sim-server
//   node scripts/regress.mjs shots <outDir> [url]    screenshot every view into <outDir>
//
// Scenario ("regress" world): the empty `ocean` template (server defaults, fixed seed), a magma chamber
// placed at map (0, 0) 3 km below the sea floor with a 10 m³/s supply, a forced eruption, then a fixed
// number of engine steps, paused. Run the server with its own scratch worlds directory, e.g.
//   sim-server --worlds-dir <scratch> --port 8797 --ui visualizer/dist
// Views (relative to the first vent): low (across the water at the cone), top (over shallow water),
// horizon (far, out to the horizon), vent (close-up of the crater), plume (the eruption column),
// surge / surgeFar (with a synthetic pyroclastic surge injected as PDC depth tiles). Compare folders
// from two runs to see what a change did.
import { mkdirSync } from 'node:fs';

const [cmd = 'shots', ...rest] = process.argv.slice(2);
const STEPS = 6000;

async function setup(url = 'ws://127.0.0.1:8797/ws') {
  const ws = new WebSocket(url, 'typhon.v1');
  let rid = 0;
  const waiting = new Map();
  let sessions = null;
  const call = (o) =>
    new Promise((res) => {
      const id = ++rid;
      waiting.set(id, res);
      ws.send(JSON.stringify({ ...o, requestId: id }));
    });
  ws.onmessage = (m) => {
    if (typeof m.data !== 'string') return;
    const j = JSON.parse(m.data);
    if (j.type === 'sessions') sessions = j.sessions;
    if ((j.type === 'ack' || j.type === 'error' || j.type === 'configResult') && waiting.has(j.requestId)) {
      waiting.get(j.requestId)(j);
      waiting.delete(j.requestId);
    }
  };
  await new Promise((r) => (ws.onopen = r));
  ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'regress' }));
  while (!sessions) await new Promise((r) => setTimeout(r, 100));
  for (const s of sessions) await call({ type: 'sessionControl', sessionId: s.id, action: 'closeWithoutSaving' });
  await call({ type: 'deleteWorld', name: 'regress' });
  const created = await call({ type: 'createSession', template: 'ocean', name: 'regress', seed: 7, paused: true, attach: true });
  console.log('create', created.ok ?? created.code, created.message ?? '');
  const placed = await call({ type: 'placeChamber', at: [0, 0], fields: { depthM: 3000, supplyRateM3PerS: 10 } });
  const vid = placed.volcanoId ?? 'volcano-1';
  console.log('place', placed.ok ?? placed.code, vid);
  await call({ type: 'command', command: { kind: 'startEruption', volcanoId: vid } });
  ws.send(JSON.stringify({ type: 'step', steps: STEPS }));
  // wait until the session has run the steps
  const t0 = Date.now();
  for (;;) {
    sessions = null;
    ws.send(JSON.stringify({ type: 'listSessions' }));
    await new Promise((r) => setTimeout(r, 2000));
    const s = sessions?.find((x) => x.world === 'regress');
    if (s && s.time >= STEPS * 0.05 - 0.01) {
      console.log('ready at', s.time, 's after', Math.round((Date.now() - t0) / 1000), 's');
      break;
    }
    if (Date.now() - t0 > 900000) {
      console.log('timeout waiting for steps');
      break;
    }
  }
  process.exit(0);
}

const VIEWS = {
  // [east offset, north offset, height above sea (m), look-at height (m), look-at east/north offset]
  low: [-700, -500, 60, 10],
  top: [-520, -360, 420, 0, -260, -180],
  horizon: [-2500, -2500, 900, 0],
  vent: [-260, -180, 160, 20],
  plume: [-3200, -1200, 400, 1500],
};

async function shots(out = 'regress-shots', url = 'http://localhost:8797/?debug&perf') {
  const { chromium } = await import('playwright');
  mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(600000);
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(url, { waitUntil: 'commit' });
  await page.waitForSelector('[data-hud]', { timeout: 180000 });
  await page.evaluate(() => window.__typhon.getState().set({ guideOpen: false }));
  await page.waitForTimeout(25000);
  const pose = async ([de, dn, h, lookH, te = 0, tn = 0]) =>
    page.evaluate(
      ({ de, dn, h, lookH, te, tn }) => {
        const s = window.__typhon.getState();
        const v = s.world.volcanoes[0];
        const vent = v.vents[0]?.at ?? v.chamber.center;
        const ve = s.verticalExaggeration;
        const target = [vent[0] + te, lookH * ve, -(vent[1] + tn)];
        const position = [vent[0] + de, h * ve, -(vent[1] + dn)];
        const dx = target[0] - position[0];
        const dy = target[1] - position[1];
        const dz = target[2] - position[2];
        const heading = Math.atan2(dx, -dz);
        const pitch = Math.atan2(dy, Math.hypot(dx, dz));
        window.__typhonCamera.getState().requestCamera({ kind: 'pose', instant: true, pose: { mode: 'orbit', position, heading, pitch, target } });
      },
      { de, dn, h, lookH, te, tn },
    );
  const snap = async (name) => {
    // wait until every tile mesh is built (a slow software renderer takes a while), then a moment more
    for (let k = 0; k < 120; k++) {
      await page.waitForTimeout(3000);
      const queue = await page.evaluate(() => window.__typhonPerf?.rebuildQueue ?? 0);
      if (queue === 0) break;
    }
    await page.waitForTimeout(3000);
    await page.screenshot({ path: `${out}/${name}.png`, timeout: 600000 });
    // what is actually in the scene: visible meshes (draw calls) and triangles, grouped by kind
    const perf = await page.evaluate(() => {
      const scene = window.__typhonScene;
      if (!scene) return null;
      let calls = 0;
      let tris = 0;
      const groups = {};
      scene.traverseVisible((o) => {
        if (!o.isMesh && !o.isPoints && !o.isLine) return;
        const g = o.geometry;
        if (!g) return;
        const range = g.drawRange?.count;
        const count = Number.isFinite(range) && range !== Infinity ? range : (g.index ? g.index.count : g.attributes.position?.count ?? 0);
        const inst = o.isInstancedMesh ? o.count : 1;
        if (inst === 0 || count === 0) return;
        const t = o.isMesh ? (count / 3) * inst : 0;
        calls++;
        tris += t;
        const key = `${o.isInstancedMesh ? 'inst:' : ''}${o.material?.type ?? '?'}/${g.type}`;
        groups[key] = groups[key] ?? [0, 0];
        groups[key][0]++;
        groups[key][1] += Math.round(t);
      });
      return { calls, tris: Math.round(tris), groups };
    });
    console.log(name, JSON.stringify(perf));
  };
  // VIEWS=low,plume runs a subset; SURGE=0 skips the surge views
  const only = process.env.VIEWS ? process.env.VIEWS.split(',') : null;
  for (const [name, v] of Object.entries(VIEWS)) {
    if (only && !only.includes(name)) continue;
    await pose(v);
    await snap(name);
  }
  if (process.env.SURGE === '0') {
    console.log('errors:', errors.slice(0, 6).join(' | ') || 'none');
    await browser.close();
    return;
  }
  // a synthetic surge: a 1.2 km tongue running north-east from the vent, 1–6 m deep, as PDC depth tiles
  await page.evaluate(() => {
    const s = window.__typhon.getState();
    const w = s.world;
    const v = w.volcanoes[0];
    const vent = v.vents[0]?.at ?? v.chamber.center;
    const t = w.tileSize;
    const frames = [];
    for (let ty = w.tiles.minTy; ty <= w.tiles.maxTy; ty++) {
      for (let tx = w.tiles.minTx; tx <= w.tiles.maxTx; tx++) {
        const values = new Float32Array(t * t);
        let any = false;
        for (let r = 0; r < t; r++) {
          for (let c = 0; c < t; c++) {
            const x = w.origin[0] + (tx * t + c + 0.5) * w.cellSize - vent[0];
            const y = w.origin[1] + (ty * t + r + 0.5) * w.cellSize - vent[1];
            const along = (x + y) / Math.SQRT2;
            const across = (x - y) / Math.SQRT2;
            if (along < 80 || along > 1300) continue;
            const half = 90 + along * 0.12;
            if (Math.abs(across) > half) continue;
            values[r * t + c] = 1 + 5 * (1 - Math.abs(across) / half) * (along / 1300);
            any = true;
          }
        }
        if (any) frames.push({ level: 0, field: 5, codec: 0, tileX: tx, tileY: ty, version: 1e9, width: t, height: t, time: 0, values });
      }
    }
    s.applyTiles(frames);
  });
  await page.waitForTimeout(3000);
  await pose([-900, 300, 250, 60]);
  await snap('surge');
  await pose([-3500, -2500, 1800, 0]);
  await snap('surgeFar');
  // across the surge tongue from its south-east side (the column stands behind it, not in front)
  await pose([1500, -500, 220, 20, 450, 450]);
  await snap('surgeSide');
  console.log('errors:', errors.slice(0, 6).join(' | ') || 'none');
  await browser.close();
}

if (cmd === 'setup') await setup(rest[0]);
else await shots(rest[0], rest[1]);
