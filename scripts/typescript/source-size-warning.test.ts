const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { sourceRecords, warnings } = require("./source-size-warning.ts");

// The committed policy, so these tests prove the rule on the numbers the hook actually uses.
const policy = JSON.parse(fs.readFileSync(path.join(__dirname, "../source-size-limits.json"), "utf8"));
const production = policy.limits.production;

const source = (file: string, purpose: string, code: number) => ({ path: file, purpose, code });

test("a file at its soft limit is silent and one code line past it is named", () => {
  assert.deepEqual(warnings(policy, [source("a/src/main/A.java", "production", production.soft)]), []);
  const found = warnings(policy, [source("a/src/main/A.java", "production", production.soft + 1)]);
  assert.equal(found.length, 1);
  assert.match(found[0], /past the soft limit/);
});

test("each purpose is held to its own entry, and a purpose without one is never named", () => {
  const split = { ...policy, limits: { production: { soft: 10, hard: 20 }, test: { soft: 30, hard: 40 } }, legacyCeilings: {} };
  assert.match(warnings(split, [source("a/src/main/A.java", "production", 25)])[0], /hard limit of 20 for production/);
  assert.deepEqual(warnings(split, [source("a/src/test/ATest.java", "test", 25)]), []);
  assert.deepEqual(warnings(split, [source("docs/archived/x.sh", "other", 1000)]), []);
});

test("a file past the hard limit is named as refused, and a listed one by where it stands against its ceiling", () => {
  assert.match(warnings(policy, [source("a/src/main/A.java", "production", production.hard + 1)])[0], /leakGuardTest refuses/);
  const [listed, ceiling] = Object.entries(policy.legacyCeilings)[0] as [string, number];
  assert.match(warnings(policy, [source(listed, "production", ceiling)])[0], new RegExp(`held at ${ceiling}`));
  assert.match(warnings(policy, [source(listed, "production", ceiling + 1)])[0], /grown past .* leakGuardTest refuses/);
  assert.match(warnings(policy, [source(listed, "production", ceiling - 1)])[0], new RegExp(`lower the ceiling to ${ceiling - 1}`));
  assert.match(warnings(policy, [source(listed, "production", production.hard)])[0], /delete its legacyCeilings entry/);
});

test("a policy without an entry for a held purpose is refused rather than exempting that purpose", () => {
  assert.throws(() => warnings({ ...policy, limits: { production } }, []), /no limits\.test/);
});

test("a report yields its source files' purpose and code lines, and nothing else", () => {
  const report = {
    metadata: { locmetry_version: "99.0.0" },
    files: [
      { path: "A.kt", kind: "source", purpose: "production", counts: { code: 7, comments: 3, blanks: 1, lines: 11 } },
      { path: "README.md", kind: "documentation", purpose: "production", counts: { code: 0, comments: 9, blanks: 0, lines: 9 } },
    ],
  };
  assert.deepEqual(sourceRecords(report), [source("A.kt", "production", 7)]);

});
