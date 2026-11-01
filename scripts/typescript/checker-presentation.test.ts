import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cpSync, readdirSync, mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { assemblePresentation, protectedInventory, assertPresentationChanges } from './checker-presentation-files.ts';
import { assertMirrorTree } from './checker-release-files.ts';
const root = process.cwd();
test('presentation refresh preserves release and Maven identities and admits only public output', t => {
    const temp = mkdtempSync(resolve(tmpdir(), 'checker-presentation-')); t.after(() => rmSync(temp, { recursive: true, force: true }));
    const mirror = resolve(temp, 'mirror'), showcase = resolve(temp, 'showcase');
    mkdirSync(resolve(mirror, 'releases'), { recursive: true }); mkdirSync(resolve(mirror, 'site/maven/io/github/mbackschat/a12/dm/dm-consistency/0.14.0'), { recursive: true });
    mkdirSync(resolve(showcase, 'assets'), { recursive: true });
    writeFileSync(resolve(showcase, 'index.html'), '<html>showcase</html>'); writeFileSync(resolve(showcase, 'assets/index-abc123.js'), 'console.log("checker");');
    writeFileSync(resolve(mirror, 'releases/v0.14.0.json'), '{"immutable":true}');
    const artifact = resolve(mirror, 'site/maven/io/github/mbackschat/a12/dm/dm-consistency/0.14.0/checker.jar'); writeFileSync(artifact, 'immutable Maven');
    const before = protectedInventory(mirror);
    const result = assemblePresentation(root, mirror, showcase, '0.14.0', 'a'.repeat(40));
    assert.deepEqual(protectedInventory(mirror), before); assertMirrorTree(mirror);
    assert.ok(result.site['showcase/assets/index-abc123.js']);
    assert.deepEqual(readFileSync(resolve(mirror, '.locmetryrc')), readFileSync(resolve(root, '.locmetryrc')));
    assert.match(readFileSync(resolve(mirror, 'README.md'), 'utf8'), /source-statistics:checker-release:start/);
    assert.match(readFileSync(resolve(mirror, 'README.md'), 'utf8'), /dm-consistency:0.14.0/);
    assert.ok(!readFileSync(resolve(mirror, 'site/index.html'), 'utf8').includes('@VERSION@'));
    writeFileSync(resolve(showcase, 'assets/source.map'), ['', 'Users', 'private', 'source'].join('/'));
    assert.throws(() => assemblePresentation(root, mirror, showcase, '0.14.0', 'b'.repeat(40)), /unexpected showcase/);
    rmSync(resolve(showcase, 'assets/source.map'));
    writeFileSync(resolve(showcase, 'assets/index-abc123.js'), `const secret="${['', 'Users', 'private', 'model'].join('/')}";`);
    assert.throws(() => assemblePresentation(root, mirror, showcase, '0.14.0', 'b'.repeat(40)), /private marker/);
    writeFileSync(resolve(artifact), 'changed'); assert.notDeepEqual(protectedInventory(mirror), before);
});

test('only presentation changes may differ from the published library source', () => {
    assert.doesNotThrow(() => assertPresentationChanges(['consistency/showcase/src/main.ts', 'docs/RELEASE-SPEC.md', 'scripts/typescript/checker-presentation.ts']));
    for (const name of ['shared-regex/src/commonMain/kotlin/Changed.kt', 'build.gradle', 'gradle.properties', 'gradle/new-build-input.json', 'consistency/src/jsMain/Changed.kt', 'unknown-input']) assert.throws(() => assertPresentationChanges([name]), /outside presentation/);
});

test('every assembled carrier is scanned before any mirror file is written', t => {
    const temp = mkdtempSync(resolve(tmpdir(), 'presentation-carriers-')); t.after(() => rmSync(temp, { recursive: true, force: true }));
    const source = resolve(temp, 'source'), mirror = resolve(temp, 'mirror'), showcase = resolve(temp, 'showcase');
    mkdirSync(mirror); mkdirSync(resolve(showcase, 'assets'), { recursive: true });
    writeFileSync(resolve(showcase, 'index.html'), 'showcase'); writeFileSync(resolve(showcase, 'assets/index-demo.js'), 'console.log("demo")');
    for (const name of ['.locmetryrc', 'consistency/public-release', 'assets/infographics', '.github/consistency-mirror-workflows']) cpSync(resolve(root, name), resolve(source, name), { recursive: true, filter: file => !file.endsWith('.png') });
    const carriers = ['consistency/public-release/README.md', 'consistency/public-release/site/guide.html', 'assets/infographics/a12-consistency-checker.avif', '.github/consistency-mirror-workflows/deploy-pages.yml'];
    for (const name of carriers) {
        const file = resolve(source, name), original = readFileSync(file);
        writeFileSync(file, Buffer.concat([original, Buffer.from(['', 'Users', 'private', 'customer-model.json'].join('/'))]));
        assert.throws(() => assemblePresentation(source, mirror, showcase, '0.14.0', 'a'.repeat(40)), /private marker/, name);
        assert.deepEqual(readdirSync(mirror), [], 'refusal must precede any write'); writeFileSync(file, original);
    }
});
