// The three-arm parity runner: one full report contract, compared identically on every host.
//
// [F's claim-specific oracle](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) for "JS matches JVM" asks to
// "execute the same fixtures through Java, packed Node and real-browser consumers, comparing the FULL portable
// report contract". `cases.ts` cannot discharge that: it asserts named fields, which is what makes it
// readable and also what makes it blind to every member it does not name. So this compares whole documents,
// and states no semantic claim of its own.
//
// Host-neutral on the same two terms as `cases.ts` — it imports nothing, and the host supplies both the
// package and the corpus (Node reads it off disk, the browser fetches it). See `parity/README.md` for the
// corpus shape and how it is regenerated.

import type { CaseOutcome, CheckerApi } from './cases.ts';

export interface ParityCase {
  readonly name: string;
  readonly call: string;
  readonly sources: readonly string[];
}

export interface ParityManifest {
  readonly sources: Readonly<Record<string, string>>;
  readonly cases: readonly ParityCase[];
}

/** The corpus as a host loaded it: the manifest, each source's TEXT, and each case's expected report text. */
export interface ParityCorpus {
  readonly manifest: ParityManifest;
  readonly sources: Readonly<Record<string, string>>;
  readonly reports: Readonly<Record<string, string>>;
}

/**
 * The first difference between two parsed documents, as a readable path — or `null` when they agree.
 *
 * Comparison is structural rather than textual, and the asymmetry is deliberate: object key ORDER is not part
 * of the wire contract, array order is. A byte comparison would fail on a re-serialization that changed
 * nothing, and a sorted-key comparison would pass an array that had been reordered.
 */
export function firstDifference(actual: unknown, expected: unknown, path = '$'): string | null {
  if (actual === expected) {
    return null;
  }
  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (!Array.isArray(actual) || !Array.isArray(expected)) {
      return `${path}: one side is an array, the other is ${typeof actual}`;
    }
    if (actual.length !== expected.length) {
      return `${path}: ${actual.length} element(s), expected ${expected.length}`;
    }
    for (let index = 0; index < expected.length; index += 1) {
      const difference = firstDifference(actual[index], expected[index], `${path}[${index}]`);
      if (difference !== null) {
        return difference;
      }
    }
    return null;
  }
  if (expected !== null && actual !== null && typeof expected === 'object' && typeof actual === 'object') {
    const actualRecord = actual as Record<string, unknown>;
    const expectedRecord = expected as Record<string, unknown>;
    const keys = [...new Set([...Object.keys(expectedRecord), ...Object.keys(actualRecord)])].sort();
    for (const key of keys) {
      if (!(key in actualRecord)) {
        return `${path}.${key}: absent, expected ${JSON.stringify(expectedRecord[key])}`;
      }
      if (!(key in expectedRecord)) {
        return `${path}.${key}: present as ${JSON.stringify(actualRecord[key])}, expected absent`;
      }
      const difference = firstDifference(actualRecord[key], expectedRecord[key], `${path}.${key}`);
      if (difference !== null) {
        return difference;
      }
    }
    return null;
  }
  return `${path}: ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`;
}

function reportFor(api: CheckerApi, kase: ParityCase, sources: readonly string[]): string {
  switch (kase.call) {
    case 'checkModel':
      return api.checkModelJson(sources[0] as string);
    case 'checkEdit':
      return api.checkEditJson(sources[0] as string, sources[1] as string);
    default:
      // An unknown call is a corpus this arm cannot execute, and passing it would be a silent skip.
      throw new Error(`the parity corpus asks for an unknown call: ${kase.call}`);
  }
}

/**
 * Every parity case, as one outcome each, plus a corpus-reached guard.
 *
 * The guard is not ceremony: the corpus is loaded by the host, so a path that resolved to nothing would make
 * every comparison below vanish and leave the arm green.
 */
export function runParity(api: CheckerApi, corpus: ParityCorpus): readonly CaseOutcome[] {
  const cases = corpus.manifest.cases;
  const reached: CaseOutcome = {
    name: 'the parity corpus reached this arm',
    ok: cases.length > 0,
    detail: `the manifest listed ${cases.length} case(s)`,
  };
  return [reached, ...cases.map((kase) => oneCase(api, corpus, kase))];
}

function oneCase(api: CheckerApi, corpus: ParityCorpus, kase: ParityCase): CaseOutcome {
  const name = `${kase.name}: the full report matches every other arm's`;
  const missingSource = kase.sources.find((key) => corpus.sources[key] === undefined);
  const expectedText = corpus.reports[kase.name];
  if (missingSource !== undefined || expectedText === undefined) {
    return {
      name,
      ok: false,
      detail: missingSource !== undefined
        ? `the corpus is missing source ${missingSource}`
        : `the corpus is missing the expected report for ${kase.name}`,
    };
  }
  const sources = kase.sources.map((key) => corpus.sources[key] as string);
  const difference = firstDifference(
    JSON.parse(reportFor(api, kase, sources)),
    JSON.parse(expectedText),
  );
  return {
    name,
    ok: difference === null,
    detail: difference === null
      ? ''
      : `${difference} — regenerate with ./build.sh :consistency:syncCheckerParityCorpus if this is intended`,
  };
}
