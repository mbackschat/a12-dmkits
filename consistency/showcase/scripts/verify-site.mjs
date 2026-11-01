import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { chromium } from 'playwright-core';
const root = resolve('../checker-presentation-preview/site');
const server = createServer(async (req, res) => {
  let file = resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
  if (file !== root && !file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
  try {
    if ((await stat(file)).isDirectory()) file = resolve(file, 'index.html');
    res.setHeader('content-type', file.endsWith('.html') ? 'text/html' : file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'image/avif');
    res.end(await readFile(file));
  } catch { res.writeHead(404).end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
let browser;
try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const page = await browser.newPage(); page.setDefaultTimeout(10000);
  const failures = []; page.on('pageerror', e => failures.push(e.message)); page.on('response', r => { if (r.status() >= 400) failures.push(`${r.status()} ${r.url()}`); });
  const base = `http://127.0.0.1:${server.address().port}`;
  for (const name of ['index.html', 'guide.html', 'privacy.html']) {
    await page.goto(`${base}/${name}`);
    assert.ok(await page.locator('h1').isVisible());
    assert.ok(!(await page.locator('body').innerText()).includes('@VERSION@'));
    const links = await page.locator('a[href]').evaluateAll(nodes => nodes.map(n => n.href));
    for (const href of links.filter(url => url.startsWith(base))) {
      const response = await page.request.get(href); assert.equal(response.status(), 200, href);
    }
    if (name === 'index.html') {
      await page.locator('summary').click();
      for (const img of await page.locator('img').all()) { await img.scrollIntoViewIfNeeded(); await img.evaluate(node => node.decode()); }
      await page.screenshot({ path: 'landing-desktop.png', fullPage: true });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, name);
    if (name === 'index.html') await page.screenshot({ path: 'landing-mobile.png', fullPage: true });
    await page.setViewportSize({ width: 1280, height: 800 });
  }
  assert.deepEqual(failures, []);
  console.log('PASS: landing, guide, privacy, local links, AVIF decoding, mobile overflow and console');
} finally { if (browser) await browser.close(); await new Promise(done => server.close(done)); }
