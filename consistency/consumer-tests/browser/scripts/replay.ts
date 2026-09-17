// The driver for F's browser arm: serve the built bundle, open it in a real system Chrome, and fail on any
// shared case the page reported as failed.
//
// Why a real browser and not jsdom or Karma. F's acceptance names the PACKED artifact, so the thing under test
// is the tarball a consumer installs, bundled the way a consumer bundles it. Karma would test the compiler's
// output instead (the reason `jsBrowserTest` stays disabled, measured in `consistency/build.gradle`), and a
// DOM emulator would not exercise a browser's module loader at all.
//
// Why a static server rather than `file://`. ES modules and `fetch` are both blocked on the file protocol, and
// the page needs both — the bundle is a module and the shared fixture is fetched.
//
// The Chrome-executable search and the slow-shutdown allowance are the ones this project deliberately does NOT
// re-derive: `interpreter/consumer-tests/browser/scripts/harness.ts` owns both, with the measurement for the
// shutdown deadline. This file repeats the mechanism because each consumer project is installed and staged
// standalone, and points at that file for the why rather than restating its numbers.

import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { access, readFile, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve, sep } from 'node:path';
import process from 'node:process';

import { chromium, type Browser } from 'playwright-core';

/**
 * The count the Node arm also guards: a corpus that silently emptied would report zero failures.
 *
 * It covers both shared suites — the named-field cases and the full-report parity corpus — because a
 * browser fetches each of them, and a 404 that resolved to an empty set is exactly what this catches.
 */
const MINIMUM_CASES = 12;
const DEADLINE_MILLIS = 30_000;
const SHUTDOWN_DEADLINE_MILLIS = 30_000;

const distribution = resolve('dist');

interface CaseOutcome {
  readonly name: string;
  readonly ok: boolean;
  readonly detail: string;
}

const withDeadline = async <T>(
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
    // Refuse a path that escapes the served root. The driver only ever asks for its own files, so this guards
    // against a future case fetching something it should not rather than against a remote caller.
    if (candidate !== distribution && !candidate.startsWith(`${distribution}${sep}`)) {
      response.writeHead(400).end();
      return;
    }
    const metadata = await stat(candidate);
    if (!metadata.isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { 'content-type': contentType(candidate) });
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
  // Deliberately a failure and never a skip: a browser arm that passes without a browser is the vacuity this
  // whole task exists to remove.
  throw new Error('Chrome/Chromium is unavailable; set CHROME_BIN to an executable system browser');
};

const closeChrome = async (browser: Browser): Promise<void> => {
  try {
    await withDeadline('Chrome shutdown', browser.close(), SHUTDOWN_DEADLINE_MILLIS);
  } catch (error) {
    // Teardown never decides the verdict — every case was read before this ran.
    process.stderr.write(`${String(error)} — the browser is still exiting; the replay already completed\n`);
  }
};

const withStaticServer = async <T>(operation: (url: string) => Promise<T>): Promise<T> => {
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
      );
    }
  }
};

const replay = async (url: string, browser: Browser): Promise<readonly CaseOutcome[]> => {
  const page = await withDeadline('browser page creation', browser.newPage());
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  const response = await withDeadline('browser navigation', page.goto(url, { waitUntil: 'networkidle' }));
  if (!response?.ok()) {
    throw new Error(`browser navigation failed with HTTP ${response?.status()}`);
  }
  await withDeadline(
    'the packed checker running in the browser',
    page.locator('#status:not([data-state=running])').waitFor(),
  );
  const published = await page.locator('#outcomes').textContent();
  if (pageErrors.length > 0) {
    throw new Error(`browser page error: ${pageErrors.join('; ')}`);
  }
  const results: unknown = JSON.parse(published ?? 'null');
  if (!Array.isArray(results)) {
    throw new Error(`the replay page published ${JSON.stringify(published)} instead of an outcome array`);
  }
  return results as readonly CaseOutcome[];
};

const browser = await chromium.launch({ executablePath: await chromeExecutable(), headless: true });
let results: readonly CaseOutcome[];
try {
  results = await withStaticServer((url) => replay(url, browser));
} finally {
  await closeChrome(browser);
}

if (results.length < MINIMUM_CASES) {
  process.stderr.write(`expected at least ${MINIMUM_CASES} shared cases, the page published ${results.length}\n`);
  process.exit(1);
}
const failed = results.filter((result) => !result.ok);
for (const result of results) {
  process.stdout.write(`${result.ok ? 'ok  ' : 'FAIL'} ${result.name}\n`);
  if (!result.ok) {
    process.stdout.write(`     ${result.detail}\n`);
  }
}
if (failed.length > 0) {
  process.stderr.write(`${failed.length} of ${results.length} shared case(s) failed in the browser\n`);
  process.exit(1);
}
process.stdout.write(`the packed checker replayed ${results.length} shared case(s) in Chrome\n`);
