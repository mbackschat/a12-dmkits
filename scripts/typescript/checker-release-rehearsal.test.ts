import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileInventory } from './checker-maven.ts';
import { bundleMaven } from './checker-release-files.ts';

const publisher = resolve('scripts/typescript/checker-release.ts');
const releaseGapScript = readFileSync(resolve('scripts/check-release-gaps.sh'));
const fakeGh = String.raw`#!/usr/bin/env node
const fs = require('node:fs'); const cp = require('node:child_process'); const path = require('node:path');
const home = process.env.CHECKER_REHEARSAL;
const statePath = path.join(home,'state.json');
const state = JSON.parse(fs.readFileSync(statePath));
const args = process.argv.slice(2); const repo = 'repos/mbackschat/a12-consistency-releases';
const save = () => fs.writeFileSync(statePath,JSON.stringify(state));
const json = value => process.stdout.write(JSON.stringify(value));
const notFound = () => { process.stderr.write('(HTTP 404)'); process.exit(1); };
function git(args) { const r = cp.spawnSync('git',args,{stdio:'pipe'}); if(r.status) {process.stderr.write(r.stderr);process.exit(r.status);} }
if(args[0]==='api') {
 const endpoint=args.find(a=>a.startsWith(repo));
 if(endpoint===repo+'/environments/github-pages') json({deployment_branch_policy:{protected_branches:false,custom_branch_policies:true}});
 else if(endpoint===repo+'/environments/github-pages/deployment-branch-policies') {
  if(args.includes('POST')){state.allowedTag=args.find(a=>a.startsWith('name=')).slice(5);save();json({});}
  else json({branch_policies:state.allowedTag?[{name:state.allowedTag,type:'tag'}]:[{name:'main',type:'branch'}]});
 }
 else if(endpoint===repo+'/pages') json({build_type:'workflow'});
 else if(endpoint===repo) { if(!state.repo)notFound(); json({name:'a12-consistency-releases'}); }
 else if(endpoint===repo+'/releases?per_page=100') json([state.release ? [state.release] : []]);
 else if(endpoint.includes('/releases/tags/')) notFound();
 else if(endpoint.includes('/releases/assets/')) process.stdout.write(fs.readFileSync(path.join(home,'asset-'+endpoint.split('/').at(-1))));
 else throw Error('unexpected api '+args);
} else if(args[0]==='repo' && args[1]==='create') {
 git(['init','--bare','--initial-branch=main',path.join(home,'mirror.git')]);state.repo=true;save();
} else if(args[0]==='repo' && args[1]==='clone') git(['clone',path.join(home,'mirror.git'),args[3]]);
else if(args[0]==='release' && args[1]==='create') { state.release={draft:true,tag_name:args[2],assets:[]};save(); }
else if(args[0]==='release' && args[1]==='upload') {
 const name=path.basename(args[3]);
 if(state.fail===name){process.stderr.write('injected upload failure');process.exit(1);}
 if(state.release.assets.some(a=>a.name===name))throw Error('replacement forbidden');
 const id=state.release.assets.length+1;fs.copyFileSync(args[3],path.join(home,'asset-'+id));state.release.assets.push({id,name});save();
} else if(args[0]==='workflow' && args[1]==='run') {
 if(state.allowedTag!==state.release.tag_name)throw Error('release tag is blocked by environment');
 if(args.at(-1)!==state.release.tag_name) throw Error('deployment must name release tag');
 state.events.push('deploy'); state.runAt=new Date().toISOString().replace(/\.\d{3}Z$/,'Z'); save();
} else if(args[0]==='run' && args[1]==='list') {
 const sha=cp.spawnSync('git',['--git-dir',path.join(home,'mirror.git'),'rev-parse','main'],{encoding:'utf8'}).stdout.trim();
 json([{databaseId:1,headSha:sha,createdAt:state.runAt,status:'completed',conclusion:'success'}]);
} else if(args[0]==='release' && args[1]==='edit') {
 if(args.includes('--draft=false')){state.release.draft=false;state.events.push('publish');}
 if(args.includes('--latest')){state.latest=true;state.events.push('latest');}
 save();
} else throw Error('unexpected gh '+args);
`;

test('production publisher resumes uploads and go-live while refusing altered identities and premature promotion', () => {
    const home = mkdtempSync(join(tmpdir(), 'checker-rehearsal-'));
    const root = join(home, 'source'); const bin = join(home, 'bin');
    mkdirSync(root); mkdirSync(bin);
    writeFileSync(join(bin, 'gh'), fakeGh, { mode: 0o755 });
    const realGit = spawnSync('which', ['git'], { encoding: 'utf8' }).stdout.trim();
    writeFileSync(join(bin, 'git'), `#!/usr/bin/env node\nconst cp=require('node:child_process'); const args=process.argv.slice(2); if(args.join(' ')==='remote get-url origin' && process.cwd()===require('node:fs').realpathSync(${JSON.stringify(root)})) console.log('https://github.com/mbackschat/a12-dmkits.git'); else {const r=cp.spawnSync(${JSON.stringify(realGit)},args,{stdio:'inherit'});process.exit(r.status??1);}`, { mode: 0o755 });
    const env = { ...process.env, CHECKER_REHEARSAL: home, PATH: `${bin}:${process.env.PATH}`, GIT_CONFIG_GLOBAL: join(home, 'gitconfig'), GIT_CONFIG_NOSYSTEM: '1' };
    writeFileSync(env.GIT_CONFIG_GLOBAL, `[url "${home}/source.git"]\n insteadOf = https://github.com/mbackschat/a12-dmkits.git\n`);
    const run = (cwd: string, cmd: string, args: string[]) => {
        const r = spawnSync(cmd, args, { cwd, env, encoding: 'utf8', timeout: 30_000 });
        assert.equal(r.status, 0, r.stderr || r.stdout); return r.stdout.trim();
    };
    const statePath = join(home, 'state.json');
    const state = () => JSON.parse(readFileSync(statePath, 'utf8'));
    const save = (value: unknown) => writeFileSync(statePath, JSON.stringify(value));
    try {
        run(home, 'git', ['init', '--bare', '--initial-branch=main', join(home, 'source.git')]);
        run(root, 'git', ['init', '--initial-branch=main']);
        run(root, 'git', ['config', 'user.name', 'Release rehearsal']);
        run(root, 'git', ['config', 'user.email', 'mbackschat@mail.de']);
        writeFileSync(join(root, '.gitignore'), 'build/\n');
        writeFileSync(join(root, 'build.gradle'), "\nversion = '0.14.0'\n");
        writeFileSync(join(root, 'LICENSE'), 'MIT License\n');
        mkdirSync(join(root, 'scripts')); mkdirSync(join(root, 'docs'));
        writeFileSync(join(root, 'scripts/check-release-gaps.sh'), releaseGapScript, { mode: 0o755 });
        writeFileSync(join(root, 'docs/REHEARSAL-GAPS.md'), '## Open\n\n_None open._\n');
        mkdirSync(join(root, '.github/consistency-mirror-workflows'), { recursive: true });
        writeFileSync(join(root, '.github/consistency-mirror-workflows/deploy-pages.yml'), 'name: fixture\n');
        run(root, 'git', ['add', '.']); run(root, 'git', ['commit', '-m', 'feat: create rehearsal']);
        run(root, 'git', ['tag', 'consistency-v0.14.0']);
        run(root, 'git', ['remote', 'add', 'origin', 'https://github.com/mbackschat/a12-dmkits.git']);
        run(root, 'git', ['push', 'origin', 'main', '--tags']);
        const source = run(root, 'git', ['rev-parse', 'HEAD']);
        const staging = join(root, 'build/consistency-release/maven'); mkdirSync(join(staging, 'io/github/mbackschat/a12/dm/dm-consistency/0.14.0'), { recursive: true });
        writeFileSync(join(staging, 'io/github/mbackschat/a12/dm/dm-consistency/0.14.0/dm-consistency-0.14.0.jar'), 'qualified fixture');
        const receipt = { schema: 1, version: '0.14.0', source, files: fileInventory(staging) };
        writeFileSync(join(root, 'build/consistency-release/qualification.json'), JSON.stringify(receipt));
        save({ fail: 'SHA256SUMS' });
        const publish = () => spawnSync(process.execPath, [publisher, 'stage', '0.14.0'], { cwd: root, env, encoding: 'utf8', timeout: 30_000 });
        let result = publish();
        assert.notEqual(result.status, 0); assert.match(result.stderr, /injected upload failure/);
        assert.equal(state().release.assets.length, 1);
        const first = readFileSync(join(home, 'asset-1'));
        const mirrorCommit = run(home, 'git', ['--git-dir', join(home, 'mirror.git'), 'rev-parse', 'main']);
        save({ ...state(), fail: null });
        result = publish(); assert.equal(result.status, 0, result.stderr);
        assert.equal(state().release.assets.length, 3);
        assert.deepEqual(readFileSync(join(home, 'asset-1')), first);
        assert.equal(run(home, 'git', ['--git-dir', join(home, 'mirror.git'), 'rev-parse', 'main']), mirrorCommit);
        writeFileSync(join(home, 'asset-1'), 'tampered');
        result = publish(); assert.notEqual(result.status, 0); assert.match(result.stderr, /asset hash mismatch/);
        writeFileSync(join(home, 'asset-1'), first);
        writeFileSync(join(staging, 'io/github/mbackschat/a12/dm/dm-consistency/0.14.0/dm-consistency-0.14.0.jar'), 'changed');
        result = publish(); assert.notEqual(result.status, 0); assert.match(result.stderr, /qualification/);
        writeFileSync(join(staging, 'io/github/mbackschat/a12/dm/dm-consistency/0.14.0/dm-consistency-0.14.0.jar'), 'qualified fixture');
        save({ ...state(), release: { ...state().release, draft: false } });
        result = publish(); assert.notEqual(result.status, 0); assert.match(result.stderr, /immutable/);
        assert.equal(state().release.assets.length, 3);
        const driver = join(home, 'go-live.mjs');
        writeFileSync(driver, `import { goLiveChecker } from ${JSON.stringify(new URL('./checker-release-github.ts', import.meta.url).href)};
import fs from 'node:fs'; import path from 'node:path';
const home=process.env.CHECKER_REHEARSAL, file=path.join(home,'state.json');
const load=()=>JSON.parse(fs.readFileSync(file)); const save=s=>fs.writeFileSync(file,JSON.stringify(s));
await goLiveChecker(process.cwd(),'0.14.0',{
 consumers:async(root,version,repository,phase)=>{const s=load(); if(version!=='0.14.0'||phase!=='public'||repository!=='https://mbackschat.github.io/a12-consistency-releases/maven/')throw Error('wrong consumer target');s.events.push('consumers');save(s);if(s.fail==='consumers')throw Error('injected consumers');},
 download:async(url)=>{const s=load();
 if(url.startsWith('https://mbackschat.github.io/a12-consistency-releases/')){
  if(!s.events.includes('pages')){s.events.push('pages');save(s);} if(s.fail==='pages')return Buffer.from('bad hash');
  return fs.readFileSync(path.join(process.cwd(),'build/consistency-release/mirror/site',url.slice('https://mbackschat.github.io/a12-consistency-releases/'.length)));
 }
 if(s.latest)throw Error('latest set before anonymous verification');
 if(s.release.draft)throw Error('release still draft');
 if(url.startsWith('https://api.github.com/')){s.events.push('anonymous');save(s);if(s.fail==='anonymous')throw Error('injected anonymous');return Buffer.from(JSON.stringify(s.release));}
 const name=url.split('/').at(-1), asset=s.release.assets.find(a=>a.name===name);if(!asset)throw Error('unknown asset');
 if(s.fail==='anonymous-asset')return Buffer.from('wrong asset');return fs.readFileSync(path.join(home,'asset-'+asset.id));
 }
});`);
        const live = () => spawnSync(process.execPath, [driver], { cwd: root, env, encoding: 'utf8', timeout: 30_000 });
        for (const failure of ['pages', 'consumers', 'anonymous', 'anonymous-asset']) {
            save({ ...state(), fail: failure, latest: false, events: [], release: { ...state().release, draft: true } });
            result = live(); assert.notEqual(result.status, 0, failure);
            assert.equal(state().latest, false, failure);
            if (['pages', 'consumers'].includes(failure)) {
                assert.equal(state().release.draft, true); assert.ok(!state().events.includes('publish'));
            } else assert.equal(state().release.draft, false);
        }
        save({ ...state(), fail: null, events: [] });
        result = live(); assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(state().events, ['pages', 'consumers', 'anonymous', 'latest']);
        save({ ...state(), latest: false, events: [], release: { ...state().release, draft: true } });
        result = live(); assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(state().events, ['deploy', 'pages', 'consumers', 'publish', 'anonymous', 'latest']);
        assert.deepEqual(readFileSync(join(home, 'asset-1')), first);
    } finally { rmSync(home, { recursive: true, force: true }); }
});

test('Maven release ZIP is deterministic across repeated assembly', () => {
    const home = mkdtempSync(join(tmpdir(), 'checker-bundle-'));
    try {
        const staging = join(home, 'maven'); mkdirSync(staging);
        writeFileSync(join(staging, 'artifact.pom'), 'public bytes');
        assert.deepEqual(bundleMaven(staging, join(home, 'one'), '0.14.0'), bundleMaven(staging, join(home, 'two'), '0.14.0'));
    } finally { rmSync(home, { recursive: true, force: true }); }
});

test('public mirror refuses unrelated root, site and workflow files and symlinks', async () => {
    const { assertMirrorTree } = await import('./checker-release-files.ts');
    const { symlinkSync } = await import('node:fs');
    const home = mkdtempSync(join(tmpdir(), 'checker-tree-'));
    try {
        assert.doesNotThrow(() => assertMirrorTree(home));
        for (const name of ['PrivateImplementation.kt', 'site/debug.txt', '.github/workflows/extra.yml']) {
            const file = join(home, name); mkdirSync(resolve(file, '..'), { recursive: true }); writeFileSync(file, 'unexpected');
            assert.throws(() => assertMirrorTree(home), /unexpected mirror file/); rmSync(file);
        }
        symlinkSync('/tmp', join(home, 'site/maven'));
        assert.throws(() => assertMirrorTree(home), /symlink/);
    } finally { rmSync(home, { recursive: true, force: true }); }
});
