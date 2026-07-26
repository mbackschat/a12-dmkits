import {createServer} from "node:http";
import {access, readFile, stat} from "node:fs/promises";
import {constants} from "node:fs";
import {resolve, sep} from "node:path";
import process from "node:process";

import {chromium} from "playwright-core";

const PAGES_BASE = "/a12-interpreter-releases/showcase/";
const DEADLINE_MILLIS = 10_000;
const distribution = resolve("dist");

const withDeadline = async (label, operation) => {
  let timeout;
  try {
    return await Promise.race([
      operation,
      new Promise((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error(`${label} exceeded ${DEADLINE_MILLIS} ms`)),
          DEADLINE_MILLIS,
        );
      }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
};

const contentType = (path) => {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".map")) return "application/json; charset=utf-8";
  return "application/octet-stream";
};

const serve = async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (!url.pathname.startsWith(PAGES_BASE)) {
      response.writeHead(404).end();
      return;
    }
    const relative = decodeURIComponent(url.pathname.slice(PAGES_BASE.length)) ||
      "index.html";
    const candidate = resolve(distribution, relative);
    if (candidate !== distribution &&
        !candidate.startsWith(`${distribution}${sep}`)) {
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
].filter(Boolean);

const chromeExecutable = async () => {
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

const server = createServer((request, response) => {
  void serve(request, response);
});
let browser;
try {
  await withDeadline(
    "static server startup",
    new Promise((resolveListen, rejectListen) => {
      server.once("error", rejectListen);
      server.listen(0, "127.0.0.1", resolveListen);
    }),
  );
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("static server did not expose an IPv4 port");
  }
  browser = await withDeadline(
    "Chrome launch",
    chromium.launch({
      executablePath: await chromeExecutable(),
      headless: true,
    }),
  );
  const page = await withDeadline("browser page creation", browser.newPage());
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  const url = `http://127.0.0.1:${address.port}${PAGES_BASE}`;
  const response = await withDeadline(
    "browser navigation",
    page.goto(url, {waitUntil: "networkidle"}),
  );
  if (!response?.ok()) {
    throw new Error(`browser navigation failed with HTTP ${response?.status()}`);
  }
  await withDeadline(
    "interpreter browser execution",
    page.locator("#status[data-state=passed]").waitFor(),
  );
  if (new URL(page.url()).pathname !== PAGES_BASE) {
    throw new Error(`browser escaped the Pages base path: ${page.url()}`);
  }
  if (pageErrors.length > 0) {
    throw new Error(`browser page error: ${pageErrors.join("; ")}`);
  }
  console.log(await page.locator("#status").textContent());
} finally {
  if (browser) {
    await withDeadline("Chrome shutdown", browser.close());
  }
  await withDeadline(
    "static server shutdown",
    new Promise((resolveClose, rejectClose) => {
      server.close((error) => error ? rejectClose(error) : resolveClose());
    }),
  );
}
