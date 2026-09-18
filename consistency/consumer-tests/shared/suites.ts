// The shared suites, as data, so a host runs them without knowing how many cases any of them has.
//
// This exists because the alternative was a maintenance defect I paid for three times: each host carried a
// hand-written floor (`outcomes.length >= 18`, `MINIMUM_CASES = 18`) that had to be bumped in two files
// whenever a suite grew, and a floor left stale is exactly the vacuity it was written to prevent. A count
// belongs to the suite that owns it, so each runner prepends its OWN reached-this-arm outcome computed from
// its own structure, and a host asserts only what a host can know: that every suite it was given reported
// something.
//
// Host-neutral on the same terms as the suites themselves — it imports only their runners, and the host
// supplies the package and the inputs.

import { runCases, type CaseOutcome, type CheckerApi } from './cases.ts';
import { runEditorCases } from './editor.ts';
import { runParity, type ParityCorpus } from './parity.ts';
import { runReferenceControls } from './references.ts';

/** Everything a host loads, in the one shape every suite is driven from. */
export interface SharedInputs {
  /** The sound storefront model's text, for the named-field cases. */
  readonly soundModel: string;
  /** The parity corpus: its manifest, its source texts and its expected reports. */
  readonly parityCorpus: ParityCorpus;
  /** B0's reference models, keyed by `REFERENCE_MODELS`. */
  readonly referenceModels: Readonly<Record<string, string>>;
}

export interface SharedSuite {
  readonly name: string;
  run(api: CheckerApi, inputs: SharedInputs): readonly CaseOutcome[];
}

/**
 * The suites, in the order a reader should meet them: the readable claims, the whole-document parity, B0's
 * grid, then the editor sequence.
 *
 * Each answers a different question, and the ordering is the only thing shared: `cases.ts` asserts NAMED
 * fields and is blind to every member it does not name; `parity.ts` compares the FULL report against a
 * committed corpus, which is what F's "JS matches JVM" oracle asks for; `references.ts` replays B0's §3.4
 * grid, which B0's own acceptance hands to F; `editor.ts` is the only one that runs a SEQUENCE rather than
 * independent cases, because N's row 2 asks whether H, I and L work together and no single-call case can
 * answer that.
 */
export const SHARED_SUITES: readonly SharedSuite[] = [
  {
    name: 'consumer cases',
    run: (api, inputs) => runCases(api, inputs.soundModel),
  },
  {
    name: 'report parity',
    run: (api, inputs) => runParity(api, inputs.parityCorpus),
  },
  {
    name: 'B0 reference controls',
    run: (api, inputs) => runReferenceControls(api, inputs.referenceModels),
  },
  {
    name: 'editor capabilities together',
    run: (api, inputs) => runEditorCases(api, inputs.soundModel),
  },
];

/**
 * Every suite's outcomes, plus one outcome per suite that reported nothing at all.
 *
 * A suite returning an empty array is the one failure a host cannot see by looking at its own output — the
 * cases simply vanish and the run reads clean — so it is turned into a failing outcome here rather than left
 * to a floor that someone has to remember to raise.
 */
export function runSharedSuites(api: CheckerApi, inputs: SharedInputs): readonly CaseOutcome[] {
  return SHARED_SUITES.flatMap((suite) => {
    const outcomes = suite.run(api, inputs);
    if (outcomes.length === 0) {
      return [{
        name: `the shared suite "${suite.name}" reported nothing`,
        ok: false,
        detail: 'the suite returned no outcomes at all, so none of its cases ran',
      }];
    }
    return outcomes;
  });
}
