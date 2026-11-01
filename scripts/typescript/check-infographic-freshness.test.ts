const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync, execFileSync } = require("node:child_process");
const test = require("node:test");

const ROOT = path.resolve(__dirname, "../..");

function fixture(t: { after(callback: () => void): void }): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "a12-infographics-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const relative of [
    "scripts/check-infographic-freshness.sh", "scripts/run-typescript.sh",
    "scripts/typescript/check-infographic-freshness.ts", "scripts/typescript/infographic-evidence.ts", "scripts/typescript/update-code-statistics.ts", ".github/workflows/ci.yml",
    "README.md", "docs/INFOGRAPHICS.md", "interpreter/public-release/PERFORMANCE.md",
  ]) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(ROOT, relative), target);
  }
  const specification = fs.readFileSync(path.join(root, "docs/INFOGRAPHICS.md"), "utf8");
  const placements = [...specification.matchAll(/\| `([^`]+\.avif)` \| \[[^\]]+\]\(([^)]+)\) \| `([^`]+\.avif)`/g)];
  for (const relative of new Set(placements.map(match => path.join("docs", match[2])))) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(ROOT, relative), target);
  }
  for (const match of placements) {
    for (const target of [path.join(root, match[1]), path.resolve(root, "docs", path.dirname(match[2]), match[3])]) {
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, "synthetic AVIF payload");
    }
  }
  fs.mkdirSync(path.join(root, 'core/src/main/java'), { recursive: true });
  fs.writeFileSync(path.join(root, 'core/build.gradle'), '');
  fs.writeFileSync(path.join(root, 'core/src/main/java/Fixture.java'), 'class Fixture {}');
  fs.copyFileSync(path.join(ROOT, '.locmetryrc'), path.join(root, '.locmetryrc'));
  execFileSync('git', ['init', '-q', root]);
  execFileSync('git', ['-C', root, 'add', '.']);
  fs.writeFileSync(path.join(root, 'docs/infographic-evidence.json'), JSON.stringify(require('./infographic-evidence.ts').captureEvidence(root)));
  return root;
}

function run(root: string) {
  return spawnSync("dash", [path.join(root, "scripts/check-infographic-freshness.sh")], {
    cwd: root, encoding: "utf8", timeout: 10_000,
  });
}

test("AVIF previews and copies pass through Dash while local PNG masters are ignored", t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, "assets/infographics/local-master.png"), "local only");
  const result = run(root);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "infographic freshness: OK\n");
});

for (const [name, change, diagnostic] of [
  ["source-only drift", (root: string) => fs.appendFileSync(path.join(root, 'core/src/main/java/Fixture.java'), '\nclass Added {}'), "production source evidence"],
  ["accepted-pixel drift", (root: string) => fs.appendFileSync(path.join(root, 'assets/infographics/a12-consistency-checker.avif'), 'changed'), "accepted artwork"],
  ["accepted-prompt drift", (root: string) => {
    const file = path.join(root, 'docs/INFOGRAPHICS.md');
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('```text\n', '```text\nChanged claim.\n'));
  }, "accepted artwork"],
  ["missing canonical AVIF", (root: string) => fs.unlinkSync(path.join(root, "assets/infographics/a12-dmkits-toolchain.avif")), "missing canonical infographic"],
  ["drifted release copy", (root: string) => fs.writeFileSync(path.join(root, "plugin/assets/infographics/dmtool-model-workbench.avif"), "drift"), "has drifted"],
  ["unregistered AVIF", (root: string) => fs.writeFileSync(path.join(root, "assets/infographics/extra.avif"), "extra"), "not registered"],
  ["PNG registry row among AVIF rows", (root: string) => {
    const spec = path.join(root, "docs/INFOGRAPHICS.md");
    fs.writeFileSync(spec, fs.readFileSync(spec, "utf8").replace("a12-dmkits-toolchain.avif", "a12-dmkits-toolchain.png"));
  }, "registry canonical asset"],
] as const) {
  test(`preflight refuses ${name}`, t => {
    const root = fixture(t);
    change(root);
    const result = run(root);
    assert.equal(result.status, 1, result.stderr);
    assert.ok(result.stderr.includes(diagnostic), result.stderr);
  });
}

test("Git ignores existing and future infographic PNG masters but admits AVIFs and unrelated PNGs", t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "a12-infographic-ignore-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.copyFileSync(path.join(ROOT, ".gitignore"), path.join(root, ".gitignore"));
  assert.equal(spawnSync("git", ["init", "--quiet", root], { timeout: 10_000 }).status, 0);
  for (const relative of [
    "assets/infographics/a12-dmkits-toolchain.png",
    "plugin/assets/infographics/new-poster.png",
    "interpreter/public-release/assets/infographics/new-poster.png",
    "docs/future/infographics/drafts/comparison.png",
  ]) {
    const ignored = spawnSync("git", ["check-ignore", relative], { cwd: root, timeout: 10_000 });
    assert.equal(ignored.status, 0, relative);
  }
  for (const relative of ["assets/infographics/new-poster.avif", "docs/screenshots/example.png"]) {
    const ignored = spawnSync("git", ["check-ignore", relative], { cwd: root, timeout: 10_000 });
    assert.equal(ignored.status, 1, relative);
  }
});
