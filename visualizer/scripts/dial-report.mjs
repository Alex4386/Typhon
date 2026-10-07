// Prints the server's per-object settings by owner, tab and tier (primary / more / internals).
// Usage: node scripts/dial-report.mjs [ws-url] [sessionId]
const [url = 'ws://127.0.0.1:8797/ws', sessionId = 's1'] = process.argv.slice(2);
const ws = new WebSocket(url, 'typhon.v1');
ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', protocol: 1, client: 'dial-report' }));
ws.onmessage = (m) => {
  if (typeof m.data !== 'string') return;
  const msg = JSON.parse(m.data);
  if (msg.type === 'welcome') ws.send(JSON.stringify({ type: 'attach', sessionId }));
  if (msg.type === 'attached') ws.send(JSON.stringify({ type: 'getSchema' }));
  if (msg.type !== 'schema') return;
  const byOwner = new Map();
  for (const p of msg.params) {
    const o = p.owner ?? '(none: Build only)';
    if (!byOwner.has(o)) byOwner.set(o, { primary: [], more: [], internals: [], none: [] });
    byOwner.get(o)[p.tier ?? 'none'].push(`${p.tab ?? '-'}:${p.id.slice(p.id.lastIndexOf(o) >= 0 ? o.length + 1 : 0)}`);
  }
  for (const [o, t] of byOwner) {
    console.log(`\n${o}: primary ${t.primary.length}, more ${t.more.length}, internals ${t.internals.length}${t.none.length ? `, unplaced ${t.none.length}` : ''}`);
    console.log(`  primary: ${t.primary.join(', ')}`);
    if (process.env.ALL) {
      console.log(`  more: ${t.more.join(', ')}`);
      console.log(`  internals: ${t.internals.join(', ')}`);
      if (t.none.length) console.log(`  unplaced: ${t.none.join(', ')}`);
    }
  }
  ws.close();
  setTimeout(() => process.exit(0), 100);
};
