const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { physicalLines, warnings } = require("./source-size-warning.ts");

// The committed policy, so these tests prove the rule on the numbers the hook actually uses.
const policy = JSON.parse(fs.readFileSync(path.join(__dirname, "../source-size-limits.json"), "utf8"));
const production = policy.limits.production;
const testLimits = policy.limits.test;

test("a file at its soft limit is silent and one line past it is named", () => {
  assert.deepEqual(warnings(policy, new Map([["a/src/main/A.java", production.soft]])), []);
  const found = warnings(policy, new Map([["a/src/main/A.java", production.soft + 1]]));
  assert.equal(found.length, 1);
  assert.match(found[0], /past the soft limit/);
});

test("a test file is held to the tighter test limit, by source set or by the .test.ts suffix", () => {
  assert.equal(warnings(policy, new Map([["a/src/test/ATest.kt", testLimits.soft + 1]])).length, 1);
  assert.equal(warnings(policy, new Map([["scripts/x.test.ts", testLimits.soft + 1]])).length, 1);
  assert.deepEqual(warnings(policy, new Map([["scripts/x.ts", testLimits.soft + 1]])), [],
    "the same count is within the production soft limit");
});

test("a file past the hard limit is named as refused, and a listed one as held at its ceiling", () => {
  assert.match(warnings(policy, new Map([["a/src/main/A.java", production.hard + 1]]))[0], /leakGuardTest refuses/);
  const [listed, ceiling] = Object.entries(policy.legacyCeilings)[0] as [string, number];
  assert.match(warnings(policy, new Map([[listed, ceiling]]))[0], new RegExp(`held at ${ceiling}`));
});

test("lines are counted as the guard counts them", () => {
  assert.equal(physicalLines(""), 0);
  assert.equal(physicalLines("a\n"), 1);
  assert.equal(physicalLines("a\nb"), 2);
});
