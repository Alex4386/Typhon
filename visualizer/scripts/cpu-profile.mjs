// Debug helper: CPU-profiles the visualizer main thread for a while and prints the functions with
// the most self time (and their callers' totals), to find what keeps the page busy.
// Usage: node scripts/cpu-profile.mjs [url] [--wait S] [--seconds S] [--top N]
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const url = argv.find((a) => a.startsWith('http')) ?? 'http://localhost:8790/?renderer=webgl&debug';
const wait = Number(opt('--wait', 10));
const seconds = Number(opt('--seconds', 15));
const top = Number(opt('--top', 30));

const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => console.log('pageerror', e.message));
const cdp = await page.context().newCDPSession(page);
await page.goto(url, { waitUntil: 'commit' });
await page.waitForTimeout(wait * 1000);
await cdp.send('Profiler.enable');
await cdp.send('Profiler.setSamplingInterval', { interval: 200 });
await cdp.send('Profiler.start');
await page.waitForTimeout(seconds * 1000);
const { profile } = await cdp.send('Profiler.stop');

const byId = new Map(profile.nodes.map((n) => [n.id, n]));
const parent = new Map();
for (const n of profile.nodes) for (const c of n.children ?? []) parent.set(c, n.id);
const self = new Map();
const dts = profile.timeDeltas;
for (let i = 0; i < profile.samples.length; i++) self.set(profile.samples[i], (self.get(profile.samples[i]) ?? 0) + (dts[i] ?? 0) / 1000);
const name = (n) => `${n.callFrame.functionName || '(anon)'} ${n.callFrame.url.split('/').pop()}:${n.callFrame.lineNumber}:${n.callFrame.columnNumber}`;
const selfBy = new Map();
const totalBy = new Map();
for (const [id, ms] of self) {
  const n = byId.get(id);
  selfBy.set(name(n), (selfBy.get(name(n)) ?? 0) + ms);
  const seen = new Set();
  for (let p = id; p !== undefined; p = parent.get(p)) {
    const k = name(byId.get(p));
    if (seen.has(k)) continue;
    seen.add(k);
    totalBy.set(k, (totalBy.get(k) ?? 0) + ms);
  }
}
const total = [...self.values()].reduce((a, b) => a + b, 0);
console.log(`profiled ${seconds}s, sampled ${total.toFixed(0)} ms`);
console.log('--- self ---');
for (const [k, v] of [...selfBy].sort((a, b) => b[1] - a[1]).slice(0, top)) console.log(v.toFixed(0).padStart(7), k);
console.log('--- total ---');
for (const [k, v] of [...totalBy].sort((a, b) => b[1] - a[1]).slice(0, top)) console.log(v.toFixed(0).padStart(7), k);
await browser.close();
