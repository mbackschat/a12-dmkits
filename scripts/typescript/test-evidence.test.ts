const assert = require("node:assert/strict");
const path = require("node:path");
const { test } = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const { execFileSync, spawnSync } = require("node:child_process");

const { withinRun, ranInThisRun, taskFor, lastVerdict, taskStates } = require("./test-evidence.ts");
const { performanceInputs, recordReleasePerformance, verifyReleasePerformance } = require("./test-evidence.ts");

function performanceFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "release-performance-"));
  const write = (name: string, content: string) => {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), content);
  };
  const git = (...args: string[]) => execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
  write(".gitignore", "build/\n");
  write("adapter/src/test/kotlin/example/TimingTest.kt", 'package example\n@Tag("perf")\nclass TimingTest {\n@Test fun measures() {}\n}\n');
  write("interpreter/src/commonMain/kotlin/Engine.kt", "class Engine\n");
  write("build.gradle", "version = '1.0.0'\n");
  write("adapter/src/test/resources/perf/anchors.json", JSON.stringify({ environments: { local: {
    os: "test", arch: "test", machine: "test", processors: 1, observations: { sample: 1 },
  } } }));
  git("init", "-q"); git("add", ".");
  git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture");
  const source = git("rev-parse", "HEAD");
  write("build/command.log", "running: ./build.sh --console=plain -Pfast -i :adapter:perfGateTest  (raw → build/perf-gate.log)\nBUILD SUCCESSFUL in 10s\n");
  write("build/run.log", `> Task :adapter:perfGateTest\n-Dperf.anchor.lane=true -Dperf.anchor.required=true -Dperf.build.source=${source}\nBUILD SUCCESSFUL in 10s\n`);
  write("build/results/TEST-example.TimingTest.xml", '<testsuite name="example.TimingTest" tests="1" failures="0" errors="0" skipped="0" timestamp="2026-11-01T02:32:50Z"><testcase name="measures()"/></testsuite>');
  write("build/record.jsonl", JSON.stringify({ kind: "run", commit: source, capturedAt: "2026-11-01T023249Z", environment: "local", ci: false, os: "test", arch: "test", machine: "test", processors: 1 }) + "\n"
    + JSON.stringify({ kind: "observation", key: "sample", status: "accepted", medianMs: 1, environment: "local" }) + "\n");
  const evidence = path.join(root, "build/evidence");
  const record = () => recordReleasePerformance(root, path.join(root, "build/command.log"), path.join(root, "build/run.log"), path.join(root, "build/results"), path.join(root, "build/record.jsonl"), evidence);
  return { root, write, git, source, evidence, record, close: () => fs.rmSync(root, { recursive: true, force: true }) };
}

test("a complete passing run is reusable across documentation, measurement and native metadata changes", () => {
  const f = performanceFixture();
  try {
    f.record();
    const before = performanceInputs(f.root);
    f.write("docs/RELEASE-SPEC.md", "updated documentation\n");
    f.write("adapter/README.md", "updated module documentation\n");
    f.write("perf-results/local/new.jsonl", "measurement history\n");
    f.write("cli/native-capture-stamp.txt", "new stamp\n");
    f.write("cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json", "{}\n");
    assert.equal(performanceInputs(f.root), before);
    assert.equal(verifyReleasePerformance(f.root, f.evidence).tests, 1);
    assert.equal(verifyReleasePerformance(f.root, f.evidence).tests, 1);
  } finally { f.close(); }
});

test("runtime, build configuration, anchor and test changes each invalidate evidence", () => {
  for (const file of ["interpreter/src/commonMain/kotlin/Engine.kt", "build.gradle", "adapter/src/test/resources/perf/anchors.json", "adapter/src/test/kotlin/example/TimingTest.kt", "adapter/src/test/resources/new-fixture.json"]) {
    const f = performanceFixture();
    try { f.record(); f.write(file, "changed\n"); assert.throws(() => verifyReleasePerformance(f.root, f.evidence), /inputs changed/); }
    finally { f.close(); }
  }
});

test("historical adoption refuses failed, filtered, vacuous, incomplete and mismatched evidence", () => {
  const changes = [
    ["build/run.log", "> Task :adapter:perfGateTest\nBUILD FAILED in 10s\n"],
    ["build/command.log", "running: ./build.sh --console=plain -Pfast -i :adapter:perfGateTest --tests '*measures*'\nBUILD SUCCESSFUL in 10s\n"],
    ["build/run.log", "> Task :adapter:perfGateTest\nanchor not asserted\nBUILD SUCCESSFUL in 10s\n"],
    ["build/results/TEST-example.TimingTest.xml", '<testsuite name="example.OtherTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="measures()"/></testsuite>'],
    ["build/results/TEST-example.TimingTest.xml", '<testsuite name="example.TimingTest" tests="1" failures="0" errors="0" skipped="1"><testcase name="measures()"><skipped/></testcase></testsuite>'],
    ["build/results/TEST-example.TimingTest.xml", "<broken>"],
    ["build/record.jsonl", '{"kind":"run","commit":"DIRTY"}\n'],
  ];
  for (const [file, content] of changes) {
    const f = performanceFixture();
    try { f.write(file, content); assert.throws(f.record); assert.equal(fs.existsSync(f.evidence), false); }
    finally { f.close(); }
  }
  const f = performanceFixture();
  try { f.record(); f.write("build/evidence/run.log", "tampered\n"); assert.throws(() => verifyReleasePerformance(f.root, f.evidence), /modified/); }
  finally { f.close(); }
});

test("the publisher's POSIX gate reuses evidence without launching another performance run", () => {
  const f = performanceFixture();
  try {
    for (const file of ["scripts/release-perf-gate.sh", "scripts/test-evidence.sh", "scripts/run-typescript.sh", "scripts/typescript/test-evidence.ts"]) {
      f.write(file, fs.readFileSync(path.resolve(__dirname, "../..", file), "utf8"));
      fs.chmodSync(path.join(f.root, file), 0o755);
    }
    f.git("add", "scripts");
    f.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "tooling");
    // The launcher is a measured input; the evidence tooling is not part of the measured program.
    const source = f.git("rev-parse", "HEAD");
    for (const name of ["build/record.jsonl", "build/run.log"]) {
      f.write(name, fs.readFileSync(path.join(f.root, name), "utf8").replaceAll(f.source, source));
    }
    f.record();
    const result = spawnSync("dash", [path.join(f.root, "scripts/release-perf-gate.sh")], {
      cwd: f.root, encoding: "utf8", timeout: 10000,
      env: { ...process.env, RK_RELEASE_PERF_EVIDENCE: f.evidence },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /reused/);
    f.write("build/vacuous.sh", "echo 'anchor not asserted: runner mismatch'\n");
    const vacuous = spawnSync("dash", [path.join(f.root, "scripts/release-perf-gate.sh"), "dash", "build/vacuous.sh"], {
      cwd: f.root, encoding: "utf8", timeout: 10000,
    });
    assert.notEqual(vacuous.status, 0);
    assert.match(vacuous.stderr, /runner mismatch/);
    assert.match(vacuous.stderr, /scripts\/perf-anchor.sh refresh/);
  } finally { f.close(); }
});

test("parameterized XML must include every invocation present in the full run log", () => {
  const f = performanceFixture();
  try {
    f.write("adapter/src/test/kotlin/example/TimingTest.kt", 'package example\n@Tag("perf")\nclass TimingTest {\n@ParameterizedTest\n@EnumSource(Reader::class)\nfun measures(reader: Reader) {}\n}\n');
    f.git("add", "."); f.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "parameterized");
    const source = f.git("rev-parse", "HEAD");
    for (const name of ["build/record.jsonl", "build/run.log"]) f.write(name, fs.readFileSync(path.join(f.root, name), "utf8").replaceAll(f.source, source));
    fs.appendFileSync(path.join(f.root, "build/run.log"), "TimingTest > measures(Reader) > [1] FIRST STANDARD_OUT\nTimingTest > measures(Reader) > [2] SECOND STANDARD_OUT\n");
    const xml = '<testsuite name="example.TimingTest" tests="2" failures="0" errors="0" skipped="0" timestamp="2026-11-01T02:32:50Z"><testcase name="[1] FIRST"/><testcase name="[2] SECOND"/></testsuite>';
    f.write("build/results/TEST-example.TimingTest.xml", xml.replace('tests="2"', 'tests="1"').replace('<testcase name="[2] SECOND"/>', ''));
    assert.throws(f.record, /incomplete/);
    f.write("build/results/TEST-example.TimingTest.xml", xml); assert.equal(f.record().tests, 2);
  } finally { f.close(); }
});

test("every accepted XML filename is sealed, including spaces", () => {
  const f = performanceFixture();
  try {
    fs.renameSync(path.join(f.root, "build/results/TEST-example.TimingTest.xml"), path.join(f.root, "build/results/TEST-name with space.xml"));
    f.record(); f.write("build/evidence/results/TEST-name with space.xml", "changed");
    assert.throws(() => verifyReleasePerformance(f.root, f.evidence), /modified/);
  } finally { f.close(); }
});

test("Git checkout line endings preserve the committed performance input identity", () => {
  const f = performanceFixture();
  try {
    f.write(".gitattributes", "*.bat text eol=crlf\n"); f.write("gradlew.bat", "line\n");
    f.git("add", "."); f.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "line endings");
    f.write("gradlew.bat", "line\r\n");
    assert.equal(performanceInputs(f.root), performanceInputs(f.root, "HEAD"));
  } finally { f.close(); }
});

test("anchor coverage, failure children, time bounds and worker identity cannot be substituted", () => {
  const mutations = [
    ["build/record.jsonl", (s: string) => s.replace('"accepted"', '"unasserted"'), /accepted absolute anchors/],
    ["build/results/TEST-example.TimingTest.xml", (s: string) => s.replace('<testcase name="measures()"/>', '<testcase name="measures()"><failure/></testcase>'), /failing/],
    ["build/results/TEST-example.TimingTest.xml", (s: string) => s.replace("2026-11-01", "2026-10-31"), /run window|mismatched/],
    ["build/run.log", (s: string) => s.replace(/-Dperf.build.source=\w+/, "-Dperf.build.source=aaaaaaa"), /identities differ/],
  ];
  for (const [name, mutate, error] of mutations) {
    const f = performanceFixture();
    try { f.write(name, mutate(fs.readFileSync(path.join(f.root, name), "utf8"))); assert.throws(f.record, error); }
    finally { f.close(); }
  }
});

test("a fresh default gate records its pass, reuses it, and invalidates it before a forced failure", () => {
  const f = performanceFixture();
  try {
    for (const file of ["scripts/release-perf-gate.sh", "scripts/run-typescript.sh", "scripts/typescript/test-evidence.ts"]) {
      f.write(file, fs.readFileSync(path.resolve(__dirname, "../..", file), "utf8")); fs.chmodSync(path.join(f.root, file), 0o755);
    }
    f.write("scripts/perf.sh", `#!/bin/sh
set -eu
if [ -e build/ran ]; then exit 9; fi
touch build/ran
mkdir -p adapter/build/test-results/perfGateTest perf-results/local
cp build/results/*.xml adapter/build/test-results/perfGateTest/
cp build/run.log build/perf-gate.log
cp build/record.jsonl perf-results/local/run.jsonl
cat build/command.log
`);
    fs.chmodSync(path.join(f.root, "scripts/perf.sh"), 0o755);
    f.git("add", "scripts"); f.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "gate");
    const source = f.git("rev-parse", "HEAD");
    for (const name of ["build/record.jsonl", "build/run.log"]) f.write(name, fs.readFileSync(path.join(f.root, name), "utf8").replaceAll(f.source, source));
    const run = (...args: string[]) => spawnSync("dash", [path.join(f.root, "scripts/release-perf-gate.sh"), ...args], {
      cwd: f.root, encoding: "utf8", timeout: 10000, env: { ...process.env, RK_RELEASE_PERF_EVIDENCE: f.evidence },
    });
    const first = run(); assert.equal(first.status, 0, first.stderr); assert.match(first.stdout, /asserted and passed/);
    const second = run(); assert.equal(second.status, 0, second.stderr); assert.match(second.stdout, /reused/);
    assert.notEqual(run("--fresh").status, 0); assert.equal(fs.existsSync(f.evidence), false);
  } finally { f.close(); }
});

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

test("historical logs exclude later overwritten results but retain the closing boundary", () => {
  const run = { epochSeconds: 100, finishedAt: 110, basis: "derived" };
  assert.equal(withinRun(99, run), false);
  assert.equal(withinRun(100, run), false, "the lower cutoff retains its existing exclusive boundary");
  assert.equal(withinRun(105, run), true);
  assert.equal(withinRun(110, run), true, "a result may share the log's filesystem timestamp");
  assert.equal(withinRun(111, run), false, "a later passing execution cannot replace this run's failure");
});
