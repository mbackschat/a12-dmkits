// The SAMPLER for H5's browser cold arm: launch Chrome once, take one observation per fresh browsing context,
// print the raw numbers. It computes no statistic and asserts no bar.
//
// **That split is deliberate and it is the reason this file is short.** The ratified paired estimator — the
// median of within-pair ratios over interleaved scales — lives in `PerfHarness` and is reused by every other
// lane in this project; a second implementation here would be a copy of a ratified instrument, free to drift
// from it silently. So the sampler's contract is only: visit the scales in the ORDER the caller asked for, one
// fresh context each, and emit one line per observation. The owning `:adapter` lane groups and medians them.
//
// **Chrome is launched once, the CONTEXT is fresh per observation**, which is where the cold claim actually
// lives: a new context starts with an empty module registry and an empty HTTP cache, while relaunching the
// browser 100 times would add a second or two of process startup to every sample and measure Chrome's
// bootstrap instead of the checker's. What a fresh context does not reset is stated on the page itself.
//
// The static server and the Chrome-executable search are the replay driver's, repeated for the reason that file
// gives — each consumer project is staged standalone — and `interpreter/consumer-tests/browser/scripts/harness.ts`
// owns the shutdown-deadline measurement both of them point at.

import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { access, readFile, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve, sep } from 'node:path';
import process from 'node:process';

import { chromium, type Browser } from 'playwright-core';

const DEADLINE_MILLIS = 120_000;
const SHUTDOWN_DEADLINE_MILLIS = 30_000;

const distribution = resolve('dist');

interface Measurement {
  readonly model: string;
  readonly modelBytes: number;
  readonly moduleMillis: number;
  readonly checkMillis: number;
  readonly reportStatus: string;
  readonly reportComplete: boolean;
  readonly rejecting: readonly string[];
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
    // beside the fresh context, and free: this server exists only for this measurement.
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

/** One observation: a brand-new context, one navigation, one published measurement. */
const observe = async (base: string, browser: Browser, model: string): Promise<Measurement> => {
  const context = await withDeadline('browser context creation', browser.newContext());
  try {
    const page = await withDeadline('browser page creation', context.newPage());
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));
    const url = `${base}cold.html?model=${encodeURIComponent(model)}`;
    const response = await withDeadline('browser navigation', page.goto(url, { waitUntil: 'load' }));
    if (!response?.ok()) {
      throw new Error(`navigation to ${url} failed with HTTP ${response?.status()}`);
    }
    await withDeadline(
      'the packed checker running in the browser',
      page.locator('#status:not([data-state=running])').waitFor(),
    );
    const published = await page.locator('#measurement').textContent();
    if (pageErrors.length > 0) {
      throw new Error(`browser page error: ${pageErrors.join('; ')}`);
    }
    const parsed: unknown = JSON.parse(published ?? 'null');
    if (!parsed || typeof parsed !== 'object' || !('checkMillis' in parsed)) {
      throw new Error(`the cold page published ${JSON.stringify(published)} instead of a measurement`);
    }
    return parsed as Measurement;
  } finally {
    await context.close();
  }
};

// argv: the models to visit, IN ORDER — the caller interleaves, because the caller owns the estimator.
const models = process.argv.slice(2);
if (models.length === 0) {
  process.stderr.write('usage: cold.js <model-url> [<model-url> …] — visited in the given order\n');
  process.exit(1);
}

const browser = await chromium.launch({ executablePath: await chromeExecutable(), headless: true });
try {
  // The runtime these numbers describe is CHROME, not the node process driving it, so every observation
  // carries the browser build that produced it. H5's per-number gate asks for a pinned runtime; a system
  // browser cannot be pinned from here and the project uses the system one deliberately, so this arm closes
  // that point by ATTRIBUTION alone: recording the build is what keeps a future Chrome update from silently
  // re-basing the published figures with nothing in the record naming which build they came from.
  //
  // ONE launch, so one build: this value is deliberately read once and stamped on every line, because every
  // context comes from the same browser and no input can make two of them differ. An earlier version had the
  // lane "refuse a set spanning two" builds, which was a check that could not fail for any input this sampler
  // can produce — a guard by appearance only. Multiplicity is not a risk here; a MISSING version is, so the
  // lane checks for that instead.
  const browserVersion = browser.version();
  await withStaticServer(async (base) => {
    for (const model of models) {
      const measured = await observe(base, browser, model);
      // One line per observation, in visit order. `:adapter` parses these; keep the shape stable.
      process.stdout.write(
        `observation model=${measured.model} bytes=${measured.modelBytes}` +
          ` moduleMillis=${measured.moduleMillis} checkMillis=${measured.checkMillis}` +
          ` status=${measured.reportStatus} complete=${measured.reportComplete}` +
          ` browserVersion=${browserVersion}` +
          ` rejecting=${measured.rejecting.join(',')}\n`,
      );
    }
  });
} finally {
  try {
    await withDeadline('Chrome shutdown', browser.close(), SHUTDOWN_DEADLINE_MILLIS);
  } catch (error) {
    // Teardown never decides the verdict — every observation was printed before this ran.
    process.stderr.write(`${String(error)} — the browser is still exiting; sampling already completed\n`);
  }
}
