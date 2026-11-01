const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

test("the public updater counts interpreter sources without checker or bridge sources and detects stale output", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "source-statistics-"));
  try {
    for (const directory of ["scripts/typescript", "interpreter/public-release", "plugin", "tools"]) {
      fs.mkdirSync(path.join(root, directory), { recursive: true });
    }
    for (const relative of ["scripts/update-code-statistics.sh", "scripts/run-typescript.sh", "scripts/typescript/update-code-statistics.ts"]) {
      const source = path.resolve(__dirname, "../..", relative);
      if (fs.existsSync(source)) fs.copyFileSync(source, path.join(root, relative));
    }
    const readme = path.join(root, "interpreter/public-release/README.md");
    fs.writeFileSync(readme, "before\n<!-- source-statistics:interpreter-release:start -->\nstale\n<!-- source-statistics:interpreter-release:end -->\nafter\n");
    fs.writeFileSync(path.join(root, "plugin/README.md"), "<!-- source-statistics:dmtool:start -->\nstale\n<!-- source-statistics:dmtool:end -->\n");
    const counter = path.join(root, "tools/tokei");
    fs.writeFileSync(counter, `#!${process.execPath}
const args=process.argv.slice(2), i=args.indexOf('--types'), language=args[i+1], roots=args.slice(i+2);
const code=roots.reduce((sum,p)=>sum+(p.includes('/consistency/')||p.includes('/consistency-gate/')||p.includes('/interpreter-bridge/')||p.includes('/predicates/')?100:1),0);
process.stdout.write(JSON.stringify({[language]:{reports:roots.map(()=>({})),code,comments:0,blanks:0}}));
`, { mode: 0o755 });
    const run = (...args: string[]) => spawnSync(path.join(root, "scripts/update-code-statistics.sh"), args, {
      encoding: "utf8", env: { ...process.env, PATH: `${path.join(root, "tools")}:${process.env.PATH}` }, timeout: 10_000,
    });
    const updated = run("--artifact", "interpreter");
    assert.equal(updated.status, 0, updated.stderr);
    const output = fs.readFileSync(readme, "utf8");
    assert.match(output, /\| Kotlin \| 3 \| 3 \| 3 \| 0 \| 0 \|/);
    assert.doesNotMatch(output, /consistency\/|consistency-gate\/|interpreter-bridge\/|predicates\//);
    assert.match(output, /^before\n/);
    assert.match(output, /\nafter\n$/);
    assert.equal(run("--check", "--artifact", "interpreter").status, 0);
    fs.writeFileSync(readme, output.replace("| Kotlin | 3 |", "| Kotlin | 999 |"));
    const stale = fs.readFileSync(readme);
    assert.equal(run("--check", "--artifact", "interpreter").status, 1);
    assert.deepEqual(fs.readFileSync(readme), stale);
    const dmtool = run("--artifact", "dmtool");
    assert.equal(dmtool.status, 0, dmtool.stderr);
    assert.match(fs.readFileSync(path.join(root, "plugin/README.md"), "utf8"), /\| Kotlin \| 8 \| 503 \| 503 \| 0 \| 0 \|/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("invalid usage retains exit status two", () => {
  for (const args of [["--artifact", "unknown"], ["--unknown"], ["--artifact"]]) {
    const run = spawnSync("dash", [path.resolve(__dirname, "../update-code-statistics.sh"), ...args], { encoding: "utf8", timeout: 10_000 });
    assert.equal(run.status, 2, run.stderr);
  }
});

test("the public path runs through Dash", () => {
  const run = spawnSync("dash", [path.resolve(__dirname, "../update-code-statistics.sh"), "--help"], { encoding: "utf8", timeout: 10_000 });
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /Usage: update-code-statistics/);
});
