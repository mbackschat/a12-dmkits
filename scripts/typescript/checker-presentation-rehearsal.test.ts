import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, cpSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileInventory } from './checker-maven.ts';
const implementation = new URL('./checker-presentation.ts', import.meta.url).href;
const hash = (bytes: string) => createHash('sha256').update(bytes).digest('hex');
const fakeGh = String.raw`#!/usr/bin/env node
const fs=require('node:fs'), cp=require('node:child_process'), p=require('node:path');
const home=process.env.PRESENTATION_REHEARSAL, file=p.join(home,'state.json'), state=JSON.parse(fs.readFileSync(file)), args=process.argv.slice(2);
const json=x=>console.log(JSON.stringify(x)), save=()=>fs.writeFileSync(file,JSON.stringify(state));
if(args[0]==='repo' && args[1]==='clone') {const r=cp.spawnSync('git',['clone',p.join(home,'mirror.git'),args[3]],{stdio:'inherit'});process.exit(r.status);}
else if(args[0]==='api') {
 const endpoint=args.find(a=>a.startsWith('repos/'));
 if(endpoint.endsWith('/releases?per_page=100')) json([[state.release]]);
 else if(endpoint.includes('/releases/assets/')) process.stdout.write(fs.readFileSync(p.join(home,'asset-'+endpoint.split('/').at(-1))));
 else if(endpoint.endsWith('/pages')) json({build_type:'workflow'});
 else if(endpoint.endsWith('/environments/github-pages')) json({deployment_branch_policy:{protected_branches:false,custom_branch_policies:true}});
 else if(endpoint.endsWith('/deployment-branch-policies')) {
  if(args.includes('POST')) {state.allowed=args.find(a=>a.startsWith('name=')).slice(5);save();json({});}
  else json({branch_policies:state.allowed?[{name:state.allowed,type:'tag'}]:[{name:'main',type:'branch'}]});
 } else throw Error('unexpected API '+args);
} else if(args[0]==='workflow' && args[1]==='run') {
 if(args.at(-1)!==state.allowed || !state.allowed.startsWith('presentation-v0.14.0-')) throw Error('wrong deployment tag');
 state.events.push('deploy');state.runAt=new Date().toISOString().replace(/\.\d{3}Z$/,'Z');save();
} else if(args[0]==='run' && args[1]==='list') {
 const sha=cp.spawnSync('git',['--git-dir',p.join(home,'mirror.git'),'rev-parse','main'],{encoding:'utf8'}).stdout.trim();
 json([{databaseId:1,headSha:sha,createdAt:state.runAt,status:'completed',conclusion:state.fail?'failure':'success'}]);
} else throw Error('unexpected mutation '+args);
`;
test('production presentation publisher preserves release bytes, resumes deployment and refuses changed assets', async t => {
    const home = mkdtempSync(resolve(tmpdir(), 'presentation-rehearsal-')); t.after(() => rmSync(home, { recursive: true, force: true }));
    const root = resolve(home, 'source'), seed = resolve(home, 'seed'), bin = resolve(home, 'bin');
    mkdirSync(root); mkdirSync(seed); mkdirSync(bin);
    const env = { ...process.env, PRESENTATION_REHEARSAL: home, PATH: `${bin}:${process.env.PATH}`, GIT_CONFIG_GLOBAL: resolve(home, 'gitconfig'), GIT_CONFIG_NOSYSTEM: '1' };
    writeFileSync(env.GIT_CONFIG_GLOBAL, '');
    const run = (cwd: string, cmd: string, args: string[]) => {
        const r = spawnSync(cmd, args, { cwd, env, encoding: 'utf8', timeout: 30_000 }); assert.equal(r.status, 0, r.stdout + r.stderr); return r.stdout.trim();
    };
    writeFileSync(resolve(bin, 'gh'), fakeGh, { mode: 0o755 });
    for (const cwd of [root, seed]) {
        run(cwd, 'git', ['init', '-b', 'main']); run(cwd, 'git', ['config', 'user.email', 'mbackschat@mail.de']); run(cwd, 'git', ['config', 'user.name', 'Test']);
    }
    cpSync(resolve('consistency/public-release'), resolve(root, 'consistency/public-release'), { recursive: true });
    cpSync(resolve('assets/infographics'), resolve(root, 'assets/infographics'), { recursive: true, filter: file => !file.endsWith('.png') });
    cpSync(resolve('.github/consistency-mirror-workflows'), resolve(root, '.github/consistency-mirror-workflows'), { recursive: true });
    mkdirSync(resolve(root, 'scripts')); writeFileSync(resolve(root, 'scripts/check-infographic-freshness.sh'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    writeFileSync(resolve(root, '.gitignore'), 'build/\n');
    run(root, 'git', ['add', '.']); run(root, 'git', ['commit', '-m', 'fixture']); run(root, 'git', ['tag', 'consistency-v0.14.0']);
    run(root, 'git', ['remote', 'add', 'origin', 'https://github.com/mbackschat/a12-dmkits.git']);
    const source = run(root, 'git', ['rev-parse', 'HEAD']);
    mkdirSync(resolve(seed, 'site/maven/io/github/mbackschat/a12/dm/dm-consistency/0.14.0'), { recursive: true }); mkdirSync(resolve(seed, 'releases'));
    writeFileSync(resolve(seed, 'site/maven/io/github/mbackschat/a12/dm/dm-consistency/0.14.0/checker.jar'), 'unchanged');
    writeFileSync(resolve(seed, 'site/index.html'), 'old presentation');
    const manifest = { schema: 1, version: '0.14.0', source, sourceTag: 'consistency-v0.14.0', files: fileInventory(resolve(seed, 'site/maven')), site: fileInventory(resolve(seed, 'site')), assets: { 'archive.zip': hash('archive') } };
    const manifestBytes = JSON.stringify(manifest, null, 2) + '\n'; writeFileSync(resolve(seed, 'releases/v0.14.0.json'), manifestBytes);
    run(seed, 'git', ['add', '.']); run(seed, 'git', ['commit', '-m', 'published release']); run(seed, 'git', ['tag', 'v0.14.0']);
    run(home, 'git', ['clone', '--bare', seed, resolve(home, 'mirror.git')]);
    const releaseSha = run(seed, 'git', ['rev-parse', 'HEAD']);
    const state = { release: { draft: false, tag_name: 'v0.14.0', assets: [{ id: 1, name: 'archive.zip' }, { id: 2, name: 'release-manifest.json' }] }, events: [], fail: true };
    writeFileSync(resolve(home, 'state.json'), JSON.stringify(state)); writeFileSync(resolve(home, 'asset-1'), 'archive'); writeFileSync(resolve(home, 'asset-2'), manifestBytes);
    const showcase = resolve(home, 'showcase'); mkdirSync(resolve(showcase, 'assets'), { recursive: true }); writeFileSync(resolve(showcase, 'index.html'), 'showcase'); writeFileSync(resolve(showcase, 'assets/index-fixture.js'), 'console.log("fixture")');
    const driver = resolve(home, 'driver.mts');
    writeFileSync(driver, `import { refreshPresentation } from ${JSON.stringify(implementation)}; import {readFileSync} from 'node:fs'; import {resolve} from 'node:path';
await refreshPresentation(${JSON.stringify(root)},'0.14.0',{build:()=>${JSON.stringify(showcase)},download:async url=>readFileSync(resolve(${JSON.stringify(root)},'build/consistency-release/mirror/site',new URL(url).pathname.replace('/a12-consistency-releases/','')))});`);
    const execute = () => spawnSync(process.execPath, [driver], { cwd: root, env, encoding: 'utf8', timeout: 30_000 });
    let result = execute(); assert.notEqual(result.status, 0); assert.match(result.stderr, /Pages run 1 failed/);
    let current = JSON.parse(readFileSync(resolve(home, 'state.json'), 'utf8')); current.fail = false; writeFileSync(resolve(home, 'state.json'), JSON.stringify(current));
    result = execute(); assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(run(home, 'git', ['--git-dir', resolve(home, 'mirror.git'), 'rev-parse', 'v0.14.0']), releaseSha);
    assert.equal(run(home, 'git', ['--git-dir', resolve(home, 'mirror.git'), 'show', 'main:releases/v0.14.0.json']), manifestBytes.trim());
    assert.equal(run(home, 'git', ['--git-dir', resolve(home, 'mirror.git'), 'show', 'main:site/maven/io/github/mbackschat/a12/dm/dm-consistency/0.14.0/checker.jar']), 'unchanged');
    writeFileSync(resolve(home, 'asset-1'), 'tampered'); result = execute(); assert.notEqual(result.status, 0); assert.match(result.stderr, /asset hash mismatch/);
    current = JSON.parse(readFileSync(resolve(home, 'state.json'), 'utf8')); assert.deepEqual(current.events, ['deploy', 'deploy']);
    writeFileSync(resolve(home, 'asset-1'), 'archive');
    for (const name of ['shared-regex/src/commonMain/kotlin/Changed.kt', 'build.gradle']) {
        const file = resolve(root, name); mkdirSync(resolve(file, '..'), { recursive: true }); writeFileSync(file, 'changed compiler input');
        run(root, 'git', ['add', name]); run(root, 'git', ['commit', '-m', 'change library input']);
        result = execute(); assert.notEqual(result.status, 0); assert.match(result.stderr, /outside presentation/);
        run(root, 'git', ['revert', '--no-edit', 'HEAD']);
    }
    assert.deepEqual(JSON.parse(readFileSync(resolve(home, 'state.json'), 'utf8')).events, ['deploy', 'deploy']);
});
