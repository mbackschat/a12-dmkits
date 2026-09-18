// Slice G: a Node CI gate over model sources, built on nothing but the published package.
//
// **The problem this example exists to solve is that "may this land?" and "is this model valid?" are
// different questions.** A CI job wants the first, and the checker answers it: `ALL_CHECKS_PASSED` says every
// family it implements ran and none rejected the model. It cannot answer the second, because its coverage of
// A12 is incomplete. The naive pipeline ("no findings, therefore green") conflates them and is precisely the
// silent-green defect the report shape was designed to prevent — a consumer writing it would never learn.
//
// So this gate reports BOTH, and never lets one stand in for the other. `validated` is a constant `false`
// with no code path that sets it true — not an oversight and not a placeholder — because that field is about
// A12 validation, which no amount of the checker's own coverage establishes. `allChecksPassed` is the signal
// a job may legitimately act on.
//
// *Amended 2026-09-18: this file previously said the checker "CANNOT SAY VALID" full stop, and that the day a
// pass arrived would be the day the constant became a computation. Half of that was right. The scoped pass
// arrived without the coverage registry, because it is a different and decidable claim — so the gate gained
// the outcome and `validated` stayed constant, which is the distinction the original wording could not make.*
//
// It uses the library ONLY — no JVM, no dmtool subprocess, no network — which is the arrangement a Node CI
// job actually has.

import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkModelJson } from 'a12-consistency';

/**
 * What one source can come to. Six values collapsing to three ACTIONS, which is what a CI job needs: stop
 * because a model is refused, stop because nothing was established, or proceed. The last two exist for
 * reasons no outcome of the checker's own covers — a file this gate could not read, and a value a newer
 * package reports that an older gate must not translate.
 */
export const Outcome = Object.freeze({
  /** A12 rejects a declaration. The verdict is established, and it is a no. */
  REJECTED: 'REJECTED',
  /**
   * Every family the checker implements ran and none rejected the model — the one PASS, and it is scoped to
   * the checker's coverage, never to A12 acceptance. See `validated` in the payload, which stays false.
   */
  ALL_CHECKS_PASSED: 'ALL_CHECKS_PASSED',
  /** Some families ran and none rejected. NOT a pass — the run was too narrow to be one. */
  NO_REJECTION: 'NO_REJECTION',
  /** Nothing was checked: the checker could not admit the source. */
  NOT_ESTABLISHED: 'NOT_ESTABLISHED',
  /** This gate could not read the FILE — a failure of the gate's own input, before any checking. */
  UNREADABLE: 'UNREADABLE',
  /** The report carries an outcome this gate does not know. Fails closed — see `classify`. */
  UNRECOGNIZED: 'UNRECOGNIZED',
});

/**
 * Exit codes taken from dmtool's published contract (CLI-SPEC §3b) rather than invented.
 *
 * The three meanings are the same three, so a second numbering would make one project speak two dialects of
 * its own result contract: `1` a rejection, `70` a tool-level failure, `0` no rejection found. `0` here is
 * deliberately NOT "validated" — that claim lives in the payload, where it is always false.
 */
export const ExitCode = Object.freeze({ NO_REJECTION: 0, REJECTED: 1, OPERATIONAL: 70 });

/**
 * Classify one report from its WIRE shape alone.
 *
 * **The library OWNS this rule and now publishes its answer, so this reads it instead of re-deriving it.**
 * Until 2026-09-18 this function mirrored `ConsistencyReport.outcome` in JavaScript, because a CI gate holds
 * JSON rather than a typed report — possibly across a process or service boundary. The durable fix named in
 * that mirror was to publish the outcome ON the wire shape, and that has happened, so the duplicated rule is
 * gone. It also bought the one outcome the mirror COULD NOT compute: deciding `ALL_CHECKS_PASSED` needs the
 * family denominator, which the wire shape does not carry and a consumer cannot know.
 *
 * **The names are the library's, deliberately, and two of them used to mean the opposite here.** This gate
 * previously called "families ran, none rejected" `NOT_ESTABLISHED` and "nothing was checked" `UNREADABLE`,
 * while the library uses `NO_REJECTION` and `NOT_ESTABLISHED` for those two. Mapping between the vocabularies
 * would have inverted two identical words across two surfaces of one product, so the gate adopted the
 * library's. `UNREADABLE` survives with a narrower and non-colliding meaning: this gate could not read the
 * FILE, which is a failure of its own input and has no library counterpart.
 *
 * An unknown outcome fails closed. A gate pinned to an older package must never read a value it has never
 * heard of as a pass — and a future `VALID` status is exactly that case: an old gate cannot verify what it
 * does not model, so it declines to translate it.
 */
export function classify(report) {
  switch (report.outcome) {
    case Outcome.REJECTED:
    case Outcome.ALL_CHECKS_PASSED:
    case Outcome.NO_REJECTION:
    case Outcome.NOT_ESTABLISHED:
      return report.outcome;
    default:
      return Outcome.UNRECOGNIZED;
  }
}

/**
 * Check one source file.
 *
 * The file's own name goes in as the diagnostic label, so a report names the file a consumer has to open
 * rather than only the model id A12 attributed the finding to — the two differ as soon as a workspace holds
 * more sources than models.
 *
 * A file that cannot be read from disk comes back as `UNREADABLE` with a null report, which is the same
 * ACTION as a source the checker could not admit — the build stops with no verdict — and the `failure` string
 * says which of the two it was. Letting `readFileSync` throw instead would end a CI run in a stack trace
 * where the job needed a structured result for every source it was given.
 */
export function checkSource(path) {
  const label = basename(path);
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch (cause) {
    return { source: path, outcome: Outcome.UNREADABLE, report: null, failure: cause.message };
  }
  const report = JSON.parse(checkModelJson(text, label));
  return { source: path, outcome: classify(report), report, failure: null };
}

/**
 * Run the gate over a set of sources and decide the job's exit code.
 *
 * **An operational failure outranks a rejection**, which is the one precedence worth stating: a run that
 * could not read one of its inputs has not checked the set it was asked about, so reporting its rejections as
 * the result would describe a smaller set than the consumer supplied. Exiting 70 says the run itself is in
 * question; exiting 1 would say the models are.
 */
export function runGate(paths) {
  const results = paths.map(checkSource);
  const operational = results.filter((r) => r.outcome === Outcome.UNREADABLE
      || r.outcome === Outcome.NOT_ESTABLISHED
      || r.outcome === Outcome.UNRECOGNIZED);
  const rejected = results.filter((r) => r.outcome === Outcome.REJECTED);
  let exitCode = ExitCode.NO_REJECTION;
  if (operational.length > 0) exitCode = ExitCode.OPERATIONAL;
  else if (rejected.length > 0) exitCode = ExitCode.REJECTED;
  return {
    // Never true, by construction, and NOT the same claim as `allChecksPassed` below. See this file's header.
    validated: false,
    // The scoped pass, per source: every family the checker implements ran and none rejected. This is the
    // signal a CI job can legitimately act on, and it is published beside `validated: false` rather than
    // folded into it precisely so the two cannot be confused — one is the checker's coverage, the other
    // A12's.
    allChecksPassed: results.filter((r) => r.outcome === Outcome.ALL_CHECKS_PASSED)
      .map((r) => ({ source: r.source })),
    exitCode,
    checked: results.length,
    rejected: rejected.map((r) => ({
      source: r.source,
      // The codes that justify the rejection, which is the half a bare `status` cannot act on. A finding
      // that does not justify rejection is left out here and stays readable in the full report below.
      codes: r.report.findings.filter((f) => f.justifiesRejection).map((f) => f.code),
    })),
    operational: operational.map((r) => ({
      source: r.source,
      outcome: r.outcome,
      failure: r.failure ?? r.report.diagnostics.map((d) => `${d.code}: ${d.explain}`).join('; '),
    })),
    // Why no source is claimed A12-VALIDATED, per source, so the absence is explicable rather than merely
    // stated. `gaps` is the checker's own disclosure of what it did not reach. A source that passed every
    // check appears here TOO, and that is not a contradiction: passing the checker's own families is not
    // A12 validation, and the gaps are what say how far apart those two are.
    notValidated: results.filter((r) => r.outcome === Outcome.ALL_CHECKS_PASSED
        || r.outcome === Outcome.NO_REJECTION).map((r) => ({
      source: r.source,
      gaps: r.report.gaps.map((g) => g.reason),
    })),
    results,
  };
}

// The runnable entry, so this file is a pipeline step and not only a library: `node ci-gate.mjs <sources…>`.
// Compared against `process.argv[1]` rather than using `import.meta.main`, which would put a Node floor on a
// consumer example whose whole point is to be ordinary.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const gate = runGate(process.argv.slice(2));
  console.log(JSON.stringify(gate, null, 2));
  process.exitCode = gate.exitCode;
}
