// The editor's three features working TOGETHER, on whichever host runs them — N's matrix row 2.
//
// Row 2 asks that "the integration example exercises H, I and L in a real browser, including a rejected
// candidate", and the word doing the work is *together*: H, I and L each have their own acceptance, proved by
// the module's own suites on both carriers, and none of that says an editor can hold an analysis, type into
// it, and decide whether to save. That sequence is what this suite runs.
//
// What it deliberately does NOT restate is any slice's own acceptance. Slice I asks for a condition, an
// operation, a common precondition and an alternative precondition through incomplete → invalid → valid; the
// members beyond the condition need a computation, and this fixture declares none because it also drives the
// committed parity corpus, where adding a declaration would invalidate every expected report. Those members
// are locked in `:consistency`'s own suites, which run on the JVM and on Node, so the gap here is a
// fixture's shape rather than a carrier's coverage.
//
// Host-neutral on `cases.ts`'s terms: imports nothing, self-checks, returns verdicts.

import { type CaseOutcome, type CheckerApi, expect, outcome } from './cases.ts';

/** The rule the fixture declares, and the member a report spells for its condition. */
const RULE = '/Storefront/EmailRequiredForPaidOrder';
const CONDITION = 'errorCondition';

/** The rule's own condition, so a draft row starts from text the model really carries. */
const LIVE = 'FieldNotFilled(CustomerEmail) And [OrderTotal] > 0';

/**
 * Text that ran out mid-expression — A12's own incompleteness class, and the state an editor is in most of
 * the time.
 *
 * The spelling is load-bearing: text that ENDS EARLY is `MVK_INCOMPLETE_INPUT`, while an unbalanced
 * parenthesis is `MVK_EXPECTED_TOKEN_NOT_FOUND`, a real rejection. Only the first is "still typing".
 */
const INCOMPLETE = `${LIVE} And`;

/** Complete text naming a field the model does not declare — a genuinely rejected candidate. */
const REJECTED = 'FieldNotFilled(NoSuchField)';

/** Complete text that the model accepts. */
const ACCEPTED = 'FieldFilled(CustomerEmail)';

/**
 * I's core property: the three states an author passes through, and only the middle one condemns the draft.
 *
 * An editor that read all three as invalid — which is what checking the edited model rather than the draft
 * gives — would flag every keystroke, so this is the row that distinguishes a draft surface from a whole-model
 * one.
 */
function draftsPassThroughThreeStates(api: CheckerApi, soundModel: string): CaseOutcome {
  const draft = (text: string) => JSON.parse(api.checkDraftJson(soundModel, RULE, CONDITION, text));

  const incomplete = draft(INCOMPLETE);
  const rejected = draft(REJECTED);
  const accepted = draft(ACCEPTED);

  return outcome('a draft runs incomplete → rejected → accepted, and only the middle one condemns it', [
    expect(incomplete.introduced.length === 1
      && incomplete.introduced[0].code === 'MVK_INCOMPLETE_INPUT',
      `incomplete text gave ${JSON.stringify(incomplete.introduced)}`),
    expect(!incomplete.introduced.some((f: { justifiesRejection: boolean }) => f.justifiesRejection),
      'unfinished text must not justify a rejection'),
    expect(incomplete.status === 'INCONCLUSIVE', `incomplete status was ${incomplete.status}`),

    expect(rejected.status === 'INVALID', `the rejected candidate's status was ${rejected.status}`),
    expect(rejected.introduced.some((f: { justifiesRejection: boolean }) => f.justifiesRejection),
      `the rejected candidate introduced ${JSON.stringify(rejected.introduced)}`),

    expect(accepted.status === 'INCONCLUSIVE', `accepted status was ${accepted.status}`),
    expect(accepted.introduced.length === 0, `accepted text introduced ${JSON.stringify(accepted.introduced)}`),
  ]);
}

/**
 * The decision an editor actually makes: the accepted draft is then SAVED, and the save agrees with the draft.
 *
 * This is where H and I meet, and the disagreement it rules out is the expensive one — a draft surface that
 * said "fine" about text the save then rejects would have the author discover it after committing.
 */
function anAcceptedDraftSurvivesBeingSaved(api: CheckerApi, soundModel: string): CaseOutcome {
  const saved = soundModel.replace(LIVE, ACCEPTED);
  const edit = JSON.parse(api.checkEditJson(soundModel, saved));
  const rejectedSave = JSON.parse(api.checkEditJson(soundModel, soundModel.replace(LIVE, REJECTED)));

  return outcome('an accepted draft saves clean, and a rejected one is refused at the save too', [
    expect(saved !== soundModel, 'the fixture must carry the live condition, or nothing was edited'),
    expect(edit.status === 'INCONCLUSIVE', `saving the accepted draft gave ${edit.status}`),
    expect(edit.introduced.length === 0, `saving it introduced ${JSON.stringify(edit.introduced)}`),
    expect(rejectedSave.status === 'INVALID', `saving the rejected draft gave ${rejectedSave.status}`),
  ]);
}

/**
 * L's claim, as WORK rather than as time: a draft answered from a held analysis re-prepares nothing.
 *
 * The counters are what make this a control instead of a statistic — an implementation that silently rebuilt
 * the fact base per keystroke reports `false` here and fails, which is the property L exists to deliver.
 */
function aHeldAnalysisAnswersADraftWithoutRebuilding(api: CheckerApi, soundModel: string): CaseOutcome {
  const entryModelId = entryIdOf(soundModel);
  const held = api.snapshotOfJson([soundModel], entryModelId);
  const report = JSON.parse(held.reportJson());
  const answered = JSON.parse(held.draftWithReuseJson(RULE, CONDITION, ACCEPTED));
  const stateless = JSON.parse(api.checkDraftJson(soundModel, RULE, CONDITION, ACCEPTED));

  return outcome('a draft off a held analysis reuses the preparation and agrees with the stateless call', [
    expect(report.modelId === entryModelId, `the handle analysed ${report.modelId}`),
    expect(answered.reuse.preparationReused === true, 'a held analysis must not re-prepare the model'),
    expect(answered.reuse.baselineReportReused === true, 'nor recheck the baseline it already reported on'),
    expect(answered.reuse.familiesReused > 0,
      `at least one family must answer from a stored verdict, got ${answered.reuse.familiesReused}`),
    // The reuse is only worth having if the answer is the same answer, which is the guarantee a consumer
    // cannot check for itself: it has no full recheck to compare against unless it pays for one.
    expect(answered.edit.status === stateless.status,
      `held gave ${answered.edit.status}, stateless gave ${stateless.status}`),
    expect(JSON.stringify(answered.edit.introduced) === JSON.stringify(stateless.introduced),
      'a reused cell must never change what the draft reports'),
  ]);
}

/**
 * A handle is a VALUE, which is what makes it safe to hand to a UI that renders asynchronously.
 *
 * An update returns a new handle and leaves the old one answering what it answered, so a view holding the
 * previous analysis cannot have its content change underneath it mid-render.
 */
function updatingAHandleLeavesTheOldOneAnswering(api: CheckerApi, soundModel: string): CaseOutcome {
  const entryModelId = entryIdOf(soundModel);
  const held = api.snapshotOfJson([soundModel], entryModelId);
  const before = held.reportJson();
  const updated = held.withSource(soundModel.replace(LIVE, REJECTED));

  return outcome('updating a handle returns a new one and leaves the old analysis intact', [
    expect(updated !== held, 'an update must return a new handle'),
    expect(held.reportJson() === before, 'the old handle must still answer exactly what it answered'),
    expect(JSON.parse(updated.reportJson()).status === 'INVALID',
      `the updated handle must see the rejected source, got ${JSON.parse(updated.reportJson()).status}`),
  ]);
}

/**
 * The entry model's id, read from the text the host loaded.
 *
 * Derived rather than passed so neither host and no shared-input shape has to carry it: a snapshot is keyed
 * by the `header.id` the model itself declares, and that is already in the bytes both arms hand over.
 */
function entryIdOf(modelJson: string): string {
  const id = JSON.parse(modelJson).header?.id;
  if (typeof id !== 'string') {
    throw new Error('the fixture must declare header.id, which is what a snapshot is keyed by');
  }
  return id;
}

/** Every editor case, run against one host's [api] and the fixture text it loaded. */
export function runEditorCases(api: CheckerApi, soundModel: string): readonly CaseOutcome[] {
  return [
    draftsPassThroughThreeStates(api, soundModel),
    anAcceptedDraftSurvivesBeingSaved(api, soundModel),
    aHeldAnalysisAnswersADraftWithoutRebuilding(api, soundModel),
    updatingAHandleLeavesTheOldOneAnswering(api, soundModel),
  ];
}
