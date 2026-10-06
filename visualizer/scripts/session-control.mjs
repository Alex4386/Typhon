// Pause or resume every session of a sim-server (debug helper for screenshot runs).
// Usage: node scripts/session-control.mjs <pause|resume> [ws-url]
const action = process.argv[2] ?? 'pause';
const url = process.argv[3] ?? 'ws://127.0.0.1:8797/ws';
const ws = new WebSocket(url, 'typhon.v1');
let pending = 0;
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'session-control' }));
ws.onmessage = (m) => {
  if (typeof m.data !== 'string') return;
  const j = JSON.parse(m.data);
  if (j.type === 'sessions' && pending === 0) {
    for (const s of j.sessions) {
      console.log(s.id, s.world, s.mode, s.time);
      pending++;
      ws.send(JSON.stringify({ type: 'sessionControl', requestId: pending, sessionId: s.id, action }));
    }
    if (pending === 0) process.exit(0);
  }
  if (j.type === 'ack' && --pending === 0) {
    console.log(action, 'ok');
    process.exit(0);
  }
};
setTimeout(() => {
  console.log('timeout');
  process.exit(1);
}, 120000);
