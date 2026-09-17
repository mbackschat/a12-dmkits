// One cold check in a browsing context that has never loaded the checker — H5's cold boundary, browser arm.
//
// §8's cold row names three hosts and this is the third: "installed package load through preparation and first
// complete consistency result, on JVM, plain Node and browser". The Node arm covers an `npm install`ed tarball
// under Node's own loader; a browser substitutes a bundler, an HTTP transfer and a document for all three of
// those, and none of it is observable from the other arms.
//
// **The import is DYNAMIC, and that is the whole reason this page exists separately.** A static import would be
// hoisted into the entry chunk, so the module would already be evaluated before any clock started and the
// figure would describe a warm call — the same trap the Node arm avoids for the same reason. A dynamic import
// makes Rollup emit the checker as its own chunk, which this page then fetches and evaluates inside the timed
// region. `moduleMillis` is reported separately precisely so that a build which inlined the chunk anyway is
// visible as a near-zero load rather than passing silently.
//
// **What a fresh context does and does not buy.** The driver opens a new Playwright context per observation, so
// the module registry and the HTTP cache start empty. It does not reset Chrome's own compilation caches or the
// OS page cache, and no claim is made that it does; the quantity under observation is a first result in a fresh
// context, which is what a browser consumer's first page view actually pays.

// `export {}` makes this file a MODULE rather than a script, which is load-bearing twice over and not
// cosmetic: a script may not use top-level `await`, and its top-level `const`s share the global scope — where
// `status` collides with `window.status` and the DOM lib's declaration wins. The replay page needs neither
// because its static imports already make it a module; this page has none, the checker being imported
// dynamically on purpose.
export {};

const statusElement = document.querySelector<HTMLElement>('#status');
const measurement = document.querySelector<HTMLElement>('#measurement');

if (!statusElement || !measurement) {
  throw new Error('the cold page must carry #status and #measurement');
}

const publish = (state: string, payload: unknown, summary: string): void => {
  measurement.textContent = JSON.stringify(payload, null, 2);
  statusElement.textContent = summary;
  statusElement.dataset['state'] = state;
};

try {
  const requested = new URL(window.location.href).searchParams.get('model');
  if (!requested) {
    throw new Error('the cold page needs ?model=<url> naming the model to check');
  }
  // Fetched BEFORE the clock starts, as both other arms read their file before theirs: the boundary is the
  // checker's cost, not the transport's for the input.
  const response = await fetch(requested);
  if (!response.ok) {
    throw new Error(`${requested} returned HTTP ${response.status}`);
  }
  const text = await response.text();

  const started = performance.now();
  const { checkModelJson } = await import('a12-consistency');
  const loaded = performance.now();
  const reportText = checkModelJson(text);
  const finished = performance.now();

  // Parsed OUTSIDE the timed region, matching the Node arm: the package's output boundary is the report TEXT,
  // and the JVM arm's quantity ends at a report object it never serialized. The exclusion is fixed, so it
  // cannot move a ratio.
  const report = JSON.parse(reportText) as {
    findings: readonly { code: string; at: string | null; justifiesRejection: boolean }[];
    status: string;
    complete: boolean;
  };
  const rejecting = report.findings
    .filter((finding) => finding.justifiesRejection)
    .map((finding) => `${finding.code}@${finding.at}`)
    .sort();

  publish(
    'measured',
    {
      model: requested,
      modelBytes: text.length,
      // Module load and check, split rather than summed: their sum is the boundary's quantity, and the split
      // is what tells a transfer cost apart from the checker's own work when a figure surprises someone.
      moduleMillis: loaded - started,
      checkMillis: finished - started,
      reportStatus: report.status,
      reportComplete: report.complete,
      rejecting,
    },
    `cold first result in ${(finished - started).toFixed(2)} ms`,
  );
} catch (error) {
  // Published as a failed measurement rather than thrown: an uncaught throw leaves `data-state` at `running`
  // and the driver then reports a timeout, which describes the harness instead of the defect.
  publish('failed', { error: String(error) }, String(error));
}
