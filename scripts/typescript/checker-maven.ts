import { createHash } from 'node:crypto';
import { lstatSync, readdirSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const checkerGroup = 'io.github.mbackschat.a12.dm';
export const checkerArtifacts = ['dm-consistency', 'dm-consistency-jvm', 'dm-consistency-js', 'dm-predicates', 'dm-predicates-jvm', 'dm-predicates-js'];
const groupPath = checkerGroup.replaceAll('.', '/');
const homepage = 'https://github.com/mbackschat/a12-consistency-releases';
const privateMarkers = /\/Users\/|\/home\/[^/\s]+\/|[A-Za-z]:\\Users\\|mgm-tp\.com|(?:gh[pousr]|github_pat|npm)_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/;

export function fileInventory(directory: string): Record<string, string> {
    const result: Record<string, string> = {};
    function visit(path: string) {
        for (const name of readdirSync(path).sort()) {
            const file = resolve(path, name);
            const stat = lstatSync(file);
            if (stat.isSymbolicLink()) throw new Error(`symlink in publication: ${file}`);
            if (stat.isDirectory()) visit(file);
            else if (stat.isFile()) result[relative(directory, file)] = createHash('sha256').update(readFileSync(file)).digest('hex');
            else throw new Error(`non-file publication entry: ${file}`);
        }
    }
    visit(directory);
    if (!Object.keys(result).length) throw new Error('empty publication');
    return result;
}

function scanBytes(name: string, bytes: Buffer): void {
    if (privateMarkers.test(bytes.toString('latin1'))) throw new Error(`private marker in publication: ${name}`);
}

export function inspectArchiveEntry(name: string, bytes: Buffer, kind: 'sources' | 'binary'): void {
    if (name.startsWith('/') || name.includes('\\') || name.split('/').includes('..')) throw new Error(`unsafe archive entry: ${name}`);
    scanBytes(name, Buffer.from(name));
    if (bytes.length === 0 && name.endsWith('/')) return;
    const source = /^(?:commonMain|jvmMain|jsMain)\/io\/github\/mbackschat\/a12\/dm\/(?:consistency|predicates|regex)\/[^]*\.kt$/;
    const metadata = /^(?:META-INF\/(?:MANIFEST\.MF|kotlin-project-structure-metadata\.json|io\.github\.mbackschat\.a12\.dm_(?:consistency|predicates)\.kotlin_module)|(?:commonMain\/)?default\/manifest)$/;
    const ir = /^default\/ir(?:_inlinable_functions)?\/(?:bodies\.knb|debugInfo\.knd|fileEntries\.knf|files\.knf|irDeclarations\.knd|signatures\.knt|strings\.knt|types\.knt)$/;
    const linkdata = /^(?:commonMain\/)?default\/linkdata\/(?:module|package_io\.github\.mbackschat\.a12\.dm\.(?:consistency|predicates|regex)(?:\.[A-Za-z0-9_]+)*\/[0-9]+_[A-Za-z0-9_$]+\.knm)$/;
    const classes = /^io\/github\/mbackschat\/a12\/dm\/(?:consistency|predicates|regex)\/[^]*\.class$/;
    if (!(kind === 'sources' ? source.test(name) || name === 'META-INF/MANIFEST.MF' : metadata.test(name) || ir.test(name) || linkdata.test(name) || classes.test(name))) {
        throw new Error(`unrelated archive entry: ${name}`);
    }
    if (/linkdata\/package_/.test(name) && !/linkdata\/package_io\.github\.mbackschat\.a12\.dm\.(?:consistency|predicates|regex)(?:[/.]|$)/.test(name)) {
        throw new Error(`unrelated Kotlin package: ${name}`);
    }
    scanBytes(name, bytes);
}

// Python's standard parsers decode XML and ZIP; all inventory, dependency and publication policy stays in TypeScript.
const decodePublications = String.raw`
import base64,json,pathlib,sys,xml.etree.ElementTree as ET,zipfile
root=pathlib.Path(sys.argv[1]); out={"poms":{},"archives":{},"metadata":{},"comments":{}}
for p in root.rglob('*'):
 if not p.is_file(): continue
 name=p.relative_to(root).as_posix()
 if p.suffix=='.pom':
  e=ET.parse(p).getroot()
  def value(node,tag): return node.findtext('{*}'+tag)
  out['poms'][name]={"group":value(e,'groupId'),"artifact":value(e,'artifactId'),"version":value(e,'version'),"url":value(e,'url'),"licenses":[value(n,'name') for n in e.findall('{*}licenses/{*}license')],"dependencies":[{"group":value(n,'groupId'),"module":value(n,'artifactId'),"version":value(n,'version')} for n in e.findall('{*}dependencies/{*}dependency')]}
 elif p.name=='maven-metadata.xml':
  e=ET.parse(p).getroot()
  out['metadata'][name]={"group":e.findtext('groupId'),"artifact":e.findtext('artifactId'),"versions":[n.text for n in e.findall('versioning/versions/version')]}
 elif p.suffix in ('.jar','.klib'):
  with zipfile.ZipFile(p) as z:
   if len(z.namelist()) != len(set(z.namelist())): raise ValueError('duplicate ZIP entry: '+name)
   out['archives'][name]={n:base64.b64encode(z.read(n)).decode() for n in z.namelist()}
   out['comments'][name]=[base64.b64encode(b).decode() for b in [z.comment]+[i.comment for i in z.infolist()]]
print(json.dumps(out))
`;

type Dependency = { group: string; module: string; version: string };
type Component = Dependency & { url?: string };
type Pom = { group: string; artifact: string; version: string; url: string; licenses: string[]; dependencies: Dependency[] };
type MavenMetadata = { group: string; artifact: string; versions: string[] };
type Decoded = { poms: Record<string, Pom>; archives: Record<string, Record<string, string>>; metadata: Record<string, MavenMetadata>; comments: Record<string, string[]> };
type GradleModule = {
    component: Component;
    variants: {
        name: string;
        attributes: Record<string, string>;
        dependencies?: { group: string; module: string; version: { requires: string } }[];
        'available-at'?: Component & { url: string };
        files?: { url: string; sha256?: string }[];
    }[];
};
function checkDependency(dependency: Dependency, version: string): void {
    if (dependency.group === checkerGroup) {
        if (!checkerArtifacts.includes(dependency.module) || dependency.version !== version) throw new Error('unresolved checker publication dependency');
    } else if (!['org.jetbrains.kotlin', 'org.jetbrains.kotlinx'].includes(dependency.group)) {
        throw new Error(`unexpected external dependency: ${dependency.group}:${dependency.module}`);
    }
}

function platformOf(artifact: string): string { return artifact.endsWith('-jvm') ? 'jvm' : artifact.endsWith('-js') ? 'js' : 'common'; }
function variantName(platform: string, mode: string): string {
    return platform === 'common' ? (mode === 'api' ? 'metadataApiElements' : 'metadataSourcesElements') : `${platform}${mode[0].toUpperCase() + mode.slice(1)}Elements-published`;
}
function requireDependencies(dependencies: Dependency[], artifact: string, platform: string, mode: string, pom: boolean): void {
    const checker = artifact.startsWith('dm-consistency');
    const expected = mode === 'sources' ? [] : [
        ...(checker ? [`${checkerGroup}:dm-predicates${pom && platform !== 'common' ? `-${platform}` : ''}`] : []),
        `org.jetbrains.kotlin:kotlin-stdlib${pom && platform === 'js' ? '-js' : ''}`,
        ...(platform === 'js' ? ['org.jetbrains.kotlin:kotlin-dom-api-compat'] : []),
        ...(checker && mode === 'runtime' && platform !== 'common' ? [`org.jetbrains.kotlinx:kotlinx-serialization-json${pom ? `-${platform}` : ''}`] : []),
    ];
    if (JSON.stringify(dependencies.map(d => `${d.group}:${d.module}`).sort()) !== JSON.stringify(expected.sort())) throw new Error(`required dependency inventory mismatch: ${artifact}/${platform}/${mode}`);
}

export function inspectCheckerMaven(directory: string, version: string): Record<string, string> {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('stable version required');
    const inventory = fileInventory(directory);
    const names = Object.keys(inventory);
    const base = `${groupPath}/`;
    const coordinate = (artifact: string) => `${base}${artifact}/${version}/${artifact}-${version}`;
    const expectedFiles = checkerArtifacts.flatMap(artifact => {
        const files = ['.pom', '.module', '-sources.jar', artifact.endsWith('-js') ? '.klib' : '.jar'].map(suffix => coordinate(artifact) + suffix);
        if (['dm-consistency', 'dm-predicates'].includes(artifact)) files.push(coordinate(artifact) + '-kotlin-tooling-metadata.json');
        files.push(`${base}${artifact}/maven-metadata.xml`);
        return files.flatMap(file => [file, ...['md5', 'sha1', 'sha256', 'sha512'].map(algorithm => `${file}.${algorithm}`)]);
    });
    if (JSON.stringify(names.sort()) !== JSON.stringify(expectedFiles.sort())) {
        throw new Error(`Maven file inventory mismatch; missing: ${expectedFiles.filter(name => !inventory[name]).join(', ')}; unexpected: ${names.filter(name => !expectedFiles.includes(name)).join(', ')}`);
    }
    for (const name of names) {
        const bytes = readFileSync(resolve(directory, name));
        if (!/\.(jar|klib)$/.test(name)) scanBytes(name, bytes);
        const algorithm = /\.(md5|sha1|sha256|sha512)$/.exec(name)?.[1];
        if (algorithm) {
            const target = name.slice(0, -(algorithm.length + 1));
            if (!inventory[target] || bytes.toString().trim() !== createHash(algorithm).update(readFileSync(resolve(directory, target))).digest('hex')) {
                throw new Error(`checksum mismatch: ${name}`);
            }
        }
    }
    for (const artifact of checkerArtifacts) {
        for (const suffix of ['.pom', '.module', '-sources.jar', artifact.endsWith('-js') ? '.klib' : '.jar']) {
            if (!inventory[coordinate(artifact) + suffix]) throw new Error(`missing publication: ${artifact}${suffix}`);
        }
    }
    for (const artifact of ['dm-consistency', 'dm-predicates']) {
        const name = coordinate(artifact) + '-kotlin-tooling-metadata.json';
        if (!inventory[name]) throw new Error(`missing Kotlin tooling metadata: ${artifact}`);
        const tooling = JSON.parse(readFileSync(resolve(directory, name), 'utf8'));
        if (JSON.stringify(tooling.projectTargets?.map((target: { platformType: string }) => target.platformType).sort()) !== JSON.stringify(['common', 'js', 'jvm'])) {
            throw new Error(`unexpected Kotlin target inventory: ${artifact}`);
        }
    }
    const decoded = spawnSync('python3', ['-c', decodePublications, directory], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: 30_000 });
    if (decoded.status !== 0 || decoded.error) throw new Error(`cannot decode publications: ${decoded.stderr || decoded.error}`);
    const { poms, archives, metadata, comments } = JSON.parse(decoded.stdout) as Decoded;
    for (const [name, pom] of Object.entries(poms)) {
        const artifact = name.slice(base.length).split('/')[0];
        if (pom.group !== checkerGroup || pom.artifact !== artifact || pom.version !== version || pom.url !== homepage || !pom.licenses.includes('MIT License')) {
            throw new Error(`POM identity/license mismatch: ${name}`);
        }
        for (const dependency of pom.dependencies) checkDependency(dependency, version);
        requireDependencies(pom.dependencies, artifact, platformOf(artifact), 'runtime', true);
    }
    for (const [name, record] of Object.entries(metadata)) {
        if (record.group !== checkerGroup || name !== `${base}${record.artifact}/maven-metadata.xml` || !checkerArtifacts.includes(record.artifact)
            || JSON.stringify(record.versions) !== JSON.stringify([version])) throw new Error(`stale Maven version inventory: ${name}`);
    }
    const reference = (from: string, url: string) => {
        if (typeof url !== 'string' || /^[a-z]+:|^\/|\\/i.test(url)) throw new Error('non-local module artifact reference');
        const path = relative(directory, resolve(directory, dirname(from), url));
        if (!inventory[path]) throw new Error(`missing module artifact: ${from} -> ${url}`);
        return path;
    };
    for (const name of names.filter(name => name.endsWith('.module'))) {
        const module = JSON.parse(readFileSync(resolve(directory, name), 'utf8')) as GradleModule;
        const artifact = name.slice(base.length).split('/')[0];
        const component = module.component;
        const expected = component.url ? artifact.replace(/-(jvm|js)$/, '') : artifact;
        if (component.group !== checkerGroup || component.module !== expected || component.version !== version || !module.variants?.length) {
            throw new Error(`Gradle module identity mismatch: ${name}`);
        }
        if (component.url && reference(name, component.url) !== `${coordinate(expected)}.module`) throw new Error('incorrect component redirect');
        const platform = platformOf(artifact);
        const owner = artifact.replace(/-(jvm|js)$/, '');
        const expectedVariants = platform === 'common'
            ? [variantName('common', 'api'), variantName('common', 'sources'), ...['js', 'jvm'].flatMap(p => ['api', 'runtime', 'sources'].map(mode => variantName(p, mode)))]
            : ['api', 'runtime', 'sources'].map(mode => variantName(platform, mode));
        if (JSON.stringify(module.variants.map(v => v.name).sort()) !== JSON.stringify(expectedVariants.sort())) throw new Error(`platform variant inventory mismatch: ${name}`);
        for (const variant of module.variants) {
            const carrier = variant.name.startsWith('metadata') ? 'common' : variant.name.startsWith('jvm') ? 'jvm' : 'js';
            const mode = variant.name.includes('Sources') ? 'sources' : variant.name.includes('Runtime') ? 'runtime' : 'api';
            const expectedUsage = carrier === 'common' ? (mode === 'api' ? 'kotlin-metadata' : 'kotlin-runtime') : `${carrier === 'jvm' ? 'java' : 'kotlin'}-${mode === 'api' ? 'api' : 'runtime'}`;
            const attributes = variant.attributes;
            if (attributes?.['org.jetbrains.kotlin.platform.type'] !== carrier || attributes['org.gradle.category'] !== (mode === 'sources' ? 'documentation' : 'library')
                || attributes['org.gradle.usage'] !== expectedUsage || (mode === 'sources' && attributes['org.gradle.docstype'] !== 'sources')) throw new Error(`incorrect platform attributes: ${name}/${variant.name}`);
            const redirected = platform === 'common' && carrier !== 'common';
            if (redirected) {
                if (variant['available-at']?.module !== `${owner}-${carrier}` || variant.files?.length || variant.dependencies?.length) throw new Error(`incorrect platform routing: ${name}/${variant.name}`);
            } else {
                if (variant['available-at']) throw new Error(`unexpected platform redirect: ${name}/${variant.name}`);
                const expectedUrl = `${artifact}-${version}${mode === 'sources' ? '-sources.jar' : carrier === 'js' ? '.klib' : '.jar'}`;
                if (variant.files?.length !== 1 || variant.files[0].url !== expectedUrl) throw new Error(`incorrect platform artifact: ${name}/${variant.name}`);
                requireDependencies((variant.dependencies ?? []).map(d => ({ ...d, version: d.version?.requires })), artifact, carrier, mode, false);
            }
            for (const dependency of variant.dependencies ?? []) checkDependency({ ...dependency, version: dependency.version?.requires }, version);
            if (variant['available-at']) {
                const target = variant['available-at'];
                checkDependency(target, version);
                if (reference(name, target.url) !== `${coordinate(target.module)}.module`) throw new Error('incorrect platform redirect');
            }
            for (const file of variant.files ?? []) {
                const target = reference(name, file.url);
                if (file.sha256 && inventory[target] !== file.sha256) throw new Error(`Gradle module checksum mismatch: ${target}`);
            }
        }
    }
    for (const [name, values] of Object.entries(comments)) for (const encoded of values) scanBytes(`${name}:ZIP comment`, Buffer.from(encoded, 'base64'));
    for (const [name, entries] of Object.entries(archives) as [string, Record<string, string>][]) {
        const sources = name.endsWith('-sources.jar');
        if (!Object.entries(entries).some(([entry, encoded]) => !entry.endsWith('/') && !entry.startsWith('META-INF/') && encoded.length > 0)) throw new Error(`empty archive: ${name}`);
        for (const [entry, encoded] of Object.entries(entries)) inspectArchiveEntry(entry, Buffer.from(encoded, 'base64'), sources ? 'sources' : 'binary');
        if (sources && name.includes('/dm-consistency')) {
            const identity = Object.keys(entries).find(entry => entry.endsWith('/CheckerIdentity.kt'));
            if (!identity || !Buffer.from(entries[identity], 'base64').toString().includes(`CHECKER_VERSION: String = "${version}"`)) {
                throw new Error(`generated checker identity missing/stale in source publication: ${name}`);
            }
        }
    }
    return inventory;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const [directory, version] = process.argv.slice(2);
    try { console.log(`Checker Maven inventory verified: ${Object.keys(inspectCheckerMaven(resolve(directory), version)).length} files`); }
    catch (error) { console.error((error as Error).message); process.exitCode = 1; }
}
