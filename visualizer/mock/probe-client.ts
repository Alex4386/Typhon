// MOCK — tiny protocol client for debugging the mock server: `npx tsx mock/probe-client.ts`.
import WebSocket from 'ws';
import { decodeTileFrame } from '../src/protocol/frames';

const ws = new WebSocket(process.env.URL ?? 'ws://localhost:8787/ws', 'typhon.v1');
const counts = new Map<string, number>();
let tiles = 0;
let bytes = 0;
ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'probe' }));
  ws.send(JSON.stringify({ type: 'listSessions' }));
});
ws.on('message', (data, isBinary) => {
  if (isBinary) {
    const buf = new Uint8Array(data as Buffer);
    bytes += buf.length;
    decodeTileFrame(buf);
    tiles++;
    ws.send(JSON.stringify({ type: 'flow', tilesProcessed: tiles }));
    return;
  }
  const m = JSON.parse(data.toString());
  counts.set(m.type, (counts.get(m.type) ?? 0) + 1);
  if (m.type === 'sessions') ws.send(JSON.stringify({ type: 'attach', sessionId: m.sessions[0].id }));
  if (m.type === 'attached') ws.send(JSON.stringify({ type: 'subscribe', fields: [1,2,3,4,5,6,7,8,9,10,11,12] }));
  if (m.type === 'clock' && counts.get('clock') === 1) console.log('first clock', m);
});
setTimeout(() => {
  console.log(Object.fromEntries(counts), 'tiles', tiles, 'bytes', bytes);
  process.exit(0);
}, 3000);
