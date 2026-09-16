// Slice G: a Node CI gate over model sources, built on nothing but the published package.
//
// **The problem this example exists to solve is that the checker CANNOT SAY VALID.** A CI job wants one bit —
// may this land? — and `checkModelJson` answers with three states, because a checker whose coverage registry
// is incomplete has no honest way to emit a pass. So the naive pipeline ("no findings, therefore green") is
// precisely the silent-green defect the report shape was designed to prevent, and a consumer writing that
// pipeline would never learn they had. This gate is what the shape asks for instead: it FAILS on what the
// checker can establish, and it reports without ever claiming validation on what it cannot.
//
// `validated` is therefore a constant `false` and has no code path that sets it true — not an oversight, and
// not a placeholder for one. When the coverage registry starts emitting a complete verdict, that is the day
// this file gains a fifth outcome and the constant becomes a computation; until then a consumer reading
// `validated` gets the truth rather than a hopeful default.
//
// It uses the library ONLY — no JVM, no dmtool subprocess, no network — which is the arrangement a Node CI
// job actually has.

import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkModelJson } from 'a12-consistency';

/**
 * What one source can come to. Four, because a CI job needs three different ACTIONS out of them and the
 * fourth is the one that keeps an older gate from misreading a newer report.
 */
export const Outcome = Object.freeze({
  /** A12 rejects a declaration. The verdict is established, and it is a no. */
  REJECTED: 'REJECTED',
  /** Families ran and found no rejection. This is NOT a pass — nothing was validated, only not refused. */
  NOT_ESTABLISHED: 'NOT_ESTABLISHED',
  /** Nothing was checked: the file did not reach the checker, or the checker could not admit it. */
  UNREADABLE: 'UNREADABLE',
  /** The report carries a status this gate does not know. Fails closed — see `classify`. */
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
 * The operational test is `checksCompleted` being empty rather than the presence of a diagnostic, and that is
 * the load-bearing choice here. The report shape documents what the emptiness means — no family ran — while a
 * diagnostic may perfectly well ride along with a complete run: `DECLARATION_NOT_WELL_FORMED` reports one
 * unreadable declaration and the other families still answer about its siblings. Keying on "any diagnostic"
 * would turn every such model into a tool failure, which is an over-rejection in the gate rather than in the
 * checker but lands on the author just the same.
 *
 * An unknown `status` fails closed. A gate pinned to an older package must never read a status it has never
 * heard of as a pass, and a future `VALID` is exactly that case: an old gate cannot verify what it does not
 * model, so it declines to translate it.
 */
export function classify(report) {
  if (report.status === 'INVALID') return Outcome.REJECTED;
  if (report.status !== 'INCONCLUSIVE') return Outcome.UNRECOGNIZED;
  return report.checksCompleted.length === 0 ? Outcome.UNREADABLE : Outcome.NOT_ESTABLISHED;
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
      || r.outcome === Outcome.UNRECOGNIZED);
  const rejected = results.filter((r) => r.outcome === Outcome.REJECTED);
  let exitCode = ExitCode.NO_REJECTION;
  if (operational.length > 0) exitCode = ExitCode.OPERATIONAL;
  else if (rejected.length > 0) exitCode = ExitCode.REJECTED;
  return {
    // Never true, by construction. See this file's header.
    validated: false,
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
    // Why no source here is claimed validated, per source, so the absence of a pass is explicable rather
    // than merely stated. `gaps` is the checker's own disclosure of what it did not reach.
    notValidated: results.filter((r) => r.outcome === Outcome.NOT_ESTABLISHED).map((r) => ({
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
