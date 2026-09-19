// ONE cold check in a plain Node process that will never run a second one — H5's cold boundary, plain-Node arm.
//
// [§8](../../../docs/archived/PORTABLE-CONSISTENCY-PROPOSAL.md)'s cold row asks for "installed package load through
// preparation and first complete consistency result, **on JVM, plain Node and browser**". Until 2026-09-17 only
// the JVM host had a lane, and not even on an installed package — it resolved `:consistency` as a project
// dependency. This file is the Node host's child process, launched once per observation by
// `CheckerColdNodePerfTest`, and it imports `a12-consistency` BY PACKAGE NAME out of the consumer project's
// `node_modules`, so the thing being loaded is the packed tarball a consumer installs.
//
// **Why a separate process per observation rather than a loop.** Everything the cold boundary is about happens
// once per process: module resolution, parsing and compiling the bundle, and the engine's first-tier execution
// of code no optimizer has seen. A loop measures the second call onwards, which is the PREPARED boundary and a
// different question. So the harness pays a process launch per sample and this file is the whole program.
//
// **The dynamic `import()` is INSIDE the timed region, and that is what makes this arm comparable to the JVM
// one rather than a different measurement.** On the JVM, class loading is lazy, so `CheckerColdStartMain`'s
// first `check(...)` call is where the checker's classes are actually loaded — its `checkNanos` includes that
// work even though its import statement sits at the top of the file. A static `import` here would hoist the
// module load ABOVE the timer and quietly measure a warm call, which is the same trap one layer over.
//
// **The JSON parse is OUTSIDE it, deliberately.** The package's own output boundary is the report TEXT, while
// the JVM arm's quantity ends at a report object it never serialized. Timing the parse here would add a
// transport cost the other arm does not pay. The exclusion is fixed, so it cancels in the ratio this lane
// actually asserts, and the preflight below still reads the parsed report.

import { readFileSync } from 'node:fs';

const [modelPath, label] = process.argv.slice(2);
if (!modelPath || !label) {
  throw new Error('usage: cold-first-result.mjs <model.json> <diagnostic-label>');
}

// Read before the clock starts, as the JVM arm does: the boundary is the checker's cost, not the filesystem's.
const text = readFileSync(modelPath, 'utf8');

const started = process.hrtime.bigint();
const { checkModelJson } = await import('a12-consistency');
const reportText = checkModelJson(text);
const checkNanos = process.hrtime.bigint() - started;

// `performance.now()` is milliseconds since Node's performance time origin, which is when the process began —
// the analogue of `ProcessHandle.startInstant()` on the JVM arm. It is early in bootstrap rather than the
// kernel's exec, so it excludes a small, fixed amount of pre-JS process setup; that is stated rather than
// papered over, and it is why this quantity is reported beside the check rather than instead of it.
const processNanos = BigInt(Math.round(performance.now() * 1e6));

const report = JSON.parse(reportText);
const rejecting = report.findings
  .filter((finding) => finding.justifiesRejection)
  .map((finding) => `${finding.code}@${finding.at}`)
  .sort();

// One `key=value` per line, which is all the lane's parser needs; it splits on the first `=` and keys a map.
// Every line is load-bearing: the two timings are the measurement, `rejecting` and the report SHAPE are the
// preflight, `nodeVersion` is the pin's per-child evidence, and `label` identifies the observation in a failure.
// `status` and `complete` are published because an empty `findings` array would otherwise satisfy an
// empty-`rejecting` check while describing a report that analysed nothing.
console.log(`checkNanos=${checkNanos}`);
console.log(`processNanos=${processNanos}`);
console.log(`rejecting=${rejecting.join(',')}`);
console.log(`status=${report.status}`);
console.log(`complete=${report.complete}`);
console.log(`nodeVersion=${process.version}`);
console.log(`label=${label}`);
