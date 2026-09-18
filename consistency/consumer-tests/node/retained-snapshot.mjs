// H5-j's plain-Node RETENTION arm: what holding one analysis costs a Node consumer's heap.
//
// [§8](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) asks the resources boundary for retained memory AND
// release behaviour. Its sibling `retained-memory.mjs` answers release — a heap flat across repeated stateless
// checks — because when that arm landed the shipped surface was string-in string-out and a Node consumer could
// hold no analysis. `snapshotOfJson` shipped a holdable handle on 2026-09-18, so the JVM arm's own subject
// (`CheckerRetainedMemoryPerfTest`: post-GC heap with a snapshot held live) is reachable here, and this is it.
//
// **Two scales, because the quantity is a GROWTH ratio and a single byte count is unreadable.** Whether 24 MB
// is a lot depends entirely on the model, so the JVM arm reports retained bytes at N and 4N and the ratio
// between them; this arm reports the same pair on the same synthesized shapes. It computes no statistic and
// asserts no bound — per H5's ordering rule the collector comes first and the bar is the owner's to ratify
// (IF137 point 6), and a bound read off this lane's own first output is what that rule forbids.
//
// **Three gates void a reading rather than weakening it**, the same posture both siblings take: explicit
// collection must observably reclaim, the model must be one the checker ACCEPTS (a heap taken over a refused
// model measures the cost of rejecting), and a dropped handle must give its bytes back. The third is §8's
// release half asked of the retained object rather than of a call, and it is what separates "held analyses are
// expensive" from "held analyses are permanent".

import { readFileSync } from 'node:fs';

const modelPaths = process.argv.slice(2);
if (modelPaths.length !== 2) {
  process.stderr.write('usage: node --expose-gc retained-snapshot.mjs <small-model.json> <large-model.json>\n');
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

const { checkModelJson, snapshotOfJson } = await import('a12-consistency');

/**
 * Retained bytes of one held analysis, and what dropping it gives back.
 *
 * The baseline is read AFTER the handle's model text is already loaded, so the figure is the analysis and not
 * the string it was built from — the JVM arm draws the same line by holding the source outside its pair.
 */
function retentionOf(path, label) {
  const text = readFileSync(path, 'utf8');
  const entry = JSON.parse(text).header?.id;
  if (typeof entry !== 'string' || entry.length === 0) {
    throw new Error(`${label}: the synthesized model declares no header.id, so it can never be admitted`);
  }

  const baseline = settledHeapUsed();
  let handle = snapshotOfJson([text], entry);
  // The report is READ, which is what makes the handle fully realised: a snapshot whose lazy members were
  // never touched would under-report its own retention, and this lane would call that a small footprint.
  const reportChars = handle.reportJson().length;
  const withHeld = settledHeapUsed();
  handle = null;
  const afterRelease = settledHeapUsed();

  return { text, entry, baseline, withHeld, afterRelease, reportChars };
}

// The warm-up IS the preflight, as every sibling arm arranges it: one full check settles the module-loading
// cost (which belongs to the COLD boundary) and the silence question — a heap reading taken over a model the
// checker refuses would measure the cost of rejecting rather than of holding.
const warmupText = readFileSync(modelPaths[0], 'utf8');
const warmup = JSON.parse(checkModelJson(warmupText, 'retained-snapshot-warmup'));
const rejecting = warmup.findings
  .filter((finding) => finding.justifiesRejection)
  .map((finding) => `${finding.code}@${finding.at}`)
  .sort();
process.stdout.write(
  `warmup status=${warmup.status} complete=${warmup.complete} rejecting=${rejecting.join(',')}\n`,
);

for (const [index, path] of modelPaths.entries()) {
  const label = index === 0 ? 'small' : 'large';
  const measured = retentionOf(path, label);
  process.stdout.write(
    `observation scale=${label} modelBytes=${Buffer.byteLength(measured.text, 'utf8')}` +
      ` reportChars=${measured.reportChars} baseline=${measured.baseline}` +
      ` withHeld=${measured.withHeld} afterRelease=${measured.afterRelease}\n`,
  );
}
process.stdout.write(`nodeVersion=${process.version}\n`);
