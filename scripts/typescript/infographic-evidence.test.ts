const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const test = require('node:test');
const { captureEvidence, verifyEvidence } = require('./infographic-evidence.ts');

test('accepted pixels and their exact prompt stay bound, while production sources do not', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'infographic-evidence-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const dir of ['core/src/main/java', 'assets/infographics', 'docs']) fs.mkdirSync(path.join(root, dir), { recursive: true });
  const source = path.join(root, 'core/src/main/java', 'Example.java'); fs.writeFileSync(source, 'class Example {}');
  const image = path.join(root, 'assets/infographics/example.avif'); fs.writeFileSync(image, 'accepted pixels');
  const doc = path.join(root, 'docs/INFOGRAPHICS.md');
  const prompt = '## Infographic 1: Example\n<img src="../assets/infographics/example.avif">\n### Generation prompt\n\n```text\nExact claim\n```\n';
  fs.writeFileSync(doc, prompt);
  const evidence = captureEvidence(root);
  assert.doesNotThrow(() => verifyEvidence(root, evidence));
  fs.writeFileSync(source, 'class Example { int added; }');
  assert.doesNotThrow(() => verifyEvidence(root, evidence));
  assert.throws(() => verifyEvidence(root, { ...evidence, schema: 1 }), /schema 2/);
  fs.writeFileSync(image, 'different pixels');
  assert.throws(() => verifyEvidence(root, evidence), /accepted artwork/);
  fs.writeFileSync(image, 'accepted pixels');
  fs.writeFileSync(doc, prompt.replace('Exact claim', 'Different claim'));
  assert.throws(() => verifyEvidence(root, evidence), /accepted artwork/);
  fs.writeFileSync(doc, 'no registered poster');
  assert.throws(() => captureEvidence(root), /empty/);
});
