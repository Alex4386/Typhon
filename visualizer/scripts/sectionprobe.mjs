// Debug helper: requests a cross-section through the UI and reports what came back.
import { chromium } from 'playwright';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
page.setDefaultTimeout(120000);
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(process.env.URL ?? 'http://localhost:5180/?renderer=webgl');
await page.waitForFunction(() => (window.__typhonMessages?.clock ?? 0) > 1, null, { timeout: 120000 });
await page.evaluate(() => window.__typhon.getState().set({ sectionPolyline: [[-4000, 1500], [4500, -3000]] }));
await page.waitForTimeout(2000);
const btn = page.getByRole('button', { name: 'Cut' }).first();
console.log('cut button:', await btn.textContent(), 'disabled:', await btn.isDisabled());
await btn.dispatchEvent('click');
for (let k = 0; k < 8; k++) {
  await page.waitForTimeout(5000);
  console.log((k + 1) * 5, 's', await page.evaluate(() => JSON.stringify({ section: window.__typhonMessages.section ?? 0, binary: window.__typhonMessages.binary, clock: window.__typhonMessages.clock, pending: window.__typhon.getState().sectionPending })));
}
console.log(
  await page.evaluate(() => {
    const s = window.__typhon.getState();
    return JSON.stringify({ pending: s.sectionPending, section: s.section && { nu: s.section.nu, nz: s.section.nz, units: s.section.meta.units.length }, errors: s.errors, msgs: window.__typhonMessages });
  }),
);
await browser.close();
