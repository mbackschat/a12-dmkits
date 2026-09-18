// What this locks about [ci-gate.mjs](./ci-gate.mjs), and why each row is here rather than in the Kotlin tests.
//
// The gate is a CONSUMER, so its defects are consumer defects: reading "no findings" as a pass, collapsing a
// tool failure into a rejection, or exporting an exit code the process never actually exits with. None of
// those is visible from inside the checker, and all four outcomes it has to tell apart are reachable here.
//
// The subprocess row is the load-bearing one: an exported `ExitCode` constant is a claim about a running
// pipeline, so it is settled by running the pipeline and reading what the process exits with — not by
// comparing the gate's return value against the constant it was built from.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkModelJson } from 'a12-consistency';
import { classify, ExitCode, Outcome, runGate } from './ci-gate.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const gate = join(here, 'ci-gate.mjs');
// The one committed fixture, owned by neither arm: `consumer-tests/shared/models`, staged as a sibling.
const soundPath = join(here, '..', 'shared', 'models', 'storefront.dm.json');
const soundModel = readFileSync(soundPath, 'utf8');

/** A scratch directory, so the broken and unreadable sources are real files the gate opens by path. */
const scratch = mkdtempSync(join(tmpdir(), 'a12-ci-gate-'));

function write(name, text) {
  const path = join(scratch, name);
  writeFileSync(path, text);
  return path;
}

/** The fixture's rule pointed at a field the model does not declare — A12 rejects it. */
const brokenPath = write('storefront-broken.dm.json',
  soundModel.replace('FieldNotFilled(CustomerEmail)', 'FieldNotFilled(NoSuchField)'));
const garbagePath = write('not-a-model.dm.json', 'not json at all');
const missingPath = join(scratch, 'never-written.dm.json');

/**
 * One extra declaration stripped of the `name` the kernel's reader requires, beside an otherwise sound
 * model. This is the discriminator between the two classifications the gate could have keyed on: the report
 * carries a diagnostic AND ran its families (103 of them, measured 2026-09-16), so "any diagnostic means the
 * tool failed" and "no family ran means the tool failed" disagree here and nowhere else in this file.
 */
const partiallyReadablePath = write('storefront-malformed-declaration.dm.json',
  soundModel.replace('"elements": [', '"elements": [{ "type": "Field", "id": "/Storefront/Nameless" },'));

test('a sound model passes every check, and is still not A12-validated', () => {
  const result = runGate([soundPath]);

  assert.equal(result.exitCode, ExitCode.NO_REJECTION);
  assert.equal(result.results[0].outcome, Outcome.ALL_CHECKS_PASSED);
  assert.equal(result.allChecksPassed.length, 1, 'the scoped pass is published per source');
  assert.match(result.allChecksPassed[0].source, /storefront\.dm\.json$/);
  assert.deepEqual(result.rejected, []);
  // The whole point of the slice: passing the gate is not a validation claim, and the payload says so with
  // the checker's own reasons rather than leaving a consumer to infer it from an empty findings array.
  assert.equal(result.validated, false);
  assert.equal(result.notValidated.length, 1);
  assert.ok(result.notValidated[0].gaps.length > 0, 'the gate republishes why nothing was validated');
});

test('a rejected model fails the gate with the codes that justify the rejection', () => {
  const result = runGate([brokenPath]);

  assert.equal(result.exitCode, ExitCode.REJECTED);
  assert.equal(result.results[0].outcome, Outcome.REJECTED);
  assert.deepEqual(result.rejected[0].codes, ['MVK_INVALID_ENTITY']);
  assert.deepEqual(result.operational, [], 'a rejection is a verdict, not a tool failure');
  assert.deepEqual(result.notValidated, []);
});

test('a source the checker cannot admit is an operational failure, never a rejection', () => {
  const result = runGate([garbagePath]);

  assert.equal(result.exitCode, ExitCode.OPERATIONAL);
  assert.equal(result.results[0].outcome, Outcome.NOT_ESTABLISHED);
  assert.deepEqual(result.rejected, [], 'nothing was checked, so nothing can be rejected');
  assert.match(result.operational[0].failure, /SOURCE_NOT_WELL_FORMED/);
  // And no family ran, which is the structural fact the classification keys on.
  assert.deepEqual(result.results[0].report.checksCompleted, []);
});

test('a file the pipeline cannot open is operational too, and names the reason', () => {
  const result = runGate([missingPath]);

  assert.equal(result.exitCode, ExitCode.OPERATIONAL);
  assert.equal(result.results[0].outcome, Outcome.UNREADABLE);
  assert.equal(result.results[0].report, null, 'a null report means the checker was never reached');
  assert.match(result.operational[0].failure, /ENOENT/);
});

test('one unreadable DECLARATION does not make the whole run a tool failure', () => {
  const result = runGate([partiallyReadablePath]);

  // The over-rejection this gate would commit if it read "a diagnostic arrived" as "the checker failed": the
  // families did run, they rejected nothing, and the one declaration they could not read is disclosed as a
  // diagnostic. Exiting 70 here would tell a CI job its tooling was broken when its model was merely partly
  // unreadable, and the author would go looking in the wrong place.
  assert.equal(result.exitCode, ExitCode.NO_REJECTION);
  // And it is not a PASS either, which is the other half: every family completed, so the family count alone
  // would have called this clean while nothing had read the skipped declaration.
  assert.equal(result.results[0].outcome, Outcome.NO_REJECTION);
  assert.deepEqual(result.allChecksPassed, [], 'a skipped declaration is not a clean model');
  assert.deepEqual(result.operational, []);
  assert.ok(result.results[0].report.diagnostics.length > 0, 'the diagnostic is present, and is not a verdict');
  assert.ok(result.results[0].report.checksCompleted.length > 0, 'and families ran, which is what decides it');
});

test('an operational failure outranks a rejection in the same run', () => {
  const result = runGate([brokenPath, garbagePath]);

  // Both are present in the payload — the precedence is about the exit code, not about hiding half the run.
  assert.equal(result.exitCode, ExitCode.OPERATIONAL);
  assert.equal(result.rejected.length, 1);
  assert.equal(result.operational.length, 1);
  assert.equal(result.checked, 2);
});

test('a status this gate does not model fails closed rather than passing', () => {
  // Today's package cannot produce this report — the checker has no VALID state — so the classifier is
  // exercised directly. That is the point of the row: it locks how a gate pinned to THIS package would read
  // a report from a later one, and no library call can stage that.
  assert.equal(classify({ outcome: 'SOMETHING_NEWER' }), Outcome.UNRECOGNIZED);
  assert.equal(classify({}), Outcome.UNRECOGNIZED, 'an older report with no outcome member fails closed too');
  assert.equal(classify({ outcome: 'ALL_CHECKS_PASSED' }), Outcome.ALL_CHECKS_PASSED);
  assert.equal(classify({ outcome: 'NO_REJECTION' }), Outcome.NO_REJECTION);
  assert.equal(classify({ outcome: 'NOT_ESTABLISHED' }), Outcome.NOT_ESTABLISHED);
  assert.equal(classify({ outcome: 'INVALID' }), Outcome.UNRECOGNIZED, 'a STATUS is not an outcome');
  assert.equal(classify({ outcome: 'REJECTED' }), Outcome.REJECTED);
});

test('the gate re-encodes nothing: every per-source report is the library call verbatim', () => {
  const result = runGate([soundPath, brokenPath]);

  for (const perSource of result.results) {
    const text = readFileSync(perSource.source, 'utf8');
    const direct = JSON.parse(checkModelJson(text, perSource.source.split('/').pop()));
    // The summaries above are projections; the report itself is carried through untouched, so a consumer who
    // needs a field the gate does not summarize reads it rather than re-running the check.
    assert.deepEqual(perSource.report, direct);
  }
});

test('the exit codes reach the process, which is the only form CI can read', () => {
  const run = (path) => spawnSync(process.execPath, [gate, path], { cwd: here, encoding: 'utf8' });

  const sound = run(soundPath);
  assert.equal(sound.status, 0);
  assert.equal(JSON.parse(sound.stdout).validated, false, 'the gate prints its payload for the job log');

  assert.equal(run(brokenPath).status, 1);
  assert.equal(run(garbagePath).status, 70);
});
