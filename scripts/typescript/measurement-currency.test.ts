import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { currency } from './measurement-currency.ts';

// Each fixture is a throwaway repository with an isolated git configuration, so a developer's global hooks,
// signing or identity can neither break nor satisfy a control.
const isolated = { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' };
function git(cwd: string, ...args: string[]): string {
    const result = spawnSync('git', ['-c', 'user.email=guard@example.invalid', '-c', 'user.name=guard', ...args], { cwd, env: isolated, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
}
function commit(root: string, path: string, contents: string, message: string): string {
    mkdirSync(dirname(resolve(root, path)), { recursive: true });
    writeFileSync(resolve(root, path), contents);
    git(root, 'add', '-A');
    git(root, 'commit', '--quiet', '-m', message);
    return git(root, 'rev-parse', '--short', 'HEAD');
}
function scratch(t: { after(fn: () => void): void }, name: string): string {
    const root = mkdtempSync(resolve(tmpdir(), `${name}-`));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    return root;
}
/** A repository whose only production commit is measured, and whose HEAD is a doc change. */
function currentRepository(root: string): void {
    git(root, 'init', '--quiet');
    const measured = commit(root, 'interpreter/src/commonMain/kotlin/Engine.kt', 'val engine = 1\n', 'production');
    commit(root, 'perf-results/fabricated/run.jsonl', `{"kind":"run","commit":"${measured}"}\n`, 'record');
    commit(root, 'NOTES.md', 'ships nothing\n', 'doc');
}

test('production source that moved past its only record is reported, naming exactly that commit', t => {
    const root = scratch(t, 'currency-unmeasured');
    currentRepository(root);
    const moved = commit(root, 'interpreter/src/jvmMain/kotlin/Engine.kt', 'val engine = 2\n', 'production moves');
    assert.deepEqual(currency(root), { kind: 'unmeasured', commits: [moved] });
});

test('a record taken at or after the production change covers it, whatever its dirty label', t => {
    const root = scratch(t, 'currency-current');
    currentRepository(root);
    const moved = commit(root, 'interpreter/src/jsMain/kotlin/Engine.kt', 'val engine = 3\n', 'production moves');
    commit(root, 'perf-results/fabricated/later.jsonl', `{"kind":"run","commit":"${moved}-DIRTY"}\n`, 'record again');
    assert.deepEqual(currency(root), { kind: 'current' });
});

test('test source never owes a measurement, shared production source does', t => {
    const root = scratch(t, 'currency-shared');
    currentRepository(root);
    commit(root, 'shared-regex/src/commonTest/kotlin/Control.kt', 'val test = 1\n', 'test only');
    assert.deepEqual(currency(root), { kind: 'current' });
    const changed = commit(root, 'shared-regex/src/commonMain/kotlin/Engine.kt', 'val shared = 1\n', 'shared production');
    assert.deepEqual(currency(root), { kind: 'unmeasured', commits: [changed] });
});

test('a shallow checkout is undecidable rather than accusing the one commit it holds', t => {
    const home = scratch(t, 'currency-shallow');
    const origin = resolve(home, 'origin');
    mkdirSync(origin);
    currentRepository(origin);
    assert.deepEqual(currency(origin), { kind: 'current' }, 'the fixture must be current while its history is readable');
    git(home, 'clone', '--quiet', '--depth', '1', `file://${origin}`, resolve(home, 'shallow'));
    assert.equal(currency(resolve(home, 'shallow')).kind, 'undecidable');
});

test('the command refuses an unmeasured checkout with the remedy and accepts a current one', t => {
    const root = scratch(t, 'currency-cli');
    currentRepository(root);
    const cli = (cwd: string) => spawnSync(process.execPath, [new URL('./measurement-currency.ts', import.meta.url).pathname, cwd], { env: isolated, encoding: 'utf8' });
    assert.equal(cli(root).status, 0);
    const moved = commit(root, 'interpreter/src/commonMain/kotlin/Engine.kt', 'val engine = 4\n', 'production moves');
    const refused = cli(root);
    assert.equal(refused.status, 1);
    assert.match(refused.stderr, new RegExp(moved));
    assert.match(refused.stderr, /scripts\/perf\.sh gate/);
});
