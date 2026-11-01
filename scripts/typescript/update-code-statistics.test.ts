const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync, spawnSync } = require("node:child_process");
const test = require("node:test");
const { measureRepository, selectSources, sectionOf } = require("./update-code-statistics.ts");
const ROOT = path.resolve(__dirname, "../..");

function fixture(t: { after(callback: () => void): void }): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "source-statistics-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const relative of ["scripts/update-code-statistics.sh", "scripts/run-typescript.sh", "scripts/typescript/update-code-statistics.ts", ".locmetryrc"]) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(ROOT, relative), target);
  }
  const files = {
    "build.gradle": "",
    "core/build.gradle": "",
    "interpreter/build.gradle": "",
    "consistency/build.gradle": "",
    "core/src/main/java/Product.java": "// explanation\nclass Product {}\n\n",
    "interpreter/src/commonMain/kotlin/Product.kt": "class Product\n",
    "shared-regex/src/Shared.kt": "class Shared\n",
    "consistency/src/commonMain/kotlin/Checker.kt": "class Checker\n",
    "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/KernelCorrectives.kt": "class Generated\n",
    "interpreter/consumer-tests/browser/src/showcase.ts": "export const showcase = 1;\n",
    "interpreter/src/commonTest/kotlin/ProductTest.kt": "class ProductTest\n",
    "interpreter/src/commonTest/resources/fixture.json": '{"not": "source"}',
    "scripts/typescript/doctor.ts": "const tool = 1;\n",
    "scripts/typescript/doctor.test.ts": "const test = 1;\n",
  };
  for (const [relative, contents] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), contents);
  }
  for (const [relative, id] of [["README.md", "root"], ["plugin/README.md", "dmtool"], ["interpreter/public-release/README.md", "interpreter-release"], ["consistency/public-release/README.md", "checker-release"]]) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), `before\n<!-- source-statistics:${id}:start -->\nstale\n<!-- source-statistics:${id}:end -->\nafter\n`);
  }
  execFileSync("git", ["init", "-q", root]);
  execFileSync("git", ["-C", root, "add", "."]);
  fs.writeFileSync(path.join(root, "core/src/main/java/Untracked.java"), "class Untracked {}\n");
  return root;
}

test("the public updater uses policy classifications, counts code separately, and preserves artifact boundaries", t => {
  const root = fixture(t);
  const run = (...args: string[]) => spawnSync("dash", [path.join(root, "scripts/update-code-statistics.sh"), ...args], { encoding: "utf8", timeout: 10_000 });
  const updated = run();
  assert.equal(updated.status, 0, updated.stderr);
  const output = fs.readFileSync(path.join(root, "README.md"), "utf8");
  for (const relative of ["README.md", "plugin/README.md", "interpreter/public-release/README.md"]) {
    assert.match(fs.readFileSync(path.join(root, relative), "utf8"), /Measured with \[locmetry\]\(https:\/\/github\.com\/mbackschat\/locmetry\) from /, relative);
  }
  assert.match(output, /\[`.locmetryrc`\]\(\.locmetryrc\)/);
  assert.doesNotMatch(output, /Counted from|Tokei/);
  assert.match(output, /\| Java \| 1 \| 3 \| 1 \| 1 \| 1 \|/);
  assert.match(output, /\| Kotlin \| 3 \| 3 \| 3 \| 0 \| 0 \|/);
  const interpreter = fs.readFileSync(path.join(root, "interpreter/public-release/README.md"), "utf8");
  assert.match(interpreter, /\| Kotlin \| 2 \| 2 \| 2 \| 0 \| 0 \|/);
  assert.match(interpreter, /https:\/\/github.com\/mbackschat\/a12-interpreter-releases\/blob\/main\/\.locmetryrc/);
  assert.doesNotMatch(interpreter, /### Tooling/);
  assert.match(output, /^before\n/); assert.match(output, /\nafter\n$/);
  assert.equal(run("--check").status, 0);
  fs.writeFileSync(path.join(root, "README.md"), output.replace("| Java | 1 |", "| Java | 999 |"));
  const stale = fs.readFileSync(path.join(root, "README.md"));
  assert.equal(run("--check").status, 1);
  assert.deepEqual(fs.readFileSync(path.join(root, "README.md")), stale);
  const report = measureRepository(root);
  assert.deepEqual(selectSources(report, "interpreter", "Production").map((f: { path: string }) => f.path).sort(), ["interpreter/consumer-tests/browser/src/showcase.ts", "interpreter/src/commonMain/kotlin/Product.kt", "shared-regex/src/Shared.kt"]);
  assert.equal(selectSources(report, "dmtool", "Production").length, 4);
  for (const artifact of ["root", "interpreter", "dmtool"]) {
    const examples = selectSources(report, artifact, "Production").filter((f: { category: string }) => f.category === "example");
    assert.equal(examples.length, artifact === "dmtool" ? 0 : 1, artifact);
  }
  assert.ok(selectSources(report, "root", "Verification").some((f: { path: string }) => f.path.endsWith("doctor.test.ts")));
  assert.ok(selectSources(report, "root", "Tooling").some((f: { path: string }) => f.path.endsWith("doctor.ts")));
});

test("tracked sources hidden by ignore policy fail instead of silently lowering the denominator", t => {
  const root = fixture(t);
  fs.writeFileSync(path.join(root, ".ignore"), "Product.java\n");
  assert.throws(() => measureRepository(root), /unaccounted tracked files.*Product.java/s);
});

test("the live policy preserves generated files and separates test implementations from their runners", () => {
  const report = measureRepository(ROOT);
  const expected = [
    ["consistency/consumer-tests/shared/parity/manifest.json", "test", "generated"],
    ["consistency/showcase/scripts/verify.mjs", "test", "integration"],
    ["consistency/showcase/scripts/verify-site.mjs", "test", "integration"],
    ["scripts/rehearse-interpreter-release.sh", "test", "selftest"],
    ["scripts/interpreter-release-rehearsal-host.sh", "test", "test-support"],
    ["scripts/typescript/checker-release.ts", "production", "distribution"],
    ["scripts/typescript/checker-presentation.ts", "production", "distribution"],
    ["scripts/typescript/checker-consumers.ts", "production", "verification-tooling"],
    ["scripts/typescript/source-size-warning.ts", "production", "verification-tooling"],
    ["consistency/public-release/site/index.html", "production", "distribution"],
    ["interpreter/public-release/index.html", "production", "distribution"],
  ];
  for (const [relative, purpose, category] of expected) {
    const file = report.files.find((f: { path: string }) => f.path === relative);
    assert.ok(file, relative); assert.deepEqual([file.purpose, file.category], [purpose, category], relative);
  }
});

test("unclassified or unknown production source cannot disappear from the statistics", () => {
  for (const [purpose, category] of [["other", "unclaimed"], ["production", "new-category"]]) {
    assert.throws(() => sectionOf({ path: "missed.ts", kind: "source", purpose, category }), /missed.ts/);
  }
});

test("invalid usage retains exit status two and help runs through Dash", () => {
  for (const args of [["--artifact", "unknown"], ["--unknown"], ["--artifact"]]) {
    const run = spawnSync("dash", [path.join(ROOT, "scripts/update-code-statistics.sh"), ...args], { encoding: "utf8", timeout: 10_000 });
    assert.equal(run.status, 2, run.stderr);
  }
  const run = spawnSync("dash", [path.join(ROOT, "scripts/update-code-statistics.sh"), "--help"], { encoding: "utf8", timeout: 10_000 });
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /Usage: update-code-statistics/);
});


test("checker statistics include its predicate dependency and shared sources without interpreter code", t => {
  const root = fixture(t);
  for (const relative of ["predicates/build.gradle", "predicates/src/commonMain/kotlin/Predicate.kt", "consistency/src/commonTest/kotlin/CheckerTest.kt"]) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), "class Fixture\n");
    execFileSync("git", ["-C", root, "add", relative]);
  }
  const run = spawnSync("dash", [path.join(root, "scripts/update-code-statistics.sh"), "--artifact", "checker"], { encoding: "utf8", timeout: 10_000 });
  assert.equal(run.status, 0, run.stderr);
  const report = measureRepository(root);
  assert.deepEqual(selectSources(report, "checker", "Production").map(f => f.path).sort(), ["consistency/src/commonMain/kotlin/Checker.kt", "predicates/src/commonMain/kotlin/Predicate.kt", "shared-regex/src/Shared.kt"]);
  const output = fs.readFileSync(path.join(root, "consistency/public-release/README.md"), "utf8");
  assert.match(output, /a12-consistency-releases\/blob\/main\/\.locmetryrc/);
  assert.match(output, /\| Kotlin \| 3 \| 3 \| 3 \| 0 \| 0 \|/);
  assert.match(output, /### Verification/);
  assert.doesNotMatch(output, /Tokei|Counted from|locmetry\) \d/);
});
