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
// Three shared suites run here, and `suites.ts` owns the list and what each of them is for. This file knows
// only how to LOAD what they need — which is the whole difference between the two arms — and how to project a
// verdict into `node:test`. It deliberately carries no expected case count: each suite asserts its own, so
// growing one does not require editing this file or the browser's driver.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  checkDraftJson,
  checkEditJson,
  checkModelJson,
  impactOfJson,
  snapshotOfJson,
} from 'a12-consistency';
import { REFERENCE_MODELS } from '../shared/references.ts';
import { runSharedSuites } from '../shared/suites.ts';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const soundModel = read('../shared/models/storefront.dm.json');
const api = { checkModelJson, checkEditJson, checkDraftJson, impactOfJson, snapshotOfJson };

// The parity corpus is DATA, so this host only has to resolve paths. Reading the manifest first is what makes
// the set of files a corpus rather than a directory: a case the manifest does not list is not run, and a case
// it lists whose file is missing fails loudly in `runParity`.
const manifest = JSON.parse(read('../shared/parity/manifest.json'));
const corpus = {
  manifest,
  // The manifest's paths are relative to `consumer-tests/shared/`, so a model committed for another suite is
  // referenced rather than copied — one source of bytes, not two that drift.
  sources: Object.fromEntries(
    Object.entries(manifest.sources).map(([key, path]) => [key, read(`../shared/${path}`)]),
  ),
  reports: Object.fromEntries(
    manifest.cases.map(({ name }) => [name, read(`../shared/parity/reports/${name}.json`)]),
  ),
};

// B0's reference grid, whose packaged replay B0 explicitly hands to F rather than claiming itself.
const referenceModels = Object.fromEntries(
  REFERENCE_MODELS.map((key) => [key, read(`../shared/models/${key}.dm.json`)]),
);

const outcomes = runSharedSuites(api, { soundModel, parityCorpus: corpus, referenceModels });

for (const { name, ok, detail } of outcomes) {
  test(name, () => {
    assert.ok(ok, detail);
  });
}
