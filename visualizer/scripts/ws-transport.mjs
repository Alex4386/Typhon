// Sets the transport of a session of a running sim-server (pause it while taking screenshots).
// Usage: node scripts/ws-transport.mjs ws://localhost:8797/ws PAUSED [speed] [sessionId]
const [url, mode, speed, sessionId = 's1'] = process.argv.slice(2);
const ws = new WebSocket(url, 'typhon.v1');
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'ws-transport' }));
ws.onmessage = (m) => {
  if (typeof m.data !== 'string') return;
  const msg = JSON.parse(m.data);
  if (msg.type === 'welcome') ws.send(JSON.stringify({ type: 'attach', sessionId }));
  if (msg.type === 'attached') ws.send(JSON.stringify({ type: 'transport', mode, ...(speed ? { speed: Number(speed) } : {}) }));
  if (msg.type === 'clock') {
    console.log(JSON.stringify({ mode: msg.mode, speed: msg.speed, time: msg.time }));
    ws.close();
    setTimeout(() => process.exit(0), 100);
  }
};
setTimeout(() => process.exit(2), 30000);
