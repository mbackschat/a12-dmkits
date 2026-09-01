const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { setTimeout: delay } = require("node:timers/promises");

const {
  commandWidthFindings,
  discoverDemos,
  fencedHttpDemos,
  parseConcurrency,
  runBounded,
} = require("./verify-demos.ts");

test("discovery derives only output-bearing Markdown demos and excludes the index", t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "a12-demos-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, "examples"));
  fs.writeFileSync(path.join(root, "examples/README.md"), "```output\nindex\n```\n");
  fs.writeFileSync(path.join(root, "examples/a.md"), "```output\nanswer\n```\n");
  fs.writeFileSync(path.join(root, "examples/b.md"), "prose only\n");

  assert.deepEqual(discoverDemos(root), ["examples/a.md"]);
});

test("the hanging-server guard distinguishes fenced commands from indented examples", () => {
  const demos = [
    { path: "examples/hangs.md", content: "```bash\ndmtool mcp --http 8080\n```\n" },
    { path: "examples/safe.md", content: "    dmtool mcp --http 8080\n" },
  ];
  assert.deepEqual(fencedHttpDemos(demos), ["examples/hangs.md"]);
});

test("command-width ratchets use the per-demo budget and the default for new demos", () => {
  const wide = "x".repeat(121);
  assert.deepEqual(commandWidthFindings([
    { path: "examples/cli-apply.md", content: `\`\`\`bash\n${wide}\n\`\`\`\n` },
    { path: "examples/new.md", content: `\`\`\`bash\n${wide}\n\`\`\`\n` },
  ]), ["new.md: longest bash line 121 > 120 (the bar for a new demo is 120)"]);
});

test("concurrency must be a positive integer", () => {
  assert.equal(parseConcurrency(undefined), 6);
  assert.equal(parseConcurrency("3"), 3);
  for (const invalid of ["0", "-1", "1.5", "many"]) {
    assert.throws(() => parseConcurrency(invalid), /positive integer/);
  }
});

test("bounded execution never exceeds the cap and preserves input order", async () => {
  let active = 0;
  let maximum = 0;
  const results = await runBounded([1, 2, 3, 4, 5], 2, async value => {
    active++;
    maximum = Math.max(maximum, active);
    await delay((6 - value) * 2);
    active--;
    return value * 10;
  });

  assert.equal(maximum, 2);
  assert.deepEqual(results, [10, 20, 30, 40, 50]);
});
