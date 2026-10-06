// Debug helper: attaches to the first session, prints WorldInfo.lod, then counts tile frames per
// level and field for a subscription to every level (node ≥ 22: global WebSocket).
// WS=ws://127.0.0.1:8797/ws WAIT=15000 node scripts/lod-info.mjs
const url = process.env.WS ?? 'ws://127.0.0.1:8797/ws';
const ws = new WebSocket(url, 'typhon.v1');
ws.binaryType = 'arraybuffer';
const counts = {};
let processed = 0;
let attached = false;
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'lod-info' }));
ws.onmessage = (e) => {
  if (typeof e.data !== 'string') {
    const b = new DataView(e.data);
    const k = `L${b.getInt16(2, true)}/f${b.getUint16(4, true)}`;
    counts[k] = (counts[k] ?? 0) + 1;
    processed++;
    ws.send(JSON.stringify({ type: 'flow', tilesProcessed: processed }));
    return;
  }
  const m = JSON.parse(e.data);
  if (m.type === 'sessions' && m.sessions.length && !attached && (attached = true)) ws.send(JSON.stringify({ type: 'attach', sessionId: m.sessions[0].id }));
  if (m.type === 'attached') {
    const w = m.world;
    console.log(JSON.stringify({ cellSize: w.cellSize, tileSize: w.tileSize, tiles: w.tiles, vents: w.volcanoes.map((v) => v.vents) }));
    console.log(JSON.stringify(w.lod));
    ws.send(JSON.stringify({ type: 'subscribe', fields: [1], levels: (w.lod?.levels ?? []).map((l) => l.level) }));
  }
};
setTimeout(() => {
  console.log(JSON.stringify(counts));
  process.exit(0);
}, Number(process.env.WAIT ?? 15000));
