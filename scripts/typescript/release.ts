// The four-repository release: the a12-dmkits source and the dmtool, interpreter and checker mirrors ship one
// version together, from one source commit (RELEASE-SPEC §4a).
//
// This sequences the three existing publishers and adds only what none of them can see on its own: that all three
// source tags name the same commit, that the libraries are live before dmtool goes live, and that both rolling
// changelog sections are frozen afterwards. Every publisher keeps its own gates; nothing here re-implements one.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { currency } from './measurement-currency.ts';

export type Component = 'interpreter' | 'checker' | 'dmtool';
export type ReleaseState = 'absent' | 'draft' | 'published' | 'latest';

/** Libraries first: dmtool's go-live is the last public step, so a failed library leaves dmtool unpublished. */
export const COMPONENTS: readonly Component[] = ['interpreter', 'checker', 'dmtool'];

const MIRROR: Record<Component, string> = {
    interpreter: 'mbackschat/a12-interpreter-releases',
    checker: 'mbackschat/a12-consistency-releases',
    dmtool: 'mbackschat/a12-dmtool-releases',
};
/** The GitHub email the source and mirror pushes must carry (global git policy for github.com remotes). */
const PUSH_EMAIL = 'mbackschat@mail.de';

export function sourceTag(component: Component, version: string): string {
    switch (component) {
        case 'interpreter': return `interpreter-v${version}`;
        case 'checker': return `consistency-v${version}`;
        case 'dmtool': return `v${version}`;
    }
}

export function rootVersion(buildGradle: string): string | null {
    return /^version\s*=\s*'([^']+)'/m.exec(buildGradle)?.[1] ?? null;
}

export interface Changelogs { plugin: string; interpreter: string; checker: string }

/**
 * The dmtool notes are the frozen `[X.Y.Z]` section `bump-version.sh` writes; the two library changelogs instead
 * keep a rolling `[@VERSION@]` section that their publishers render, and both refuse an empty one.
 */
export function changelogProblems(version: string, logs: Changelogs): string[] {
    const problems: string[] = [];
    if (!new RegExp(`^## \\[${version.replaceAll('.', '\\.')}\\]`, 'm').test(logs.plugin)) {
        problems.push(`plugin/CHANGELOG.md has no "## [${version}]" section (scripts/bump-version.sh writes it)`);
    }
    for (const [name, text] of [['interpreter/CHANGELOG.md', logs.interpreter], ['consistency/public-release/CHANGELOG.md', logs.checker]]) {
        const rolling = rollingSection(text);
        if (rolling === null) problems.push(`${name} does not open with a "## [@VERSION@]" section`);
        else if (!/^- /m.test(rolling.body)) problems.push(`${name}: the "## [@VERSION@]" section has no entry`);
    }
    return problems;
}

interface Section { start: number; end: number; body: string }

function rollingSection(text: string): Section | null {
    const headings = [...text.matchAll(/^## \[[^\]]+\].*$/gm)];
    const first = headings[0];
    if (!first || first[0] !== '## [@VERSION@]') return null;
    const start = first.index;
    const end = headings[1]?.index ?? text.length;
    return { start, end, body: text.slice(start + first[0].length, end) };
}

export interface TagState { name: string; local: string | null; remote: string | null }

/** A tag may be absent (the `tag` step creates it) or name HEAD on both sides; anything else is a mixed release. */
export function tagProblems(tags: TagState[], head: string): string[] {
    return tags.flatMap(({ name, local, remote }) => {
        if (local === null && remote === null) return [];
        if (local !== remote) return [`${name}: local ${local ?? 'absent'} differs from origin ${remote ?? 'absent'}`];
        return local === head ? [] : [`${name} names ${local}, not HEAD ${head}; all three source tags must name one commit`];
    });
}

export type Step = { component: Component; action: 'stage' | 'go-live' };

/**
 * What remains to run. A published library still runs go-live until it is latest, because both library
 * publishers resume a go-live interrupted after publication; dmtool's refuses anything but a draft.
 */
export function plan(operation: 'stage' | 'go-live', states: Record<Component, ReleaseState>): Step[] {
    return COMPONENTS.filter(component => {
        const state = states[component];
        if (operation === 'stage') return state === 'absent' || state === 'draft';
        if (component === 'dmtool') return state === 'draft';
        return state === 'draft' || state === 'published';
    }).map(component => ({ component, action: operation }));
}

/**
 * Freeze both rolling sections at [version] and open fresh ones. The interpreter's new section carries the
 * standing `### Performance` story forward, because its current section must present one
 * (`InterpreterDocumentationTest`); the checker's opens empty, which its stager refuses until an entry is written.
 */
export function freezeChangelog(text: string, version: string, carryPerformance: boolean): string {
    const rolling = rollingSection(text);
    if (rolling === null) throw new Error('the changelog does not open with a "## [@VERSION@]" section');
    const performance = carryPerformance ? /^### Performance\n[\s\S]*?(?=^### |(?![\s\S]))/m.exec(rolling.body)?.[0] : undefined;
    if (carryPerformance && !performance) throw new Error('the rolling section has no "### Performance" subsection to carry forward');
    const opened = performance ? `## [@VERSION@]\n\n${performance.trimEnd()}\n\n` : '## [@VERSION@]\n\n';
    return text.slice(0, rolling.start) + opened + `## [${version}]` + text.slice(rolling.start + '## [@VERSION@]'.length);
}

// ---------------------------------------------------------------------------------------------------------------

function run(root: string, command: string, args: string[]): void {
    console.log(`\n$ ${[command, ...args].join(' ')}`);
    const result = spawnSync(command, args, { cwd: root, stdio: 'inherit' });
    if (result.status !== 0) throw new Error(`${command} failed (exit ${result.status ?? result.signal})`);
}

function read(root: string, command: string, args: string[]): string | null {
    const result = spawnSync(command, args, { cwd: root, encoding: 'utf8' });
    return result.status === 0 ? result.stdout.trim() : null;
}

function tagStates(root: string, version: string): TagState[] {
    return COMPONENTS.map(component => {
        const name = sourceTag(component, version);
        const remote = (read(root, 'git', ['ls-remote', 'origin', `refs/tags/${name}^{}`]) || read(root, 'git', ['ls-remote', 'origin', `refs/tags/${name}`]) || '').split(/\s/)[0] || null;
        return { name, local: read(root, 'git', ['rev-parse', '--verify', '--quiet', `${name}^{}`]), remote };
    });
}

function releaseState(root: string, component: Component, version: string): ReleaseState {
    const draft = read(root, 'gh', ['release', 'view', `v${version}`, '--repo', MIRROR[component], '--json', 'isDraft', '-q', '.isDraft']);
    if (draft === null) return 'absent';
    if (draft === 'true') return 'draft';
    const latest = read(root, 'gh', ['api', `repos/${MIRROR[component]}/releases/latest`, '-q', '.tag_name']);
    return latest === `v${version}` ? 'latest' : 'published';
}

function releaseStates(root: string, version: string): Record<Component, ReleaseState> {
    return Object.fromEntries(COMPONENTS.map(c => [c, releaseState(root, c, version)])) as Record<Component, ReleaseState>;
}

function changelogs(root: string): Changelogs {
    const text = (path: string) => readFileSync(resolve(root, path), 'utf8');
    return { plugin: text('plugin/CHANGELOG.md'), interpreter: text('interpreter/CHANGELOG.md'), checker: text('consistency/public-release/CHANGELOG.md') };
}

/** Every refusal that needs no build, collected so one run names them all. */
function preflight(root: string, version: string): void {
    const problems: string[] = [];
    const declared = rootVersion(readFileSync(resolve(root, 'build.gradle'), 'utf8'));
    if (declared !== version) problems.push(`build.gradle declares ${declared}, not ${version} (scripts/bump-version.sh ${version})`);
    if (read(root, 'git', ['branch', '--show-current']) !== 'main') problems.push('not on main');
    if (read(root, 'git', ['status', '--porcelain'])) problems.push('the working tree is not clean');
    if (read(root, 'git', ['config', 'user.email']) !== PUSH_EMAIL) problems.push(`git user.email must be ${PUSH_EMAIL} for the github.com pushes`);
    run(root, 'git', ['fetch', '--quiet', 'origin', 'main']);
    const head = read(root, 'git', ['rev-parse', 'HEAD']) ?? '';
    if (head !== read(root, 'git', ['rev-parse', 'origin/main'])) problems.push('HEAD is not origin/main; push the release commit first');
    problems.push(...changelogProblems(version, changelogs(root)));
    problems.push(...tagProblems(tagStates(root, version), head));
    const measured = currency(root);
    if (measured.kind !== 'current') problems.push(`interpreter measurement currency is ${measured.kind}; run ./scripts/perf.sh gate and commit its record`);
    const evidence = process.env.RK_RELEASE_PERF_EVIDENCE ?? resolve(root, 'build/release-perf-evidence');
    if (!existsSync(evidence)) problems.push('no sealed release performance evidence; run scripts/release-perf-gate.sh --fresh on an idle machine');
    if (problems.length) throw new Error(`release ${version} preflight refused:\n  - ${problems.join('\n  - ')}`);
    run(root, './scripts/check-release-gaps.sh', []);
    run(root, './scripts/update-code-statistics.sh', ['--check', '--artifact', 'all']);
    run(root, './scripts/release-perf-gate.sh', []);
    console.log(`\npreflight passed for ${version} at ${head}`);
}

function requireTagsAtHead(root: string, version: string): void {
    const head = read(root, 'git', ['rev-parse', 'HEAD']) ?? '';
    const tags = tagStates(root, version);
    const problems = tags.filter(t => t.local === null || t.remote === null).map(t => `${t.name} is not tagged and pushed`);
    problems.push(...tagProblems(tags, head));
    if (problems.length) throw new Error(`run "release.ts tag ${version}" first:\n  - ${problems.join('\n  - ')}`);
}

function tag(root: string, version: string): void {
    preflight(root, version);
    const gradle = readFileSync(resolve(root, 'build.gradle'), 'utf8');
    const kernel = /kernelVersion\s*=\s*'([^']+)'/.exec(gradle)?.[1];
    const distribution = /a12Distribution\s*=\s*'([^']+)'/.exec(gradle)?.[1];
    const messages: Record<Component, string> = {
        interpreter: `A12 Interpreter ${version}`,
        checker: `A12 consistency checker ${version}`,
        dmtool: `v${version} — kernel ${kernel} (A12 Tools ${distribution})`,
    };
    const missing = tagStates(root, version).filter(t => t.local === null);
    for (const { name } of missing) {
        const component = COMPONENTS.find(c => sourceTag(c, version) === name)!;
        run(root, 'git', ['tag', '-a', name, '-m', messages[component]]);
    }
    run(root, 'git', ['push', '--atomic', 'origin', ...COMPONENTS.map(c => `refs/tags/${sourceTag(c, version)}`)]);
}

function execute(root: string, version: string, { component, action }: Step): void {
    const live = action === 'go-live';
    switch (component) {
        case 'interpreter':
            if (!live) {
                run(root, './build.sh', [':interpreter:check', '--console=plain']);
                run(root, './scripts/publish-interpreter-release.sh', [sourceTag(component, version), '--qualify']);
            }
            run(root, './scripts/publish-interpreter-release.sh', [sourceTag(component, version), ...(live ? ['--go-live'] : [])]);
            return;
        case 'checker': {
            const checker = ['checker release', 'scripts/typescript/checker-release.ts'];
            if (!live) run(root, './scripts/run-typescript.sh', [...checker, 'qualify', version]);
            run(root, './scripts/run-typescript.sh', [...checker, action, version]);
            return;
        }
        case 'dmtool':
            run(root, './scripts/publish-release.sh', [`v${version}`, ...(live ? ['--go-live'] : [])]);
            return;
    }
}

function publish(root: string, version: string, operation: 'stage' | 'go-live'): void {
    requireTagsAtHead(root, version);
    for (const step of plan(operation, releaseStates(root, version))) {
        // Re-read before dmtool: its go-live is the one step that must follow two live libraries.
        if (step.component === 'dmtool' && operation === 'go-live') {
            const states = releaseStates(root, version);
            const pending = COMPONENTS.filter(c => c !== 'dmtool' && states[c] !== 'latest');
            if (pending.length) throw new Error(`dmtool goes live only after the libraries: ${pending.join(', ')} not latest`);
        }
        execute(root, version, step);
    }
    status(root, version);
}

function freeze(root: string, version: string): void {
    const states = releaseStates(root, version);
    const pending = COMPONENTS.filter(c => states[c] !== 'latest');
    if (pending.length) throw new Error(`freeze follows go-live; not yet latest: ${pending.join(', ')}`);
    for (const [path, carry] of [['interpreter/CHANGELOG.md', true], ['consistency/public-release/CHANGELOG.md', false]] as const) {
        const file = resolve(root, path);
        writeFileSync(file, freezeChangelog(readFileSync(file, 'utf8'), version, carry));
        console.log(`froze ${path} at [${version}]`);
    }
}

function status(root: string, version: string): void {
    const states = releaseStates(root, version);
    for (const { name, local, remote } of tagStates(root, version)) {
        console.log(`${name.padEnd(24)} local ${(local ?? 'absent').slice(0, 12).padEnd(12)} origin ${(remote ?? 'absent').slice(0, 12)}`);
    }
    for (const component of COMPONENTS) console.log(`${MIRROR[component].padEnd(40)} v${version} ${states[component]}`);
}

const OPERATIONS = ['status', 'preflight', 'tag', 'stage', 'go-live', 'freeze'] as const;

function main(args: string[]): void {
    const [operation, version] = args;
    if (!OPERATIONS.includes(operation as never) || !/^\d+\.\d+\.\d+$/.test(version ?? '') || args.length !== 2) {
        throw new Error(`usage: release.ts ${OPERATIONS.join('|')} X.Y.Z`);
    }
    const root = resolve(import.meta.dirname, '../..');
    switch (operation as typeof OPERATIONS[number]) {
        case 'status': return status(root, version);
        case 'preflight': return preflight(root, version);
        case 'tag': return tag(root, version);
        case 'stage': return publish(root, version, 'stage');
        case 'go-live': return publish(root, version, 'go-live');
        case 'freeze': return freeze(root, version);
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try { main(process.argv.slice(2)); } catch (error) { console.error((error as Error).message); process.exitCode = 1; }
}
