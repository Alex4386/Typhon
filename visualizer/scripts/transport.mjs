// Sets the transport of a session (debug helper for screenshot runs).
// Usage: node scripts/transport.mjs <REALTIME|UNBOUNDED|PAUSED> [speed] [ws-url] [sessionId]
const [mode = 'UNBOUNDED', speed, url = 'ws://127.0.0.1:8797/ws', sessionId = 's1'] = process.argv.slice(2);
const ws = new WebSocket(url, 'typhon.v1');
ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'transport' }));
  ws.send(JSON.stringify({ type: 'transport', mode, ...(speed ? { speed: Number(speed) } : {}), sessionId }));
  setTimeout(() => {
    console.log('sent', mode, speed ?? '');
    process.exit(0);
  }, 500);
};
setTimeout(() => process.exit(1), 30000);
