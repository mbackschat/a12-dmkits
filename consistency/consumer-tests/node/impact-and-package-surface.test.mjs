// The two things about the editor surface that only a NODE consumer can establish.
//
// This file used to carry the behavioural rows for slices I and L as well. They moved into
// `../shared/editor.ts` on 2026-09-18 so the browser arm runs them too — N's matrix row 2 asks for H, I and L
// working together "in a real browser", and a Node-only file cannot answer that. Keeping copies here would
// have left two expected-value corpora for one contract, which is exactly the drift the shared suites exist
// to prevent.
//
// What stays is what does not transfer. Slice J is here rather than in the shared editor suite because row 2
// names H, I and L and an impact report is not part of that sequence; row 1 asks for it "exercised from a
// separate packed-package Node project", which is this. And the declaration-file row reads the installed
// package's own bytes, which a bundled browser page has no access to.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkEditJson, impactOfJson } from 'a12-consistency';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const soundModel = read('../shared/models/storefront.dm.json');

const RULE_PATH = '/Storefront/EmailRequiredForPaidOrder';

test('slice J: an impact analysis is reachable, and answers a different question from the edit', () => {
  // The rename is the case where the two questions part company: the rule below is REACHED by it without
  // necessarily being reported as broken, which is the whole reason this export exists beside checkEditJson.
  //
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

test('the published surface declares the snapshot handle opaque', () => {
  // The opacity is TYPE-LEVEL, and stating that precisely took a wrong assertion first: `new
  // held.constructor()` does NOT throw, because Kotlin/JS emits an ordinary class and `private constructor()`
  // is a declaration rather than a runtime guard. So the honest oracle is the shipped declaration file, which
  // the package names in its own `types` field — a consumer compiling against this package cannot mint a
  // handle, and a consumer bypassing TypeScript was never constrained by anything here.
  const declarations = read('./node_modules/a12-consistency/a12-consistency.d.mts');

  assert.match(declarations, /export declare class ConsistencySnapshotHandle \{\s*private constructor\(\);/,
    'the handle must be declared un-constructible, or a consumer can fabricate one that analysed nothing');
  assert.match(declarations, /export declare function snapshotOfJson\(/,
    'and the factory that is the only legitimate way to get one must be exported');

  // The whole surface the shared suites' `CheckerApi` asserts structurally, pinned here against the bytes the
  // package actually ships — a structural interface is satisfied by whatever the host hands it, so it cannot
  // notice an export that was never published.
  for (const exported of ['checkModelJson', 'checkEditJson', 'checkDraftJson', 'impactOfJson']) {
    assert.match(declarations, new RegExp(`export declare function ${exported}\\(`),
      `${exported} must be declared in the package's own types`);
  }
});
