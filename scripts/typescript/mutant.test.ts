const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");

const { inferLane, plant, fastFlag, resultsDir, resultFileMatcher, countsIn, judge, exitCodeFor, parseBatch,
  restoreVerified } = require("./mutant.ts");

/**
 * Every row here is a way a mutant once got the WRONG VERDICT. A wrong verdict reads as a finding — about a
 * control that does not exist, or one that does not work — so each is locked as a refusal, never a warning.
 */

const GREEN = { tests: 3, failures: 0, errors: 0 };
const RED = { tests: 3, failures: 1, errors: 0 };

test("a lane is inferred from the mutated file's module, and a JS-only source set is refused", () => {
  assert.deepEqual(inferLane("adapter/src/main/java/X.java"), { lane: ":adapter:test" });
  assert.deepEqual(inferLane("consistency/src/commonMain/kotlin/X.kt"), { lane: ":consistency:jvmTest" });
  assert.deepEqual(inferLane("consistency-gate/src/main/kotlin/X.kt"), { lane: ":consistency-gate:test" },
    "a longer module name must not be read as its prefix's");
  assert.deepEqual(inferLane("interpreter-bridge/src/X.kt"), { lane: ":interpreter-bridge:test" });
  assert.deepEqual(inferLane("rulekit/src/main/java/X.java"), { lane: ":test" },
    "a module without its own test source set is owned by root :test");
  assert.ok("refusal" in inferLane("consistency/src/jsMain/kotlin/X.kt"),
    "a jvmTest lane never compiles a JS-only source, so every test passes and the mutant reads SURVIVED");
  assert.ok("refusal" in inferLane("interpreter/src/jsTest/kotlin/X.kt"));
});

test("a mutant plants only on one exact, changing, unique anchor", () => {
  assert.deepEqual(plant("a < b; c", "a < b", "a <= b"), { text: "a <= b; c" });
  assert.match(plant("x", "y", "z").refusal, /NOT in the file/, "an unapplied mutant reports SURVIVED");
  assert.match(plant("x", "x", "x").refusal, /identical/);
  assert.match(plant("f(a)\nf(a) + 1\n", "f(a)", "g(a)").refusal, /2 times.*lines: 1, 2/s,
    "a prefix match on a second line is a second site, and the refusal names both lines");
});

test("a guard lane runs without -Pfast, which would skip the very task named", () => {
  assert.deepEqual(fastFlag(":adapter:test"), ["-Pfast"]);
  assert.deepEqual(fastFlag(":docGuardTest"), []);
  assert.deepEqual(fastFlag(":cli:referentGuardTest"), []);
});

test("a lane maps to the directory its result files land in", () => {
  const root = path.resolve(__dirname, "../..");
  assert.equal(resultsDir(":test"), path.join(root, "build", "test-results", "test"));
  assert.equal(resultsDir(":consistency:jvmTest"), path.join(root, "consistency", "build", "test-results", "jvmTest"));
});

test("a filter selects the result files of the classes it names", () => {
  assert.ok(resultFileMatcher("*CrossEngineRetryTest*").test("TEST-io.x.CrossEngineRetryTest.xml"));
  assert.ok(resultFileMatcher("io.x.FooTest").test("TEST-io.x.FooTest.xml"));
  assert.ok(!resultFileMatcher("io.x.FooTest").test("TEST-ioAxBFooTest.xml"), "a dot is literal");
});

test("a result file's counts come from its suite element", () => {
  assert.deepEqual(countsIn('<?xml?>\n<testsuite name="x" tests="4" skipped="0" failures="1" errors="0">'),
    { tests: 4, failures: 1, errors: 0 });
});

test("a kill needs a fresh failing result, not merely a failed build", () => {
  assert.equal(judge(false, "", [RED]).verdict, "killed");
  assert.equal(judge(true, "", [GREEN]).verdict, "survived");
  assert.equal(judge(false, "e: Unresolved reference: nope\nBUILD FAILED", []).verdict, "no-verdict",
    "a mutant that does not compile once read as the control catching it");
  assert.equal(judge(false, "BUILD FAILED", [GREEN]).verdict, "no-verdict",
    "a failure outside the control — a prerequisite — is not a kill");
});

test("each run that ran nothing is no verdict, whatever the build's exit said", () => {
  assert.equal(judge(false, "Unknown command-line option '--tests'", []).verdict, "no-verdict",
    "a lifecycle task rejects --tests while configuring, and every mutant read killed");
  assert.equal(judge(false, "No tests found for given includes: [*Typo*]", []).verdict, "no-verdict");
  assert.equal(judge(false, "selected no test for *Typo*", []).verdict, "no-verdict");
  assert.match(judge(false, ":cli:test deliberately excludes FooTest\n  run :cli:fooGuardTest\n\nrest", []).reason,
    /fooGuardTest/, "the build's own routing hint is surfaced");
  assert.equal(judge(true, "", []).verdict, "no-verdict", "a Kotlin/JS lane passes an empty selection silently");
  assert.equal(judge(true, "", [{ tests: 0, failures: 0, errors: 0 }]).verdict, "no-verdict");
});

test("a batch exits with its worst verdict, and an incomplete batch is no verdict", () => {
  assert.equal(exitCodeFor(["killed", "killed"]), 0);
  assert.equal(exitCodeFor(["killed", "survived"]), 1);
  assert.equal(exitCodeFor(["survived", "no-verdict"]), 2);
});

test("a batch file is refused unless every entry carries its four strings", () => {
  const one = { file: "f", old: "a", new: "b", filter: "*T*" };
  assert.deepEqual(parseBatch(JSON.stringify([one])), [one]);
  assert.ok("refusal" in parseBatch("[]"));
  assert.ok("refusal" in parseBatch("{"));
  assert.match(parseBatch(JSON.stringify([{ ...one, new: 1 }])).refusal, /entry 0: "new"/);
  assert.match(parseBatch(JSON.stringify([{ ...one, lane: 3 }])).refusal, /"lane"/);
});

test("a restore puts the original bytes back and proves it", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mutant-test-"));
  const file = path.join(dir, "x.kt");
  const original = Buffer.from("val x = 1\r\n", "utf8");
  fs.writeFileSync(file, "val x = 2\n");
  restoreVerified(file, original);
  assert.ok(fs.readFileSync(file).equals(original), "bytes, including a CRLF, not a re-encoded string");
  fs.rmSync(dir, { recursive: true });
});
