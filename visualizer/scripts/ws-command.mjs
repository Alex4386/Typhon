// Sends one command to a session of a running sim-server (attaches as a throwaway client).
// Usage: node scripts/ws-command.mjs ws://localhost:8791/ws '{"kind":"startEruption","volcanoId":"FIRST"}' [sessionId]
// "FIRST" is replaced by the first volcano's id.
const [url, json, sessionId = 's1'] = process.argv.slice(2);
const ws = new WebSocket(url, 'typhon.v1');
const done = (code) => {
  ws.close();
  setTimeout(() => process.exit(code), 100);
};
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'ws-command' }));
ws.onmessage = (m) => {
  if (typeof m.data !== 'string') return;
  const msg = JSON.parse(m.data);
  if (msg.type === 'welcome') ws.send(JSON.stringify({ type: 'attach', sessionId }));
  if (msg.type === 'attached') {
    const cmd = JSON.parse(json.replace('FIRST', msg.world.volcanoes[0].id));
    ws.send(JSON.stringify({ type: 'command', requestId: 1, command: cmd }));
  }
  if (msg.requestId === 1 || msg.type === 'error') {
    console.log(JSON.stringify(msg).slice(0, 300));
    done(msg.type === 'error' ? 1 : 0);
  }
};
setTimeout(() => done(2), 60000);
