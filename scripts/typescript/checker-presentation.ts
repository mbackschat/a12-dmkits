import { mkdirSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assertHistoricalArtifacts } from './checker-release-core.ts';
import { fileInventory } from './checker-maven.ts';
import { assemblePresentation, protectedInventory, assertPresentationChanges, type Presentation } from './checker-presentation-files.ts';
import { buildShowcase } from './checker-showcase.ts';
import { command, githubIdentity, getRelease, cloneMirror, equalInventory, verifyRelease, releaseAssets, deployPagesTag, anonymousBytes } from './checker-release-github.ts';
import { pages, type ReleaseManifest } from './checker-release-files.ts';
import { createHash } from 'node:crypto';

type Runtime = { build(root: string, version: string): string; download(url: string): Promise<Buffer> };
const runtime: Runtime = {
    build(root, version) {
        command(root, './build.sh', [':consistency:packConsistencyNpmPackage', '--console=plain'], true);
        const showcase = buildShowcase(root, version);
        command(resolve(root, 'build/consistency-showcase'), 'pnpm', ['run', 'verify'], true);
        return showcase;
    },
    download: anonymousBytes,
};
export async function refreshPresentation(root: string, version: string, operations: Runtime = runtime): Promise<void> {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('stable SemVer required');
    if (command(root, 'git', ['status', '--porcelain'])) throw new Error('presentation source checkout must be clean');
    if (command(root, 'git', ['remote', 'get-url', 'origin']) !== 'https://github.com/mbackschat/a12-dmkits.git') throw new Error('unexpected source repository');
    githubIdentity(root);
    const source = command(root, 'git', ['rev-parse', 'HEAD']);
    const sourceTag = `consistency-v${version}`;
    const sourceRelease = command(root, 'git', ['rev-parse', `${sourceTag}^{commit}`]);
    assertPresentationChanges(command(root, 'git', ['diff', '--name-only', '-z', sourceTag]).split('\0').filter(Boolean));
    command(root, './scripts/check-infographic-freshness.sh', [], true);
    const release = getRelease(root, version);
    if (!release || release.draft) throw new Error('presentation refresh requires an already public release');
    const mirror = cloneMirror(root);
    const releaseTag = `v${version}`, tag = `presentation-v${version}-${source}`;
    const tagsBefore = command(mirror, 'git', ['show-ref', '--tags']);
    const before = protectedInventory(mirror);
    const manifest: ReleaseManifest = JSON.parse(readFileSync(resolve(mirror, `releases/${releaseTag}.json`), 'utf8'));
    if (manifest.version !== version || manifest.source !== sourceRelease || manifest.sourceTag !== sourceTag) throw new Error('release identity differs from source tag');
    const history = readdirSync(resolve(mirror, 'releases')).map(name => JSON.parse(readFileSync(resolve(mirror, 'releases', name), 'utf8')) as ReleaseManifest);
    assertHistoricalArtifacts(fileInventory(resolve(mirror, 'site/maven')), history);
    const output = resolve(root, 'build/checker-presentation/assets'); mkdirSync(output, { recursive: true });
    const assets = releaseAssets(output, manifest);
    verifyRelease(root, release, assets);
    let presentation: Presentation;
    const existingTag = command(mirror, 'git', ['tag', '--list', tag]) === tag;
    if (existingTag) {
        if (command(mirror, 'git', ['rev-parse', tag]) !== command(mirror, 'git', ['rev-parse', 'HEAD'])) throw new Error('presentation tag is not current HEAD; refuse rollback');
        presentation = JSON.parse(readFileSync(resolve(mirror, `presentations/v${version}-${source}.json`), 'utf8'));
        if (presentation.version !== version || presentation.source !== source) throw new Error('presentation identity mismatch');
        equalInventory(presentation.site, fileInventory(resolve(mirror, 'site')));
        equalInventory(presentation.protectedFiles, before);
    } else {
        const showcase = operations.build(root, version);
        presentation = assemblePresentation(root, mirror, showcase, version, source);
        equalInventory(before, protectedInventory(mirror));
        if (command(mirror, 'git', ['show-ref', '--tags']) !== tagsBefore) throw new Error('existing release tags changed');
        if (command(root, 'git', ['status', '--porcelain']) || command(root, 'git', ['rev-parse', 'HEAD']) !== source) throw new Error('presentation source changed while building');
        command(mirror, 'git', ['add', '.']);
        command(mirror, 'git', ['commit', '-m', `docs(checker): publish ${version} showcase and presentation`, '-m', 'Keep the published Maven and release identities intact while updating the public documentation and browser experience.']);
        command(mirror, 'git', ['tag', tag]);
        githubIdentity(mirror);
        command(mirror, 'git', ['push', '--atomic', 'origin', 'main', `refs/tags/${tag}`]);
    }
    await deployPagesTag(root, tag, command(mirror, 'git', ['rev-parse', 'HEAD']));
    for (const [name, expected] of Object.entries(presentation.site)) {
        const bytes = await operations.download(`${pages}/${name}`);
        if (createHash('sha256').update(bytes).digest('hex') !== expected) throw new Error(`public Pages mismatch: ${name}`);
    }
    verifyRelease(root, getRelease(root, version)!, assets);
    equalInventory(before, protectedInventory(mirror));
    const remoteTags = command(mirror, 'git', ['ls-remote', '--tags', 'origin']);
    for (const line of tagsBefore.split('\n')) {
        const [sha, ref] = line.split(' ');
        if (!remoteTags.split('\n').includes(`${sha}\t${ref}`)) throw new Error(`existing remote tag changed: ${ref}`);
    }
    console.log(`Published checker presentation ${tag}; release assets, Maven files and existing tags unchanged`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    if (process.argv.length !== 3) { console.error('usage: checker-presentation.ts VERSION'); process.exitCode = 1; }
    else refreshPresentation(process.cwd(), process.argv[2]).catch(error => { console.error(error.message); process.exitCode = 1; });
}
