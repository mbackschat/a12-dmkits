const assert = require("node:assert/strict");
const { test } = require("node:test");

const { judge, lanesOf, invokesGradle, statesItsQuestion } = require("./gradle-lane-guard.ts");

/**
 * The claim under test is narrow: the guard must fire on the shape that actually wasted a session, and must
 * stay silent on every shape the repository's own instructions prescribe. The second half is the one that
 * decides whether a guard like this survives — `scripts/lane-guard.sh` was unwired within a day because it
 * refused justified work, and its header names the two cases that did it: a `--tests` OPERAND read as a task,
 * and the pre-commit hook's own fix command refused.
 *
 * The commands below are the real ones from the 2026-09-06 session and from the hooks' own prescribed fixes,
 * not invented shapes.
 */

const RAN_CLI = [":cli:test"];

test("the shape that wasted the session is blocked on repeat", () => {
  const first = judge("./build.sh :cli:test", []);
  assert.equal(first.decision, "allow", "the first run of a lane is free");
  assert.deepEqual(first.lanes, [":cli:test"]);

  const repeat = judge("./build.sh :cli:test", RAN_CLI);
  assert.equal(repeat.decision, "block");
  assert.match(repeat.message ?? "", /# why:/, "the block must name the escape hatch");
  assert.match(repeat.message ?? "", /--tests/, "and the narrower instrument to prefer");
});

/**
 * The guard must never make a change run FEWER tests than it needs. CLAUDE.md's end-of-session checkpoint is
 * six distinct lanes, and a change can genuinely require several — so every lane this session has not already
 * run must pass untouched, however many precede it. This is the case that killed the first version of the
 * rule, which keyed on the session rather than on the lane.
 */
test("a change needing several DIFFERENT lanes is never nagged", () => {
  const checkpoint = [
    "./build.sh :cli:check", "./build.sh :test", "./build.sh :adapter:test",
    "./build.sh :interpreter:check", "./build.sh :schemakit:test", "./build.sh :interpreter-bridge:check",
  ];
  const alreadyRun: string[] = [];
  for (const command of checkpoint) {
    const verdict = judge(command, alreadyRun);
    assert.equal(verdict.decision, "allow", `${command} after ${alreadyRun.join(" ") || "(nothing)"}`);
    alreadyRun.push(...verdict.lanes);
  }
  assert.equal(alreadyRun.length, 6, "each lane recorded exactly once");

  assert.equal(judge("./build.sh :cli:check", alreadyRun).decision, "block",
    "only a REPEAT of an already-run lane is blocked");
});

test("one command naming several lanes is judged on the repeated one only", () => {
  assert.equal(judge("./build.sh :test :cli:test :adapter:test", []).decision, "allow");
  const verdict = judge("./build.sh :test :cli:test :adapter:test", RAN_CLI);
  assert.equal(verdict.decision, "block");
  assert.match(verdict.message ?? "", /:cli:test already ran/,
    "the message names the repeated lane, not the innocent siblings");
});

test("a stated question always lets the run through", () => {
  const justified = judge(
    "./build.sh --console=plain :cli:check  # why: end-of-session backstop over KernelModelPreparation",
    RAN_CLI,
  );
  assert.equal(justified.decision, "allow");
  assert.deepEqual(justified.lanes, [":cli:check"]);
});

test("a filtered run is never judged, however many lanes precede it", () => {
  for (const command of [
    "./build.sh :test --tests '*ExtremumOperandKindGridLawsTest*' -Pfast",
    "./build.sh :adapter:test --tests '*DiffTest*' --tests '*LawsTest*' -Pfast",
    "./build.sh :cli:test --tests=io.github.mbackschat.a12.dm.cli.ApplyVerbTest",
  ]) {
    assert.deepEqual(lanesOf(command), [], command);
    assert.equal(judge(command, RAN_CLI).decision, "allow", command);
  }
});

/** `lane-guard.sh` defect #1: a bare `--tests` OPERAND must never be read as a task selector. */
test("an option operand is not a task", () => {
  assert.deepEqual(lanesOf("./build.sh --console plain :docGuardTest"), []);
  assert.deepEqual(lanesOf("./build.sh -x check :cli:test"), [":cli:test"],
    "the excluded task is skipped as an operand; the real lane still counts");
});

/** `lane-guard.sh` defect #2: never refuse the fix line another hook prescribes. */
test("the pre-commit hook's own prescribed doc-guard command is never blocked", () => {
  const prescribed = "./build.sh --console=plain :docGuardTest :cli:docGuardTest :cli:referentGuardTest "
    + ":cli:staleGapGuardTest :cli:semanticsMapGuardTest";
  assert.deepEqual(lanesOf(prescribed), [], "guard tasks are not wide lanes");
  assert.equal(judge(prescribed, RAN_CLI).decision, "allow");
  assert.equal(judge(`${prescribed} --rerun`, RAN_CLI).decision, "allow", "the --rerun variant too");
});

test("narrower test tasks are left alone", () => {
  for (const task of [
    ":cli:publicContractTest", ":adapter:corpusReplayTest", ":interpreter:jvmTest", ":apiGuardTest",
    ":cli:evalGuardTest", ":adapter:perfGateTest",
  ]) {
    assert.deepEqual(lanesOf(`./build.sh ${task}`), [], task);
  }
});

test("non-Gradle commands are invisible to the guard", () => {
  for (const command of [
    "git commit -m 'test: check the lane'",
    "./scripts/mutant.sh a.java 'x' 'y' '*Test*' ':test'",
    "grep -rn 'build.sh :cli:test' docs/",
    "echo 'run ./build.sh :cli:test next'",
  ]) {
    assert.equal(judge(command, RAN_CLI).decision, "allow", command);
  }
  assert.equal(invokesGradle("git commit -m 'build.sh'"), false,
    "a mere mention inside a quoted argument is not an invocation at a command position");
});

/**
 * The anchoring under-matches deliberately, and the cost is recorded here rather than left to be rediscovered:
 * a wrapper before the command hides it from the guard. That direction is safe (one unguarded run); the other
 * direction blocks a grep about a lane, which is what unwired `scripts/lane-guard.sh`.
 */
test("a shell operator before the command still counts, a wrapper word does not", () => {
  assert.deepEqual(lanesOf("cd /repo && ./build.sh :cli:test"), [":cli:test"]);
  assert.deepEqual(lanesOf("./build.sh :cli:test | tee log"), [":cli:test"]);
  assert.deepEqual(lanesOf("time ./build.sh :cli:test"), [], "under-matches, and fails open by doing so");
});

test("bare and multi-lane invocations are recognised", () => {
  assert.deepEqual(lanesOf("./gradlew test"), ["test"]);
  assert.deepEqual(lanesOf("./build.sh :test :cli:test :adapter:test"), [":test", ":cli:test", ":adapter:test"]);
  assert.deepEqual(lanesOf("./build.sh :interpreter:check"), [":interpreter:check"]);
});

test("the justification token needs actual content", () => {
  assert.equal(statesItsQuestion("./build.sh :test # why: does the fix hold across modules"), true);
  assert.equal(statesItsQuestion("./build.sh :test # why:"), false, "an empty reason is not a reason");
  assert.equal(statesItsQuestion("./build.sh :test # whyever"), false);
  // The prior lane must be the SAME lane, or the per-lane rule allows it for the right reason.
  assert.equal(judge("./build.sh :test # why:", [":test"]).decision, "block");
});
