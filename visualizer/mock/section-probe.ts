// MOCK — debug: request a section from the running mock and decode it (`npx tsx mock/section-probe.ts`).
import WebSocket from 'ws';
import { decodeSectionFrame } from '../src/protocol/frames';

const ws = new WebSocket('ws://localhost:8787/ws', 'typhon.v1');
ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'section-probe' }));
  if (process.env.SUBSCRIBE) ws.send(JSON.stringify({ type: 'subscribe', fields: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] }));
  ws.send(JSON.stringify({ type: 'section', requestId: 5, polyline: [[-4000, 1500], [4500, -3000]], zMin: -6000, zMax: 2300, nu: 600, nz: 260 }));
});
ws.on('message', (data, isBinary) => {
  if (!isBinary) {
    const m = JSON.parse(data.toString());
    if (m.type === 'error') console.log('error', m);
    return;
  }
  const buf = new Uint8Array(data as Buffer);
  if (buf[0] !== 2) return;
  const s = decodeSectionFrame(buf);
  console.log('section', s.nu, s.nz, s.meta.units.length, s.meta.overlays.length, buf.length, 'bytes');
  process.exit(0);
});
setTimeout(() => {
  console.log('timeout');
  process.exit(1);
}, 15000);
