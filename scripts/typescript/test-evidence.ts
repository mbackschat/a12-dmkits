const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync, spawnSync } = require("node:child_process");

type TaskState = "EXECUTED" | "FROM-CACHE" | "UP-TO-DATE" | "NO-SOURCE" | "SKIPPED" | "FAILED";
type Counts = { tests: number; failures: number; errors: number };
type Cutoff = { epochSeconds: number; finishedAt: number; basis: "derived" | "birth-only" };

const ROOT = path.resolve(__dirname, "../..");

// RELEASE-SPEC §5a: reporting and native reflection output do not change the measured JVM program.
const PERFORMANCE_REPORTERS = new Set([
  "cli/native-capture-stamp.txt", "scripts/release-perf-gate.sh", "scripts/selftest-release-perf-gate.sh",
  "scripts/test-evidence.sh", "scripts/selftest-test-evidence.sh", "scripts/typescript/test-evidence.ts",
  "scripts/typescript/test-evidence.test.ts", "scripts/publish-release.sh",
  "scripts/verify-graalvm-native.sh", "scripts/selftest-jdk-pin.sh",
]);
function performanceInput(name: string): boolean {
  return !PERFORMANCE_REPORTERS.has(name) && !/^(perf-results|docs|src\/test)\//.test(name)
    && !/^cli\/src\/main\/resources\/META-INF\/native-image\//.test(name)
    && !/^(?:[^/]+|[^/]+\/[^/]+|interpreter\/public-release\/[^/]+)\.md$/.test(name);
}
function gitOutput(root: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd: root, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });
}
function digest(bytes: Buffer | string): string { return crypto.createHash("sha256").update(bytes).digest("hex"); }
function performanceInputs(root: string, revision?: string): string {
  let entries: string[];
  if (revision) {
    entries = gitOutput(root, "ls-tree", "-rz", "--full-tree", revision).split("\0").filter(Boolean)
      .filter(entry => performanceInput(entry.slice(entry.indexOf("\t") + 1))).map(entry => entry.replace(" blob ", " "));
  } else {
    const files = [...new Set<string>(gitOutput(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0").filter(Boolean))];
    const selected = files.filter(performanceInput);
    const regular = selected.filter(name => fs.existsSync(path.join(root, name)) && !fs.lstatSync(path.join(root, name)).isSymbolicLink());
    // Git applies .gitattributes here, so a CRLF checkout has the same identity as its committed LF blob.
    const normalized = execFileSync("git", ["hash-object", "--stdin-paths"], { cwd: root, encoding: "utf8",
      input: regular.map(name => JSON.stringify(name)).join("\n") + "\n", maxBuffer: 32 * 1024 * 1024 }).trim().split("\n");
    const hashes = new Map(regular.map((name, index) => [name, normalized[index]]));
    entries = selected.map(name => {
      const file = path.join(root, name);
      if (!fs.existsSync(file)) return `missing\t${name}`;
      const stat = fs.lstatSync(file);
      const bytes = stat.isSymbolicLink() ? Buffer.from(fs.readlinkSync(file)) : fs.readFileSync(file);
      const oid = hashes.get(name) ?? crypto.createHash("sha1").update(`blob ${bytes.length}\0`).update(bytes).digest("hex");
      return `${stat.isSymbolicLink() ? "120000" : stat.mode & 0o111 ? "100755" : "100644"} ${oid}\t${name}`;
    });
  }
  if (!entries.length) throw new Error("no performance inputs found");
  return digest(entries.sort().join("\0"));
}
type PerformanceSummary = { source: string; inputs: string; tests: number; classes: number };
function performanceSummary(root: string, command: string, log: string, results: string, record: string): PerformanceSummary {
  const commands = command.split(/\r?\n/).filter(line => line.startsWith("running:"));
  if (commands.length !== 1 || !/^running: \.\/build\.sh --console=plain -Pfast -i :adapter:perfGateTest\s+\(raw → build\/perf-gate.log\)$/.test(commands[0])) throw new Error("evidence must come from the unfiltered full performance gate");
  if (lastVerdict(log) !== "BUILD SUCCESSFUL" || lastVerdict(command) !== "BUILD SUCCESSFUL"
    || taskStates(log).get(":adapter:perfGateTest") !== "EXECUTED"
    || /not asserting|anchor not asserted|UNANCHORED|\[FLAG\]/i.test(log)) throw new Error("performance evidence failed, was cached, or did not assert its limits");
  const rows = record.trim().split(/\r?\n/).map(line => JSON.parse(line));
  const runs = rows.filter(row => row.kind === "run");
  if (runs.length !== 1 || runs[0].ci !== false || !/^[a-f0-9]{7,40}$/.test(runs[0].commit)) throw new Error("evidence needs one clean source identity from an isolated non-CI run");
  const run = runs[0];
  const source = gitOutput(root, "rev-parse", `${run.commit}^{commit}`).trim();
  if (!log.includes(`-Dperf.build.source=${run.commit} `) && !log.includes(`-Dperf.build.source=${run.commit}\n`)) throw new Error("record and test worker source identities differ");
  for (const option of ["-Dperf.anchor.lane=true", "-Dperf.anchor.required=true"]) if (!log.includes(option)) throw new Error(`missing isolated-run evidence: ${option}`);
  const inputs = performanceInputs(root, source);
  if (performanceInputs(root) !== inputs) throw new Error("performance inputs changed since the measured source");
  const anchors = JSON.parse(fs.readFileSync(path.join(root, "adapter/src/test/resources/perf/anchors.json"), "utf8")).environments[run.environment];
  if (!anchors || ["os", "arch", "machine", "processors"].some(key => anchors[key] !== run[key])) throw new Error("record does not match a calibrated runner");
  const observations = rows.filter(row => row.kind === "observation");
  const accepted = new Map(observations.filter(row => row.status === "accepted" && row.environment === run.environment
    && Number.isFinite(row.medianMs) && row.medianMs > 0).map(row => [row.key, row]));
  const keys = Object.keys(anchors.observations);
  if (!keys.length || keys.some(key => !accepted.has(key)) || observations.some(row => ["breached", "flagged"].includes(row.status))) throw new Error("performance evidence is missing accepted absolute anchors");
  const expected = new Map<string, string[]>();
  for (const file of gitOutput(root, "ls-files", "-z", "adapter/src/test").split("\0").filter(name => /\.(kt|java)$/.test(name))) {
    const sourceText = fs.readFileSync(path.join(root, file), "utf8");
    if (!/^@Tag\("perf"\)\s*$/m.test(sourceText)) continue;
    const pkg = sourceText.match(/^package ([\w.]+)/m)?.[1], cls = sourceText.match(/^class (\w+)/m)?.[1];
    if (!pkg || !cls) throw new Error(`cannot derive the routine test class from ${file}`);
    const methods = [...sourceText.matchAll(/@Test\b[\s\S]*?\bfun\s+(\w+)\s*\(/g)].map(match => `${match[1]}()`);
    for (const match of sourceText.matchAll(/@ParameterizedTest\b[\s\S]*?\bfun\s+(\w+)\s*\(/g)) {
      const prefix = `${cls} > ${match[1]}(`;
      const invocations = new Set<string>();
      for (const line of log.split(/\r?\n/)) {
        if (!line.startsWith(prefix)) continue;
        const invocation = line.match(/\) > (.+) STANDARD_(?:OUT|ERROR)$/)?.[1];
        if (invocation) invocations.add(invocation);
      }
      if (!invocations.size) throw new Error(`missing parameterized invocation evidence: ${cls}.${match[1]}`);
      methods.push(...invocations);
    }
    if (!methods.length) throw new Error(`no supported test methods in ${file}`);
    expected.set(`${pkg}.${cls}`, methods);
  }
  // A standard XML parser makes malformed evidence a refusal rather than a zero-failure report.
  const xml = JSON.parse(execFileSync("python3", ["-c", `import json,pathlib,sys,xml.etree.ElementTree as E
out=[]
for p in sorted(pathlib.Path(sys.argv[1]).glob('TEST-*.xml')):
 r=E.parse(p).getroot()
 assert r.tag=='testsuite'
 cases=r.findall('testcase')
 out.append(dict(r.attrib,cases=[c.get('name') for c in cases],bad=any(c.find(x) is not None for c in cases for x in ('failure','error','skipped'))))
print(json.dumps(out))`, results], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }));
  const start = Date.parse(run.capturedAt.replace(/T(\d{2})(\d{2})(\d{2})Z$/, "T$1:$2:$3Z")), duration = durationSeconds(log);
  if (!Number.isFinite(start) || duration === undefined) throw new Error("missing measurement time bounds");
  if (!expected.size || xml.length !== expected.size || new Set(xml.map((suite: any) => suite.name)).size !== expected.size) throw new Error("incomplete routine performance class coverage");
  const timestamps = xml.map((suite: any) => Date.parse(suite.timestamp));
  const first = Math.min(...timestamps);
  // PerfAnchors initializes its record on the first observation, after the first test has started.
  if (first > start + 1000 || Math.max(...timestamps) - first > (duration + 1) * 1000) throw new Error("results exceed the measured run window");
  let tests = 0;
  for (const suite of xml) {
    const methods = expected.get(suite.name), timestamp = Date.parse(suite.timestamp);
    if (!methods || !/^\d+$/.test(suite.tests) || Number(suite.tests) < 1 || Number(suite.tests) !== suite.cases.length
      || ["failures", "errors", "skipped"].some(key => suite[key] !== "0") || suite.bad
      || methods.some(method => suite.cases.filter((name: string) => name === method).length < methods.filter(name => name === method).length) || !Number.isFinite(timestamp)
      || timestamp < start - (duration + 1) * 1000 || timestamp > first + (duration + 1) * 1000) throw new Error(`incomplete, failing, skipped or mismatched results: ${suite.name}`);
    tests += Number(suite.tests);
  }
  return { source, inputs, tests, classes: xml.length };
}
function evidenceFiles(directory: string): string[] {
  return ["command.log", "run.log", "record.jsonl", ...fs.readdirSync(path.join(directory, "results"))
    .filter((name: string) => name.startsWith("TEST-") && name.endsWith(".xml")).map((name: string) => `results/${name}`)].sort();
}
function recordReleasePerformance(root: string, command: string, log: string, results: string, record: string, destination: string): PerformanceSummary {
  if (record === "auto") {
    const source = fs.readFileSync(log, "utf8").match(/-Dperf\.build\.source=([a-f0-9]+)/)?.[1];
    const candidates: { file: string; capturedAt: string }[] = [];
    for (const dir of fs.readdirSync(path.join(root, "perf-results"), { withFileTypes: true }).filter((entry: any) => entry.isDirectory())) {
      for (const file of fs.readdirSync(path.join(root, "perf-results", dir.name)).filter((name: string) => name.endsWith(".jsonl"))) {
        const candidate = path.join(root, "perf-results", dir.name, file);
        const run = JSON.parse(fs.readFileSync(candidate, "utf8").split(/\r?\n/)[0]);
        if (run.kind === "run" && run.ci === false && run.commit === source) candidates.push({ file: candidate, capturedAt: run.capturedAt });
      }
    }
    record = candidates.sort((a, b) => a.capturedAt.localeCompare(b.capturedAt)).at(-1)?.file ?? "";
    if (!record) throw new Error("no performance record for the measured source");
  }
  const summary = performanceSummary(root, fs.readFileSync(command, "utf8"), fs.readFileSync(log, "utf8"), results, fs.readFileSync(record, "utf8"));
  if (fs.existsSync(destination)) throw new Error(`evidence already exists: ${destination}`);
  const temporary = `${destination}.tmp-${process.pid}`;
  fs.mkdirSync(path.join(temporary, "results"), { recursive: true });
  try {
    fs.copyFileSync(command, path.join(temporary, "command.log")); fs.copyFileSync(log, path.join(temporary, "run.log")); fs.copyFileSync(record, path.join(temporary, "record.jsonl"));
    for (const name of fs.readdirSync(results).filter((name: string) => name.startsWith("TEST-") && name.endsWith(".xml"))) fs.copyFileSync(path.join(results, name), path.join(temporary, "results", name));
    const hashes = Object.fromEntries(evidenceFiles(temporary).map(file => [file, digest(fs.readFileSync(path.join(temporary, file)))]));
    fs.writeFileSync(path.join(temporary, "receipt.json"), JSON.stringify({ schemaVersion: 1, ...summary, hashes }, null, 2) + "\n");
    fs.renameSync(temporary, destination);
  } finally { fs.rmSync(temporary, { recursive: true, force: true }); }
  return summary;
}
function verifyReleasePerformance(root: string, directory: string): PerformanceSummary {
  const receipt = JSON.parse(fs.readFileSync(path.join(directory, "receipt.json"), "utf8"));
  if (receipt.schemaVersion !== 1 || performanceInputs(root) !== receipt.inputs) throw new Error("performance inputs changed or evidence version is unsupported");
  const files = evidenceFiles(directory);
  if (JSON.stringify(Object.keys(receipt.hashes).sort()) !== JSON.stringify(files)
    || files.some(file => digest(fs.readFileSync(path.join(directory, file))) !== receipt.hashes[file])) throw new Error("performance evidence was modified");
  const summary = performanceSummary(root, fs.readFileSync(path.join(directory, "command.log"), "utf8"), fs.readFileSync(path.join(directory, "run.log"), "utf8"), path.join(directory, "results"), fs.readFileSync(path.join(directory, "record.jsonl"), "utf8"));
  if (["source", "inputs", "tests", "classes"].some(key => receipt[key] !== summary[key as keyof PerformanceSummary])) throw new Error("receipt does not match its evidence");
  return summary;
}
function performanceGate(args: string[]): void {
  const evidence = process.env.RK_RELEASE_PERF_EVIDENCE ?? path.join(ROOT, "build/release-perf-evidence");
  const fresh = args[0] === "--fresh";
  if (fresh) args.shift();
  if (!args.length && !fresh && fs.existsSync(evidence)) {
    const result = verifyReleasePerformance(ROOT, evidence);
    console.log(`performance gate reused: ${result.tests} tests, ${result.classes} classes, unchanged inputs from ${result.source}`);
    return;
  }
  if (!args.length && fresh) fs.rmSync(evidence, { recursive: true, force: true });
  const log = process.env.RK_PERF_GATE_LOG ?? path.join(ROOT, "build/release-perf-gate.log");
  const results = process.env.RK_PERF_GATE_XML_DIR ?? path.join(ROOT, "adapter/build/test-results/perfGateTest");
  fs.mkdirSync(path.dirname(log), { recursive: true }); fs.rmSync(results, { recursive: true, force: true });
  const output = fs.openSync(log, "w");
  let status;
  try { status = spawnSync(args[0] ?? path.join(ROOT, "scripts/perf.sh"), args.length ? args.slice(1) : ["gate"], { cwd: ROOT, stdio: ["inherit", output, output] }); }
  finally { fs.closeSync(output); }
  if (status.error || status.status !== 0) throw new Error(`PERFORMANCE GATE FAILED — see ${log}`);
  const text = fs.readFileSync(log, "utf8");
  if (/not asserting|anchor not asserted/.test(text)) {
    const reasons = text.split(/\r?\n/).filter(line => /not asserting|anchor not asserted/.test(line)).slice(0, 5).join("\n");
    throw new Error(`PERFORMANCE GATE WAS VACUOUS — it ran but did not assert its limits.\n${reasons}\nRefresh the anchor on this machine (scripts/perf-anchor.sh refresh) or release from the machine that authored it.`);
  }
  const files = fs.existsSync(results) ? fs.readdirSync(results).filter((name: string) => name.endsWith(".xml")) : [];
  if (!files.length) throw new Error("PERFORMANCE GATE LEFT NO RESULT XMLs — confirm perfGateTest executed");
  const skipped = files.reduce((sum: number, name: string) => sum + Number(fs.readFileSync(path.join(results, name), "utf8").match(/\bskipped=["'](\d+)["']/)?.[1] ?? 0), 0);
  if (skipped) throw new Error(`PERFORMANCE GATE SKIPPED ${skipped} test(s): ${files.join(", ")}`);
  if (!args.length) recordReleasePerformance(ROOT, log, path.join(ROOT, "build/perf-gate.log"), results, "auto", evidence);
  console.log(`performance gate asserted and passed (${files.length} result XMLs, 0 skipped, all written by this run)`);
}
const STATE_RANK: Readonly<Record<TaskState, number>> = {
  FAILED: 4,
  EXECUTED: 3,
  "FROM-CACHE": 2,
  "UP-TO-DATE": 1,
  "NO-SOURCE": 0,
  SKIPPED: 0,
};

function usage(): never {
  console.error("usage: scripts/test-evidence.sh [--print-cutoff | --class <substring>] "
    + "<path-to-gradle-console-plain-log>\n"
    + "       scripts/test-evidence.sh --record-release-performance <command-log> <raw-log> <results-dir> <record-jsonl|auto> <destination>\n"
    + "       scripts/release-perf-gate.sh [--fresh]");
  process.exit(2);
}

function durationSeconds(log: string): number | undefined {
  const units: Readonly<Record<string, number>> = { h: 3600, m: 60, s: 1, ms: 0.001 };
  let duration: number | undefined;
  for (const line of log.split(/\r?\n/)) {
    const verdict = line.match(/^BUILD (?:SUCCESSFUL|FAILED) in (.+?)\s*$/);
    if (!verdict) continue;
    let total = 0;
    let seen = false;
    for (const match of verdict[1].matchAll(/(\d+(?:\.\d+)?)\s*(ms|[hms])/g)) {
      total += Number(match[1]) * units[match[2]];
      seen = true;
    }
    if (seen) duration = total;
  }
  return duration;
}

function freshnessCutoff(logPath: string, log: string): Cutoff {
  const margin = Number(process.env.TEST_EVIDENCE_CUTOFF_MARGIN ?? "30");
  if (!Number.isFinite(margin)) {
    throw new Error(`TEST_EVIDENCE_CUTOFF_MARGIN is not a number: ${process.env.TEST_EVIDENCE_CUTOFF_MARGIN}`);
  }
  const stat = fs.statSync(logPath);
  const mtime = stat.mtimeMs / 1000;
  const birth = stat.birthtimeMs > 0 ? stat.birthtimeMs / 1000 : mtime;
  const duration = durationSeconds(log);
  return duration === undefined
    ? { epochSeconds: birth, finishedAt: mtime, basis: "birth-only" }
    : { epochSeconds: Math.max(birth, mtime - duration - margin), finishedAt: mtime, basis: "derived" };
}

/** A later run can overwrite the same XML; a lower cutoff alone credits it to the old log (G-EVIDENCE). */
function withinRun(epochSeconds: number, cutoff: Cutoff): boolean {
  return epochSeconds > cutoff.epochSeconds && epochSeconds <= cutoff.finishedAt;
}

function lastVerdict(log: string): string | undefined {
  let verdict: string | undefined;
  for (const match of log.matchAll(/BUILD (SUCCESSFUL|FAILED)/g)) verdict = `BUILD ${match[1]}`;
  return verdict;
}

function taskStates(log: string): Map<string, TaskState> {
  const states = new Map<string, TaskState>();
  const task = /^> Task (:[A-Za-z:_-]*[Tt]est[A-Za-z]*)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?/;
  for (const line of log.split(/\r?\n/)) {
    const match = line.match(task);
    if (!match || /compile|[Pp]rocess.*Resources|Classes|Jar/.test(match[1])) continue;
    const state = (match[2] ?? "EXECUTED") as TaskState;
    const previous = states.get(match[1]);
    if (previous === undefined || STATE_RANK[state] > STATE_RANK[previous]) states.set(match[1], state);
  }
  return new Map([...states.entries()].sort(([left], [right]) => left.localeCompare(right)));
}

function walkResultFiles(directory: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) results.push(...walkResultFiles(candidate));
    else if (entry.isFile() && /^TEST-.*\.xml$/.test(entry.name)) results.push(candidate);
  }
  return results;
}

function resultDirectories(root: string): string[] {
  const bases = [root];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (entry.isDirectory()) bases.push(path.join(root, entry.name));
  }
  const directories: string[] = [];
  for (const base of bases) {
    const results = path.join(base, "build", "test-results");
    if (!fs.existsSync(results) || !fs.statSync(results).isDirectory()) continue;
    for (const entry of fs.readdirSync(results, { withFileTypes: true })) {
      if (entry.isDirectory()) directories.push(path.join(results, entry.name));
    }
  }
  return directories.sort();
}

function xmlCounts(file: string): Counts {
  try {
    const xml = fs.readFileSync(file, "utf8");
    const root = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
    const attribute = (name: string): number => {
      const match = root.match(new RegExp(`\\b${name}\\s*=\\s*(?:"(\\d+)"|'(\\d+)')`));
      return Number(match?.[1] ?? match?.[2] ?? 0);
    };
    return { tests: attribute("tests"), failures: attribute("failures"), errors: attribute("errors") };
  } catch {
    return { tests: 0, failures: 0, errors: 0 };
  }
}

function taskFor(directory: string): string {
  const relative = path.relative(ROOT, directory).split(path.sep).join("/").replace(/\/$/, "");
  const leaf = relative.slice(relative.lastIndexOf("/") + 1);
  return relative.startsWith("build/test-results/") ? `:${leaf}` : `:${relative.slice(0, relative.indexOf("/"))}:${leaf}`;
}

function pad(value: string, width: number): string {
  return value.padEnd(width, " ");
}

/**
 * Whether a result file is evidence of THE RUN THIS LOG DESCRIBES — the load-bearing decision of the class
 * report, and the one worth locking on its own.
 *
 * <p>Both conditions are necessary and each alone is a trap. A fresh file under a task the log does not report
 * as executed is someone else's run (a parallel build, an editor); a stale file under a task that DID execute
 * is a class the filter excluded, and its green counts read exactly like a fresh pass. Only the conjunction is
 * evidence.
 */
function ranInThisRun(fresh: boolean, task: TaskState): boolean {
  return fresh && (task === "EXECUTED" || task === "FAILED");
}

/**
 * Per-CLASS evidence: for one named class, did the run this log describes actually execute it?
 *
 * The whole-estate report answers "which tasks ran"; a filtered run (`--tests '*Foo*'`) leaves every other
 * class's XML in place, so "is THIS class green" cannot be read off a task total. Measured 2026-09-04: a
 * corrective test was judged from a `tests completed` line four times, and twice the judgement was about a
 * class the run had not touched. This prints the discriminator directly — the file's own freshness, beside the
 * state of the task that owns it.
 */
function reportClass(pattern: string, states: Map<string, TaskState>, cutoff: Cutoff): void {
  console.log(`class evidence for a name containing "${pattern}":`);
  console.log();
  console.log(`${pad("class", 52)} ${pad("task", 26)} ${pad("state", 12)} ${pad("this run", 10)} result`);
  let matched = false;
  for (const directory of resultDirectories(ROOT)) {
    const task = states.get(taskFor(directory)) ?? "ABSENT";
    for (const file of walkResultFiles(directory)) {
      const name = path.basename(file).replace(/^TEST-/, "").replace(/\.xml$/, "");
      if (!name.includes(pattern)) continue;
      matched = true;
      const fresh = withinRun(fs.statSync(file).mtimeMs / 1000, cutoff);
      const counts = xmlCounts(file);
      const ran = ranInThisRun(fresh, task);
      const outcome = counts.failures + counts.errors > 0
        ? `FAILURES=${counts.failures} ERRORS=${counts.errors}`
        : `${counts.tests} tests, 0 failures`;
      console.log(`${pad(name.slice(name.lastIndexOf(".") + 1), 52)} ${pad(taskFor(directory), 26)} `
        + `${pad(task, 12)} ${pad(ran ? "YES" : "no — OTHER RUN", 10)} ${outcome}`);
    }
  }
  if (!matched) {
    console.log("  (no result file matches that name — the class has never run here, so there is no evidence"
      + " either way; that is not the same as passing)");
  }
}

function main(): void {
  const args = process.argv.slice(2);
  if (args[0] === "--performance-gate") return performanceGate(args.slice(1));
  if (args[0] === "--record-release-performance" && args.length === 6) {
    const result = recordReleasePerformance(ROOT, args[1], args[2], args[3], args[4], args[5]);
    console.log(`recorded release performance evidence: ${result.tests} tests from ${result.source}`);
    return;
  }
  const printCutoff = args[0] === "--print-cutoff";
  if (printCutoff) args.shift();
  let classPattern: string | undefined;
  if (args[0] === "--class") {
    args.shift();
    classPattern = args.shift();
    if (!classPattern) usage();
  }
  // The two modes answer different questions and neither subsumes the other, so asking for both is a mistake
  // rather than a preference. It used to honour whichever came first and drop the other silently.
  if (printCutoff && classPattern) {
    console.error("test-evidence: --print-cutoff and --class are separate modes; pass one.");
    process.exit(2);
  }
  const logPath = args[0];
  if (!logPath || !fs.existsSync(logPath) || !fs.statSync(logPath).isFile()) usage();

  const log = fs.readFileSync(logPath, "utf8");
  const cutoff = freshnessCutoff(logPath, log);
  if (printCutoff) {
    console.log(cutoff.epochSeconds.toFixed(6));
    return;
  }
  if (classPattern) {
    reportClass(classPattern, taskStates(log), cutoff);
    return;
  }

  console.log(`verdict:   ${lastVerdict(log) ?? "(none — the build did not finish; the log may still be buffering)"}`);
  const states = taskStates(log);
  console.log();
  console.log(`${pad("test task", 38)} ${pad("state", 12)}`);
  console.log(`${pad("--------------------------------------", 38)} ${pad("------------", 12)}`);
  for (const [task, state] of states) console.log(`${pad(task, 38)} ${pad(state, 12)}`);

  console.log();
  console.log("fresh results (this run's own executions):");
  let found = false;
  const withheld: string[] = [];
  for (const directory of resultDirectories(ROOT)) {
    const fresh = walkResultFiles(directory).filter(file => withinRun(fs.statSync(file).mtimeMs / 1000, cutoff));
    if (fresh.length === 0) continue;
    const relative = `${path.relative(ROOT, directory).split(path.sep).join("/")}/`;
    const task = taskFor(directory);
    const state = states.get(task) ?? "ABSENT";
    if (state !== "EXECUTED" && state !== "FAILED") {
      withheld.push(`  ${pad(relative, 46)} ${pad(state, 12)} ${String(fresh.length).padStart(5)} recent file(s) — NOT this run`);
      continue;
    }

    found = true;
    const counts = fresh.map(xmlCounts).reduce(
      (sum, next) => ({
        tests: sum.tests + next.tests,
        failures: sum.failures + next.failures,
        errors: sum.errors + next.errors,
      }),
      { tests: 0, failures: 0, errors: 0 },
    );
    const outcome = counts.failures + counts.errors > 0
      ? `FAILURES=${counts.failures} ERRORS=${counts.errors}`
      : "0 failures";
    console.log(`  ${pad(relative, 46)} ${pad(state, 12)} ${String(fresh.length).padStart(5)} classes ${String(counts.tests).padStart(6)} tests  ${outcome}`);
  }
  if (!found) console.log("  (none — this run executed no test task whose results survive, so NOTHING here is evidence)");

  if (withheld.length > 0) {
    console.log();
    console.log("withheld — recent files whose task this log does not report as executed:");
    for (const line of withheld) console.log(line);
    console.log("  (a reused log name, a parallel build, or an editor touching files; none of it is this run's evidence)");
  }

  console.log();
  console.log("reminder: UP-TO-DATE and FROM-CACHE are valid greens ONLY if the inputs are the ones you meant to test.");
  if (cutoff.basis === "birth-only") {
    console.log("warning:   the log carries no 'BUILD … in <time>' line, so the cutoff is the file's birth time alone.");
    console.log("           If this log path was reused, that birth time predates the run and the cutoff is too early.");
  }
}

// Only run as a command; a `require` from the test gets the pure helpers without printing a report.
if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`test-evidence: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}

module.exports = { withinRun, ranInThisRun, taskFor, lastVerdict, taskStates, performanceInputs, recordReleasePerformance, verifyReleasePerformance };
