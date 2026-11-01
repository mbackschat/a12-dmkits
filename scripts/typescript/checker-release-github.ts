import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileInventory } from './checker-maven.ts';
import { assertQualification, promoteChecker, type Receipt } from './checker-release-core.ts';
import { assembleChecker, assertMirrorTree, bundleMaven, pages, repository, type ReleaseManifest } from './checker-release-files.ts';
import { smokeChecker } from './checker-consumers.ts';

export function command(cwd: string, executable: string, args: string[], inherit = false): string {
    const result = spawnSync(executable, args, { cwd, encoding: 'utf8', stdio: inherit ? 'inherit' : 'pipe', timeout: 120_000, maxBuffer: 16 * 1024 * 1024 });
    if (result.error || result.status !== 0) throw new Error(`${executable} ${args[0]} failed: ${result.error?.message ?? result.stderr ?? result.status}`);
    return result.stdout?.trim() ?? '';
}
function gh(cwd: string, args: string[]): string { return command(cwd, 'gh', args); }
function api(cwd: string, path: string): unknown | undefined {
    const result = spawnSync('gh', ['api', path], { cwd, encoding: 'utf8', timeout: 30_000 });
    if (result.status === 0) return JSON.parse(result.stdout);
    if (result.stderr?.includes('(HTTP 404)')) return undefined;
    throw new Error(`GitHub lookup failed: ${result.stderr || result.error}`);
}
export function sourceIdentity(root: string, version: string): string {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('stable SemVer required');
    if (command(root, 'git', ['status', '--porcelain'])) throw new Error('release source checkout must be clean');
    if (!readFileSync(resolve(root, 'build.gradle'), 'utf8').includes(`\nversion = '${version}'\n`)) throw new Error('source version differs from requested release');
    const source = command(root, 'git', ['rev-parse', 'HEAD']);
    if (command(root, 'git', ['rev-parse', `consistency-v${version}^{commit}`]) !== source) throw new Error('HEAD must equal the immutable checker source tag');
    return source;
}
function githubIdentity(root: string): void {
    if (command(root, 'git', ['config', 'user.email']) !== 'mbackschat@mail.de') throw new Error('GitHub push requires mbackschat@mail.de');
}
function equalInventory(expected: Record<string, string>, actual: Record<string, string>): void {
    if (JSON.stringify(Object.entries(expected).sort()) !== JSON.stringify(Object.entries(actual).sort())) throw new Error('published payload inventory differs from its sealed manifest');
}
async function anonymousBytes(url: string): Promise<Buffer> {
    const response = await fetch(url, { signal: AbortSignal.timeout(60_000), headers: { 'Cache-Control': 'no-cache' } });
    if (!response.ok) throw new Error(`anonymous download failed: ${response.status} ${url}`);
    return Buffer.from(await response.arrayBuffer());
}
function digest(bytes: Buffer): string { return createHash('sha256').update(bytes).digest('hex'); }
type Release = { draft: boolean; tag_name: string; assets: { id: number; name: string }[] };
function getRelease(root: string, version: string): Release | undefined {
    // Tag lookup returned 404 for the initial draft; authenticated enumeration returned it (2026-11-01).
    const pages = JSON.parse(gh(root, ['api', `repos/${repository}/releases?per_page=100`, '--paginate', '--slurp'])) as Release[][];
    return pages.flat().find(release => release.tag_name === `v${version}`);
}
function cloneMirror(root: string): string {
    const mirror = resolve(root, 'build/consistency-release/mirror');
    rmSync(mirror, { recursive: true, force: true });
    gh(root, ['repo', 'clone', repository, mirror]);
    command(mirror, 'git', ['config', 'user.name', 'Martin Backschat']);
    command(mirror, 'git', ['config', 'user.email', 'mbackschat@mail.de']);
    assertMirrorTree(mirror);
    return mirror;
}
function releaseAssets(output: string, manifest: ReleaseManifest): Record<string, string> {
    const name = 'release-manifest.json';
    writeFileSync(resolve(output, name), JSON.stringify(manifest, null, 2) + '\n');
    return { ...manifest.assets, [name]: digest(readFileSync(resolve(output, name))) };
}
function verifyRelease(root: string, release: Release, expected: Record<string, string>, allowMissing = false): void {
    const actual = Object.fromEntries(release.assets.map(asset => [asset.name, asset]));
    if (Object.keys(actual).some(name => !expected[name]) || (!allowMissing && Object.keys(actual).length !== Object.keys(expected).length)) throw new Error('unexpected or missing release assets');
    for (const [name, asset] of Object.entries(actual)) {
        const result = spawnSync('gh', ['api', '-H', 'Accept: application/octet-stream', `repos/${repository}/releases/assets/${asset.id}`], { cwd: root, timeout: 60_000, maxBuffer: 32 * 1024 * 1024 });
        if (result.status !== 0 || digest(result.stdout) !== expected[name]) throw new Error(`draft asset hash mismatch: ${name}`);
    }
}
export async function stageChecker(root: string, receipt: Receipt): Promise<void> {
    const source = sourceIdentity(root, receipt.version);
    const staging = resolve(root, 'build/consistency-release/maven');
    assertQualification(receipt, receipt.version, source, fileInventory(staging));
    const remote = command(root, 'git', ['remote', 'get-url', 'origin']);
    if (remote !== 'https://github.com/mbackschat/a12-dmkits.git') throw new Error('unexpected source repository');
    const remoteTag = command(root, 'git', ['ls-remote', 'origin', `refs/tags/consistency-v${receipt.version}`, `refs/tags/consistency-v${receipt.version}^{}`]);
    if (!remoteTag.split('\n').some(line => line.startsWith(source + '\t'))) throw new Error('source tag must be pushed before staging');
    githubIdentity(root);
    if (!api(root, `repos/${repository}`)) gh(root, ['repo', 'create', repository, '--public', '--description', 'Kernel-free A12 consistency checker Maven releases']);
    const existingRelease = getRelease(root, receipt.version);
    if (existingRelease && !existingRelease.draft) throw new Error('published release is immutable; use go-live to resume verification');
    const mirror = cloneMirror(root);
    const output = resolve(root, 'build/consistency-release/assets');
    mkdirSync(output, { recursive: true });
    const tag = `v${receipt.version}`;
    const tagExists = command(mirror, 'git', ['tag', '--list', tag]) === tag;
    let manifest: ReleaseManifest;
    if (tagExists) {
        if (command(mirror, 'git', ['rev-parse', tag]) !== command(mirror, 'git', ['rev-parse', 'HEAD'])) throw new Error('staged tag is not current mirror HEAD');
        manifest = JSON.parse(readFileSync(resolve(mirror, `releases/${tag}.json`), 'utf8'));
        assertQualification(manifest, receipt.version, source, receipt.files);
        equalInventory(manifest.site, fileInventory(resolve(mirror, 'site')));
        equalInventory(manifest.assets, bundleMaven(staging, output, receipt.version));
    } else {
        manifest = assembleChecker(root, mirror, staging, output, receipt);
        command(mirror, 'git', ['add', '.']);
        command(mirror, 'git', ['checkout', '-B', 'main']);
        command(mirror, 'git', ['commit', '-m', `chore(release): publish checker ${receipt.version}`]);
        command(mirror, 'git', ['tag', tag]);
        githubIdentity(mirror);
        command(mirror, 'git', ['push', '--atomic', 'origin', 'main', `refs/tags/${tag}`]);
    }
    const assets = releaseAssets(output, manifest);
    if (!existingRelease) gh(root, ['release', 'create', tag, '--repo', repository, '--verify-tag', '--draft', '--title', `A12 consistency checker ${receipt.version}`, '--notes', `Maven publications for the checker and its predicate dependency. Usage: https://github.com/${repository}. Source: consistency-v${receipt.version} (${source}).`]);
    const release = getRelease(root, receipt.version)!;
    verifyRelease(root, release, assets, true);
    for (const name of Object.keys(assets)) if (!release.assets.some(a => a.name === name)) gh(root, ['release', 'upload', tag, resolve(output, name), '--repo', repository]);
    verifyRelease(root, getRelease(root, receipt.version)!, assets);
    console.log(`Staged immutable ${tag}; ready for go-live`);
}
async function deployPages(root: string, version: string, mirrorSha: string): Promise<void> {
    const endpoint = `repos/${repository}/pages`;
    const current = api(root, endpoint) as { build_type: string } | undefined;
    if (!current) gh(root, ['api', '--method', 'POST', endpoint, '-f', 'build_type=workflow']);
    else if (current.build_type !== 'workflow') throw new Error('Pages is not configured for workflow deployment');
    const started = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
    gh(root, ['workflow', 'run', 'deploy-pages.yml', '--repo', repository, '--ref', `v${version}`]);
    const deadline = Date.now() + 600_000;
    while (Date.now() < deadline) {
        const runs = JSON.parse(gh(root, ['run', 'list', '--repo', repository, '--workflow', 'deploy-pages.yml', '--json', 'databaseId,headSha,createdAt,status,conclusion', '--limit', '20'])) as { databaseId: number; headSha: string; createdAt: string; status: string; conclusion: string }[];
        const run = runs.find(r => r.headSha === mirrorSha && r.createdAt >= started);
        if (run?.status === 'completed') {
            if (run.conclusion !== 'success') throw new Error(`Pages run ${run.databaseId} failed: ${run.conclusion}`);
            console.log(`Pages deployment passed: ${run.databaseId}`); return;
        }
        console.log(`Waiting for Pages deployment${run ? ` ${run.databaseId}` : ''}`);
        await new Promise(resolve => setTimeout(resolve, 10_000));
    }
    throw new Error('Pages deployment deadline exceeded; go-live can be retried');
}
type PublicRuntime = { download(url: string): Promise<Buffer>; consumers: typeof smokeChecker };
export async function goLiveChecker(root: string, version: string, runtime: PublicRuntime = { download: anonymousBytes, consumers: smokeChecker }): Promise<void> {
    const source = sourceIdentity(root, version);
    const mirror = cloneMirror(root);
    const tag = `v${version}`;
    const mirrorSha = command(mirror, 'git', ['rev-parse', `${tag}^{commit}`]);
    if (mirrorSha !== command(mirror, 'git', ['rev-parse', 'HEAD'])) throw new Error('release tag is not the latest mirror commit; refuse Pages rollback');
    const manifest: ReleaseManifest = JSON.parse(readFileSync(resolve(mirror, `releases/${tag}.json`), 'utf8'));
    if (manifest.schema !== 1 || manifest.source !== source || manifest.version !== version || manifest.sourceTag !== `consistency-v${version}`) throw new Error('mirror release identity differs from source');
    equalInventory(manifest.site, fileInventory(resolve(mirror, 'site')));
    const output = resolve(root, 'build/consistency-release/assets'); mkdirSync(output, { recursive: true });
    const assets = releaseAssets(output, manifest);
    const release = getRelease(root, version);
    if (!release) throw new Error('release must be staged first');
    await promoteChecker({
        draft: release.draft,
        verifyDraft: async () => verifyRelease(root, release, assets),
        deploy: () => deployPages(root, version, mirrorSha),
        verifyPages: async () => {
            // Verify every advertised byte, including transitive artifacts and historical versions, before invoking consumers.
            for (const [name, hash] of Object.entries(manifest.site)) {
                if (digest(await runtime.download(`${pages}/${name}`)) !== hash) throw new Error(`Pages hash mismatch: ${name}`);
            }
            console.log(`Anonymous Pages inventory verified: ${Object.keys(manifest.site).length} files`);
        },
        consumers: () => runtime.consumers(root, version, `${pages}/maven/`, 'public'),
        publish: async () => { gh(root, ['release', 'edit', tag, '--repo', repository, '--draft=false', '--latest=false']); },
        verifyAnonymous: async () => {
            const publicRelease = JSON.parse((await runtime.download(`https://api.github.com/repos/${repository}/releases/tags/${tag}`)).toString()) as Release;
            if (publicRelease.draft || publicRelease.tag_name !== tag || JSON.stringify(publicRelease.assets.map(a => a.name).sort()) !== JSON.stringify(Object.keys(assets).sort())) throw new Error('anonymous release identity/inventory mismatch');
            for (const [name, hash] of Object.entries(assets)) if (digest(await runtime.download(`https://github.com/${repository}/releases/download/${tag}/${name}`)) !== hash) throw new Error(`anonymous asset mismatch: ${name}`);
        },
        latest: async () => { gh(root, ['release', 'edit', tag, '--repo', repository, '--latest']); },
    });
    console.log(`Published latest: https://github.com/${repository}/releases/tag/${tag}`);
}
