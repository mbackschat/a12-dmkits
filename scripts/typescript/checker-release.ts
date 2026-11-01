import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { inspectCheckerMaven } from './checker-maven.ts';
import { smokeChecker } from './checker-consumers.ts';
import { command, sourceIdentity, stageChecker, goLiveChecker } from './checker-release-github.ts';
import type { Receipt } from './checker-release-core.ts';

async function main(): Promise<void> {
    const [operation, version] = process.argv.slice(2);
    if (!['qualify', 'stage', 'go-live'].includes(operation) || !/^\d+\.\d+\.\d+$/.test(version ?? '') || process.argv.length !== 4) throw new Error('usage: checker-release.ts qualify|stage|go-live VERSION');
    const root = process.cwd();
    const receiptPath = resolve(root, 'build/consistency-release/qualification.json');
    if (operation !== 'qualify') command(root, './scripts/check-release-gaps.sh', [], true);
    if (operation === 'qualify') {
        // Losing this build-local receipt is harmless: qualification recreates it; public tags and manifests retain release identity.
        rmSync(receiptPath, { force: true });
        const source = sourceIdentity(root, version);
        command(root, './build.sh', [':consistency:verifyCheckerMavenPublication', '--console=plain'], true);
        const staging = resolve(root, 'build/consistency-release/maven');
        const files = inspectCheckerMaven(staging, version);
        await smokeChecker(root, version, pathToFileURL(staging).href, 'local');
        if (sourceIdentity(root, version) !== source) throw new Error('source changed during qualification');
        const receipt: Receipt = { schema: 1, source, version, files };
        writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n');
        console.log(`Qualified checker ${version} at ${source}`);
    } else if (operation === 'stage') {
        const receipt: Receipt = JSON.parse(readFileSync(receiptPath, 'utf8'));
        if (receipt.version !== version) throw new Error('qualification version differs from requested version');
        await stageChecker(root, receipt);
    } else await goLiveChecker(root, version);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
