// Server performance probe: how fast a sim-server runs an eruption, as a browser would watch it.
//
//   node scripts/perf.mjs [ws-url] [--tiles] [--seconds N] [--supply M3S]
//
// Builds a scratch "perf" world (the empty `ocean` template, seed 7, a chamber at (0, 0) 3 km below
// the sea floor), forces an eruption, runs the session as fast as it goes (transport UNBOUNDED) and
// prints the achieved simulated seconds per wall second every few seconds, with the volcano's state.
// --tiles subscribes to every field over the whole map, like the visualizer, so tile sampling and
// streaming are measured too. Run the server with its own scratch worlds directory (never a live one).

const args = process.argv.slice(2);
const url = args.find((a) => a.startsWith('ws')) ?? 'ws://127.0.0.1:8797/ws';
const opt = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 ? Number(args[i + 1]) : def;
};
const TILES = args.includes('--tiles');
const WALL = opt('--seconds', 60);
const SUPPLY = opt('--supply', 26.6);

const ws = new WebSocket(url, 'typhon.v1');
ws.binaryType = 'arraybuffer';
let rid = 0;
const waiting = new Map();
let sessions = null;
let world = null;
let state = null;
let tileBytes = 0;
const call = (o) =>
  new Promise((res) => {
    const id = ++rid;
    waiting.set(id, res);
    ws.send(JSON.stringify({ ...o, requestId: id }));
  });
ws.onmessage = (m) => {
  if (typeof m.data !== 'string') {
    tileBytes += m.data.byteLength;
    return;
  }
  const j = JSON.parse(m.data);
  if (j.type === 'sessions') sessions = j.sessions;
  if (j.type === 'attached') world = j.world;
  if (j.type === 'state') state = j;
  if ((j.type === 'ack' || j.type === 'error' || j.type === 'configResult') && waiting.has(j.requestId)) {
    waiting.get(j.requestId)(j);
    waiting.delete(j.requestId);
  }
};
await new Promise((r) => (ws.onopen = r));
ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'perf' }));
while (!sessions) await new Promise((r) => setTimeout(r, 100));
for (const s of sessions) await call({ type: 'sessionControl', sessionId: s.id, action: 'closeWithoutSaving' });
await call({ type: 'deleteWorld', name: 'perf' });
const created = await call({ type: 'createSession', template: 'ocean', name: 'perf', seed: 7, paused: true, attach: true });
console.log('create', created.ok ?? created.code, created.message ?? '');
const placed = await call({ type: 'placeChamber', at: [0, 0], fields: { depthM: 3000, supplyRateM3PerS: SUPPLY } });
const vid = placed.volcanoId ?? 'volcano-1';
console.log('place', placed.ok ?? placed.code, vid);
while (!world) await new Promise((r) => setTimeout(r, 100));
if (TILES && world.tiles) {
  const b = world.tiles;
  ws.send(JSON.stringify({ type: 'subscribe', fields: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], bounds: b }));
  console.log('subscribed to every field over', (b.maxTx - b.minTx + 1) * (b.maxTy - b.minTy + 1), 'tiles');
}
await call({ type: 'command', command: { kind: 'startEruption', volcanoId: vid } });
await call({ type: 'transport', mode: 'UNBOUNDED' });

const time = async () => {
  sessions = null;
  ws.send(JSON.stringify({ type: 'listSessions' }));
  while (!sessions) await new Promise((r) => setTimeout(r, 20));
  return sessions.find((x) => x.world === 'perf');
};
const v0 = await time();
let last = { t: v0.time, wall: Date.now() };
const start = { ...last };
const PERIOD = 5000;
while (Date.now() - start.wall < WALL * 1000) {
  await new Promise((r) => setTimeout(r, PERIOD));
  const s = await time();
  const now = Date.now();
  const rate = (s.time - last.t) / ((now - last.wall) / 1000);
  const v = state?.volcanoes?.[0];
  const c = v?.chamber;
  const eruption = c ? `${c.regime ?? ''} ${v.alert?.style ?? ''} ${Number(c.eruptionRate ?? 0).toFixed(1)} m³/s, ${Number(c.overpressureMPa ?? 0).toFixed(2)} MPa` : '';
  console.log(`t=${s.time.toFixed(0)} s  ×${rate.toFixed(1)}  ${eruption}  tiles ${(tileBytes / 1e6).toFixed(1)} MB`);
  last = { t: s.time, wall: now };
}
const end = await time();
console.log(`overall ×${((end.time - start.t) / ((Date.now() - start.wall) / 1000)).toFixed(1)} over ${WALL} s wall`);
await call({ type: 'transport', mode: 'PAUSED' });
process.exit(0);
