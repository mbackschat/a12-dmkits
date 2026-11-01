import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileInventory, scanBytes } from './checker-maven.ts';
import { assertMirrorTree } from './checker-release-files.ts';
export type Presentation = { schema: number; version: string; source: string; site: Record<string, string>; protectedFiles: Record<string, string> };
export function assertPresentationChanges(names: string[]): void {
    const presentation = /^(?:\.github\/|docs\/|assets\/infographics\/|consistency\/(?:showcase|public-release)\/|(?:interpreter\/public-release|plugin)\/assets\/infographics\/|(?:README\.md|CLAUDE\.md|\.locmetryrc|consistency\/README\.md)$|scripts\/typescript\/(?:checker-(?:presentation(?:-files|-rehearsal)?|showcase|release(?:-files|-github|-rehearsal|-core)?|maven|consumers)|infographic-evidence|check-infographic-freshness|update-code-statistics)(?:\.test)?\.ts$)/;
    const refused = names.filter(name => !presentation.test(name));
    if (refused.length) throw new Error(`source changes outside presentation require a new library release or a matching source checkout: ${refused.join(', ')}`);
}
export function protectedInventory(mirror: string): Record<string, string> {
    return Object.fromEntries(['releases', 'site/maven'].flatMap(directory => existsSync(resolve(mirror, directory)) ? Object.entries(fileInventory(resolve(mirror, directory))).map(([name, hash]) => [`${directory}/${name}`, hash]) : []));
}
export function assemblePresentation(root: string, mirror: string, showcase: string, version: string, source: string): Presentation {
    if (!/^\d+\.\d+\.\d+$/.test(version) || !/^[a-f0-9]{40}$/.test(source)) throw new Error('invalid presentation identity');
    assertMirrorTree(mirror);
    const protectedFiles = protectedInventory(mirror);
    const files: Record<string, Buffer> = {};
    for (const name of Object.keys(fileInventory(showcase))) {
        if (!/^(?:index\.html|assets\/[A-Za-z0-9_-]+\.(?:js|css))$/.test(name)) throw new Error(`unexpected showcase file: ${name}`);
        const bytes = readFileSync(resolve(showcase, name));
        if (/file:\/\/|sourceMappingURL=/.test(bytes.toString('utf8'))) throw new Error(`private path or source map in showcase: ${name}`);
        files[`site/showcase/${name}`] = bytes;
    }
    if (!files['site/showcase/index.html'] || !Object.keys(files).some(name => name.endsWith('.js'))) throw new Error('showcase payload is empty');
    for (const name of ['README.md', 'site/index.html', 'site/guide.html', 'site/privacy.html', 'site/style.css']) {
        files[name] = Buffer.from(readFileSync(resolve(root, 'consistency/public-release', name), 'utf8').replaceAll('@VERSION@', version));
    }
    files[".locmetryrc"] = readFileSync(resolve(root, ".locmetryrc"));
    for (const name of ['a12-consistency-checker', 'kotlin-graalvm-kernel-roles']) files[`site/assets/${name}.avif`] = readFileSync(resolve(root, `assets/infographics/${name}.avif`));
    files['.github/workflows/deploy-pages.yml'] = readFileSync(resolve(root, '.github/consistency-mirror-workflows/deploy-pages.yml'));
    for (const [name, bytes] of Object.entries(files)) scanBytes(name, bytes);
    rmSync(resolve(mirror, 'site/showcase'), { recursive: true, force: true });
    for (const [name, bytes] of Object.entries(files)) { const target = resolve(mirror, name); mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes); }
    const result: Presentation = { schema: 1, version, source, site: fileInventory(resolve(mirror, 'site')), protectedFiles };
    if (JSON.stringify(protectedFiles) !== JSON.stringify(protectedInventory(mirror))) throw new Error('presentation changed immutable release files');
    mkdirSync(resolve(mirror, 'presentations'), { recursive: true });
    writeFileSync(resolve(mirror, `presentations/v${version}-${source}.json`), JSON.stringify(result, null, 2) + '\n');
    assertMirrorTree(mirror);
    return result;
}
