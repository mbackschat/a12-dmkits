const fs = require("node:fs");
const path = require("node:path");

type Reporter = {
  out(message: string): void;
  error(message: string): void;
};

type Asset = {
  label: string;
  source: string;
  destination: string;
};

const PLUGINS = ["plugin", "plugin-codex"] as const;
const ASSETS: readonly Asset[] = [
  { label: "canonical skill", source: "skill/SKILL.md", destination: "skills/a12-dmtool/SKILL.md" },
  {
    label: "canonical installer",
    source: "scripts/ensure-dmtool.sh",
    destination: "skills/a12-dmtool/ensure-dmtool.sh",
  },
  {
    label: "canonical release asset manifest",
    source: "scripts/release-assets.sh",
    destination: "skills/a12-dmtool/release-assets.sh",
  },
  {
    label: "canonical bug-report skill",
    source: "skill-bug-report/SKILL.md",
    destination: "skills/a12-dmtool-bug-report/SKILL.md",
  },
];

function sameBytes(left: string, right: string): boolean {
  return fs.existsSync(right) && fs.statSync(right).isFile() && fs.readFileSync(left).equals(fs.readFileSync(right));
}

function repositoryRelative(root: string, file: string): string {
  return path.relative(root, file).split(path.sep).join("/");
}

function synchronizePluginAssets(root: string, check: boolean, reporter: Reporter): number {
  for (const asset of ASSETS) {
    const source = path.join(root, asset.source);
    if (!fs.existsSync(source) || !fs.statSync(source).isFile()) {
      reporter.error(`${asset.label} not found: ${source}`);
      return 2;
    }
  }

  let stale = false;
  for (const plugin of PLUGINS) {
    for (const asset of ASSETS) {
      const source = path.join(root, asset.source);
      const destination = path.join(root, plugin, asset.destination);
      const relative = repositoryRelative(root, destination);
      if (check) {
        if (sameBytes(source, destination)) reporter.out(`in sync: ${relative}`);
        else {
          reporter.error(`STALE: ${relative} — run ./scripts/sync-plugin-assets.sh`);
          stale = true;
        }
        continue;
      }

      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.copyFileSync(source, destination);
      fs.chmodSync(destination, fs.statSync(source).mode & 0o777);
      reporter.out(`synced → ${relative}`);
    }
  }
  return stale ? 1 : 0;
}

function main(): number {
  const root = path.resolve(__dirname, "../..");
  return synchronizePluginAssets(root, process.argv[2] === "--check", {
    out: message => console.log(message),
    error: message => console.error(message),
  });
}

module.exports = { synchronizePluginAssets };

if (require.main === module) process.exitCode = main();
