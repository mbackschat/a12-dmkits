const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { measureRepository, selectSources } = require('./update-code-statistics.ts');

type Artwork = { file: string; sha256: string; promptSha256: string };
type Evidence = { schema: number; productionSourcesSha256: string; artwork: Artwork[] };
const digest = (bytes: string | Buffer): string => createHash('sha256').update(bytes).digest('hex');

function captureEvidence(root: string): Evidence {
  const sources: [string, string][] = [];
  for (const file of selectSources(measureRepository(root), 'root', 'Production')) {
    if (!['Java', 'Kotlin'].includes(file.language)) continue;
    sources.push([file.path, digest(fs.readFileSync(path.join(root, file.path)))]);
  }
  if (!sources.length) throw new Error('empty production source inventory');
  const specification = fs.readFileSync(path.join(root, 'docs/INFOGRAPHICS.md'), 'utf8');
  const artwork: Artwork[] = [];
  for (const section of specification.split(/^## Infographic \d+: /m).slice(1)) {
    const file = section.match(/<img src="\.\.\/(assets\/infographics\/[^"/]+\.avif)"/)?.[1];
    const prompt = section.match(/### Generation prompt[^]*?```text\n([^]*?)\n```/)?.[1];
    if (!file || !prompt) throw new Error('infographic lacks its image or exact generation prompt');
    artwork.push({ file, sha256: digest(fs.readFileSync(path.join(root, file))), promptSha256: digest(prompt) });
  }
  if (!artwork.length) throw new Error('empty infographic evidence inventory');
  return { schema: 1, productionSourcesSha256: digest(JSON.stringify(sources.sort(([a], [b]) => a.localeCompare(b, 'en')))), artwork };
}

function verifyEvidence(root: string, expected: Evidence): void {
  const actual = captureEvidence(root);
  if (expected.schema !== 1 || actual.productionSourcesSha256 !== expected.productionSourcesSha256) {
    throw new Error('infographic production source evidence is stale; measure source statistics and revalidate the technology counts');
  }
  if (JSON.stringify(actual.artwork) !== JSON.stringify(expected.artwork)) {
    throw new Error('infographic accepted artwork or prompt changed; inspect the complete poster and record its accepted evidence');
  }
}
module.exports = { captureEvidence, verifyEvidence };

if (require.main === module) {
  const root = path.resolve(__dirname, '../..');
  const target = path.join(root, 'docs/infographic-evidence.json');
  try {
    if (process.argv.slice(2).join(' ') !== '--record-reviewed') throw new Error('usage: infographic-evidence.ts --record-reviewed (after visual acceptance)');
    execFileSync(path.join(root, 'scripts/update-code-statistics.sh'), ['--check'], { stdio: 'inherit', timeout: 30_000 });
    fs.writeFileSync(target, JSON.stringify(captureEvidence(root), null, 2) + '\n');
    console.log('Recorded inspected artwork and measured production source identity');
  } catch (error) { console.error((error as Error).message); process.exitCode = 1; }
}
