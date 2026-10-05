// Debug helper: CPU-profiles the page for a few seconds and prints the hottest functions.
// Usage: node scripts/cpu-probe.mjs <url> [seconds]
import { chromium } from 'playwright';

const browser = await chromium.launch({
  executablePath: process.env.CHROME || undefined,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const cdp = await page.context().newCDPSession(page);
await cdp.send('Profiler.enable');
await page.goto(process.argv[2], { waitUntil: 'commit' });
await page.waitForTimeout(1500);
await cdp.send('Profiler.start');
await page.waitForTimeout(Number(process.argv[3] ?? 8) * 1000);
const { profile } = await cdp.send('Profiler.stop');
const self = new Map();
const byId = new Map(profile.nodes.map((n) => [n.id, n]));
const dt = profile.timeDeltas;
const counts = new Map();
profile.samples.forEach((id, i) => counts.set(id, (counts.get(id) ?? 0) + (dt[i] ?? 0)));
for (const [id, us] of counts) {
  const f = byId.get(id).callFrame;
  const key = `${f.functionName || '(anon)'} ${f.url.split('/').slice(-1)[0]}:${f.lineNumber + 1}`;
  self.set(key, (self.get(key) ?? 0) + us);
}
// Which app functions (src/) are on the stack, inclusive time.
const parent = new Map();
for (const n of profile.nodes) for (const c of n.children ?? []) parent.set(c, n.id);
const inclusive = new Map();
for (const [id, us] of counts) {
  const seen = new Set();
  for (let cur = id; cur !== undefined; cur = parent.get(cur)) {
    const f = byId.get(cur).callFrame;
    if (!f.url.includes('/src/')) continue;
    const key = `${f.functionName || '(anon)'} ${f.url.split('/').slice(-1)[0]}:${f.lineNumber + 1}`;
    if (seen.has(key)) continue;
    seen.add(key);
    inclusive.set(key, (inclusive.get(key) ?? 0) + us);
  }
}
console.log('-- app functions, inclusive');
[...inclusive.entries()].sort((a, b) => b[1] - a[1]).slice(0, 15).forEach(([k, us]) => console.log(((us / 1000) | 0) + ' ms', k));
console.log('-- self');
const total = [...self.values()].reduce((a, b) => a + b, 0);
console.log('total ms', (total / 1000) | 0);
[...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25).forEach(([k, us]) => console.log(((us / 1000) | 0) + ' ms', k));
await browser.close();
