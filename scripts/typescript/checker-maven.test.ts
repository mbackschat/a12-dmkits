import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { inspectCheckerMaven, inspectArchiveEntry } from './checker-maven.ts';

const version = '0.14.0';
const group = 'io.github.mbackschat.a12.dm';
const artifacts = ['dm-consistency', 'dm-consistency-jvm', 'dm-consistency-js', 'dm-predicates', 'dm-predicates-jvm', 'dm-predicates-js'];
function fixture() {
    const root = mkdtempSync(join(tmpdir(), 'checker-maven-'));
    let sealed = false;
    const files = new Set<string>();
    const rehash = (path: string) => {
        for (const algorithm of ['md5', 'sha1', 'sha256', 'sha512']) {
            writeFileSync(join(root, `${path}.${algorithm}`), createHash(algorithm).update(readFileSync(join(root, path))).digest('hex'));
        }
    };
    const write = (path: string, value: string | Buffer) => {
        mkdirSync(join(root, path, '..'), { recursive: true }); writeFileSync(join(root, path), value); files.add(path);
        if (sealed && !/\.(md5|sha1|sha256|sha512)$/.test(path)) rehash(path);
    };
    const path = (artifact: string, suffix: string) => `io/github/mbackschat/a12/dm/${artifact}/${version}/${artifact}-${version}${suffix}`;
    const archives: Record<string, Record<string, string>> = {};
    for (const artifact of artifacts) {
        const checker = artifact.startsWith('dm-consistency');
        const family = checker ? 'consistency' : 'predicates';
        const platform = artifact.endsWith('-jvm') ? 'jvm' : artifact.endsWith('-js') ? 'js' : 'common';
        const owner = `dm-${family}`;
        const extension = platform === 'js' ? '.klib' : '.jar';
        const binary = path(artifact, extension);
        const sources = path(artifact, '-sources.jar');
        archives[binary] = { [`io/github/mbackschat/a12/dm/${family}/Example.class`]: 'fixture class' };
        archives[sources] = { [`commonMain/io/github/mbackschat/a12/dm/${family}/${checker ? 'CheckerIdentity' : 'Example'}.kt`]: checker ? `const val CHECKER_VERSION: String = "${version}"` : 'package fixture' };
        const dependencies = (p: string, runtime: boolean, pom: boolean) => [
            ...(checker ? [{ group, module: pom && p !== 'common' ? `dm-predicates-${p}` : 'dm-predicates', version: { requires: version } }] : []),
            { group: 'org.jetbrains.kotlin', module: pom && p === 'js' ? 'kotlin-stdlib-js' : 'kotlin-stdlib', version: { requires: '2.4.0' } },
            ...(p === 'js' ? [{ group: 'org.jetbrains.kotlin', module: 'kotlin-dom-api-compat', version: { requires: '2.4.0' } }] : []),
            ...(checker && runtime && p !== 'common' ? [{ group: 'org.jetbrains.kotlinx', module: pom ? `kotlinx-serialization-json-${p}` : 'kotlinx-serialization-json', version: { requires: '1.9.0' } }] : []),
        ];
        const variant = (p: string, mode: string, redirect: boolean) => {
            const name = p === 'common' ? (mode === 'api' ? 'metadataApiElements' : 'metadataSourcesElements') : `${p}${mode[0].toUpperCase() + mode.slice(1)}Elements-published`;
            const attributes = { 'org.jetbrains.kotlin.platform.type': p, 'org.gradle.category': mode === 'sources' ? 'documentation' : 'library', 'org.gradle.usage': p === 'common' ? (mode === 'api' ? 'kotlin-metadata' : 'kotlin-runtime') : `${p === 'jvm' ? 'java' : 'kotlin'}-${mode === 'api' ? 'api' : 'runtime'}`, ...(mode === 'sources' ? { 'org.gradle.docstype': 'sources' } : {}) };
            if (redirect) return { name, attributes, 'available-at': { group, module: `${owner}-${p}`, version, url: `../../${owner}-${p}/${version}/${owner}-${p}-${version}.module` } };
            return { name, attributes, dependencies: mode === 'sources' ? [] : dependencies(p, mode === 'runtime', false), files: [{ url: `${artifact}-${version}${mode === 'sources' ? '-sources.jar' : extension}` }] };
        };
        const variants = platform === 'common' ? [variant('common', 'api', false), variant('common', 'sources', false), ...['js', 'jvm'].flatMap(p => ['api', 'runtime', 'sources'].map(mode => variant(p, mode, true)))] : ['api', 'runtime', 'sources'].map(mode => variant(platform, mode, false));
        write(path(artifact, '.module'), JSON.stringify({ formatVersion: '1.1', component: { group, module: owner, version, ...(platform === 'common' ? {} : { url: `../../${owner}/${version}/${owner}-${version}.module` }) }, variants }));
        write(path(artifact, '.pom'), `<project><groupId>${group}</groupId><artifactId>${artifact}</artifactId><version>${version}</version><url>https://github.com/mbackschat/a12-consistency-releases</url><licenses><license><name>MIT License</name></license></licenses><dependencies>${dependencies(platform, true, true).map(d => `<dependency><groupId>${d.group}</groupId><artifactId>${d.module}</artifactId><version>${d.version.requires}</version></dependency>`).join('')}</dependencies></project>`);
        write(`io/github/mbackschat/a12/dm/${artifact}/maven-metadata.xml`, `<metadata><groupId>${group}</groupId><artifactId>${artifact}</artifactId><versioning><versions><version>${version}</version></versions></versioning></metadata>`);
    }
    const zip = spawnSync('python3', ['-c', 'import json,sys,pathlib,zipfile\nr=pathlib.Path(sys.argv[1])\nfor p,entries in json.load(sys.stdin).items():\n with zipfile.ZipFile(r/p,"w") as z:\n  for n,v in entries.items(): z.writestr(n,v)\n', root], { input: JSON.stringify(archives), encoding: 'utf8' });
    assert.equal(zip.status, 0, zip.stderr);
    for (const path of Object.keys(archives)) files.add(path);
    for (const artifact of ['dm-consistency', 'dm-predicates']) write(path(artifact, '-kotlin-tooling-metadata.json'), JSON.stringify({ schemaVersion: '1.1.0', projectTargets: [{ platformType: 'common' }, { platformType: 'jvm' }, { platformType: 'js' }] }));
    for (const path of files) rehash(path);
    sealed = true;
    const close = () => rmSync(root, { recursive: true, force: true });
    return { root, path, write, rehash, close };
}

test('complete six-coordinate publication passes and yields a nonempty digest inventory', () => {
    const f = fixture();
    try { const files = inspectCheckerMaven(f.root, version); assert.equal(Object.keys(files).length, 160); }
    finally { f.close(); }
});

test('missing coordinates, wrong versions, missing module targets and unresolved dependencies fail', () => {
    for (const kind of ['missing', 'version', 'file', 'dependency', 'pom-dependency']) {
        const f = fixture();
        try {
            if (kind === 'missing') rmSync(join(f.root, 'io/github/mbackschat/a12/dm/dm-predicates-js'), { recursive: true });
            else if (kind === 'pom-dependency') f.write(f.path('dm-consistency-jvm', '.pom'), readFileSync(join(f.root, f.path('dm-consistency-jvm', '.pom')), 'utf8').replaceAll('dm-predicates-jvm', 'unpublished-jvm'));
            else {
                const p = f.path('dm-consistency', '.module');
                const metadata = JSON.parse(readFileSync(join(f.root, p), 'utf8'));
                if (kind === 'version') metadata.component.version = '0.13.0';
                if (kind === 'file') metadata.variants[0].files[0].url = 'missing.jar';
                if (kind === 'dependency') metadata.variants[0].dependencies[0].module = 'unpublished';
                f.write(p, JSON.stringify(metadata));
            }
            assert.throws(() => inspectCheckerMaven(f.root, version), undefined, kind);
        } finally { f.close(); }
    }
});

test('archive scan rejects private paths, tokens, traversal and unrelated packages while admitting public source', () => {
    const name = 'commonMain/io/github/mbackschat/a12/dm/consistency/Check.kt';
    assert.doesNotThrow(() => inspectArchiveEntry(name, Buffer.from('package io.github.mbackschat.a12.dm.consistency'), 'sources'));
    for (const bytes of ['/' + 'Users/person/source.kt', '/' + 'home/person/source.kt', 'C:\\Users\\person\\source.kt', 'github_pat_' + 'x'.repeat(30)]) {
        assert.throws(() => inspectArchiveEntry(name, Buffer.from(bytes), 'sources'));
    }
    for (const bad of ['../secret.txt', '/secret.txt', 'com/mgmtp/Kernel.class', 'commonTest/Fixture.kt']) {
        assert.throws(() => inspectArchiveEntry(bad, Buffer.from('x'), 'sources'));
    }
});

test('wrong sidecar hashes and unrelated files fail rather than enter the public payload', () => {
    const f = fixture();
    try {
        const target = f.path('dm-consistency-jvm', '.jar');
        f.write(`${target}.sha256`, createHash('sha256').update(readFileSync(join(f.root, target))).digest('hex'));
        inspectCheckerMaven(f.root, version);
        f.write(`${target}.sha256`, '0'.repeat(64));
        assert.throws(() => inspectCheckerMaven(f.root, version));
        f.rehash(target);
        f.write('credentials.txt', 'not a publication');
        assert.throws(() => inspectCheckerMaven(f.root, version));
    } finally { f.close(); }
});

test('module redirects and hashes bind to the named coordinate rather than any existing file', () => {
    for (const kind of ['component', 'redirect', 'digest']) {
        const f = fixture();
        try {
            const p = f.path('dm-consistency-jvm', '.module');
            const metadata = JSON.parse(readFileSync(join(f.root, p), 'utf8'));
            const wrongTarget = '../../dm-predicates/0.14.0/dm-predicates-0.14.0.module';
            if (kind === 'component') metadata.component = { group, module: 'dm-consistency', version, url: wrongTarget };
            if (kind === 'redirect') metadata.variants[0]['available-at'] = { group, module: 'dm-consistency', version, url: wrongTarget };
            if (kind === 'digest') metadata.variants[0].files[0].sha256 = '0'.repeat(64);
            f.write(p, JSON.stringify(metadata));
            assert.throws(() => inspectCheckerMaven(f.root, version), undefined, kind);
        } finally { f.close(); }
    }
});

test('archive decoder feeds privacy and generated-source identity guards', () => {
    for (const kind of ['private', 'identity', 'empty', 'comment']) {
        const f = fixture();
        try {
            const target = join(f.root, f.path('dm-consistency-jvm', '-sources.jar'));
            const payload = kind === 'private' ? '/' + 'Users/person/source.kt' : `const val CHECKER_VERSION: String = "${kind === 'comment' ? version : '0.13.0'}"`;
            const entries = kind === 'empty' ? { 'META-INF/': '' } : { 'commonMain/io/github/mbackschat/a12/dm/consistency/CheckerIdentity.kt': payload };
            const edit = spawnSync('python3', ['-c', 'import json,sys,zipfile\nwith zipfile.ZipFile(sys.argv[1],"w") as z:\n z.comment=sys.argv[2].encode()\n for n,v in json.load(sys.stdin).items(): z.writestr(n,v)', target, kind === 'comment' ? '/' + 'Users/person/archive' : ''], { input: JSON.stringify(entries), encoding: 'utf8' });
            assert.equal(edit.status, 0, edit.stderr);
            f.rehash(f.path('dm-consistency-jvm', '-sources.jar'));
            const expectedError = kind === 'empty' ? /empty archive/ : kind === 'identity' ? /identity missing\/stale/ : /private marker/;
            assert.throws(() => inspectCheckerMaven(f.root, version), expectedError, kind);
        } finally { f.close(); }
    }
});

test('compiler metadata directories cannot hide unrelated code, tests or local paths', () => {
    for (const name of ['META-INF/versions/21/com/mgmtp/Kernel.class', 'default/tests/Fixture.kt', 'META-INF/Users/person/secret.txt', 'commonMain/default/private-resource.txt']) {
        assert.throws(() => inspectArchiveEntry(name, Buffer.from('payload'), 'binary'), undefined, name);
    }
    for (const name of ['META-INF/io.github.mbackschat.a12.dm_consistency.kotlin_module', 'default/ir/strings.knt', 'commonMain/default/linkdata/package_io.github.mbackschat.a12.dm.consistency/000_consistency.knm']) {
        assert.doesNotThrow(() => inspectArchiveEntry(name, Buffer.from('compiler payload'), 'binary'));
    }
});

 test('exact inventory rejects missing sidecars or metadata and extra plausible Maven files', () => {
    for (const kind of ['sidecar', 'metadata', 'extra']) {
        const f = fixture();
        try {
            if (kind === 'sidecar') rmSync(join(f.root, f.path('dm-consistency', '.jar.sha256')));
            if (kind === 'metadata') rmSync(join(f.root, 'io/github/mbackschat/a12/dm/dm-consistency/maven-metadata.xml'));
            if (kind === 'extra') f.write(f.path('dm-consistency', '-sources.pom'), '<project/>');
            assert.throws(() => inspectCheckerMaven(f.root, version), undefined, kind);
        } finally { f.close(); }
    }
});

test('root platform variants and predicate edges cannot disappear or change carrier', () => {
    for (const kind of ['missing-variant', 'wrong-carrier', 'dependency']) {
        const f = fixture();
        try {
            const path = f.path('dm-consistency', '.module');
            const module = JSON.parse(readFileSync(join(f.root, path), 'utf8'));
            if (kind === 'missing-variant') module.variants.pop();
            if (kind === 'dependency') module.variants[0].dependencies = [];
            if (kind === 'wrong-carrier') {
                const target = module.variants.find((v: { name: string }) => v.name === 'jvmApiElements-published');
                target['available-at'].module = 'dm-consistency-js';
                target['available-at'].url = '../../dm-consistency-js/0.14.0/dm-consistency-js-0.14.0.module';
            }
            f.write(path, JSON.stringify(module));
            assert.throws(() => inspectCheckerMaven(f.root, version), undefined, kind);
        } finally { f.close(); }
    }
});
