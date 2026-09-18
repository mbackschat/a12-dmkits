// The PREPARED boundary in one warm Node process — H5-h's plain-Node arm, §8's prepared row.
//
// [§8](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md)'s prepared row asks for "initial analysis work and
// repeated same-snapshot queries reported separately", and the owner's hard-acceptance line attaches its host
// list to every budget rather than to the cold row alone. Until 2026-09-17 every prepared observation was on
// the JVM.
//
// **This is the same quantity `CheckerGrowthPerfTest` clocks on the JVM, deliberately.** That lane times
// `check(ModelSource(text))` on a runtime whose class loading and reflective setup a preflight already paid
// for — a repeated whole-model check, not a snapshot-reuse call. So the analogue here is `checkModelJson` after
// a full warm-up per scale. The analogue follows from WHAT the JVM lane clocks, not from what JavaScript
// exports, so it survived the surface widening: `snapshotOfJson` shipped on 2026-09-18, and a snapshot-reuse
// call is the AFTER-EDIT boundary's quantity (H5-i) rather than this row's.
//
// **One process for the whole run, where the cold arm pays a launch per observation.** Warmth is the subject
// here, so relaunching would measure exactly what this boundary excludes. The cost is that machine drift now
// lands inside one process rather than across many, which is what the interleaving below is for.
//
// It visits the scales in the ORDER the caller asked for and computes no statistic: the ratified paired
// estimator lives in `PerfHarness`, and a second implementation here could drift from it silently.

import { readFileSync } from 'node:fs';

const specifications = process.argv.slice(2);
if (specifications.length === 0) {
  process.stderr.write('usage: prepared-growth.mjs <programs>=<model.json> [...] then visit order on stdin\n');
  process.exit(1);
}

const { checkModelJson } = await import('a12-consistency');

// `<programs>=<path>` pairs, then the visit order as a comma-separated list of scales. Both on argv, because a
// child that read a plan off stdin would make a hung parent and a hung child look identical.
const order = specifications.pop().split(',').map(Number);
const texts = new Map(
  specifications.map((pair) => {
    const split = pair.indexOf('=');
    return [Number(pair.slice(0, split)), readFileSync(pair.slice(split + 1), 'utf8')];
  }),
);

// The warm-up IS the preflight, exactly as the JVM lane arranges it: one full check per scale settles both the
// class-loading question (which belongs to the COLD boundary) and the silence question (a ratio taken over a
// refused model would compare rejection costs). Outside every clock.
for (const [scale, text] of texts) {
  const report = JSON.parse(checkModelJson(text, `prepared-warmup-${scale}`));
  const rejecting = report.findings
    .filter((finding) => finding.justifiesRejection)
    .map((finding) => `${finding.code}@${finding.at}`)
    .sort();
  process.stdout.write(
    `warmup scale=${scale} status=${report.status} complete=${report.complete}` +
      ` rejecting=${rejecting.join(',')}\n`,
  );
}

for (const scale of order) {
  const text = texts.get(scale);
  if (text === undefined) {
    throw new Error(`no model text supplied for ${scale} programs`);
  }
  const started = process.hrtime.bigint();
  const reportText = checkModelJson(text, `prepared-${scale}`);
  const checkNanos = process.hrtime.bigint() - started;
  // The result is read, which is what keeps the call live: a check whose report nothing touches is work an
  // optimizer may elide, and a timing lane measuring dead code is the one outcome it must never produce.
  const findings = JSON.parse(reportText).findings.length;
  // The parse is deliberately after the clock and the SERIALIZATION deliberately inside it, which is the one
  // way this number differs from the JVM lane's: `checkModelJson` renders the report to JSON as part of the
  // call, while the JVM quantity ends at a report object it never serializes. The difference is a per-call cost
  // that scales with the report rather than with the model, and the lane asserts a RATIO, so it does not
  // change the quantity under test — it is stated because a reader comparing absolute figures across hosts
  // would otherwise attribute it to the checker. The cold arms carry the identical asymmetry.
  // `reportChars` is the DISCRIMINATOR for the serialization above, and it costs one integer. If the report
  // text is model-independent, an in-call serialization cannot explain any difference between this arm's
  // growth ratio and the JVM lane's — the rival mechanism is then out, measured rather than argued.
  process.stdout.write(
    `observation scale=${scale} checkNanos=${checkNanos} findings=${findings}` +
      ` reportChars=${reportText.length}\n`,
  );
}

process.stdout.write(`nodeVersion=${process.version}\n`);
