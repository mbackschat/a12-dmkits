import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertQualification, assertMavenOrigins, mergeMaven, promoteChecker } from './checker-release-core.ts';

const version = '0.14.0';
const source = 'a'.repeat(40);
const files = { 'a.jar': 'b'.repeat(64) };
test('qualification binds the entire artifact inventory and source identity', () => {
    const receipt = { schema: 1, version, source, files };
    assert.doesNotThrow(() => assertQualification(receipt, version, source, files));
    for (const bad of [{ ...receipt, source: 'c'.repeat(40) }, { ...receipt, version: '0.13.0' }, { ...receipt, schema: 2 }, { ...receipt, files: {} }]) {
        assert.throws(() => assertQualification(bad, version, source, files), /qualification/);
    }
    assert.throws(() => assertQualification(receipt, version, source, { ...files, extra: 'd'.repeat(64) }), /qualification/);
    assert.throws(() => assertQualification(receipt, version, source, { 'a.jar': 'e'.repeat(64) }), /qualification/);
});

test('Maven must resolve every checker and predicate JVM POM and JAR from the release repository', () => {
    const records = Object.fromEntries(['dm-consistency-jvm', 'dm-predicates-jvm'].map(a => [a,
        ['pom', 'jar'].map(ext => `${a}-${version}.${ext}>checker-release=`).join('\n')]));
    assert.doesNotThrow(() => assertMavenOrigins(records, version));
    for (const a of Object.keys(records)) for (const ext of ['pom', 'jar']) {
        const bad = { ...records, [a]: records[a].replace(`${a}-${version}.${ext}>checker-release=`, `${a}-${version}.${ext}>central=`) };
        assert.throws(() => assertMavenOrigins(bad, version), /origin/);
    }
    assert.throws(() => assertMavenOrigins({}, version), /origin/);
});

const groupPath = 'io/github/mbackschat/a12/dm';
function repository(v: string) {
    return Object.fromEntries(['dm-consistency', 'dm-consistency-jvm', 'dm-consistency-js', 'dm-predicates', 'dm-predicates-jvm', 'dm-predicates-js'].flatMap(a => [
        [`${groupPath}/${a}/${v}/${a}-${v}.pom`, Buffer.from(v)],
        [`${groupPath}/${a}/maven-metadata.xml`, Buffer.from(`<metadata><groupId>io.github.mbackschat.a12.dm</groupId><artifactId>${a}</artifactId><versioning><latest>${v}</latest><release>${v}</release><versions><version>${v}</version></versions></versioning></metadata>`)],
    ]));
}
test('cumulative Maven assembly preserves historical bytes and refuses duplicate or older versions', () => {
    const old = repository('0.13.0');
    const next = repository(version);
    const result = mergeMaven(old, next, version);
    for (const [name, bytes] of Object.entries(old)) if (!name.includes('maven-metadata.xml')) assert.deepEqual(result[name], bytes);
    for (const name of Object.keys(next).filter(n => n.endsWith('maven-metadata.xml'))) {
        assert.match(result[name].toString(), /<version>0.13.0<\/version><version>0.14.0<\/version>/);
        assert.match(result[name].toString(), /<release>0.14.0<\/release>/);
        for (const algorithm of ['md5', 'sha1', 'sha256', 'sha512']) assert.ok(result[`${name}.${algorithm}`]);
    }
    assert.throws(() => mergeMaven(old, repository('0.13.0'), '0.13.0'), /already|newer/);
    assert.throws(() => mergeMaven(old, repository('0.12.0'), '0.12.0'), /newer/);
    assert.throws(() => mergeMaven({ ...old, 'unexpected.txt': Buffer.from('bad') }, next, version), /historical/);
    assert.equal(Object.keys(mergeMaven({}, next, version)).length, 36);
});

test('promotion fails closed at each boundary and resumes after non-latest publication', async () => {
    for (const draft of [true, false]) {
        const expected = ['verifyDraft', ...(draft ? ['deploy'] : []), 'verifyPages', 'consumers', ...(draft ? ['publish'] : []), 'verifyAnonymous', 'latest'];
        for (const failure of [undefined, ...expected]) {
            const calls: string[] = [];
            const host = { draft, ...Object.fromEntries(expected.map(name => [name, async () => {
                calls.push(name); if (name === failure) throw new Error(name);
            }])) };
            if (failure) await assert.rejects(() => promoteChecker(host as never), new RegExp(failure));
            else await promoteChecker(host as never);
            assert.deepEqual(calls, failure ? expected.slice(0, expected.indexOf(failure) + 1) : expected);
        }
    }
});

test('historical artifact hashes reject modification, removal and unrecorded versions', async () => {
    const { assertHistoricalArtifacts } = await import('./checker-release-core.ts');
    const old = { 'io/github/mbackschat/a12/dm/dm-consistency/0.13.0/a.jar': 'a'.repeat(64), 'io/github/mbackschat/a12/dm/dm-consistency/maven-metadata.xml': 'b'.repeat(64) };
    const manifests = [{ schema: 1, source, version: '0.13.0', files: old }];
    assert.doesNotThrow(() => assertHistoricalArtifacts(old, manifests));
    for (const bad of [{}, { ...old, 'unrecorded/0.12.0/a.jar': 'c'.repeat(64) }, { ...old, [Object.keys(old)[0]]: 'c'.repeat(64) }]) {
        assert.throws(() => assertHistoricalArtifacts(bad, manifests), /historical/);
    }
});
