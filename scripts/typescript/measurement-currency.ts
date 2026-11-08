// Whether interpreter production source has outrun every recorded performance measurement (NFR #1: missing
// evidence fails rather than passes).
//
// It needs only git and `perf-results/`, so it runs from the pre-push hook and as an early CI step rather than
// inside a build lane, where the answer would wait for everything that lane compiles and runs first.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

/**
 * Interpreter source that SHIPS. Test source sets are deliberately absent: a test-only commit changes no consumer's
 * runtime, and owing a measurement for one would train people to run the gate to clear noise.
 */
const PRODUCTION_PATHS = [
    'interpreter/src/commonMain',
    'interpreter/src/jvmMain',
    'interpreter/src/jsMain',
    'shared-regex/src/commonMain',
];

export type Currency =
    | { kind: 'current' }
    | { kind: 'unmeasured'; commits: string[] }
    | { kind: 'undecidable'; reason: string };

/**
 * A commit is covered when SOME record sits at or after it — the minimum over every record, not the newest,
 * because a run may be taken on an older tree while a newer record exists from a side branch.
 *
 * `undecidable` is its own outcome because a shallow checkout resolves none of the recorded commits and so looks,
 * by commit list alone, like an unmeasured one. Failing is still right there; naming a commit it never read is not.
 */
export function currency(root: string): Currency {
    const recorded = recordedCommits(root);
    const measured = recorded.filter(commit => git(root, 'cat-file', '-e', `${commit}^{commit}`) !== null);
    if (measured.length === 0) {
        if (recorded.length > 0) return { kind: 'undecidable', reason: undecidableReason(root, recorded.length) };
        const history = productionCommits(root, 'HEAD');
        return history.length === 0 ? { kind: 'current' } : { kind: 'unmeasured', commits: history };
    }
    const outstanding = measured
        .map(commit => productionCommits(root, `${commit}..HEAD`))
        .reduce((fewest, candidate) => (candidate.length < fewest.length ? candidate : fewest));
    return outstanding.length === 0 ? { kind: 'current' } : { kind: 'unmeasured', commits: outstanding };
}

function undecidableReason(root: string, records: number): string {
    const why = git(root, 'rev-parse', '--is-shallow-repository') === 'true'
        ? 'this repository is SHALLOW, so the history the records point into was never fetched'
        : 'the history the records point into is not present in this repository';
    return `${records} committed measurement record(s) name commits this checkout cannot resolve, and ${why}. ` +
        'Fetch full history (`fetch-depth: 0` on actions/checkout, or `git fetch --unshallow`) and run it again.';
}

/** Every `commit` a record names, its `-DIRTY`/`-UNKNOWN-STATE` label stripped; read textually, line by line. */
function recordedCommits(root: string): string[] {
    const commits = new Set<string>();
    for (const file of jsonlFiles(resolve(root, 'perf-results'))) {
        for (const line of readFileSync(file, 'utf8').split('\n')) {
            const value = /"commit":\s*"([^"]*)"/.exec(line)?.[1]?.split('-')[0];
            if (value) commits.add(value);
        }
    }
    return [...commits];
}

function jsonlFiles(directory: string): string[] {
    if (!existsSync(directory)) return [];
    return readdirSync(directory).flatMap(name => {
        const path = join(directory, name);
        if (statSync(path).isDirectory()) return jsonlFiles(path);
        return name.endsWith('.jsonl') ? [path] : [];
    });
}

function productionCommits(root: string, range: string): string[] {
    return (git(root, 'rev-list', '--abbrev-commit', range, '--', ...PRODUCTION_PATHS) ?? '')
        .split('\n').filter(line => line.trim() !== '');
}

/** Null when git refuses: an unknown revision is a question this check must not answer with a pass. */
function git(root: string, ...args: string[]): string | null {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    return result.status === 0 ? result.stdout.trim() : null;
}

function main(root: string): void {
    const verdict = currency(root);
    if (verdict.kind === 'current') {
        console.log('measurement currency: every interpreter production commit is covered by a recorded measurement');
        return;
    }
    if (verdict.kind === 'unmeasured') {
        console.error('measurement currency: interpreter production source has moved past every recorded measurement.');
        console.error(`Unmeasured commits (newest first):\n  ${verdict.commits.join('\n  ')}\n`);
        console.error('Take a measurement and commit the record it writes:\n  ./scripts/perf.sh gate');
    } else {
        console.error(`measurement currency: not decidable in this checkout. ${verdict.reason}`);
    }
    process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    main(resolve(process.argv[2] ?? join(import.meta.dirname, '../..')));
}
