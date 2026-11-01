import { assertMavenOrigins } from './checker-release-core.ts';
import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

function run(command: string, args: string[], env: NodeJS.ProcessEnv, cwd: string): void {
    console.log(`Running ${command} ${args.join(' ')}`);
    const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', timeout: 120_000 });
    if (result.error || result.status !== 0) throw new Error(`consumer command failed: ${command}: ${result.error?.message ?? result.status}`);
}

async function fetchBytes(url: string): Promise<Buffer> {
    const response = await fetch(url, { signal: AbortSignal.timeout(60_000) });
    if (!response.ok) throw new Error(`${response.status} downloading ${url}`);
    return Buffer.from(await response.arrayBuffer());
}

async function maven(root: string, env: NodeJS.ProcessEnv): Promise<string> {
    const tools = resolve(root, 'build/consistency-release/tools');
    const archive = resolve(tools, 'apache-maven-3.9.9-bin.tar.gz');
    const executable = resolve(tools, 'apache-maven-3.9.9/bin/mvn');
    const url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz';
    await mkdir(tools, { recursive: true });
    const expected = (await fetchBytes(`${url}.sha512`)).toString().trim().split(/\s+/)[0];
    const bytes = existsSync(archive) ? await readFile(archive) : await fetchBytes(url);
    if (!/^[a-f0-9]{128}$/.test(expected) || createHash('sha512').update(bytes).digest('hex') !== expected) {
        throw new Error('Apache Maven distribution checksum mismatch');
    }
    await writeFile(archive, bytes);
    await rm(resolve(tools, 'apache-maven-3.9.9'), { recursive: true, force: true });
    run('tar', ['-xzf', archive, '-C', tools], env, root);
    return executable;
}

export async function smokeChecker(root: string, version: string, repository: string, phase: 'local' | 'public'): Promise<void> {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('checker version must be stable SemVer');
    const url = new URL(repository);
    if ((phase === 'public' && url.protocol !== 'https:') || !['file:', 'https:'].includes(url.protocol)
        || url.username || url.password) throw new Error('consumer repository must be anonymous HTTPS (or local file staging)');
    const work = resolve(root, `build/consistency-release/consumers-${phase}`);
    await rm(work, { recursive: true, force: true });
    await mkdir(work, { recursive: true });
    const gradle = resolve(work, 'gradle');
    const java = resolve(work, 'maven');
    await cp(resolve(root, 'consistency/consumer-tests/maven-gradle'), gradle, { recursive: true });
    await cp(resolve(root, 'consistency/consumer-tests/maven-java'), java, { recursive: true });
    const fixtures = resolve(work, 'fixtures');
    await mkdir(fixtures);
    await cp(resolve(root, 'consistency/consumer-tests/shared/models/storefront.dm.json'), resolve(fixtures, 'storefront.dm.json'));
    await cp(resolve(root, 'consistency/consumer-tests/shared/parity/sources/storefront-broken.dm.json'), resolve(fixtures, 'storefront-broken.dm.json'));
    const discovery = spawnSync('sh', ['-c', '. "$1"; a12_gradle_user_home "$2"; a12_pinned_jdk21_home',
        'checker-consumers', resolve(root, 'scripts/gradle-environment.sh'), root], { encoding: 'utf8' });
    if (discovery.status !== 0) throw new Error(discovery.stderr);
    const [sourceGradleHome, javaHome] = discovery.stdout.trim().split('\n');
    const home = resolve(work, 'gradle-home');
    await mkdir(resolve(home, 'wrapper'), { recursive: true });
    // Only the wrapper distribution is shared; fresh dependency caches prevent mavenLocal or init scripts masking missing publications.
    await symlink(resolve(sourceGradleHome, 'wrapper/dists'), resolve(home, 'wrapper/dists'));
    const env: NodeJS.ProcessEnv = { ...process.env, GRADLE_USER_HOME: home, CHECKER_VERSION: version, CHECKER_MAVEN_REPOSITORY: repository, CHECKER_FIXTURES: fixtures };
    for (const name of ['GRADLE_OPTS', 'MAVEN_OPTS', 'MAVEN_ARGS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS']) delete env[name];
    if (javaHome) env.JAVA_HOME = javaHome;
    run(resolve(root, 'build.sh'), ['--no-daemon', '--console=plain', '--project-dir', gradle, 'runJvm', 'jsNodeDevelopmentRun'], env, root);
    const settings = resolve(work, 'settings.xml');
    await writeFile(settings, '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n');
    const command = await maven(root, env);
    run(command, ['--batch-mode', '--no-transfer-progress', '-s', settings, '-gs', settings,
        `-Dmaven.repo.local=${resolve(work, 'maven-home')}`, 'compile', 'exec:java'], env, java);
    const origins: Record<string, string> = {};
    for (const artifact of ['dm-consistency-jvm', 'dm-predicates-jvm']) {
        origins[artifact] = await readFile(resolve(work, 'maven-home/io/github/mbackschat/a12/dm', artifact, version, '_remote.repositories'), 'utf8');
    }
    assertMavenOrigins(origins, version);
    console.log(`Checker ${phase} Gradle JVM/JS and Maven consumers passed`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const [version, repository, phase] = process.argv.slice(2);
    if (!version || !repository || !['local', 'public'].includes(phase)) {
        console.error('usage: checker-consumers.ts VERSION REPOSITORY_URL local|public');
        process.exitCode = 2;
    } else {
        smokeChecker(process.cwd(), version, repository, phase as 'local' | 'public')
            .catch(error => { console.error(error.message); process.exitCode = 1; });
    }
}
