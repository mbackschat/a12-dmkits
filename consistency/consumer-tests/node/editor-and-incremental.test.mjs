// N row 1's oracle for slices I, J and L: each feature reached through the PACKED package.
//
// The plan's N matrix asks that every application feature G–J and L be "exported through the documented typed
// package surface and exercised from a separate packed-package Node project", and says in the same breath that
// N "cannot pass with a required feature represented only by documentation, a stub, an internal-only API or a
// deferred task". Until 2026-09-18 `checkDraft` and `snapshotOf` were public Kotlin reachable from no JS
// consumer at all, so I and L were exactly that internal-only case — measured by reading the published
// `a12-consistency.d.ts`, which carried two functions.
//
// So this file's job is reachability first and behaviour second. It imports BY PACKAGE NAME, like its
// siblings, because a relative import into the build tree would prove the compiler emitted something where
// this proves a consumer's `node_modules` resolution reaches it.
//
// What it deliberately does not do is restate the semantics those slices already lock in the module's own
// suites. A draft's attribution rules and a snapshot's reuse guarantees have their own tests on both
// carriers; duplicating them here would be a second expected-value corpus that can drift from the first.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkDraftJson, checkEditJson, impactOfJson, snapshotOfJson } from 'a12-consistency';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const soundModel = read('../shared/models/storefront.dm.json');

// A rule the fixture really declares, and the member a report names for its condition. A path this model does
// not carry would make every assertion below pass on a refusal, which is the vacuity this corpus keeps
// tripping over.
const RULE_PATH = '/Storefront/EmailRequiredForPaidOrder';
const CONDITION = 'errorCondition';
const ENTRY_ID = JSON.parse(soundModel).header.id;
// The rule's own condition, read from the fixture so an edit to it cannot silently make the draft rows
// exercise a different parse than the one they name.
const LIVE_CONDITION = 'FieldNotFilled(CustomerEmail) And [OrderTotal] > 0';

test('slice I: a draft is checkable through the packed package', () => {
  const report = JSON.parse(checkDraftJson(soundModel, RULE_PATH, CONDITION, 'FieldFilled(Email)'));

  // The edit's own shape, which is what distinguishes a draft from a whole-model check: `introduced` and
  // `resolved` are the attribution, and both are unconditional so absent and empty cannot look alike.
  assert.ok(Array.isArray(report.introduced), 'a draft attributes what it introduced');
  assert.ok(Array.isArray(report.resolved), 'and what it resolved');
  assert.ok(report.baseline, 'with the baseline riding along as the disclosure that makes it readable');
  assert.notEqual(report.status, 'VALID', 'no surface of this checker may report an A12 validity claim');
});

test('slice I: text that ends early is its own class, not a defect', () => {
  // The property an editor would otherwise get wrong on every keystroke, and the reason this export exists
  // rather than `checkModelJson` on edited text.
  //
  // The spelling matters and cost a wrong assertion here first: text that ENDS EARLY is
  // `MVK_INCOMPLETE_INPUT`, while an unbalanced parenthesis (`FieldFilled(`) is
  // `MVK_EXPECTED_TOKEN_NOT_FOUND` — a real rejection, measured in the plan's 12-input parser probe. Only the
  // first is the "still typing" state, so appending to the rule's own valid condition is what reaches it.
  const endsEarly = `${LIVE_CONDITION} And`;
  const report = JSON.parse(checkDraftJson(soundModel, RULE_PATH, CONDITION, endsEarly));

  assert.deepEqual(
    report.introduced.map((f) => f.code),
    ['MVK_INCOMPLETE_INPUT'],
    'the draft ran out of input, which A12 gives its own code',
  );
  assert.deepEqual(
    report.introduced.filter((f) => f.justifiesRejection),
    [],
    'and nothing promotes it to a rejection an author would see mid-word',
  );
  assert.equal(report.status, 'INCONCLUSIVE', 'so the draft is not condemned while it is being written');
});

test('slice J: an impact analysis is reachable, and answers a different question from the edit', () => {
  // The rename is the case where the two questions part company: the rule below is REACHED by it without
  // necessarily being reported as broken, which is the whole reason this export exists beside checkEditJson.
  // Both the declaration id and the name, because a path is derived from the name and the id is what the
  // fixture keys the declaration by — moving one and not the other would not be a rename of anything.
  const renamed = soundModel
    .replaceAll('"/Storefront/CustomerEmail"', '"/Storefront/BuyerEmail"')
    .replaceAll('"CustomerEmail"', '"BuyerEmail"');
  assert.notEqual(renamed, soundModel, 'the fixture must carry that field, or this row measures nothing');

  const report = JSON.parse(impactOfJson(soundModel, renamed));

  assert.deepEqual(report.delta.removed, ['/Storefront/CustomerEmail'], 'the delta names what went away');
  assert.ok(report.affected.includes(RULE_PATH), `the rule reading it is reached: ${report.affected}`);
  assert.deepEqual(
    report.affected,
    report.impacts.map((i) => i.at).filter((at, n, all) => all.indexOf(at) === n),
    'affected is the de-duplicated impact order, which a consumer would otherwise have to derive',
  );

  // Each impact says WHY, with the dependency's type in `kind` so a consumer need not guess whether `value`
  // is a path or a bare name.
  for (const impact of report.impacts) {
    assert.ok(['ELEMENT', 'TYPE_DEFINITION', 'SHORT_NAME', 'UNFOLLOWABLE'].includes(impact.on.kind),
      `an unknown dependency kind crossed the wire: ${JSON.stringify(impact.on)}`);
    assert.ok(impact.reason, 'and every impact states its reason');
  }

  // The verdict half is delegated, not re-derived, so it is the same document checkEditJson returns.
  assert.deepEqual(report.edit, JSON.parse(checkEditJson(soundModel, renamed)));
});

test('slice L: a snapshot is holdable through the packed package, and reports its reuse', () => {
  const held = snapshotOfJson([soundModel], ENTRY_ID);

  const report = JSON.parse(held.reportJson());
  assert.equal(report.modelId, ENTRY_ID, 'the handle holds an analysis of the entry model');

  // L's claim is about WORK, so the handle publishes what it reused. A draft answered from a held fact base
  // must report the preparation as reused — that is the whole difference from `checkDraftJson`, which
  // re-admits and re-prepares per call.
  const answered = JSON.parse(held.draftWithReuseJson(RULE_PATH, CONDITION, 'FieldFilled(Email)'));
  assert.equal(answered.reuse.preparationReused, true, 'a draft off a handle must not re-prepare the model');
  assert.equal(answered.reuse.baselineReportReused, true, 'nor recheck the baseline it already reported on');
  assert.ok(answered.reuse.familiesReused > 0, 'and at least one family answers from a stored verdict');
  assert.ok(answered.edit, 'while still returning the edit itself');
});

test('slice L: a handle is a value — updating one leaves the old answering as before', () => {
  const held = snapshotOfJson([soundModel], ENTRY_ID);
  const before = held.reportJson();

  const updated = held.withSource(soundModel);

  assert.notEqual(updated, held, 'an update returns a new handle');
  assert.equal(held.reportJson(), before, 'and the old one still answers what it answered');
});

test('slice L: the published surface declares the handle opaque', () => {
  // The opacity is TYPE-LEVEL, and stating that precisely took a wrong assertion first: `new
  // held.constructor()` does NOT throw, because Kotlin/JS emits an ordinary class and `private constructor()`
  // is a declaration rather than a runtime guard. So the honest oracle is the shipped declaration file, which
  // the package names in its own `types` field — a consumer compiling against this package cannot mint a
  // handle, and a consumer bypassing TypeScript was never constrained by anything here.
  const declarations = readFileSync(new URL('./node_modules/a12-consistency/a12-consistency.d.ts',
    import.meta.url), 'utf8');

  assert.match(declarations, /export declare class ConsistencySnapshotHandle \{\s*private constructor\(\);/,
    'the handle must be declared un-constructible, or a consumer can fabricate one that analysed nothing');
  assert.match(declarations, /export declare function snapshotOfJson\(/,
    'and the factory that is the only legitimate way to get one must be exported');
});
