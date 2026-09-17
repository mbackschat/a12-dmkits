// Regenerate the three-arm parity corpus from the PACKED package.
//
// Run through `./build.sh :consistency:syncCheckerParityCorpus`, never by hand: the task packs the tarball
// and installs it first, so the corpus is written by the artifact a consumer receives rather than by whatever
// happens to be compiled. That direction matters — the JVM arm then compares against a report the packaged
// JS produced, which is the parity relation F's oracle names.
//
// It records the INPUT each case ran on by PATH, and the paths are relative to `consumer-tests/shared/`, so a
// model that is already committed for another suite is referenced rather than copied. Only the sources this
// corpus DERIVES (a substitution, an unparseable string) are written, into `parity/sources/`. An arm that
// derived its own input could differ from another arm's by one substitution, and the mismatch would read as a
// platform divergence — hence bytes, from one place, for every arm.

import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { checkEditJson, checkModelJson } from 'a12-consistency';

const [sharedDir] = process.argv.slice(2);
if (!sharedDir) {
  throw new Error('usage: node write-parity-corpus.mjs <consumer-tests/shared dir>');
}
const corpusDir = join(sharedDir, 'parity');
const read = (relative) => readFileSync(join(sharedDir, relative), 'utf8');

const sound = read('models/storefront.dm.json');
const broken = sound.replace('FieldNotFilled(CustomerEmail)', 'FieldNotFilled(NoSuchField)');
if (broken === sound) {
  throw new Error('the fixture no longer spells its rule condition as FieldNotFilled(CustomerEmail)');
}
const anchor = '"elements": [';
if (!sound.includes(anchor)) {
  throw new Error('the fixture must still declare its elements in the expected shape');
}
// An unrelated declared field: nothing references it, so it cannot introduce a finding, which is what makes
// the attribution cases about the MODEL's state rather than about the edit's own effect.
const unrelatedField = '{ "type": "Field", "id": "/Storefront/InternalNote", "name": "InternalNote",'
  + ' "Field": { "fieldType": { "type": "StringType" } } },';

/** Sources this corpus DERIVES and therefore owns, written under `parity/sources/`. */
const derived = {
  broken: { file: 'storefront-broken.dm.json', text: broken },
  'broken-plus-field': {
    file: 'storefront-broken-plus-field.dm.json',
    text: broken.replace(anchor, anchor + unrelatedField),
  },
  unreadable: { file: 'not-a-model.txt', text: 'not json at all' },
};

/** Sources committed for another suite and referenced by path — never a second copy. */
const referenced = {
  sound: 'models/storefront.dm.json',
  // B0's §3.4 grid, whose per-row semantics `references.ts` asserts. Here they serve a different purpose:
  // five more model shapes — a config flag, an ambiguity, a rule nested one level down — whose FULL report
  // must agree across all four readers.
  'catalog-resolved': 'models/catalog-reference-resolved.dm.json',
  'catalog-no-fallback': 'models/catalog-reference-no-fallback.dm.json',
  'catalog-absent': 'models/catalog-reference-absent.dm.json',
  'catalog-ambiguous': 'models/catalog-reference-ambiguous.dm.json',
  'catalog-declaring-wins': 'models/catalog-reference-declaring-wins.dm.json',
};

const cases = [
  { name: 'model-sound', call: 'checkModel', sources: ['sound'] },
  { name: 'model-broken', call: 'checkModel', sources: ['broken'] },
  { name: 'model-unreadable', call: 'checkModel', sources: ['unreadable'] },
  { name: 'edit-unrelated-on-broken', call: 'checkEdit', sources: ['broken', 'broken-plus-field'] },
  { name: 'edit-breaks-the-model', call: 'checkEdit', sources: ['sound', 'broken'] },
  { name: 'edit-repairs-the-model', call: 'checkEdit', sources: ['broken', 'sound'] },
  { name: 'catalog-resolved', call: 'checkModel', sources: ['catalog-resolved'] },
  { name: 'catalog-no-fallback', call: 'checkModel', sources: ['catalog-no-fallback'] },
  { name: 'catalog-absent', call: 'checkModel', sources: ['catalog-absent'] },
  { name: 'catalog-ambiguous', call: 'checkModel', sources: ['catalog-ambiguous'] },
  { name: 'catalog-declaring-wins', call: 'checkModel', sources: ['catalog-declaring-wins'] },
];

// A full rewrite rather than an update: a renamed case must not leave its old file behind, where an arm that
// read the directory would still find it and an arm that read the manifest would not.
rmSync(join(corpusDir, 'sources'), { recursive: true, force: true });
rmSync(join(corpusDir, 'reports'), { recursive: true, force: true });
mkdirSync(join(corpusDir, 'sources'), { recursive: true });
mkdirSync(join(corpusDir, 'reports'), { recursive: true });

const sources = { ...referenced };
for (const [key, { file, text }] of Object.entries(derived)) {
  writeFileSync(join(corpusDir, 'sources', file), text);
  sources[key] = `parity/sources/${file}`;
}

const report = (kase) => {
  const text = kase.sources.map((key) => read(sources[key]));
  switch (kase.call) {
    case 'checkModel':
      return checkModelJson(text[0]);
    case 'checkEdit':
      return checkEditJson(text[0], text[1]);
    default:
      throw new Error(`unknown call ${kase.call}`);
  }
};

for (const kase of cases) {
  const pretty = `${JSON.stringify(JSON.parse(report(kase)), null, 2)}\n`;
  writeFileSync(join(corpusDir, 'reports', `${kase.name}.json`), pretty);
}
writeFileSync(
  join(corpusDir, 'manifest.json'),
  `${JSON.stringify({ sources, cases }, null, 2)}\n`,
);
process.stdout.write(
  `wrote ${cases.length} parity case(s) over ${Object.keys(sources).length} source(s), `
  + `${Object.keys(derived).length} of them derived here\n`,
);
