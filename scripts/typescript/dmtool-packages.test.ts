import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { renderPackages, validateRelease } from './dmtool-packages.ts';

const assets = ['dmtool-macos-arm64', 'dmtool-linux-x64', 'dmtool-linux-arm64', 'dmtool-windows-x64.exe'];
const hashes = assets.map((_, i) => String(i + 1).repeat(64));
const sums = assets.map((name, i) => `${hashes[i]}  ${name}`).join('\n') + '\n';
const release = () => ({ tag_name: 'v0.14.0', draft: false, prerelease: false,
    assets: [...assets.map((name, i) => ({ name, digest: `sha256:${hashes[i]}` })), { name: 'SHA256SUMS' }] });
const revision = 'a'.repeat(40);
const archiveHash = 'b'.repeat(64);

test('package asset denominator matches the canonical POSIX release manifest', () => {
    const result = spawnSync('dash', ['-c', '. ./scripts/release-assets.sh; rk_release_assets'], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.stdout.trim().split('\n').sort(), [...assets, 'SHA256SUMS'].sort());
});

test('published stable release accepts CRLF checksums and pins all platform downloads', () => {
    const input = validateRelease('0.14.0', release(), sums.replaceAll('\n', '\r\n'));
    const { formula, scoop } = renderPackages(input, revision, archiveHash);
    const assertMappings = (rendered: string, manifestText: string) => {
        const branches = [
            /on_macos do[\s\S]*?on_arm do\s+url "([^"]+)", using: :nounzip\s+sha256 "([a-f0-9]+)"/,
            /on_linux do\s+on_intel do\s+url "([^"]+)", using: :nounzip\s+sha256 "([a-f0-9]+)"/,
            /on_linux do[\s\S]*?on_arm do\s+url "([^"]+)", using: :nounzip\s+sha256 "([a-f0-9]+)"/,
        ];
        branches.forEach((branch, i) => assert.deepEqual(branch.exec(rendered)?.slice(1), [
            `https://github.com/mbackschat/a12-dmtool-releases/releases/download/v0.14.0/${assets[i]}`, hashes[i],
        ]));
        const windows = JSON.parse(manifestText).architecture['64bit'];
        assert.deepEqual(windows.url, [
            'https://github.com/mbackschat/a12-dmtool-releases/releases/download/v0.14.0/dmtool-windows-x64.exe',
            `https://github.com/mbackschat/a12-dmtool-releases/archive/${revision}.tar.gz`,
        ]);
        assert.deepEqual(windows.hash, [hashes[3], archiveHash]);
        assert.equal(windows.extract_dir, `a12-dmtool-releases-${revision}`);
        assert.equal(windows.extract_to, '_notices');
    };
    assertMappings(formula, scoop);
    const swapped = formula.replace(hashes[1], 'SWAP').replace(hashes[2], hashes[1]).replace('SWAP', hashes[2]);
    assert.throws(() => assertMappings(swapped, scoop));
    assert.throws(() => assertMappings(formula.replace('on_intel do', 'on_arm do'), scoop));
    const swappedWindows = JSON.parse(scoop);
    swappedWindows.architecture['64bit'].hash.reverse();
    assert.throws(() => assertMappings(formula, JSON.stringify(swappedWindows)));
    assert.match(formula, /depends_on macos: :golden_gate/);
    assert.match(formula, /depends_on arch: :arm64/);
    assert.match(formula, /using: :nounzip/);
    assert.match(formula, /chmod 0755/);
    assert.match(formula, /"rulekit"/);
    for (const output of [formula, scoop]) {
        assert.ok(output.includes(`${revision}.tar.gz`));
        assert.ok(output.includes(archiveHash));
        assert.ok(output.includes('EUPL-1.2'));
        for (const notice of ['LICENSE', 'KERNEL-LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES', 'licenses']) {
            assert.ok(output.includes(notice), notice);
        }
    }
    const manifest = JSON.parse(scoop);
    assert.deepEqual(Object.keys(manifest.architecture), ['64bit']);
    assert.deepEqual(manifest.bin, [['dmtool-windows-x64.exe', 'dmtool']]);
    assert.equal(manifest.architecture['64bit'].extract_to, '_notices');
    assert.equal(manifest.autoupdate, undefined);
});

test('refuses wrong identities, unpublished releases, prereleases and incomplete inventories', () => {
    for (const change of [{ tag_name: 'v0.13.0' }, { draft: true }, { prerelease: true }, { assets: release().assets.slice(1) }]) {
        assert.throws(() => validateRelease('0.14.0', { ...release(), ...change }, sums));
    }
    for (const version of ['../main', '0.14.0"', '0.14.0-rc.1']) {
        assert.throws(() => validateRelease(version, release(), sums));
    }
});

test('refuses missing, duplicate, unexpected, malformed and conflicting checksums', () => {
    for (const bad of [sums.split('\n').slice(1).join('\n'), sums + sums,
        sums + `${archiveHash}  extra\n`, sums.replace(hashes[0], 'bad'), sums.replace(hashes[0], archiveHash)]) {
        assert.throws(() => validateRelease('0.14.0', release(), bad));
    }
    const duplicate = release();
    duplicate.assets.push(duplicate.assets[0]);
    assert.throws(() => validateRelease('0.14.0', duplicate, sums));
});

test('notice resource cannot follow a mutable ref or accept a malformed digest', () => {
    const input = validateRelease('0.14.0', release(), sums);
    assert.throws(() => renderPackages(input, 'main', archiveHash));
    assert.throws(() => renderPackages(input, revision, 'not-a-hash'));
});
