// What every browser SAMPLER in this project needs: a deadline, a static server, a Chrome, one observation.
//
// Extracted when the second sampler arrived. The cold arm (`cold.ts`) and the prepared arm (`prepared.ts`)
// differ in exactly two ways — one fresh context per observation against one warm context for the whole run,
// and which page they navigate to — while the ~120 lines around that were identical: the deadline wrapper, the
// no-store static server, the system-Chrome search and the shutdown that must never decide a verdict. Copying
// them would have put the machine-specific half (the executable search) in two places, which is the half
// nobody notices is wrong until a different machine runs it.
//
// `interpreter/consumer-tests/browser/scripts/harness.ts` is the same idea in the sibling project and owns the
// shutdown-deadline measurement both of them rest on. Each consumer project is staged standalone, so the two
// are deliberately separate files rather than a shared package.

import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { access, readFile, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve, sep } from 'node:path';
import process from 'node:process';

import { chromium, type Browser, type Page } from 'playwright-core';

export const DEADLINE_MILLIS = 120_000;
export const SHUTDOWN_DEADLINE_MILLIS = 30_000;

const distribution = resolve('dist');

export const withDeadline = async <T>(
  label: string,
  operation: PromiseLike<T>,
  deadlineMillis = DEADLINE_MILLIS,
): Promise<T> => {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<T>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error(`${label} exceeded ${deadlineMillis} ms`)),
          deadlineMillis,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) {
      clearTimeout(timeout);
    }
  }
};

const contentType = (path: string): string => {
  if (path.endsWith('.html')) return 'text/html; charset=utf-8';
  if (path.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (path.endsWith('.json')) return 'application/json; charset=utf-8';
  if (path.endsWith('.css')) return 'text/css; charset=utf-8';
  if (path.endsWith('.map')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
};

const serve = async (request: IncomingMessage, response: ServerResponse): Promise<void> => {
  try {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const relative = decodeURIComponent(url.pathname.slice(1)) || 'index.html';
    const candidate = resolve(distribution, relative);
    if (candidate !== distribution && !candidate.startsWith(`${distribution}${sep}`)) {
      response.writeHead(400).end();
      return;
    }
    const metadata = await stat(candidate);
    if (!metadata.isFile()) {
      response.writeHead(404).end();
      return;
    }
    // No-store on every asset, so a context that somehow shared a cache still refetches. Belt and braces
    // beside the fresh context the cold arm uses, and free: this server exists only for these measurements.
    response.writeHead(200, { 'content-type': contentType(candidate), 'cache-control': 'no-store' });
    response.end(await readFile(candidate));
  } catch {
    response.writeHead(404).end();
  }
};

const executableCandidates = [
  process.env['CHROME_BIN'],
  process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'],
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter((candidate): candidate is string => candidate !== undefined && candidate.length > 0);

const chromeExecutable = async (): Promise<string> => {
  for (const candidate of executableCandidates) {
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Try the next explicit system-browser location.
    }
  }
  // A failure and never a skip: a browser measurement taken without a browser is worse than none.
  throw new Error('Chrome/Chromium is unavailable; set CHROME_BIN to an executable system browser');
};

export const withStaticServer = async <T>(operation: (url: string) => Promise<T>): Promise<T> => {
  const server = createServer((request, response) => {
    void serve(request, response);
  });
  try {
    await withDeadline(
      'static server startup',
      new Promise<void>((resolveListen, rejectListen) => {
        server.once('error', rejectListen);
        server.listen(0, '127.0.0.1', () => resolveListen());
      }),
      30_000,
    );
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('static server did not expose an IPv4 port');
    }
    return await operation(`http://127.0.0.1:${address.port}/`);
  } finally {
    if (server.listening) {
      await withDeadline(
        'static server shutdown',
        new Promise<void>((resolveClose, rejectClose) => {
          server.close((error) => (error ? rejectClose(error) : resolveClose()));
        }),
        30_000,
      );
    }
  }
};

/**
 * Run `body` against one system Chrome, and never let teardown decide the verdict.
 *
 * A slow exit after every observation has been printed is not a failure of the measurement, so the shutdown
 * deadline reports and moves on. The browser BUILD is handed to `body` because these numbers describe Chrome
 * rather than the node process driving it, and a figure with no build in the record cannot be compared against
 * a later one.
 */
export const withChrome = async <T>(body: (browser: Browser, version: string) => Promise<T>): Promise<T> => {
  const browser = await chromium.launch({ executablePath: await chromeExecutable(), headless: true });
  try {
    return await body(browser, browser.version());
  } finally {
    try {
      await withDeadline('Chrome shutdown', browser.close(), SHUTDOWN_DEADLINE_MILLIS);
    } catch (error) {
      process.stderr.write(`${String(error)} — the browser is still exiting; sampling already completed\n`);
    }
  }
};

/**
 * Navigate a fresh page to `url`, wait for the page to declare itself finished, and return what it published.
 *
 * The wait is on `#status` leaving `data-state=running`, never on a timeout expiring: a page that never
 * finished must not read as a fast one. A page error is raised rather than ignored, because a published
 * measurement taken beside a thrown exception describes neither.
 *
 * **`waitMillis` is explicit because Playwright's own locator timeout is 30 s and fires FIRST.** The outer
 * `withDeadline` looked like the bound and was not — measured when the prepared arm's page, which runs its
 * whole ~100 s campaign before reporting once, failed with `locator.waitFor: Timeout 30000ms exceeded` while
 * the cold arm (under a second per observation) had never come close to it. A wrapper that cannot fire before
 * the thing it wraps is not a deadline, so the locator gets the real number and the wrapper stays strictly
 * looser as a backstop.
 */
export const published = async (
  page: Page,
  url: string,
  what: string,
  waitMillis = DEADLINE_MILLIS,
): Promise<unknown> => {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  const response = await withDeadline('browser navigation', page.goto(url, { waitUntil: 'load' }));
  if (!response?.ok()) {
    throw new Error(`navigation to ${url} failed with HTTP ${response?.status()}`);
  }
  await withDeadline(
    what,
    page.locator('#status:not([data-state=running])').waitFor({ timeout: waitMillis }),
    waitMillis + 30_000,
  );
  const text = await page.locator('#measurement').textContent();
  if (pageErrors.length > 0) {
    throw new Error(`browser page error: ${pageErrors.join('; ')}`);
  }
  const parsed: unknown = JSON.parse(text ?? 'null');
  if (!parsed || typeof parsed !== 'object') {
    throw new Error(`the page published ${JSON.stringify(text)} instead of a measurement`);
  }
  return parsed;
};
