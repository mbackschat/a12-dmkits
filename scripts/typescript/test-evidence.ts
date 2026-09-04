const fs = require("node:fs");
const path = require("node:path");

type TaskState = "EXECUTED" | "FROM-CACHE" | "UP-TO-DATE" | "NO-SOURCE" | "SKIPPED" | "FAILED";
type Counts = { tests: number; failures: number; errors: number };
type Cutoff = { epochSeconds: number; basis: "derived" | "birth-only" };

const ROOT = path.resolve(__dirname, "../..");
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
    + "<path-to-gradle-console-plain-log>");
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
    ? { epochSeconds: birth, basis: "birth-only" }
    : { epochSeconds: Math.max(birth, mtime - duration - margin), basis: "derived" };
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
      const fresh = fs.statSync(file).mtimeMs / 1000 > cutoff.epochSeconds;
      const counts = xmlCounts(file);
      const ran = ranInThisRun(fresh, task);
      const outcome = counts.failures + counts.errors > 0
        ? `FAILURES=${counts.failures} ERRORS=${counts.errors}`
        : `${counts.tests} tests, 0 failures`;
      console.log(`${pad(name.slice(name.lastIndexOf(".") + 1), 52)} ${pad(taskFor(directory), 26)} `
        + `${pad(task, 12)} ${pad(ran ? "YES" : "no — STALE", 10)} ${outcome}`);
    }
  }
  if (!matched) {
    console.log("  (no result file matches that name — the class has never run here, so there is no evidence"
      + " either way; that is not the same as passing)");
  }
}

function main(): void {
  const args = process.argv.slice(2);
  const printCutoff = args[0] === "--print-cutoff";
  if (printCutoff) args.shift();
  let classPattern: string | undefined;
  if (args[0] === "--class") {
    args.shift();
    classPattern = args.shift();
    if (!classPattern) usage();
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
    const fresh = walkResultFiles(directory).filter(file => fs.statSync(file).mtimeMs / 1000 > cutoff.epochSeconds);
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

module.exports = { ranInThisRun, taskFor, lastVerdict, taskStates };
