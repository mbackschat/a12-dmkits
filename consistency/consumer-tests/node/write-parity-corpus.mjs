// Regenerate the three-arm parity corpus from the PACKED package.
//
// Run through `./build.sh :consistency:syncCheckerParityCorpus`, never by hand: the task packs the tarball
// and installs it first, so the corpus is written by the artifact a consumer receives rather than by whatever
// happens to be compiled. That direction matters — the JVM arm then compares against a report the packaged
// JS produced, which is the parity relation F's oracle names.
//
// It writes the INPUT BYTES as well as the reports. An arm that derived its own input could differ from
// another arm's input by a substitution, and the resulting mismatch would read as a platform divergence.

import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { checkEditJson, checkModelJson } from 'a12-consistency';

const [corpusDir, soundModelPath] = process.argv.slice(2);
if (!corpusDir || !soundModelPath) {
  throw new Error('usage: node write-parity-corpus.mjs <corpus-dir> <sound-model-path>');
}

const sound = readFileSync(soundModelPath, 'utf8');
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
const brokenPlusField = broken.replace(anchor, anchor + unrelatedField);

const sources = {
  sound: { file: 'storefront.dm.json', text: sound },
  broken: { file: 'storefront-broken.dm.json', text: broken },
  'broken-plus-field': { file: 'storefront-broken-plus-field.dm.json', text: brokenPlusField },
  unreadable: { file: 'not-a-model.txt', text: 'not json at all' },
};

const cases = [
  { name: 'model-sound', call: 'checkModel', sources: ['sound'] },
  { name: 'model-broken', call: 'checkModel', sources: ['broken'] },
  { name: 'model-unreadable', call: 'checkModel', sources: ['unreadable'] },
  { name: 'edit-unrelated-on-broken', call: 'checkEdit', sources: ['broken', 'broken-plus-field'] },
  { name: 'edit-breaks-the-model', call: 'checkEdit', sources: ['sound', 'broken'] },
  { name: 'edit-repairs-the-model', call: 'checkEdit', sources: ['broken', 'sound'] },
];

const report = (kase) => {
  const text = kase.sources.map((key) => sources[key].text);
  switch (kase.call) {
    case 'checkModel':
      return checkModelJson(text[0]);
    case 'checkEdit':
      return checkEditJson(text[0], text[1]);
    default:
      throw new Error(`unknown call ${kase.call}`);
  }
};

// A full rewrite rather than an update: a renamed case must not leave its old file behind, where an arm that
// read the directory would still find it and an arm that read the manifest would not.
rmSync(join(corpusDir, 'sources'), { recursive: true, force: true });
rmSync(join(corpusDir, 'reports'), { recursive: true, force: true });
mkdirSync(join(corpusDir, 'sources'), { recursive: true });
mkdirSync(join(corpusDir, 'reports'), { recursive: true });

for (const { file, text } of Object.values(sources)) {
  writeFileSync(join(corpusDir, 'sources', file), text);
}
for (const kase of cases) {
  const pretty = `${JSON.stringify(JSON.parse(report(kase)), null, 2)}\n`;
  writeFileSync(join(corpusDir, 'reports', `${kase.name}.json`), pretty);
}
writeFileSync(
  join(corpusDir, 'manifest.json'),
  `${JSON.stringify({
    sources: Object.fromEntries(Object.entries(sources).map(([key, { file }]) => [key, file])),
    cases: cases.map(({ name, call, sources: keys }) => ({ name, call, sources: keys })),
  }, null, 2)}\n`,
);
process.stdout.write(`wrote ${cases.length} parity case(s) and ${Object.keys(sources).length} source(s)\n`);
