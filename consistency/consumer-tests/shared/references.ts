// B0's retained reference controls, replayed through whatever host loaded the packed package.
//
// B0's acceptance locks [proposal §3.4](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md)'s grid and then hands
// this replay away explicitly: *"the packed Node/browser replay of these same cases belongs to F, which owns
// the delivery tooling, so B0 does not silently depend on a later slice's deliverable"*. So these are B0's
// rows, asserted here through the only surface a packaged consumer has — `checkModelJson` and its report.
//
// **They are NOT a copy of `ReferenceBindingTest`.** That suite calls `prepare(...).resolve(base, name)`, an
// internal API no consumer can reach, so it cannot say whether the packaged artifact carries the behaviour.
// These cases drive the same five rows end to end, through a rule whose condition names the field, which is
// the only way the resolution outcome becomes observable from outside.
//
// Host-neutral on the same terms as `cases.ts`: no imports beyond the shared types, and the host supplies the
// package and the model text.

import type { CaseOutcome, CheckerApi } from './cases.ts';

/** The five models, by the fixture key the host loads them under. */
export const REFERENCE_MODELS = [
  'catalog-reference-resolved',
  'catalog-reference-no-fallback',
  'catalog-reference-absent',
  'catalog-reference-ambiguous',
  'catalog-reference-declaring-wins',
] as const;

export type ReferenceModelKey = (typeof REFERENCE_MODELS)[number];

interface Finding {
  readonly code: string;
  readonly at: string;
  readonly explain: string;
  readonly severity: string;
  readonly justifiesRejection: boolean;
  readonly span?: { readonly member: string; readonly start: number; readonly end: number } | null;
  readonly corrective?: { readonly fix: string; readonly meaning: string } | null;
}

interface Report {
  readonly status: string;
  readonly complete: boolean;
  readonly findings: readonly Finding[];
  readonly diagnostics: readonly unknown[];
}

function expect(ok: boolean, detail: string): string | null {
  return ok ? null : detail;
}

function outcome(name: string, checks: readonly (string | null)[]): CaseOutcome {
  const failures = checks.filter((check): check is string => check !== null);
  return { name, ok: failures.length === 0, detail: failures.join('; ') };
}

/** The single finding a row expects, or a failure naming what came instead. */
function soleFinding(report: Report): Finding | string {
  if (report.findings.length !== 1) {
    return `expected exactly one finding, got ${report.findings.map((f) => f.code).join(', ') || '(none)'}`;
  }
  return report.findings[0] as Finding;
}

/**
 * Every B0 reference row, against the models [models] supplies keyed by [REFERENCE_MODELS].
 *
 * The grid's discipline is that each pair differs in ONE axis, so a row that fails names which axis moved.
 * `resolved` and `no-fallback` are the same bytes but for `fieldRefByShortNameAllowed`; `ambiguous` and
 * `declaring-wins` are the same bytes but for where the rule sits.
 */
export function runReferenceControls(
  api: CheckerApi,
  models: Readonly<Record<string, string>>,
): readonly CaseOutcome[] {
  const missing = REFERENCE_MODELS.filter((key) => models[key] === undefined);
  if (missing.length > 0) {
    return [{
      name: 'B0 reference controls reached this arm',
      ok: false,
      detail: `the host did not supply ${missing.join(', ')}`,
    }];
  }
  const report = (key: ReferenceModelKey): Report =>
    JSON.parse(api.checkModelJson(models[key] as string)) as Report;

  return [
    theControlIsAcceptedAndStillNotValid(report('catalog-reference-resolved')),
    onlyTheFlagMovedAndTheKernelRejects(report('catalog-reference-no-fallback')),
    aNameNothingDeclaresIsAbsentRatherThanAmbiguous(report('catalog-reference-absent')),
    twoCandidatesIsAmbiguityWithTheOppositeCorrective(report('catalog-reference-ambiguous')),
    theDeclaringGroupWinsOverTheModelWideFallback(report('catalog-reference-declaring-wins')),
    noRowCanEverBeValid(REFERENCE_MODELS.map((key) => report(key))),
  ];
}

/**
 * B0's positive control, and the half that is easy to lose: accepted is NOT valid.
 *
 * One model-wide `Code`, the fallback on, so the reference resolves and no unresolved-reference finding is
 * reported — while the overall verdict stays INCONCLUSIVE with `complete: false`, because the checker cannot
 * emit VALID at all. A control asserting only "no findings" would pass on a checker that had stopped checking.
 */
function theControlIsAcceptedAndStillNotValid(report: Report): CaseOutcome {
  return outcome('B0 control: the fallback resolves a unique short name, and the verdict is still not VALID', [
    expect(report.findings.length === 0, `findings were ${report.findings.map((f) => f.code).join(', ')}`),
    expect(report.diagnostics.length === 0, 'the control model is readable, so nothing is diagnosed'),
    expect(report.status === 'INCONCLUSIVE', `status was ${report.status}`),
    expect(report.complete === false, `complete was ${report.complete}`),
  ]);
}

/** B0's row 1, which differs from the control in `fieldRefByShortNameAllowed` and in nothing else. */
function onlyTheFlagMovedAndTheKernelRejects(report: Report): CaseOutcome {
  const finding = soleFinding(report);
  if (typeof finding === 'string') {
    return { name: 'B0 row 1: turning the fallback OFF makes the same reference undeclared', ok: false, detail: finding };
  }
  return outcome('B0 row 1: turning the fallback OFF makes the same reference undeclared', [
    expect(report.status === 'INVALID', `status was ${report.status}`),
    expect(finding.code === 'MVK_INVALID_ENTITY', `code was ${finding.code}`),
    expect(finding.at === '/Catalog/CodeRequired', `at was ${finding.at}`),
    expect(finding.justifiesRejection, 'an unresolved reference justifies rejection'),
    // The span is asserted as the SLICE it cuts, not as two offsets: a wrong pair that is self-consistent
    // reads as right.
    expect(
      finding.span?.member === 'errorCondition',
      `span.member was ${finding.span?.member}`,
    ),
    expect(
      'FieldNotFilled(Code)'.slice(finding.span?.start, finding.span?.end) === 'Code',
      `the span sliced ${JSON.stringify('FieldNotFilled(Code)'.slice(finding.span?.start, finding.span?.end))}`,
    ),
  ]);
}

/** Absence and ambiguity are different Kernel rejections; this is the absent one, with the fallback ON. */
function aNameNothingDeclaresIsAbsentRatherThanAmbiguous(report: Report): CaseOutcome {
  const finding = soleFinding(report);
  if (typeof finding === 'string') {
    return { name: 'B0: a name nothing declares is ABSENT, not ambiguous', ok: false, detail: finding };
  }
  return outcome('B0: a name nothing declares is ABSENT, not ambiguous', [
    expect(report.status === 'INVALID', `status was ${report.status}`),
    expect(finding.code === 'MVK_INVALID_ENTITY', `code was ${finding.code}`),
    expect(
      'FieldNotFilled(Missing)'.slice(finding.span?.start, finding.span?.end) === 'Missing',
      `the span sliced ${JSON.stringify('FieldNotFilled(Missing)'.slice(finding.span?.start, finding.span?.end))}`,
    ),
  ]);
}

/**
 * The row the classification exists for.
 *
 * Two candidates is `MVK_FIELDNAME_NOT_UNIQUE`, not `MVK_INVALID_ENTITY`, and the correctives are OPPOSITE —
 * qualify the reference here, declare the field there. That opposition is the whole reason the two codes are
 * carried separately, so it is asserted rather than described: a consumer told only "unresolved" cannot know
 * which of the two things to do.
 */
function twoCandidatesIsAmbiguityWithTheOppositeCorrective(report: Report): CaseOutcome {
  const finding = soleFinding(report);
  if (typeof finding === 'string') {
    return { name: 'B0: two candidates is AMBIGUITY, with the opposite corrective', ok: false, detail: finding };
  }
  const fix = finding.corrective?.fix ?? '';
  return outcome('B0: two candidates is AMBIGUITY, with the opposite corrective', [
    expect(report.status === 'INVALID', `status was ${report.status}`),
    expect(finding.code === 'MVK_FIELDNAME_NOT_UNIQUE', `code was ${finding.code}`),
    // Both candidate paths, because "cannot choose" is only actionable if it says between what.
    expect(
      finding.explain.includes('/Catalog/Warehouse/Code') && finding.explain.includes('/Catalog/Store/Code'),
      `the explain named ${JSON.stringify(finding.explain)}`,
    ),
    expect(fix.includes('Qualify the reference'), 'the corrective says to qualify, not to declare'),
    expect(
      fix.includes('OPPOSITE corrective to MVK_INVALID_ENTITY'),
      'the corrective names the code it is the opposite of',
    ),
  ]);
}

/**
 * Declaring-group precedence, with the fallback ON and two candidates present.
 *
 * The Kernel tries `base/name` first, so a rule inside the group that declares the field resolves there and
 * never reaches the ambiguous model-wide lookup. A resolver that consulted the fallback first would report
 * `MVK_FIELDNAME_NOT_UNIQUE` on a model A12 accepts — an over-rejection, the one forbidden direction.
 */
function theDeclaringGroupWinsOverTheModelWideFallback(report: Report): CaseOutcome {
  return outcome('B0: the declaring group wins over the model-wide fallback, ambiguity notwithstanding', [
    expect(report.findings.length === 0, `findings were ${report.findings.map((f) => f.code).join(', ')}`),
    expect(report.status === 'INCONCLUSIVE', `status was ${report.status}`),
    expect(report.complete === false, `complete was ${report.complete}`),
  ]);
}

/**
 * B0's type-level clause, in the only form a packaged JS consumer can assert it.
 *
 * B0 establishes "the slice's type cannot emit VALID" by the ABSENCE OF A VALID FACTORY — a claim about a
 * Kotlin API that no JSON string can carry. Across a host that has no type system, the observable form is
 * that no row ever reports VALID and every row reports `complete: false`, over the whole grid rather than
 * over the one row a reader might check.
 */
function noRowCanEverBeValid(reports: readonly Report[]): CaseOutcome {
  const valid = reports.filter((r) => r.status === 'VALID').length;
  const complete = reports.filter((r) => r.complete).length;
  return outcome('B0: no row reports VALID, and none reports a complete check', [
    expect(reports.length === REFERENCE_MODELS.length, `only ${reports.length} row(s) were read`),
    expect(valid === 0, `${valid} row(s) reported VALID`),
    expect(complete === 0, `${complete} row(s) reported complete: true`),
  ]);
}
