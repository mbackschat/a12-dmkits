import {createServer} from "node:http";
import type {IncomingMessage, ServerResponse} from "node:http";
import {access, readFile, stat} from "node:fs/promises";
import {constants} from "node:fs";
import {resolve, sep} from "node:path";
import process from "node:process";

import {
  chromium,
  type Browser,
  type BrowserContext,
  type Page,
} from "playwright-core";

export const PAGES_BASE = "/a12-interpreter-releases/showcase/";
export const DEADLINE_MILLIS = 10_000;
export const CHROME_SHUTDOWN_DEADLINE_MILLIS = 30_000;

const distribution = resolve("dist");

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
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".map")) return "application/json; charset=utf-8";
  return "application/octet-stream";
};

const serve = async (
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> => {
  try {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (!url.pathname.startsWith(PAGES_BASE)) {
      response.writeHead(404).end();
      return;
    }
    const relative = decodeURIComponent(url.pathname.slice(PAGES_BASE.length)) ||
      "index.html";
    const candidate = resolve(distribution, relative);
    if (
      candidate !== distribution &&
      !candidate.startsWith(`${distribution}${sep}`)
    ) {
      response.writeHead(400).end();
      return;
    }
    const metadata = await stat(candidate);
    if (!metadata.isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, {"content-type": contentType(candidate)});
    response.end(await readFile(candidate));
  } catch {
    response.writeHead(404).end();
  }
};

const executableCandidates = [
  process.env.CHROME_BIN,
  process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
  "/usr/bin/google-chrome",
  "/usr/bin/google-chrome-stable",
  "/usr/bin/chromium",
  "/usr/bin/chromium-browser",
].filter((candidate): candidate is string =>
  candidate !== undefined && candidate.length > 0);

const chromeExecutable = async (): Promise<string> => {
  for (const candidate of executableCandidates) {
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Try the next explicit system-browser location.
    }
  }
  throw new Error(
    "Chrome/Chromium is unavailable; set CHROME_BIN to an executable system browser",
  );
};

export const launchChrome = async (): Promise<Browser> =>
  withDeadline(
    "Chrome launch",
    chromium.launch({
      executablePath: await chromeExecutable(),
      headless: true,
    }),
  );

export const closeChrome = async (browser: Browser): Promise<void> =>
  withDeadline(
    "Chrome shutdown",
    browser.close(),
    CHROME_SHUTDOWN_DEADLINE_MILLIS,
  );

export const withStaticServer = async <T>(
  operation: (url: string) => Promise<T>,
): Promise<T> => {
  const server = createServer((request, response) => {
    void serve(request, response);
  });
  try {
    await withDeadline(
      "static server startup",
      new Promise<void>((resolveListen, rejectListen) => {
        server.once("error", rejectListen);
        server.listen(0, "127.0.0.1", () => resolveListen());
      }),
    );
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("static server did not expose an IPv4 port");
    }
    return await operation(
      `http://127.0.0.1:${address.port}${PAGES_BASE}`,
    );
  } finally {
    if (server.listening) {
      await withDeadline(
        "static server shutdown",
        new Promise<void>((resolveClose, rejectClose) => {
          server.close((error) =>
            error ? rejectClose(error) : resolveClose());
        }),
      );
    }
  }
};

export const openCheckedPage = async (
  browser: Browser | BrowserContext,
  url: string,
): Promise<Page> => {
  const page = await withDeadline("browser page creation", browser.newPage());
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const response = await withDeadline(
    "browser navigation",
    page.goto(url, {waitUntil: "networkidle"}),
  );
  if (!response?.ok()) {
    throw new Error(`browser navigation failed with HTTP ${response?.status()}`);
  }
  await withDeadline(
    "interpreter browser execution",
    page.locator("#status:not([data-state=running])").waitFor(),
  );
  if (pageErrors.length > 0) {
    throw new Error(`browser page error: ${pageErrors.join("; ")}`);
  }
  return page;
};
