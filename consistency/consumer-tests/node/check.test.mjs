// The Node half of F's packaged replay: the shared consumer cases, run in a plain Node project.
//
// What it asserts is the PLUMBING, not the coverage: that the packed npm package installs, imports, runs a
// real model through the checker, and returns a report a consumer can read. The checker is deliberately NOT
// feature-complete, so a partial verdict over a partial family set passes here — and a checker that cannot
// be installed or called fails, however complete its families are.
//
// It imports `a12-consistency` BY PACKAGE NAME, not by a relative path into the build tree. That is the
// whole point of packing a tarball first: a relative import would prove the compiler emitted a file, where
// this proves a consumer's `node_modules` resolution reaches the same code.
//
// Neither the CASES nor the FIXTURE are here. Both live under `../shared/` and the browser arm runs the same
// ones, because F asks for "the same corpus" through Node and browser and two hand-written files cannot be
// shown to be the same. This file supplies a host: it resolves the package, reads the shared fixture off disk,
// and projects each shared verdict into `node:test`. `cases.ts` is TypeScript imported directly — Node strips
// the types, so this arm still needs no build step.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkEditJson, checkModelJson } from 'a12-consistency';
import { runCases } from '../shared/cases.ts';

const soundModel = readFileSync(new URL('../shared/models/storefront.dm.json', import.meta.url), 'utf8');
const outcomes = runCases({ checkModelJson, checkEditJson }, soundModel);

// A case set that silently became empty would make every `test()` below vanish and the file still pass — the
// vacuity this arm is most exposed to, since the count lives in another file.
test('the shared consumer corpus reached this arm', () => {
  assert.ok(outcomes.length >= 5, `expected the shared cases, got ${outcomes.length}`);
});

for (const { name, ok, detail } of outcomes) {
  test(name, () => {
    assert.ok(ok, detail);
  });
}
