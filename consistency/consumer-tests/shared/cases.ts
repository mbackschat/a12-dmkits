// The consumer cases BOTH packaged arms run — Node and browser — so that "the same corpus" is structural.
//
// [F's acceptance](../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) asks for "the same
// accepted/rejected/inconclusive corpus through Java, packed Node and browser", and the plan adds that F owns
// that replay "using the same inputs rather than inventing another matrix". Two arms asserting the same facts
// in two hand-written files satisfies neither: they drift, and nothing says they have. So the cases live here
// once, host-neutral, and each arm only supplies a host — Node reads the fixture from disk and drives these
// through `node:test`, the browser fetches it and drives them in real Chrome.
//
// Host-neutral means exactly two things, and both are load-bearing. The module imports NOTHING: not the
// package under test (each host resolves `a12-consistency` its own way, which is the whole point of installing
// a packed tarball) and no assertion library (`node:assert` does not exist in a browser). A case therefore
// self-checks and returns a verdict, which also makes the browser arm's job trivial: it has no test runner, so
// a case that threw would need catching per case anyway.
//
// It is TypeScript rather than JavaScript because Node 24 strips types natively (repo rule: "syntax supported
// by native type stripping", so no build step for the Node arm) and Vite compiles it for the browser arm. One
// file, two hosts, no compilation asymmetry.

/** The packaged surface these cases exercise — the two exports H2 and H promise a consumer. */
export interface CheckerApi {
  checkModelJson(source: string): string;
  checkEditJson(baseline: string, edited: string): string;
}

/** One case's verdict. `detail` is only read when `ok` is false, and must name the observed value. */
export interface CaseOutcome {
  readonly name: string;
  readonly ok: boolean;
  readonly detail: string;
}

const BROKEN_REFERENCE = 'FieldNotFilled(NoSuchField)';
const SOUND_REFERENCE = 'FieldNotFilled(CustomerEmail)';

/**
 * A genuine but unrelated edit: one more declared field.
 *
 * It cannot introduce a finding — nothing references it — which is what isolates the question the attribution
 * cases ask: whether the model's OWN state leaks into the edit's verdict. An edit that changed nothing would
 * assert nothing.
 */
function withUnrelatedField(modelJson: string): string {
  const anchor = '"elements": [';
  if (!modelJson.includes(anchor)) {
    throw new Error('the fixture must still declare its elements in the expected shape');
  }
  const field = '{ "type": "Field", "id": "/Storefront/InternalNote", "name": "InternalNote",'
    + ' "Field": { "fieldType": { "type": "StringType" } } },';
  return modelJson.replace(anchor, anchor + field);
}

/** A self-checking assertion that reads as a sentence in a failure. */
function expect(ok: boolean, detail: string): string | null {
  return ok ? null : detail;
}

function outcome(name: string, checks: readonly (string | null)[]): CaseOutcome {
  const failures = checks.filter((check): check is string => check !== null);
  return { name, ok: failures.length === 0, detail: failures.join('; ') };
}

/**
 * Every case, run against one host's [api] and the fixture text it loaded.
 *
 * The fixture is passed in rather than imported for the same reason the API is: a browser cannot read a file
 * and Node should not fetch one. Both arms must hand over the SAME bytes, and each arm asserts that it did.
 */
export function runCases(api: CheckerApi, soundModel: string): readonly CaseOutcome[] {
  const brokenModel = soundModel.replace(SOUND_REFERENCE, BROKEN_REFERENCE);
  if (brokenModel === soundModel) {
    return [{
      name: 'fixture integrity',
      ok: false,
      detail: `the fixture no longer spells its rule condition as ${SOUND_REFERENCE}, so every case below `
        + 'would describe an unchanged model',
    }];
  }

  return [
    soundModelIsInconclusive(api, soundModel),
    undeclaredReferenceIsInvalid(api, brokenModel),
    unreadableSourceReturnsAReport(api),
    anUnrelatedEditIsNotBlamedForTheModel(api, brokenModel),
    anEditThatBreaksTheModelIsAttributedToIt(api, soundModel, brokenModel),
  ];
}

/**
 * The single most misreadable thing about this checker, asserted rather than described: no finding does NOT
 * mean the model is valid. The checker cannot say VALID at all, and `complete` says why.
 */
function soundModelIsInconclusive(api: CheckerApi, soundModel: string): CaseOutcome {
  const report = JSON.parse(api.checkModelJson(soundModel));
  return outcome('a model the checker finds nothing wrong with is INCONCLUSIVE, never valid', [
    expect(report.modelId === 'storefront', `modelId was ${JSON.stringify(report.modelId)}`),
    expect(report.findings.length === 0, `findings were ${JSON.stringify(report.findings)}`),
    expect(report.status === 'INCONCLUSIVE', `status was ${report.status}`),
    expect(report.complete === false, `complete was ${report.complete}`),
    expect(report.checksCompleted.length > 0, 'families ran, so the report is not empty of evidence'),
    expect(report.gaps.length > 0, 'an incomplete checker always discloses what it did not check'),
  ]);
}

/**
 * A rule naming an undeclared field, with the code A12 gives for it.
 *
 * The `corrective` and `span` assertions are the ones only a PACKAGED consumer can make. A12's guidance is
 * projected from its Java owners into the library, and the span is what an editor underlines — both are
 * generated on the JVM side, so reading the generator's output proves nothing about what ships. The span is
 * asserted as a SLICE of the condition rather than as two offsets, because a wrong pair of numbers that
 * happens to be self-consistent reads as right.
 */
function undeclaredReferenceIsInvalid(api: CheckerApi, brokenModel: string): CaseOutcome {
  const report = JSON.parse(api.checkModelJson(brokenModel));
  const finding = report.findings.find((f: { code: string }) => f.code === 'MVK_INVALID_ENTITY');
  if (!finding) {
    const codes = report.findings.map((f: { code: string }) => f.code).join(', ');
    return {
      name: 'a rule naming an undeclared field is INVALID, with the code A12 gives',
      ok: false,
      detail: `expected MVK_INVALID_ENTITY, got ${codes || '(no findings)'}`,
    };
  }
  return outcome('a rule naming an undeclared field is INVALID, with the code A12 gives', [
    expect(report.status === 'INVALID', `status was ${report.status}`),
    expect(finding.at === '/Storefront/EmailRequiredForPaidOrder', `at was ${finding.at}`),
    expect(finding.severity === 'ERROR', `severity was ${finding.severity}`),
    // Not derivable from severity alone: an AUTHORING_POLICY finding at ERROR is deliberately not a rejection.
    expect(finding.justifiesRejection === true, `justifiesRejection was ${finding.justifiesRejection}`),
    expect(Boolean(finding.corrective), 'MVK_INVALID_ENTITY is a code A12 explains'),
    expect(finding.corrective?.fix?.length > 0, 'the corrective carries a fix'),
    expect(finding.corrective?.meaning?.length > 0, 'the corrective carries a meaning'),
    expect(finding.span?.member === 'errorCondition', `span.member was ${finding.span?.member}`),
    expect(
      BROKEN_REFERENCE.slice(finding.span?.start, finding.span?.end) === 'NoSuchField',
      `the span sliced ${JSON.stringify(BROKEN_REFERENCE.slice(finding.span?.start, finding.span?.end))}`,
    ),
  ]);
}

/**
 * A diagnostic is the checker saying it could not READ something; a finding is A12's verdict on a declaration.
 * A consumer that merged the two arrays would report a model defect that was never measured.
 */
function unreadableSourceReturnsAReport(api: CheckerApi): CaseOutcome {
  const report = JSON.parse(api.checkModelJson('not json at all'));
  return outcome('an unreadable source returns a report rather than throwing', [
    expect(report.modelId === null, `modelId was ${JSON.stringify(report.modelId)}`),
    expect(report.findings.length === 0, `findings were ${JSON.stringify(report.findings)}`),
    expect(report.diagnostics.length > 0, 'the failure is stated as a diagnostic, not as a finding'),
    expect(report.diagnostics[0]?.at === null, `the diagnostic was anchored at ${report.diagnostics[0]?.at}`),
  ]);
}

/**
 * H's surface, and the trap it exists for: the baseline is already broken and the edit is unrelated to that
 * break, so a consumer reading the edited model's own report would call this edit INVALID. The attribution
 * must not — while still disclosing the model's own defect, which the author needs to know about even though
 * their edit is not what broke it.
 */
function anUnrelatedEditIsNotBlamedForTheModel(api: CheckerApi, brokenModel: string): CaseOutcome {
  const report = JSON.parse(api.checkEditJson(brokenModel, withUnrelatedField(brokenModel)));
  return outcome('an edit is judged by what IT introduced, not by the state of the model it lands in', [
    expect(report.status === 'INCONCLUSIVE', `status was ${report.status}`),
    expect(report.introduced.length === 0, `introduced ${JSON.stringify(report.introduced)}`),
    expect(report.resolved.length === 0, `resolved ${JSON.stringify(report.resolved)}`),
    expect(report.baseline.status === 'INVALID', `baseline status was ${report.baseline.status}`),
    expect(report.baseline.findings.length > 0, "the baseline's own findings stay readable"),
  ]);
}

/** Both directions: an edit that breaks the model, and the reverse edit that repairs it. */
function anEditThatBreaksTheModelIsAttributedToIt(
  api: CheckerApi,
  soundModel: string,
  brokenModel: string,
): CaseOutcome {
  const broke = JSON.parse(api.checkEditJson(soundModel, brokenModel));
  const repair = JSON.parse(api.checkEditJson(brokenModel, soundModel));
  return outcome('an edit that introduces a defect is attributed to the edit, and its repair to the repair', [
    expect(broke.status === 'INVALID', `status was ${broke.status}`),
    expect(broke.introduced.length === 1, `introduced ${JSON.stringify(broke.introduced)}`),
    expect(broke.introduced[0]?.code === 'MVK_INVALID_ENTITY', `introduced ${broke.introduced[0]?.code}`),
    expect(broke.resolved.length === 0, `resolved ${JSON.stringify(broke.resolved)}`),
    expect(repair.status === 'INCONCLUSIVE', `the repair's status was ${repair.status}`),
    expect(repair.resolved.length === 1, `the repair resolved ${JSON.stringify(repair.resolved)}`),
    expect(repair.introduced.length === 0, `the repair introduced ${JSON.stringify(repair.introduced)}`),
  ]);
}
