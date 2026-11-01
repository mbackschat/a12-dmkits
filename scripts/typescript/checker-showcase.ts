import { cpSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { assemblePresentation } from './checker-presentation-files.ts';
import { pathToFileURL } from 'node:url';
export function buildShowcase(root: string, version: string): string {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('invalid showcase version');
    const output = resolve(root, 'build/consistency-showcase');
    const archive = resolve(root, `consistency/build/consumer-tests/pack/a12-consistency-${version}.tgz`);
    if (!existsSync(archive)) throw new Error('packConsistencyNpmPackage must run first');
    mkdirSync(output, { recursive: true });
    rmSync(resolve(output, 'src'), { recursive: true, force: true });
    cpSync(resolve(root, 'consistency/showcase'), output, { recursive: true });
    for (const [source, target] of [['models/storefront.dm.json', 'storefront.dm.json'], ['parity/sources/storefront-broken.dm.json', 'storefront-broken.dm.json']]) cpSync(resolve(root, 'consistency/consumer-tests/shared', source), resolve(output, 'src', target));
    const run = (cmd: string, args: string[]) => {
        const result = spawnSync(cmd, args, { cwd: output, stdio: 'inherit', timeout: 120_000 });
        if (result.status !== 0) throw new Error(`showcase command failed: ${cmd} ${args.join(' ')}`);
    };
    run('pnpm', ['install', '--ignore-scripts', '--no-frozen-lockfile']);
    run('pnpm', ['add', '--ignore-scripts', archive]);
    const manifest = JSON.parse(readFileSync(resolve(output, 'node_modules/a12-consistency/package.json'), 'utf8'));
    if (manifest.name !== 'a12-consistency' || manifest.version !== version) throw new Error('packed checker identity mismatch');
    writeFileSync(resolve(output, 'src/artifact.json'), JSON.stringify({ version }));
    run('pnpm', ['run', 'build']);
    const preview = resolve(root, 'build/checker-presentation-preview');
    rmSync(preview, { recursive: true, force: true }); mkdirSync(preview, { recursive: true });
    assemblePresentation(root, preview, resolve(output, 'dist'), version, '0'.repeat(40));
    run('pnpm', ['run', 'verify:site']);
    return resolve(output, 'dist');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { console.log(buildShowcase(process.cwd(), process.argv[2] ?? readFileSync(resolve('build.gradle'), 'utf8').match(/^version = '([^']+)'/m)?.[1] ?? '')); } catch (error) { console.error((error as Error).message); process.exitCode = 1; }
}
