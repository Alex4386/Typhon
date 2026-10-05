// Debug helper: subscribes to every field, acknowledges tiles immediately and reports message
// rates and bytes per second. Usage: node scripts/bandwidth.mjs [ws://host:port/ws] [seconds]
import WebSocket from 'ws';

const url = process.argv[2] ?? 'ws://localhost:8787/ws';
const seconds = Number(process.argv[3] ?? 20);
const ws = new WebSocket(url, 'typhon.v1');
const stats = { textMsgs: 0, textBytes: 0, tiles: 0, tileBytes: 0, sections: 0 };
let processed = 0;
let start = 0;
ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'bandwidth' })));
ws.on('message', (data, binary) => {
  if (!binary) {
    const m = JSON.parse(data.toString());
    stats.textMsgs++;
    stats.textBytes += data.length;
    if (m.type === 'welcome') ws.send(JSON.stringify({ type: 'listSessions' }));
    if (m.type === 'sessions') ws.send(JSON.stringify({ type: 'attach', sessionId: m.sessions[0].id }));
    if (m.type === 'attached') {
      start = Date.now();
      ws.send(JSON.stringify({ type: 'subscribe', fields: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] }));
    }
    return;
  }
  if (data[0] === 1) {
    stats.tiles++;
    stats.tileBytes += data.length;
    processed++;
    ws.send(JSON.stringify({ type: 'flow', tilesProcessed: processed }));
  } else stats.sections++;
});
setTimeout(() => {
  const s = (Date.now() - start) / 1000;
  console.log(JSON.stringify({ seconds: s.toFixed(1), ...stats, tilesPerSec: (stats.tiles / s).toFixed(1),
    kBps: ((stats.textBytes + stats.tileBytes) / 1024 / s).toFixed(1), avgTileBytes: (stats.tileBytes / Math.max(1, stats.tiles)) | 0 }));
  ws.close();
  process.exit(0);
}, seconds * 1000);
