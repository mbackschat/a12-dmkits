// The PREPARED boundary in ONE warm browser context — H5-h's browser arm, §8's prepared row.
//
// A module with no static imports would be a SCRIPT rather than a module, which rejects top-level `await` and
// lets the DOM's `window.status` win over a local `status`; `export {}` and the renamed element below are for
// exactly that, as on the cold page. The checker is imported dynamically here too, but for a different reason:
// the import is what WARMS it, and it must complete before any clock starts.
export {};

const statusElement = document.querySelector<HTMLElement>('#status');
const measurement = document.querySelector<HTMLElement>('#measurement');

if (!statusElement || !measurement) {
  throw new Error('the prepared page must carry #status and #measurement');
}

const publish = (state: string, payload: unknown, summary: string): void => {
  measurement.textContent = JSON.stringify(payload);
  statusElement.textContent = summary;
  statusElement.dataset['state'] = state;
};

interface Report {
  readonly findings: readonly { code: string; at: string | null; justifiesRejection: boolean }[];
  readonly status: string;
  readonly complete: boolean;
}

try {
  const parameters = new URL(window.location.href).searchParams;
  const order = (parameters.get('order') ?? '').split(',').filter((entry) => entry.length > 0).map(Number);
  const sources = parameters.getAll('model');
  if (order.length === 0 || sources.length === 0) {
    throw new Error('the prepared page needs ?model=<scale>@<url> (repeatable) and ?order=<scale,scale,…>');
  }

  // Fetched BEFORE anything is timed, as every other arm reads its input before its clock: the boundary is the
  // checker's cost, never the transport's for the model.
  const texts = new Map<number, string>();
  for (const source of sources) {
    const at = source.indexOf('@');
    if (at < 0) {
      throw new Error(`?model must be <scale>@<url>, got ${source}`);
    }
    const scale = Number(source.slice(0, at));
    const url = source.slice(at + 1);
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`${url} returned HTTP ${response.status}`);
    }
    texts.set(scale, await response.text());
  }

  const { checkModelJson } = await import('a12-consistency');

  // The warm-up IS the preflight, the arrangement the JVM lane uses: one full check per scale settles the
  // module-load question (which belongs to the COLD page) and the silence question (a ratio taken over a model
  // the checker refuses would compare rejection costs) in one pass, outside every clock.
  const warmups = [];
  for (const [scale, text] of texts) {
    const report = JSON.parse(checkModelJson(text, `prepared-warmup-${scale}`)) as Report;
    warmups.push({
      scale,
      status: report.status,
      complete: report.complete,
      rejecting: report.findings
        .filter((finding) => finding.justifiesRejection)
        .map((finding) => `${finding.code}@${finding.at}`)
        .sort(),
    });
  }

  const observations = [];
  for (const scale of order) {
    const text = texts.get(scale);
    if (text === undefined) {
      throw new Error(`no model supplied for ${scale} programs`);
    }
    const started = performance.now();
    const reportText = checkModelJson(text, `prepared-${scale}`);
    const finished = performance.now();
    // Read after the clock, which keeps the call live without charging the parse to the measurement — and
    // `reportChars` is the discriminator for the in-call serialization, the same integer the Node arm reports.
    observations.push({
      scale,
      checkMillis: finished - started,
      findings: (JSON.parse(reportText) as Report).findings.length,
      reportChars: reportText.length,
    });
  }

  publish(
    'measured',
    { warmups, observations },
    `${observations.length} prepared observations in one warm context`,
  );
} catch (error) {
  // Published as a failed measurement rather than thrown: an uncaught throw leaves `data-state` at `running`
  // and the driver then reports a timeout, which describes the harness instead of the defect.
  publish('failed', { error: String(error) }, String(error));
}
