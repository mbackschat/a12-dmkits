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
// Neither the CASES nor the FIXTURES are here. Both live under `../shared/` and the browser arm runs the same
// ones, because F asks for "the same corpus" through Node and browser and two hand-written files cannot be
// shown to be the same. This file supplies a host: it resolves the package, reads the shared inputs off disk,
// and projects each shared verdict into `node:test`. The shared modules are TypeScript imported directly —
// Node strips the types, so this arm still needs no build step.
//
// Two shared suites run here, and they answer different questions. `cases.ts` asserts NAMED fields, which is
// what makes it readable and also what makes it blind to every member it does not name; `parity.ts` compares
// the FULL report document against a committed corpus every arm reads, which is what F's "JS matches JVM"
// oracle asks for and what no per-field assertion can give.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkEditJson, checkModelJson } from 'a12-consistency';
import { runCases } from '../shared/cases.ts';
import { runParity } from '../shared/parity.ts';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const soundModel = read('../shared/models/storefront.dm.json');
const api = { checkModelJson, checkEditJson };

// The parity corpus is DATA, so this host only has to resolve paths. Reading the manifest first is what makes
// the set of files a corpus rather than a directory: a case the manifest does not list is not run, and a case
// it lists whose file is missing fails loudly in `runParity`.
const manifest = JSON.parse(read('../shared/parity/manifest.json'));
const corpus = {
  manifest,
  sources: Object.fromEntries(
    Object.entries(manifest.sources).map(([key, file]) => [key, read(`../shared/parity/sources/${file}`)]),
  ),
  reports: Object.fromEntries(
    manifest.cases.map(({ name }) => [name, read(`../shared/parity/reports/${name}.json`)]),
  ),
};

const outcomes = [...runCases(api, soundModel), ...runParity(api, corpus)];

// A case set that silently became empty would make every `test()` below vanish and the file still pass — the
// vacuity this arm is most exposed to, since the count lives in another file. `runParity` carries the same
// guard for its own half, because its corpus is loaded from paths this file resolves.
test('the shared consumer corpus reached this arm', () => {
  assert.ok(outcomes.length >= 12, `expected the shared cases and the parity corpus, got ${outcomes.length}`);
});

for (const { name, ok, detail } of outcomes) {
  test(name, () => {
    assert.ok(ok, detail);
  });
}
