// Debug helper: prints one row of crater-detail elevations through the first vent next to the core
// columns they refine (run with tsx). WS=ws://127.0.0.1:8797/ws npx tsx scripts/detail-row.mjs
import { decodeTileFrame } from '../src/protocol/frames';

const url = process.env.WS ?? 'ws://127.0.0.1:8797/ws';
const ws = new WebSocket(url, 'typhon.v1');
ws.binaryType = 'arraybuffer';
const tiles = new Map();
let processed = 0;
let attached = false;
let world = null;
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'detail-row' }));
ws.onmessage = (e) => {
  if (typeof e.data !== 'string') {
    processed++;
    ws.send(JSON.stringify({ type: 'flow', tilesProcessed: processed }));
    const f = decodeTileFrame(new Uint8Array(e.data));
    if (f.field === 1) tiles.set(`${f.level}:${f.tileX},${f.tileY}`, f);
    return;
  }
  const m = JSON.parse(e.data);
  if (m.type === 'sessions' && m.sessions.length && !attached && (attached = true)) ws.send(JSON.stringify({ type: 'attach', sessionId: m.sessions[0].id }));
  if (m.type === 'attached') {
    world = m.world;
    const detail = world.lod.levels.filter((l) => l.kind === 'detail').map((l) => l.level);
    ws.send(JSON.stringify({ type: 'subscribe', fields: [1], levels: detail }));
  }
};
setTimeout(() => {
  const T = world.tileSize;
  const vent = world.volcanoes[0].vents[0].at;
  const l = world.lod.levels.find((x) => x.kind === 'detail');
  const c = l.cellSize;
  const r = Math.round(world.cellSize / c);
  const j = Math.floor((vent[1] - world.origin[1]) / c);
  const i0 = Math.floor((vent[0] - 300 - world.origin[0]) / c);
  const out = [];
  for (let i = i0; i < i0 + 120; i++) {
    const tx = Math.floor(i / T);
    const ty = Math.floor(j / T);
    const d = tiles.get(`${l.level}:${tx},${ty}`);
    const ci = Math.floor(i / r);
    const cj = Math.floor(j / r);
    const ct = tiles.get(`0:${Math.floor(ci / T)},${Math.floor(cj / T)}`);
    const dv = d ? d.values[(j - ty * T) * T + (i - tx * T)] : NaN;
    const cv = ct ? ct.values[(cj - Math.floor(cj / T) * T) * T + (ci - Math.floor(ci / T) * T)] : NaN;
    out.push(`${(world.origin[0] + (i + 0.5) * c).toFixed(0)}:${dv.toFixed(2)}/${cv.toFixed(2)}`);
  }
  console.log(`detail cell ${c} m, ${r} per column; x:detail/core`);
  for (let k = 0; k < out.length; k += 8) console.log(out.slice(k, k + 8).join('  '));
  process.exit(0);
}, Number(process.env.WAIT ?? 25000));
