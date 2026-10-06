// Debug helper: prints the minimum surface elevation (frame param0) of every tile of a level, as a
// map with north up (node ≥ 22). WS=ws://127.0.0.1:8797/ws LEVEL=0 node scripts/tile-minima.mjs
const url = process.env.WS ?? 'ws://127.0.0.1:8797/ws';
const want = Number(process.env.LEVEL ?? 0);
const ws = new WebSocket(url, 'typhon.v1');
ws.binaryType = 'arraybuffer';
const mins = new Map();
let processed = 0;
let attached = false;
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'tile-minima' }));
ws.onmessage = (e) => {
  if (typeof e.data !== 'string') {
    const b = new DataView(e.data);
    processed++;
    ws.send(JSON.stringify({ type: 'flow', tilesProcessed: processed }));
    if (b.getInt16(2, true) !== want || b.getUint16(4, true) !== 1) return;
    mins.set(`${b.getInt32(8, true)},${b.getInt32(12, true)}`, b.getFloat32(24, true));
    return;
  }
  const m = JSON.parse(e.data);
  if (m.type === 'sessions' && m.sessions.length && !attached && (attached = true)) ws.send(JSON.stringify({ type: 'attach', sessionId: m.sessions[0].id }));
  if (m.type === 'attached') {
    console.log('seaLevel', m.world.seaLevel, 'elevationRange', JSON.stringify(m.world.elevationRange));
    ws.send(JSON.stringify({ type: 'subscribe', fields: [1], levels: want === 0 ? [] : [want] }));
  }
};
setTimeout(() => {
  const keys = [...mins.keys()].map((k) => k.split(',').map(Number));
  const ys = [...new Set(keys.map((k) => k[1]))].sort((a, b) => b - a);
  const xs = [...new Set(keys.map((k) => k[0]))].sort((a, b) => a - b);
  for (const y of ys) console.log(String(y).padStart(3), xs.map((x) => String(Math.round(mins.get(`${x},${y}`) ?? NaN)).padStart(6)).join(''));
  process.exit(0);
}, Number(process.env.WAIT ?? 15000));
