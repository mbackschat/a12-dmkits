// The AFTER-EDIT boundary in one warm Node process — H5-i's plain-Node arm.
//
// [§8](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md)'s after-edit row asks what holding one analysis across
// many questions saves, and the owner's hard-acceptance line attaches its host list to every budget rather
// than to the cold row alone. This cell was unmeasurable until 2026-09-18: the quantity is a snapshot-reuse
// ratio, and no stateful surface was exported to JavaScript at all. `snapshotOfJson` changed that.
//
// **The quantity is the JVM lane's keystroke arm**, and only that arm: `CheckerAfterEditPerfTest` carries
// three and exactly one has a bar, because the other two sit at ~1.0 by construction. So this measures a
// draft answered from a HELD analysis against the stateless `checkDraftJson` that answers identically —
// `checkDraft` performs one admission, one preparation and two checks per call, where a snapshot makes the
// same keystroke cost one check.
//
// **Two preflights, neither of them ceremony.** A model the checker REJECTS would make every pair below a
// comparison of two rejection paths; two calls that DISAGREE would make the ratio a comparison of different
// answers rather than of two ways to reach one. The agreement preflight doubles as the warm-up, outside every
// clock — and it compares the two payloads BYTE for byte, which is available here and stronger than the JVM
// arm's object equality, because both calls render through the one wire-shape function.
//
// **One process for the whole run**, as the prepared arm does: warmth is the subject, so relaunching per pair
// would measure what this boundary excludes. It computes NO statistic — the ratified paired estimator lives in
// `PerfHarness`, and a second implementation here could drift from it silently.

import { readFileSync } from 'node:fs';

const [modelPath, pairsArgument] = process.argv.slice(2);
if (!modelPath || !pairsArgument) {
  process.stderr.write('usage: after-edit.mjs <model.json> <pairs>\n');
  process.exit(1);
}
const pairs = Number(pairsArgument);
if (!Number.isInteger(pairs) || pairs <= 0) {
  throw new Error(`pairs must be a positive integer, got ${pairsArgument}`);
}

const { checkDraftJson, checkModelJson, snapshotOfJson } = await import('a12-consistency');

const text = readFileSync(modelPath, 'utf8');
const entry = JSON.parse(text).header?.id;
if (typeof entry !== 'string') {
  throw new Error('the synthesized model declares no header.id, so it can never be admitted');
}

// The declaration an author would be typing into, and the text they are typing. Kept identical to the JVM
// lane's constants so the two carriers measure the same edit rather than two edits that happen to share a
// name; a path this model does not declare would make every draft below a REFUSAL, which is fast, uniform and
// measures nothing.
const AT = '/Calibration/RequireInput00000';
const MEMBER = 'errorCondition';
const DRAFTED = 'FieldNotFilled(Input00000) And FieldFilled(Input00001)';

// Preflight 1 — the model is one the checker accepts. Also the whole-model warm-up.
const baseline = JSON.parse(checkModelJson(text, 'after-edit-node-warmup'));
const rejecting = baseline.findings
  .filter((finding) => finding.justifiesRejection)
  .map((finding) => `${finding.code}@${finding.at}`)
  .sort();
process.stdout.write(
  `warmup status=${baseline.status} complete=${baseline.complete} rejecting=${rejecting.join(',')}\n`,
);

// Preflight 2 — the two calls agree, and the draft actually reached the declaration. `gaps` is what a refused
// draft reports, so an empty-gaps assertion is what separates "checked" from "declined to check".
const held = snapshotOfJson([text], entry);
const statefulWarm = held.draftJson(AT, MEMBER, DRAFTED);
const statelessWarm = checkDraftJson(text, AT, MEMBER, DRAFTED);
const warmParsed = JSON.parse(statefulWarm);
process.stdout.write(
  `agreement identical=${statefulWarm === statelessWarm} gaps=${warmParsed.gaps.length}` +
    ` introduced=${warmParsed.introduced.length} chars=${statefulWarm.length}\n`,
);

// The REUSE counters, printed from the preflight rather than from a timed call so the timed quantity stays
// exactly `draftJson`. They are the discriminator this lane needs when the ratio goes the wrong way: a ratio
// above 1.0 with `familiesReused` at zero means the reuse is not happening, while the same ratio with the
// reuse demonstrably happening means its bookkeeping costs more than it saves on this carrier. Without them
// the two mechanisms are indistinguishable from the outside, and only one of them is a defect in the reuse.
const accounted = JSON.parse(held.draftWithReuseJson(AT, MEMBER, DRAFTED)).reuse;
process.stdout.write(
  `reuse preparationReused=${accounted.preparationReused}` +
    ` baselineReportReused=${accounted.baselineReportReused}` +
    ` familiesReused=${accounted.familiesReused}` +
    ` familiesRecomputed=${accounted.familiesRecomputed}\n`,
);

// The pairs, INTERLEAVED: stateless then stateful within each pair, so drift over the run lands in both terms
// and the median of within-pair ratios survives it. Each term reads the payload it produced, because a call
// whose result nothing touches is the one thing a timing lane must never measure.
let consumed = 0;
for (let pair = 0; pair < pairs; pair += 1) {
  const statelessStarted = process.hrtime.bigint();
  consumed += checkDraftJson(text, AT, MEMBER, DRAFTED).length;
  const statelessNanos = process.hrtime.bigint() - statelessStarted;

  const statefulStarted = process.hrtime.bigint();
  consumed += held.draftJson(AT, MEMBER, DRAFTED).length;
  const statefulNanos = process.hrtime.bigint() - statefulStarted;

  process.stdout.write(`observation stateless=${statelessNanos} stateful=${statefulNanos}\n`);
}
process.stdout.write(`consumed chars=${consumed}\n`);
