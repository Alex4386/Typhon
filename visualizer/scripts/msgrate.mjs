// Debug helper: samples the app's received-message counters over time.
import { chromium } from 'playwright';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
const page = await browser.newPage({ viewport: { width: 800, height: 500 } });
await page.goto(process.argv[2] ?? 'http://localhost:5180/?renderer=webgl');
for (let k = 0; k < 6; k++) {
  await page.waitForTimeout(2000);
  console.log(k * 2 + 2, 's', JSON.stringify(await page.evaluate(() => window.__typhonMessages)));
}
await browser.close();
