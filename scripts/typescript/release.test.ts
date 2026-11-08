import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { changelogProblems, freezeChangelog, plan, rootVersion, sourceTag, tagProblems } from './release.ts';
import type { Component, ReleaseState } from './release.ts';

const states = (interpreter: ReleaseState, checker: ReleaseState, dmtool: ReleaseState): Record<Component, ReleaseState> =>
    ({ interpreter, checker, dmtool });
const steps = (operation: 'stage' | 'go-live', s: Record<Component, ReleaseState>) => plan(operation, s).map(step => step.component);

test('stage runs every unpublished component, libraries before dmtool', () => {
    assert.deepEqual(steps('stage', states('absent', 'draft', 'absent')), ['interpreter', 'checker', 'dmtool']);
    assert.deepEqual(steps('stage', states('latest', 'published', 'draft')), ['dmtool']);
});

test('go-live resumes a published library until it is latest, but never re-promotes dmtool', () => {
    assert.deepEqual(steps('go-live', states('published', 'latest', 'draft')), ['interpreter', 'dmtool']);
    assert.deepEqual(steps('go-live', states('latest', 'latest', 'published')), []);
    assert.deepEqual(steps('go-live', states('absent', 'absent', 'absent')), []);
});

test('the three source tags must name HEAD, or not exist yet', () => {
    const head = 'a'.repeat(40);
    assert.deepEqual(tagProblems([{ name: 'v1.0.0', local: null, remote: null }, { name: 'interpreter-v1.0.0', local: head, remote: head }], head), []);
    assert.match(tagProblems([{ name: 'v1.0.0', local: 'b'.repeat(40), remote: 'b'.repeat(40) }], head)[0], /not HEAD/);
    assert.match(tagProblems([{ name: 'v1.0.0', local: head, remote: null }], head)[0], /differs from origin/);
});

test('source tags follow each publisher\'s naming', () => {
    assert.deepEqual(['interpreter', 'checker', 'dmtool'].map(c => sourceTag(c as Component, '1.2.3')),
        ['interpreter-v1.2.3', 'consistency-v1.2.3', 'v1.2.3']);
});

test('the root version is read from build.gradle', () => {
    assert.equal(rootVersion("plugins {}\nversion = '0.17.0'\n"), '0.17.0');
    assert.equal(rootVersion('plugins {}\n'), null);
});

const library = (entry: string) => `# Changelog\n\nPreamble.\n\n## [@VERSION@]\n\n${entry}\n## [0.16.0]\n\n- old\n`;

test('changelogs need a frozen dmtool section and non-empty rolling library sections', () => {
    const plugin = '# Changelog\n\n## [0.17.0] — kernel 31.3.1\n\n- new\n';
    assert.deepEqual(changelogProblems('0.17.0', { plugin, interpreter: library('- a'), checker: library('- b') }), []);
    const problems = changelogProblems('0.17.0', { plugin: '## [0.16.0]\n', interpreter: library(''), checker: '## [0.16.0]\n- old\n' });
    assert.equal(problems.length, 3);
    assert.match(problems[0], /plugin\/CHANGELOG.md has no "## \[0.17.0\]"/);
    assert.match(problems[1], /interpreter.*has no entry/);
    assert.match(problems[2], /consistency.*does not open with/);
});

test('a version is matched literally, not as a pattern', () => {
    assert.equal(changelogProblems('0.1.0', { plugin: '## [0x1y0]\n', interpreter: library('- a'), checker: library('- b') }).length, 1);
});

test('freezing keeps released entries and opens a fresh rolling section', () => {
    const interpreter = library('### Changed\n\n- c\n\n### Performance\n\n- fast\n');
    const frozen = freezeChangelog(interpreter, '0.17.0', true);
    assert.equal(frozen, '# Changelog\n\nPreamble.\n\n## [@VERSION@]\n\n### Performance\n\n- fast\n\n'
        + '## [0.17.0]\n\n### Changed\n\n- c\n\n### Performance\n\n- fast\n\n## [0.16.0]\n\n- old\n');
    assert.equal(freezeChangelog(library('- b\n'), '0.17.0', false),
        '# Changelog\n\nPreamble.\n\n## [@VERSION@]\n\n## [0.17.0]\n\n- b\n\n## [0.16.0]\n\n- old\n');
});

test('freezing refuses a changelog that cannot be frozen', () => {
    assert.throws(() => freezeChangelog('## [0.16.0]\n', '0.17.0', false), /does not open with/);
    assert.throws(() => freezeChangelog(library('- c\n'), '0.17.0', true), /no "### Performance"/);
});

// publish-release.sh pushes and builds natively, so no test can drive it; this locks the text of its go-live
// branch instead: both library mirrors are checked before the flip that makes dmtool public.
test('dmtool go-live refuses before its flip while a library release is unpublished', () => {
    const script = readFileSync(resolve(import.meta.dirname, '../publish-release.sh'), 'utf8');
    const goLive = script.slice(script.indexOf('if [[ "$GO_LIVE" == 1 ]]'));
    const flip = goLive.indexOf('--draft=false --latest');
    for (const mirror of ['mbackschat/a12-interpreter-releases', 'mbackschat/a12-consistency-releases']) {
        const check = goLive.indexOf(mirror);
        assert.ok(check >= 0 && check < flip, `${mirror} is not checked before dmtool goes live`);
    }
});
