import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { chromium } from 'playwright-core';
const root = resolve('dist');
const server = createServer(async (req, res) => {
  const file = resolve(root, '.' + new URL(req.url, 'http://localhost').pathname.replace(/\/$/, '/index.html'));
  if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
  try {
    const bytes = await readFile(file);
    res.setHeader('content-type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
    res.end(bytes);
  } catch { res.writeHead(404).end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
const base = process.env.SHOWCASE_URL || `http://127.0.0.1:${server.address().port}/`;
let browser;
try {
  browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(10000);
  const errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => requests.push({ url: r.url(), method: r.method() }));
  await page.goto(base);
  await page.getByRole('button', { name: 'Check model', exact: true }).click();
  await page.getByTestId('verdict').filter({ hasText: 'ALL_CHECKS_PASSED' }).waitFor();
  assert.match(await page.getByTestId('verdict').innerText(), /INCONCLUSIVE/);
  assert.match(await page.getByTestId('coverage').innerText(), /complete: false/);
  await page.getByLabel('Sample model').selectOption('broken');
  await page.getByRole('button', { name: 'Check model', exact: true }).click();
  await page.getByTestId('verdict').filter({ hasText: 'INVALID' }).waitFor();
  assert.match(await page.getByTestId('findings').innerText(), /MVK_/);
  await page.getByLabel('DocumentModel JSON').fill('{');
  await page.getByRole('button', { name: 'Check model', exact: true }).click();
  assert.match(await page.getByTestId('diagnostics').innerText(), /SOURCE_NOT_WELL_FORMED/);
  await page.getByLabel('Sample model').selectOption('storefront');
  await page.getByLabel('Draft text').fill('[MissingField] > 0');
  await page.getByRole('button', { name: 'Check draft', exact: true }).click();
  await page.getByTestId('verdict').filter({ hasText: 'INVALID' }).waitFor();
  assert.match(await page.getByTestId('findings').innerText(), /MVK_/);
  assert.match(await page.getByTestId('changes').innerText(), /Introduced/);
  await page.getByLabel('Draft text').fill('[OrderTotal');
  await page.getByRole('button', { name: 'Check draft', exact: true }).click();
  assert.match(await page.locator('#raw').textContent(), /MVK_(?:EXPECTED_TOKEN_NOT_FOUND|INCOMPLETE_INPUT)/);
  const model = await page.getByLabel('DocumentModel JSON').inputValue();
  const networkBefore = requests.length;
  await page.getByLabel('Open local model').setInputFiles({ name: 'local.dm.json', mimeType: 'application/json', buffer: Buffer.from(model) });
  await page.locator('#file-status').filter({ hasText: 'Loaded locally: local.dm.json' }).waitFor();
  await page.getByRole('button', { name: 'Check model', exact: true }).click();
  await page.getByTestId('verdict').filter({ hasText: 'ALL_CHECKS_PASSED' }).waitFor();
  assert.deepEqual(requests.slice(networkBefore), [], 'local model reading/checking must make no network request');
  assert.ok(requests.every(r => r.method === 'GET' && r.url.startsWith(new URL(base).origin)), 'no uploads or third-party requests');
  assert.deepEqual(errors, []);
  await page.screenshot({ path: 'showcase-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: 'showcase-mobile.png', fullPage: true });
  console.log('PASS: scoped pass, broken reference, malformed JSON, draft attribution and incomplete input, local-file privacy, responsive layout and console');
} finally { if (browser) await browser.close(); await new Promise(done => server.close(done)); }
