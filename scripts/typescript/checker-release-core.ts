import { createHash } from 'node:crypto';
import { checkerArtifacts, checkerGroup } from './checker-maven.ts';

export type Receipt = { schema: number; version: string; source: string; files: Record<string, string> };
function canonical(value: Record<string, string>): string { return JSON.stringify(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))); }
export function assertQualification(receipt: Receipt, version: string, source: string, files: Record<string, string>): void {
    if (receipt.schema !== 1 || receipt.version !== version || receipt.source !== source || !Object.keys(files).length
        || canonical(receipt.files) !== canonical(files)) throw new Error('qualification is missing, stale or modified; qualify this source again');
}
export function assertMavenOrigins(records: Record<string, string>, version: string): void {
    for (const artifact of ['dm-consistency-jvm', 'dm-predicates-jvm']) for (const extension of ['pom', 'jar']) {
        if (!records[artifact]?.split(/\r?\n/).includes(`${artifact}-${version}.${extension}>checker-release=`)) {
            throw new Error(`Maven artifact origin is not checker-release: ${artifact}.${extension}`);
        }
    }
}

export function assertHistoricalArtifacts(existing: Record<string, string>, manifests: Receipt[]): void {
    const versionFiles = (files: Record<string, string>) => Object.fromEntries(Object.entries(files).filter(([name]) => !/\/maven-metadata\.xml(?:\.(?:md5|sha1|sha256|sha512))?$/.test(name)));
    const expected = Object.assign({}, ...manifests.map(manifest => versionFiles(manifest.files)));
    if (canonical(versionFiles(existing)) !== canonical(expected)) throw new Error('historical Maven artifact inventory changed');
}

export function mergeMaven(existing: Record<string, Buffer>, incoming: Record<string, Buffer>, version: string): Record<string, Buffer> {
    const base = checkerGroup.replaceAll('.', '/');
    const versions = new Set<string>();
    for (const name of Object.keys(existing)) {
        const parts = name.slice(base.length + 1).split('/');
        if (!name.startsWith(`${base}/`) || !checkerArtifacts.includes(parts[0]) || parts.length < 2 || parts.length > 3) throw new Error(`unexpected historical Maven file: ${name}`);
        if (parts.length === 2 && !/^maven-metadata\.xml(?:\.(?:md5|sha1|sha256|sha512))?$/.test(parts[1])) throw new Error(`unexpected historical metadata: ${name}`);
        if (parts.length === 3) {
            if (!/^\d+\.\d+\.\d+$/.test(parts[1])) throw new Error(`unexpected historical version: ${name}`);
            versions.add(parts[1]);
        }
    }
    const compare = (a: string, b: string) => {
        const aa = a.split('.').map(Number), bb = b.split('.').map(Number);
        return aa[0] - bb[0] || aa[1] - bb[1] || aa[2] - bb[2];
    };
    if ([...versions].some(v => compare(version, v) <= 0)) throw new Error('new release must be newer than every historical version; version already present or older');
    versions.add(version);
    const result = { ...existing, ...incoming };
    for (const artifact of checkerArtifacts) {
        const name = `${base}/${artifact}/maven-metadata.xml`;
        result[name] = Buffer.from(`<?xml version="1.0" encoding="UTF-8"?>\n<metadata><groupId>${checkerGroup}</groupId><artifactId>${artifact}</artifactId><versioning><latest>${version}</latest><release>${version}</release><versions>${[...versions].sort(compare).map(v => `<version>${v}</version>`).join('')}</versions></versioning></metadata>\n`);
        for (const algorithm of ['md5', 'sha1', 'sha256', 'sha512']) result[`${name}.${algorithm}`] = Buffer.from(createHash(algorithm).update(result[name]).digest('hex'));
    }
    return result;
}

export type PromotionHost = {
    draft: boolean;
    verifyDraft(): Promise<void>;
    deploy(): Promise<void>;
    verifyPages(): Promise<void>;
    consumers(): Promise<void>;
    publish(): Promise<void>;
    verifyAnonymous(): Promise<void>;
    latest(): Promise<void>;
};
export async function promoteChecker(host: PromotionHost): Promise<void> {
    await host.verifyDraft();
    if (host.draft) await host.deploy();
    await host.verifyPages();
    await host.consumers();
    if (host.draft) await host.publish();
    await host.verifyAnonymous();
    await host.latest();
}
