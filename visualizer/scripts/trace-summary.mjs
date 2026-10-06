// Debug helper: records a Chrome trace of the visualizer and sums the top-level event durations per
// thread and event name (what the main thread, compositor and GPU threads spend their time on).
// Usage: node scripts/trace-summary.mjs [url] [--wait S] [--seconds S] [--size WxH]
import { chromium } from 'playwright';

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const url = argv.find((a) => a.startsWith('http')) ?? 'http://localhost:8790/?renderer=webgl&debug';
const [vw, vh] = String(opt('--size', '1440x900')).split('x').map(Number);
const browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: vw, height: vh } });
await page.goto(url, { waitUntil: 'commit' });
await page.waitForTimeout(Number(opt('--wait', 15)) * 1000);
await browser.startTracing(page, { categories: ['devtools.timeline', 'disabled-by-default-devtools.timeline', 'gpu', 'cc', 'viz', 'blink', 'v8'] });
await page.waitForTimeout(Number(opt('--seconds', 8)) * 1000);
const buf = await browser.stopTracing();
const trace = JSON.parse(buf.toString());
const events = trace.traceEvents ?? trace;
const threads = new Map();
for (const e of events) if (e.ph === 'M' && e.name === 'thread_name') threads.set(`${e.pid}:${e.tid}`, e.args.name);
const sums = new Map();
for (const e of events) {
  if (e.ph !== 'X' || !e.dur) continue;
  const t = threads.get(`${e.pid}:${e.tid}`) ?? `${e.pid}:${e.tid}`;
  const k = `${t} | ${e.name}`;
  sums.set(k, (sums.get(k) ?? 0) + e.dur / 1000);
}
for (const [k, v] of [...sums].sort((a, b) => b[1] - a[1]).slice(0, 40)) console.log(v.toFixed(0).padStart(8), k);
await browser.close();
