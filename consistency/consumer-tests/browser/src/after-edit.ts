// The AFTER-EDIT boundary in ONE warm browser context, holding ONE analysis — H5-i's browser arm.
//
// A module with no static imports would be a SCRIPT rather than a module, which rejects top-level `await` and
// lets the DOM's `window.status` win over a local `status`; `export {}` and the renamed element below are for
// exactly that, as on the prepared page. The checker is imported dynamically because the import is what WARMS
// it, and it must complete before any clock starts.
//
// **The quantity is a keystroke off a HELD analysis against the stateless draft that answers identically.**
// `checkDraftJson` performs one admission, one preparation and two checks per call; a handle makes the same
// keystroke cost one check. This cell was unmeasurable until `snapshotOfJson` shipped on 2026-09-18 — there
// was no stateful entry point in JavaScript to hold anything with.
export {};

const statusElement = document.querySelector<HTMLElement>('#status');
const measurement = document.querySelector<HTMLElement>('#measurement');

if (!statusElement || !measurement) {
  throw new Error('the after-edit page must carry #status and #measurement');
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

interface EditReport {
  readonly introduced: readonly { code: string }[];
  readonly gaps: readonly { at: string | null; reason: string }[];
}

// The declaration an author would be typing into, and the text they are typing — identical to the JVM and
// Node arms' constants, so the three carriers measure the same edit rather than three edits sharing a name.
const AT = '/Calibration/RequireInput00000';
const MEMBER = 'errorCondition';
const DRAFTED = 'FieldNotFilled(Input00000) And FieldFilled(Input00001)';

try {
  const parameters = new URL(window.location.href).searchParams;
  const modelUrl = parameters.get('model');
  const pairs = Number(parameters.get('pairs') ?? '0');
  if (!modelUrl || !Number.isInteger(pairs) || pairs <= 0) {
    throw new Error('the after-edit page needs ?model=<url> and ?pairs=<positive integer>');
  }

  // Fetched BEFORE anything is timed, as every other arm reads its input before its clock: the boundary is
  // the checker's cost, never the transport's for the model.
  const response = await fetch(modelUrl);
  if (!response.ok) {
    throw new Error(`${modelUrl} returned HTTP ${response.status}`);
  }
  const text = await response.text();
  const entry = (JSON.parse(text) as { header?: { id?: unknown } }).header?.id;
  if (typeof entry !== 'string') {
    throw new Error('the synthesized model declares no header.id, so it can never be admitted');
  }

  const { checkDraftJson, checkModelJson, snapshotOfJson } = await import('a12-consistency');

  // Preflight 1 — the model is one the checker accepts, and the module-load cost is paid. A ratio taken over
  // a refused model would compare two rejection paths.
  const baseline = JSON.parse(checkModelJson(text, 'after-edit-browser-warmup')) as Report;
  const warmup = {
    status: baseline.status,
    complete: baseline.complete,
    rejecting: baseline.findings
      .filter((finding) => finding.justifiesRejection)
      .map((finding) => `${finding.code}@${finding.at}`)
      .sort(),
  };

  // Preflight 2 — the two calls agree BYTE for byte, and the draft actually reached the declaration. `gaps` is
  // what a refused draft reports, so its count separates "checked" from "declined to check".
  const held = snapshotOfJson([text], entry);
  const statefulWarm = held.draftJson(AT, MEMBER, DRAFTED);
  const statelessWarm = checkDraftJson(text, AT, MEMBER, DRAFTED);
  const warmEdit = JSON.parse(statefulWarm) as EditReport;
  const agreement = {
    identical: statefulWarm === statelessWarm,
    gaps: warmEdit.gaps.length,
    introduced: warmEdit.introduced.length,
    chars: statefulWarm.length,
  };

  // The pairs, INTERLEAVED: stateless then stateful within each pair, so drift over the campaign lands in both
  // terms and the median of within-pair ratios survives it. Each term reads the payload it produced, because a
  // call whose result nothing touches is the one thing a timing lane must never measure.
  const observations = [];
  let consumed = 0;
  for (let pair = 0; pair < pairs; pair += 1) {
    const statelessStarted = performance.now();
    consumed += checkDraftJson(text, AT, MEMBER, DRAFTED).length;
    const statelessFinished = performance.now();

    const statefulStarted = performance.now();
    consumed += held.draftJson(AT, MEMBER, DRAFTED).length;
    const statefulFinished = performance.now();

    observations.push({
      statelessMillis: statelessFinished - statelessStarted,
      statefulMillis: statefulFinished - statefulStarted,
    });
  }

  publish(
    'measured',
    { warmup, agreement, observations, consumed },
    `${observations.length} after-edit pairs off one held analysis`,
  );
} catch (error) {
  // Published as a failed measurement rather than thrown: an uncaught throw leaves `data-state` at `running`
  // and the driver then reports a timeout, which describes the harness instead of the defect.
  publish('failed', { error: String(error) }, String(error));
}
