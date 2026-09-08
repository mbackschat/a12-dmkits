const assert = require("node:assert/strict");
const path = require("node:path");
const { test } = require("node:test");

const { ranInThisRun, taskFor, lastVerdict, taskStates } = require("./test-evidence.ts");

/**
 * The claim under test is the one the reporter exists for: a result file is evidence of THE RUN YOU JUST DID,
 * and the two ways to get that wrong both produce green-looking numbers.
 */

test("evidence needs BOTH a fresh file and a task the log says executed", () => {
  assert.equal(ranInThisRun(true, "EXECUTED"), true);
  assert.equal(ranInThisRun(true, "FAILED"), true, "a red class still ran — the report must show its result");

  assert.equal(ranInThisRun(false, "EXECUTED"), false,
    "the trap that matters: --tests excluded this class, so its green counts are from an earlier run");
  assert.equal(ranInThisRun(true, "UP-TO-DATE"), false,
    "a fresh file under a task that did not execute belongs to another build");
  assert.equal(ranInThisRun(true, "FROM-CACHE"), false, "…including a cache hit, which wrote no new XML");
  assert.equal(ranInThisRun(true, "ABSENT"), false, "…and a task this log never mentions");
});

test("a result directory maps back to the Gradle task path that owns it", () => {
  // The input is built from the module's OWN root, not from process.cwd(): `taskFor` relativizes against
  // `path.resolve(__dirname, "../..")`, so a cwd-derived input asserted a property of where the test was
  // launched — it failed from any other directory, and passed in CI only because the glob runs at the root.
  const root = path.resolve(__dirname, "../..");
  assert.equal(taskFor(path.join(root, "build/test-results/apiGuardTest")), ":apiGuardTest",
    "a root-project task carries no module segment");
  assert.equal(taskFor(path.join(root, "cli/build/test-results/publicContractTest")),
    ":cli:publicContractTest", "a module task does — and conflating the two mislabels every row");
});

test("the verdict is the LAST one in the log, because a wrapper prints its own", () => {
  const log = "> Task :cli:installDist\nBUILD SUCCESSFUL in 10s\n> Task :cli:test\nBUILD FAILED in 2m\n";
  assert.equal(lastVerdict(log), "BUILD FAILED");
});

test("task states are read per task, and the worst state for a task wins", () => {
  const states = taskStates("> Task :test\n> Task :cli:test UP-TO-DATE\n> Task :cli:docGuardTest FAILED\n");
  assert.equal(states.get(":test"), "EXECUTED");
  assert.equal(states.get(":cli:test"), "UP-TO-DATE");
  assert.equal(states.get(":cli:docGuardTest"), "FAILED");
});
