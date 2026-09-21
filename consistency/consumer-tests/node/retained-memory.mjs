// H5-j's plain-Node arm: §8's RELEASE behaviour, on the host that can answer it.
//
// [§8](../../../docs/archived/PORTABLE-CONSISTENCY-PROPOSAL.md) asks the resources boundary for "retained memory,
// release behavior, supported peak observations and installed/downloaded production closure size". The JVM arm
// (`CheckerRetainedMemoryPerfTest`) answers the first by holding a `ConsistencySnapshot` live and reading the
// post-GC heap. **A Node consumer cannot hold one**: the shipped `a12-consistency.d.mts` exports
// `checkModelJson` and `checkEditJson`, both taking and returning strings, so there is no analysis object and
// no cell store on this host — which is why the quantity here is the OTHER half of the same row. What a
// stateless surface can be asked is whether repeated use accumulates: a consumer's heap flat across many
// checks, or growing per call.
//
// It computes no statistic and asserts no bound. Per H5's ordering rule the collector comes first and the bar
// is the owner's to ratify (IF137 point 6); a flatness bound taken from this lane's own first output is what
// that rule forbids. Two readings of the same quantity at K and 2K checks are printed so the SHAPE of any
// growth is visible: a per-call leak makes the second delta match the first, while a one-off warm-up cost
// makes the second delta collapse.
//
// **The GC viability gate is not ceremony.** A post-GC reading on an engine that ignored the request is a
// reading of whatever happened to be uncollected, so the run refuses unless an explicit collection observably
// reclaims a known allocation — the same posture the JVM arm takes with `requireExplicitGcIsViable`.

import { readFileSync } from 'node:fs';

const [modelPath, checksArgument] = process.argv.slice(2);
if (!modelPath || !checksArgument) {
  process.stderr.write('usage: node --expose-gc retained-memory.mjs <model.json> <checks-per-phase>\n');
  process.exit(1);
}
const checksPerPhase = Number(checksArgument);
if (!Number.isInteger(checksPerPhase) || checksPerPhase <= 0) {
  process.stderr.write(`checks-per-phase must be a positive integer, got ${checksArgument}\n`);
  process.exit(1);
}

if (typeof globalThis.gc !== 'function') {
  process.stderr.write('no explicit gc: launch this child with --expose-gc, or its readings mean nothing\n');
  process.exit(1);
}

/** Collect until the heap stops shrinking, then read it — one collection can leave a generation behind. */
function settledHeapUsed() {
  let previous = Number.POSITIVE_INFINITY;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    globalThis.gc();
    const used = process.memoryUsage().heapUsed;
    if (used >= previous) {
      return used;
    }
    previous = used;
  }
  return previous;
}

// The viability gate: allocate something large enough to dwarf the noise, drop it, and require the collection
// to give most of it back. A run that cannot do this cannot answer the question it was launched for.
const gateBefore = settledHeapUsed();
let ballast = Array.from({ length: 1_000_000 }, (_, index) => ({ index }));
const gateHeld = process.memoryUsage().heapUsed;
ballast = null;
const gateAfter = settledHeapUsed();
const reclaimed = gateHeld - gateAfter;
const held = gateHeld - gateBefore;
if (held <= 0 || reclaimed < held * 0.5) {
  process.stderr.write(
    `explicit gc is not viable here: held=${held} reclaimed=${reclaimed} — a post-GC reading would be noise\n`,
  );
  process.exit(1);
}
process.stdout.write(`gate held=${held} reclaimed=${reclaimed}\n`);

const { checkModelJson } = await import('a12-consistency');
const text = readFileSync(modelPath, 'utf8');

// The warm-up IS the preflight, as every sibling arm arranges it: one full check settles the module-loading
// cost (which belongs to the COLD boundary) and the silence question — a heap reading taken over a model the
// checker refuses would measure the cost of rejecting rather than of checking.
const warmup = JSON.parse(checkModelJson(text, 'retained-warmup'));
const rejecting = warmup.findings
  .filter((finding) => finding.justifiesRejection)
  .map((finding) => `${finding.code}@${finding.at}`)
  .sort();
process.stdout.write(
  `warmup status=${warmup.status} complete=${warmup.complete} rejecting=${rejecting.join(',')}\n`,
);

/** [checks] checks whose reports are read and dropped, then the settled heap. */
function phase(label) {
  let characters = 0;
  for (let index = 0; index < checksPerPhase; index += 1) {
    // The report text is measured rather than ignored, which is what keeps the call live: a check whose
    // result nothing touches is work an optimizer may elide, and then this lane measures nothing at all.
    // A DISTINCT label per call, deliberately: it defeats any deduplication a later version might add, so
    // this stays the conservative direction for a leak test — identical calls could read flat because
    // something recognised them rather than because nothing was retained.
    characters += checkModelJson(text, `retained-${label}-${index}`).length;
  }
  return { heapUsed: settledHeapUsed(), characters };
}

const baseline = settledHeapUsed();
const first = phase('first');
const second = phase('second');

process.stdout.write(
  `observation checks=${checksPerPhase} modelBytes=${Buffer.byteLength(text, 'utf8')}` +
    ` reportChars=${first.characters / checksPerPhase}` +
    ` baseline=${baseline} afterFirst=${first.heapUsed} afterSecond=${second.heapUsed}\n`,
);
process.stdout.write(`nodeVersion=${process.version}\n`);
