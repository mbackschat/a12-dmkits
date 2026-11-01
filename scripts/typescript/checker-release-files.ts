import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileInventory, scanBytes } from './checker-maven.ts';
import { assertHistoricalArtifacts, mergeMaven, type Receipt } from './checker-release-core.ts';

export const repository = 'mbackschat/a12-consistency-releases';
export const pages = 'https://mbackschat.github.io/a12-consistency-releases';
export type ReleaseManifest = Receipt & { sourceTag: string; site: Record<string, string>; assets: Record<string, string> };
export function assertMirrorTree(mirror: string): void {
    function visit(directory: string, prefix = ''): void {
        for (const entry of readdirSync(directory)) {
            if (!prefix && entry === '.git') continue;
            const name = prefix + entry;
            const file = resolve(directory, entry), stat = lstatSync(file);
            if (stat.isSymbolicLink()) throw new Error(`unexpected mirror symlink: ${name}`);
            if (stat.isDirectory()) { visit(file, name + '/'); continue; }
            if (!stat.isFile() || !/^(?:\.locmetryrc|README\.md|LICENSE|\.github\/workflows\/deploy-pages\.yml|releases\/v\d+\.\d+\.\d+\.json|presentations\/v\d+\.\d+\.\d+-[a-f0-9]{40}\.json|site\/(?:index|guide|privacy)\.html|site\/style\.css|site\/assets\/(?:a12-consistency-checker|kotlin-graalvm-kernel-roles)\.avif|site\/showcase\/(?:index\.html|assets\/[A-Za-z0-9_-]+\.(?:js|css))|site\/maven\/io\/github\/mbackschat\/a12\/dm\/[^]+)$/.test(name)) throw new Error(`unexpected mirror file: ${name}`);
        }
    }
    visit(mirror);
}

export function readTree(directory: string): Record<string, Buffer> {
    return existsSync(directory) ? Object.fromEntries(Object.keys(fileInventory(directory)).map(name => [name, readFileSync(resolve(directory, name))])) : {};
}
function writeTree(directory: string, files: Record<string, Buffer>): void {
    for (const [name, bytes] of Object.entries(files)) {
        const path = resolve(directory, name); mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, bytes);
    }
}
export function bundleMaven(staging: string, output: string, version: string): Record<string, string> {
    mkdirSync(output, { recursive: true });
    const name = `dm-consistency-${version}-maven-repository.zip`;
    // Fixed ZIP timestamps and sorted entries make retries byte-identical; release assets are never replaced.
    const result = spawnSync('python3', ['-c', 'import pathlib,sys,zipfile\nroot=pathlib.Path(sys.argv[1])\nwith zipfile.ZipFile(sys.argv[2],"w",compression=zipfile.ZIP_DEFLATED) as z:\n for p in sorted(root.rglob("*")):\n  if p.is_file():\n   i=zipfile.ZipInfo("maven/"+p.relative_to(root).as_posix(),(1980,1,1,0,0,0)); i.compress_type=zipfile.ZIP_DEFLATED; i.external_attr=0o100644<<16; z.writestr(i,p.read_bytes())', staging, resolve(output, name)], { encoding: 'utf8', timeout: 30_000 });
    if (result.status !== 0) throw new Error(`cannot bundle Maven repository: ${result.stderr}`);
    const hash = createHash('sha256').update(readFileSync(resolve(output, name))).digest('hex');
    writeFileSync(resolve(output, 'SHA256SUMS'), `${hash}  ${name}\n`);
    return { [name]: hash, SHA256SUMS: createHash('sha256').update(readFileSync(resolve(output, 'SHA256SUMS'))).digest('hex') };
}
export function assembleChecker(root: string, mirror: string, staging: string, output: string, receipt: Receipt): ReleaseManifest {
    assertMirrorTree(mirror);
    const { version } = receipt;
    const template = readFileSync(resolve(root, 'consistency/public-release/README.md'), 'utf8');
    const blocks = template.match(/<!-- source-statistics:checker-release:start -->[\s\S]*?<!-- source-statistics:checker-release:end -->/g);
    if (blocks?.length !== 1) throw new Error('checker README must contain one source-statistics block');
    const policy = readFileSync(resolve(root, '.locmetryrc'));
    scanBytes('README statistics', Buffer.from(blocks[0]));
    scanBytes('.locmetryrc', policy);
    const maven = resolve(mirror, 'site/maven');
    const history = resolve(mirror, 'releases');
    const manifests = existsSync(history) ? readdirSync(history).map(name => {
        if (!/^v\d+\.\d+\.\d+\.json$/.test(name)) throw new Error(`unexpected historical manifest: ${name}`);
        return JSON.parse(readFileSync(resolve(history, name), 'utf8')) as ReleaseManifest;
    }) : [];
    assertHistoricalArtifacts(existsSync(maven) ? fileInventory(maven) : {}, manifests);
    writeTree(maven, mergeMaven(readTree(maven), readTree(staging), version));
    const readme = `# A12 consistency checker\n\nKernel-free model consistency checks for Kotlin and Java, released under the MIT license. This checker has partial coverage: ALL_CHECKS_PASSED means its implemented checks passed; it does not establish that A12 would accept the model. Reports retain complete: false and disclose gaps.\n\n## Gradle (Kotlin Multiplatform or JVM)\n\n\u0060\u0060\u0060kotlin\nrepositories {\n    maven("${pages}/maven/")\n    mavenCentral()\n}\ndependencies {\n    implementation("io.github.mbackschat.a12.dm:dm-consistency:${version}")\n}\n\u0060\u0060\u0060\n\nGradle selects the JVM or JS variant. Java/Maven consumers use the same repository URL and io.github.mbackschat.a12.dm:dm-consistency-jvm:${version}. The matching dm-predicates coordinates are transitive dependencies. JVM requires Java 21 or newer.\n\n[Checker sources](${pages}/maven/io/github/mbackschat/a12/dm/dm-consistency/${version}/dm-consistency-${version}-sources.jar) · [Release assets](https://github.com/${repository}/releases/tag/v${version})\n\nThis mirror provides Maven artifacts only. npm and Maven Central publication are not part of this release.\n`;
    writeFileSync(resolve(mirror, 'README.md'), readme + '\n' + blocks[0] + '\n');
    writeFileSync(resolve(mirror, '.locmetryrc'), policy);
    writeFileSync(resolve(mirror, 'LICENSE'), readFileSync(resolve(root, 'LICENSE')));
    mkdirSync(resolve(mirror, '.github/workflows'), { recursive: true });
    writeFileSync(resolve(mirror, '.github/workflows/deploy-pages.yml'), readFileSync(resolve(root, '.github/consistency-mirror-workflows/deploy-pages.yml')));
    writeFileSync(resolve(mirror, 'site/index.html'), `<!doctype html><html lang="en"><meta charset="utf-8"><title>A12 consistency checker</title><h1>A12 consistency checker ${version}</h1><p><a href="https://github.com/${repository}">Usage and release documentation</a></p><p>Maven repository: <code>${pages}/maven/</code></p></html>\n`);
    const manifest: ReleaseManifest = { ...receipt, sourceTag: `consistency-v${version}`, site: fileInventory(resolve(mirror, 'site')), assets: bundleMaven(staging, output, version) };
    mkdirSync(resolve(mirror, 'releases'), { recursive: true });
    writeFileSync(resolve(mirror, `releases/v${version}.json`), JSON.stringify(manifest, null, 2) + '\n');
    return manifest;
}
