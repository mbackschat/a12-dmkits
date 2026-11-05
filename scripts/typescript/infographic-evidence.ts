const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');

// Binds each accepted poster's pixels to the exact prompt it was reviewed against. Schema 1 also fingerprinted
// every production source so a kLOC label could not go stale; posters no longer print counts, versions or dates
// outside dated evidence (INFOGRAPHICS.md, shared visual system), so that binding only blocked pushes on edits
// the posters cannot show (2026-11-05: a comment-only change refused a push).
type Artwork = { file: string; sha256: string; promptSha256: string };
type Evidence = { schema: number; artwork: Artwork[] };
const digest = (bytes: string | Buffer): string => createHash('sha256').update(bytes).digest('hex');

function captureEvidence(root: string): Evidence {
  const specification = fs.readFileSync(path.join(root, 'docs/INFOGRAPHICS.md'), 'utf8');
  const artwork: Artwork[] = [];
  for (const section of specification.split(/^## Infographic \d+: /m).slice(1)) {
    const file = section.match(/<img src="\.\.\/(assets\/infographics\/[^"/]+\.avif)"/)?.[1];
    const prompt = section.match(/### Generation prompt[^]*?```text\n([^]*?)\n```/)?.[1];
    if (!file || !prompt) throw new Error('infographic lacks its image or exact generation prompt');
    artwork.push({ file, sha256: digest(fs.readFileSync(path.join(root, file))), promptSha256: digest(prompt) });
  }
  if (!artwork.length) throw new Error('empty infographic evidence inventory');
  return { schema: 2, artwork };
}

function verifyEvidence(root: string, expected: Evidence): void {
  if (expected.schema !== 2) throw new Error('infographic evidence predates schema 2; record the reviewed artwork again');
  if (JSON.stringify(captureEvidence(root).artwork) !== JSON.stringify(expected.artwork)) {
    throw new Error('infographic accepted artwork or prompt changed; inspect the complete poster and record its accepted evidence');
  }
}
module.exports = { captureEvidence, verifyEvidence };

if (require.main === module) {
  const root = path.resolve(__dirname, '../..');
  const target = path.join(root, 'docs/infographic-evidence.json');
  try {
    if (process.argv.slice(2).join(' ') !== '--record-reviewed') throw new Error('usage: infographic-evidence.ts --record-reviewed (after visual acceptance)');
    fs.writeFileSync(target, JSON.stringify(captureEvidence(root), null, 2) + '\n');
    console.log('Recorded inspected artwork and its exact prompts');
  } catch (error) { console.error((error as Error).message); process.exitCode = 1; }
}
