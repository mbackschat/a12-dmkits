// The first end-to-end proof that the checker is consumable from Node.
//
// What it asserts is the PLUMBING, not the coverage: that the packed npm package installs, imports, runs a
// real model through the checker, and returns a report a consumer can read. The checker is deliberately NOT
// feature-complete, so a partial verdict over a partial family set passes here — and a checker that cannot
// be installed or called fails, however complete its families are.
//
// It imports `a12-consistency` BY PACKAGE NAME, not by a relative path into the build tree. That is the
// whole point of packing a tarball first: a relative import would prove the compiler emitted a file, where
// this proves a consumer's `node_modules` resolution reaches the same code.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkModelJson } from 'a12-consistency';

const soundModel = readFileSync(new URL('./models/storefront.dm.json', import.meta.url), 'utf8');

/** The same model with the rule's condition naming a field the model does not declare. */
const brokenModel = soundModel.replace('FieldNotFilled(CustomerEmail)', 'FieldNotFilled(NoSuchField)');

test('a model the checker finds nothing wrong with is INCONCLUSIVE, never valid', () => {
  const report = JSON.parse(checkModelJson(soundModel));

  assert.equal(report.modelId, 'storefront');
  assert.deepEqual(report.findings, []);
  // The single most misreadable thing about this checker, asserted rather than described: no finding does
  // NOT mean the model is valid. The checker cannot say VALID at all, and `complete` says why.
  assert.equal(report.status, 'INCONCLUSIVE');
  assert.equal(report.complete, false);
  assert.ok(report.checksCompleted.length > 0, 'families ran, so the report is not empty of evidence');
  assert.ok(report.gaps.length > 0, 'an incomplete checker always discloses what it did not check');
});

test('a rule naming an undeclared field is INVALID, with the code A12 gives', () => {
  const report = JSON.parse(checkModelJson(brokenModel));

  assert.equal(report.status, 'INVALID');
  const finding = report.findings.find((f) => f.code === 'MVK_INVALID_ENTITY');
  assert.ok(finding, `expected MVK_INVALID_ENTITY, got ${report.findings.map((f) => f.code).join(', ')}`);
  assert.equal(finding.at, '/Storefront/EmailRequiredForPaidOrder');
  assert.equal(finding.severity, 'ERROR');
  // Not derivable from severity alone: an AUTHORING_POLICY finding at ERROR is deliberately not a rejection.
  assert.equal(finding.justifiesRejection, true);
  // A12's own guidance for the code, projected from its Java owners into the packaged library. Asserted
  // HERE because the projection is generated on the JVM side: only a real Node consumer can show that it
  // survives packaging, and reading the generator's output proves nothing about what ships.
  assert.ok(finding.corrective, 'MVK_INVALID_ENTITY is a code A12 explains');
  assert.ok(finding.corrective.fix.length > 0);
  assert.ok(finding.corrective.meaning.length > 0);
});

test('an unreadable source returns a report rather than throwing', () => {
  const report = JSON.parse(checkModelJson('not json at all'));

  assert.equal(report.modelId, null);
  assert.deepEqual(report.findings, []);
  assert.ok(report.diagnostics.length > 0, 'the failure is stated as a diagnostic, not as a finding');
  // A diagnostic is the checker saying it could not read something; a finding is A12's verdict on a
  // declaration. A consumer that merged the two arrays would report a model defect that was never measured.
  assert.equal(report.diagnostics[0].at, null);
});
