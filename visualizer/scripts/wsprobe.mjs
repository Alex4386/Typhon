// Debug helper: from the app's origin, opens a raw protocol WebSocket, requests a section and
// counts what arrives (text types and binary frame kinds).
import { chromium } from 'playwright';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const page = await browser.newPage();
await page.goto((process.env.URL ?? 'http://localhost:5180/') + '?renderer=webgl');
await page.waitForTimeout(3000);
const result = await page.evaluate(
  
    (SUB) => new Promise((resolve) => {
      const counts = {};
      const ws = new WebSocket('ws://localhost:8787/ws', 'typhon.v1');
      ws.binaryType = 'arraybuffer';
      ws.onopen = () => {
        ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'wsprobe' }));
        if (SUB) ws.send(JSON.stringify({ type: 'subscribe', fields: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] }));
        ws.send(JSON.stringify({ type: 'section', requestId: 9, polyline: [[-4000, 1500], [4500, -3000]], zMin: -6000, zMax: 2300, nu: 300, nz: 100 }));
      };
      ws.onerror = () => (counts.error = (counts.error ?? 0) + 1);
      ws.onclose = (e) => (counts.close = e.code);
      ws.onmessage = (e) => {
        if (typeof e.data !== 'string') {
          const k = `bin${new Uint8Array(e.data)[0]}`;
          counts[k] = (counts[k] ?? 0) + 1;
          return;
        }
        const m = JSON.parse(e.data);
        counts[m.type] = (counts[m.type] ?? 0) + 1;
      };
      setTimeout(() => resolve(counts), 8000);
    }),
  Boolean(process.env.SUBSCRIBE),
);
console.log(JSON.stringify(result));
await browser.close();
