import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const repository = 'mbackschat/a12-dmtool-releases';
const homepage = `https://github.com/${repository}`;
const binaryNames = ['dmtool-macos-arm64', 'dmtool-linux-x64', 'dmtool-linux-arm64', 'dmtool-windows-x64.exe'] as const;
type BinaryName = typeof binaryNames[number];
type Release = { tag_name: string; draft: boolean; prerelease: boolean; assets: { name: string; digest?: string | null }[] };
type PackageInput = { version: string; hashes: Record<BinaryName, string> };
const sha256 = /^[a-f0-9]{64}$/;

export function validateRelease(version: string, release: Release, sums: string): PackageInput {
    if (!/^\d+\.\d+\.\d+$/.test(version) || release.tag_name !== `v${version}` || release.draft || release.prerelease) {
        throw new Error('packages require the matching published stable release');
    }
    const expected = [...binaryNames, 'SHA256SUMS'].sort();
    if (JSON.stringify(release.assets.map(asset => asset.name).sort()) !== JSON.stringify(expected)) {
        throw new Error('release assets do not match the four-platform inventory');
    }
    const entries = new Map<string, string>();
    for (const line of sums.trim().split(/\r?\n/)) {
        const match = /^([a-f0-9]{64}) [ *](\S+)$/.exec(line);
        if (!match || entries.has(match[2])) throw new Error('malformed or duplicate checksum');
        entries.set(match[2], match[1]);
    }
    if (JSON.stringify([...entries.keys()].sort()) !== JSON.stringify([...binaryNames].sort())) {
        throw new Error('checksum inventory must cover exactly the four binaries');
    }
    for (const asset of release.assets) {
        if (asset.name !== 'SHA256SUMS' && asset.digest && asset.digest !== `sha256:${entries.get(asset.name)}`) {
            throw new Error(`release digest disagrees with SHA256SUMS: ${asset.name}`);
        }
    }
    return { version, hashes: Object.fromEntries(entries) as Record<BinaryName, string> };
}

export function renderPackages(input: PackageInput, revision: string, archiveHash: string): { formula: string; scoop: string } {
    if (!/^[a-f0-9]{40}$/.test(revision) || !sha256.test(archiveHash)) throw new Error('notices require an immutable revision and SHA-256');
    const { version, hashes } = input;
    const base = `${homepage}/releases/download/v${version}`;
    const archive = `${homepage}/archive/${revision}.tar.gz`;
    const formula = `class Dmtool < Formula
  desc "Inspect, validate, and edit A12 DocumentModels"
  homepage "${homepage}"
  license "EUPL-1.2"

  on_macos do
    depends_on arch: :arm64
    depends_on macos: :golden_gate

    on_arm do
      url "${base}/dmtool-macos-arm64", using: :nounzip
      sha256 "${hashes['dmtool-macos-arm64']}"
    end
  end

  on_linux do
    on_intel do
      url "${base}/dmtool-linux-x64", using: :nounzip
      sha256 "${hashes['dmtool-linux-x64']}"
    end
    on_arm do
      url "${base}/dmtool-linux-arm64", using: :nounzip
      sha256 "${hashes['dmtool-linux-arm64']}"
    end
  end

  resource "notices" do
    url "${archive}"
    sha256 "${archiveHash}"
  end

  def install
    binary = Dir["dmtool-*"]
    odie "Expected exactly one release binary" if binary.length != 1
    bin.install binary.first => "dmtool"
    chmod 0755, bin/"dmtool"
    resource("notices").stage do
      pkgshare.install "LICENSE", "KERNEL-LICENSE", "NOTICE", "THIRD_PARTY_NOTICES", "licenses"
    end
  end

  test do
    output = with_env(PATH: "", JAVA_HOME: "/nonexistent") { shell_output("#{bin}/dmtool --version") }
    assert_equal "a12-dmkits #{version}", output.lines.first.strip
    manifest = JSON.parse(shell_output("#{bin}/dmtool manifest"))
    assert_equal version.to_s, manifest.fetch("version").fetch("rulekit")
    resource("notices").stage do
      notices = %w[LICENSE KERNEL-LICENSE NOTICE THIRD_PARTY_NOTICES]
      notices += Dir["licenses/**/*"].select { |f| File.file?(f) }
      assert_includes notices, "licenses/EUPL-1.2.txt"
      notices.each do |notice|
        assert_equal File.binread(notice), (pkgshare/notice).binread
      end
    end
  end
end
`;
    const scoop = JSON.stringify({
        version,
        description: 'Inspect, validate, and edit A12 DocumentModels',
        homepage,
        license: { identifier: 'EUPL-1.2', url: `${homepage}/blob/${revision}/licenses/EUPL-1.2.txt` },
        architecture: { '64bit': {
            url: [`${base}/dmtool-windows-x64.exe`, archive],
            hash: [hashes['dmtool-windows-x64.exe'], archiveHash],
            // Scoop lib/decompress.ps1 Invoke-Extraction indexes archives only; the raw EXE consumes no extraction slot.
            extract_dir: `a12-dmtool-releases-${revision}`,
            extract_to: '_notices',
        } },
        pre_install: [
            "$ErrorActionPreference = 'Stop'",
            "$destination = Join-Path $dir 'share/dmtool'",
            'New-Item -ItemType Directory -Force -Path $destination | Out-Null',
            "foreach ($name in @('LICENSE', 'KERNEL-LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES', 'licenses')) {",
            "    Move-Item -LiteralPath (Join-Path $dir \"_notices/$name\") -Destination $destination",
            '}',
            "Remove-Item -LiteralPath (Join-Path $dir '_notices') -Recurse -Force",
        ],
        bin: [['dmtool-windows-x64.exe', 'dmtool']],
        checkver: 'github',
    }, null, 4) + '\n';
    return { formula, scoop };
}

async function download(url: string): Promise<Response> {
    const response = await fetch(url, { signal: AbortSignal.timeout(60_000), headers: { 'User-Agent': 'dmtool-package-renderer' } });
    if (!response.ok) throw new Error(`${response.status} downloading ${url}`);
    return response;
}

export async function generatePackages(version: string, destination: string): Promise<void> {
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('version must be MAJOR.MINOR.PATCH');
    const release = await (await download(`https://api.github.com/repos/${repository}/releases/tags/v${version}`)).json() as Release;
    const sums = await (await download(`${homepage}/releases/download/v${version}/SHA256SUMS`)).text();
    const input = validateRelease(version, release, sums);
    let object = (await (await download(`https://api.github.com/repos/${repository}/git/ref/tags/v${version}`)).json()).object;
    if (object.type === 'tag') {
        object = (await (await download(`https://api.github.com/repos/${repository}/git/tags/${object.sha}`)).json()).object;
    }
    if (object.type !== 'commit' || !/^[a-f0-9]{40}$/.test(object.sha)) throw new Error('release tag must resolve to a commit');
    const archive = await (await download(`${homepage}/archive/${object.sha}.tar.gz`)).arrayBuffer();
    const archiveHash = createHash('sha256').update(Buffer.from(archive)).digest('hex');
    const output = renderPackages(input, object.sha, archiveHash);
    await mkdir(destination, { recursive: true });
    await writeFile(resolve(destination, 'dmtool.rb'), output.formula);
    await writeFile(resolve(destination, 'dmtool.json'), output.scoop);
    console.log(`Rendered dmtool ${version} for Homebrew and Scoop in ${destination}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const [version, destination, ...extra] = process.argv.slice(2);
    if (!version || !destination || extra.length) {
        console.error('usage: dmtool-packages.ts VERSION OUTPUT_DIRECTORY');
        process.exitCode = 2;
    } else {
        generatePackages(version, destination).catch(error => { console.error(error.message); process.exitCode = 1; });
    }
}
