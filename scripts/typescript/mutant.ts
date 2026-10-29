const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

/**
 * Plant one mutant, run ONLY its control class, report the verdict, restore — or do that for a batch.
 *
 * WHY THIS EXISTS, measured. Mutation testing is the most expensive thing a review does, and nearly all of that
 * cost is self-inflicted: the same mutant, same verdict, took 3 m 17 s run as `-Pfast :adapter:test` and 18 s
 * filtered to the class that should catch it. Telling reviewers to filter did not stick, so this makes the cheap
 * path the easy one. Every refusal below exists because its absence once produced a WRONG VERDICT, and each
 * names the date it was measured; a wrong verdict reads as a finding, which is why none of them is a warning.
 */

const ROOT = path.resolve(__dirname, "../..");

type Mutant = { readonly file: string; readonly old: string; readonly new: string; readonly filter: string;
  readonly lane?: string };
type Verdict = "killed" | "survived" | "no-verdict";
type Outcome = { readonly verdict: Verdict; readonly reason: string };
type Counts = { readonly tests: number; readonly failures: number; readonly errors: number };
type Refusal = { readonly refusal: string };

const USAGE = `usage: scripts/mutant.sh <file> <old-text> <new-text> <test-filter> [gradle-lane]
       scripts/mutant.sh --batch <mutants.json>

The lane is INFERRED from the mutated file's module and printed; pass a 5th argument to override. Name the ONE
lane that owns the control: the first version of this script ran two lanes and timed out, which is the very
cost it exists to remove. The filter names the control that SHOULD catch the mutant. If you cannot name one,
stop — that is the finding, and writing the control first is what makes the mutant cheap.

A batch file is a JSON array of {"file", "old", "new", "filter", "lane"?}. JSON rather than shell arguments,
because shell escaping ate a pattern twice and each time the unapplied mutant read SURVIVED. Every entry is
checked before the first one runs, each is restored before the next, and every run's log is kept.

EXIT: 0 = killed (the control fired). 1 = SURVIVED (report it). 2 = no verdict. A batch exits with its worst.`;

/**
 * The lane that owns a file's tests, inferred from its module.
 *
 * `:adapter:test` was the unconditional default once, so a mutant planted in core/ or cli/ reported "matched no
 * test" and had to be relaunched (measured twice, 2026-09-04). Root `:test` owns every module without its own
 * test source set (core, rulekit, modelkit, edit), which is why it is the fallback rather than an error. A
 * JS-only source set is refused: a jvmTest lane never compiles it, so every test passes and the mutant reads
 * SURVIVED, and a Kotlin Multiplatform module has no plain `test` task at all (measured 2026-09-12).
 */
function inferLane(file: string): { readonly lane: string } | Refusal {
  const rel = path.relative(ROOT, path.resolve(ROOT, file)).split(path.sep).join("/");
  for (const module of ["interpreter", "consistency"]) {
    if (rel.startsWith(`${module}/src/jsMain/`) || rel.startsWith(`${module}/src/jsTest/`)) {
      return { refusal: `REFUSING to guess a lane for a JS-only source set. :${module}:jvmTest never compiles `
        + `${rel}, so the mutant applies and every test passes — a false SURVIVED. Name the JS lane `
        + `(:${module}:jsNodeTest) explicitly as the 5th argument.` };
    }
  }
  const lanes: ReadonlyArray<readonly [string, string]> = [
    ["adapter/", ":adapter:test"], ["cli/", ":cli:test"], ["consistency-gate/", ":consistency-gate:test"],
    ["consistency/", ":consistency:jvmTest"], ["interpreter-bridge/", ":interpreter-bridge:test"],
    ["interpreter/", ":interpreter:jvmTest"], ["schemakit/", ":schemakit:test"],
  ];
  return { lane: lanes.find(([prefix]) => rel.startsWith(prefix))?.[1] ?? ":test" };
}

/**
 * The mutated text, or why it cannot be planted.
 *
 * An absent anchor would silently not apply, and an unapplied mutant reports SURVIVED. An AMBIGUOUS one is the
 * same defect hiding better: the replacement takes the first occurrence, the mutation lands on a site nobody
 * meant, and SURVIVED reads as a finding about the test (measured 2026-09-18, a `forEach` anchor that matched
 * two indexes in one file). The refusal names every starting line, counted by offset because the second site
 * is usually a prefix match that a line comparison misses.
 */
function plant(text: string, old: string, replacement: string): { readonly text: string } | Refusal {
  if (old === replacement) return { refusal: "old and new text are identical — that mutant tests nothing" };
  const lines: number[] = [];
  for (let at = text.indexOf(old); at !== -1; at = text.indexOf(old, at + 1)) {
    lines.push(text.slice(0, at).split("\n").length);
  }
  if (lines.length === 0) {
    return { refusal: "the text to replace is NOT in the file — an unapplied mutant reports SURVIVED, which reads "
      + `as a finding. Text was: ${old.slice(0, 160)}` };
  }
  if (lines.length > 1) {
    return { refusal: `the text to replace appears ${lines.length} times, so this would mutate the FIRST and report `
      + `a verdict about the wrong site. Extend the anchor until it is unique. It starts at lines: `
      + lines.slice(0, 8).join(", ") };
  }
  const at = text.indexOf(old);
  return { text: text.slice(0, at) + replacement + text.slice(at + old.length) };
}

/**
 * `-Pfast` drops the guard prerequisites, which is the whole speed win — and skips every guard task outright, so
 * naming one as the lane under `-Pfast` runs nothing and reports SURVIVED. A guard lane runs without it.
 */
function fastFlag(lane: string): readonly string[] {
  return lane.endsWith("GuardTest") ? [] : ["-Pfast"];
}

/** Where a Gradle test task writes its JUnit XML: `:consistency:jvmTest` → `consistency/build/test-results/jvmTest`. */
function resultsDir(lane: string): string {
  const segments = lane.split(":").filter((s) => s.length > 0);
  const task = segments.pop() ?? "test";
  return path.join(ROOT, ...segments, "build", "test-results", task);
}

/** A Gradle `--tests` pattern as a matcher for the result file it produces, `TEST-<class>.xml`. */
function resultFileMatcher(filter: string): RegExp {
  const pattern = filter.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*");
  return new RegExp(`^TEST-${pattern}\\.xml$`);
}

function countsIn(xml: string): Counts {
  const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
  const read = (name: string) => Number(suite.match(new RegExp(`\\b${name}="(\\d+)"`))?.[1] ?? 0);
  return { tests: read("tests"), failures: read("failures"), errors: read("errors") };
}

const isRed = (c: Counts) => c.failures > 0 || c.errors > 0;

/**
 * The verdict a finished run supports, from its log and the result files it wrote.
 *
 * A kill needs a FRESH FAILING RESULT, not merely a failed build. The shell version read every failed build as
 * killed, so a mutant that did not compile — or a prerequisite that broke — read as the control catching it,
 * the false verdict in the direction that confirms what the operator hoped. Each log signal below is a run that
 * ran nothing: a lane that is not a Test task rejects `--tests` while configuring (measured 2026-09-12, a review
 * packet naming `:consistency:check`); a filter that selects nothing prints Gradle's own phrase or the selection
 * guard's; a dedicated task hosts the control and the build names it (measured 2026-09-04); and a Kotlin/JS lane
 * passes an empty selection silently (measured 2026-09-24), which the fresh-result requirement catches.
 */
function judge(buildSucceeded: boolean, log: string, fresh: readonly Counts[]): Outcome {
  if (log.includes("Unknown command-line option '--tests'")) {
    return { verdict: "no-verdict", reason: "the lane is not a Test task, so it rejected --tests before running "
      + "anything. Name the Test task itself (for a Kotlin Multiplatform module :<module>:jvmTest or "
      + ":<module>:jsNodeTest, never :<module>:check)." };
  }
  if (/No tests found for given includes|selected no test for/.test(log)) {
    return { verdict: "no-verdict", reason: "the filter matched no test in this lane — a typo or the wrong lane" };
  }
  // Located by indexOf, never by a regex opening with `[^\n]*`: unanchored, that scan is quadratic in the line, and a
  // failing map assertion prints millions of characters on one (2.4 s at 40,000 measured 2026-10-29; hours at 3.2M).
  const hint = log.indexOf("deliberately excludes");
  const blank = hint < 0 ? -1 : log.slice(hint).search(/\n[ \t\r]*\n/);
  const routing = hint < 0 ? undefined
    : log.slice(log.lastIndexOf("\n", hint) + 1, blank < 0 ? log.length : hint + blank);
  if (routing) {
    return { verdict: "no-verdict", reason: `this lane does not host that control; the build names the task that `
      + `does — re-run with it as the lane:\n${routing.trim()}` };
  }
  const ran = fresh.filter((c) => c.tests > 0);
  if (ran.some(isRed)) return { verdict: "killed", reason: "the control fired" };
  if (!buildSucceeded) {
    return { verdict: "no-verdict", reason: ran.length === 0
      ? "the build failed before any test recorded a result — a mutant that does not compile, or a failing "
        + "prerequisite. That is not the control catching it; read the log."
      : "the build failed while every fresh test result is green, so the failure is not the control's; read the log" };
  }
  if (ran.length === 0) {
    return { verdict: "no-verdict", reason: "the lane passed but no fresh result records a test — a filter that "
      + "selected nothing. On a Kotlin/JS lane end the pattern with a wildcard (`*Name*`) or name the class fully "
      + "qualified." };
  }
  return { verdict: "survived", reason: "the control did not catch it" };
}

/** A batch's exit code is its worst verdict: an incomplete batch is no verdict, even beside a survivor. */
function exitCodeFor(verdicts: readonly Verdict[]): number {
  if (verdicts.includes("no-verdict")) return 2;
  return verdicts.includes("survived") ? 1 : 0;
}

function parseBatch(json: string): readonly Mutant[] | Refusal {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (error) {
    return { refusal: `the batch file is not JSON: ${(error as Error).message}` };
  }
  if (!Array.isArray(parsed) || parsed.length === 0) {
    return { refusal: "the batch file must be a non-empty JSON array of mutants" };
  }
  for (const [i, entry] of parsed.entries()) {
    for (const key of ["file", "old", "new", "filter"]) {
      if (typeof entry?.[key] !== "string") return { refusal: `batch entry ${i}: "${key}" must be a string` };
    }
    if (entry.lane !== undefined && typeof entry.lane !== "string") {
      return { refusal: `batch entry ${i}: "lane", when given, must be a string` };
    }
  }
  return parsed as readonly Mutant[];
}

/**
 * Put the original bytes back and PROVE they are back, because a restore nobody checks is how a mutant ships.
 * The comparison is against our own copy, never git: `git checkout --` to revert a mutant destroyed uncommitted
 * work three times in one session (2026-08-16), and git knows nothing about an untracked file.
 */
function restoreVerified(file: string, original: Buffer): void {
  fs.writeFileSync(file, original);
  if (!fs.readFileSync(file).equals(original)) {
    throw new Error(`RESTORE FAILED: ${file} does not hold its original bytes after the write`);
  }
}

function git(...args: readonly string[]): number {
  return spawnSync("git", args, { cwd: ROOT, stdio: "ignore" }).status ?? 2;
}

function resultDirs(): readonly string[] {
  const modules = [ROOT, ...fs.readdirSync(ROOT, { withFileTypes: true })
    .filter((e: { isDirectory(): boolean }) => e.isDirectory()).map((e: { name: string }) => path.join(ROOT, e.name))];
  return modules.map((m) => path.join(m, "build", "test-results")).filter((d) => fs.existsSync(d));
}

function xmlFilesUnder(dir: string): readonly string[] {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { recursive: true }).map(String)
    .filter((f: string) => /(^|[\\/])TEST-[^\\/]*\.xml$/.test(f)).map((f: string) => path.join(dir, f));
}

/** Refusals that need no run: a missing or dirty file, an anchor that cannot be planted, a control already red. */
function preflight(mutant: Mutant, lane: string): string | undefined {
  const file = path.resolve(ROOT, mutant.file);
  if (!fs.existsSync(file)) return `no such file: ${mutant.file}`;
  if (git("diff", "--quiet", "--", file) !== 0 || git("diff", "--cached", "--quiet", "--", file) !== 0) {
    return `REFUSING — ${mutant.file} has uncommitted changes. Commit first: reverting a mutant over uncommitted `
      + "work is how three edits were lost in one session.";
  }
  const planted = plant(fs.readFileSync(file, "utf8"), mutant.old, mutant.new);
  if ("refusal" in planted) return planted.refusal;
  // A control that is ALREADY red reports every mutant killed, and the verdict reads as proof (measured
  // 2026-09-25: an unfinished mirror test "killed" a mutant its green form let survive). The kills this tool
  // causes delete their own red files, so a red one still here is the operator's own last run.
  const matcher = resultFileMatcher(mutant.filter);
  const red = xmlFilesUnder(resultsDir(lane))
    .filter((f) => matcher.test(path.basename(f)) && isRed(countsIn(fs.readFileSync(f, "utf8"))));
  if (red.length > 0) {
    return `REFUSING — the control's last recorded run in ${lane} is red, so any mutant would read killed:\n  `
      + red.join("\n  ") + "\nRun the control green on the unmutated source first.";
  }
  return undefined;
}

let interrupted = false;

function runOne(mutant: Mutant, lane: string, logDir: string, index: number): Outcome {
  const file = path.resolve(ROOT, mutant.file);
  const original: Buffer = fs.readFileSync(file);
  const planted = plant(original.toString("utf8"), mutant.old, mutant.new);
  if ("refusal" in planted) return { verdict: "no-verdict", reason: planted.refusal };
  const backup = path.join(logDir, `${index}-${path.basename(file)}.orig`);
  fs.writeFileSync(backup, original);
  const started = Date.now();
  try {
    fs.writeFileSync(file, planted.text);
    // Prove it landed by bytes, never `git diff`, which is silent for an untracked file and once reported
    // every mutant in a just-created guard class unchanged (2026-09-13).
    if (fs.readFileSync(file).equals(original)) {
      return { verdict: "no-verdict", reason: "the file is unchanged after the edit" };
    }
    console.log(`mutant ${index} applied to ${mutant.file} (original kept at ${backup}); lane ${lane}`);
    const log = path.join(logDir, `${index}-${path.basename(file)}.log`);
    const fd = fs.openSync(log, "w");
    // `--rerun` is not optional: a mutated file is an input only of the tasks that declare it, so a lane that
    // Gradle finds up-to-date runs nothing and reports SURVIVED (measured 2026-09-12 on docs/MVK-LEDGER.md).
    const run = spawnSync("./build.sh", ["--console=plain", ...fastFlag(lane), lane, "--rerun", "--tests",
      mutant.filter], { cwd: ROOT, stdio: ["ignore", fd, fd] });
    fs.closeSync(fd);
    console.log(`  log: ${log}`);
    if (interrupted) return { verdict: "no-verdict", reason: "interrupted" };
    const fresh = xmlFilesUnder(resultsDir(lane)).filter((f) => fs.statSync(f).mtimeMs >= started)
      .map((f) => countsIn(fs.readFileSync(f, "utf8")));
    return judge(run.status === 0, fs.readFileSync(log, "utf8"), fresh);
  } finally {
    restoreVerified(file, original);
    fs.rmSync(backup);
    // A killed mutant leaves a FAILING result file behind, and those persist; left there, every later "are you
    // about to commit on red?" check cries wolf. The experiment cleans up after itself, in every module.
    for (const xml of resultDirs().flatMap(xmlFilesUnder)) {
      if (fs.statSync(xml).mtimeMs >= started && isRed(countsIn(fs.readFileSync(xml, "utf8")))) fs.rmSync(xml);
    }
  }
}

function report(mutant: Mutant, outcome: Outcome): void {
  switch (outcome.verdict) {
    case "killed":
      console.log(`killed ✓  — ${mutant.filter} caught it`);
      break;
    case "survived":
      console.log(`SURVIVED ✗ — ${mutant.filter} did NOT catch it.`);
      console.log("            Before reporting this, confirm no OTHER control covers it; only that needs a wider run.");
      break;
    case "no-verdict":
      console.error(`mutant: no verdict — ${outcome.reason}`);
      break;
  }
}

function main(argv: readonly string[]): number {
  let mutants: readonly Mutant[];
  if (argv[0] === "--batch" && argv.length === 2) {
    const parsed = parseBatch(fs.readFileSync(argv[1], "utf8"));
    if ("refusal" in parsed) {
      console.error(`mutant: ${parsed.refusal}`);
      return 2;
    }
    mutants = parsed;
  } else if (argv.length === 4 || argv.length === 5) {
    mutants = [{ file: argv[0], old: argv[1], new: argv[2], filter: argv[3], lane: argv[4] }];
  } else {
    console.error(USAGE);
    return 2;
  }
  const lanes: string[] = [];
  for (const [i, mutant] of mutants.entries()) {
    const label = `mutant${mutants.length > 1 ? ` ${i + 1}` : ""}`;
    const inferred = mutant.lane === undefined ? inferLane(mutant.file) : { lane: mutant.lane };
    if ("refusal" in inferred) {
      console.error(`${label}: ${inferred.refusal}`);
      return 2;
    }
    const refused = preflight(mutant, inferred.lane);
    if (refused !== undefined) {
      console.error(`${label}: ${refused}`);
      return 2;
    }
    if (mutant.lane === undefined) {
      console.log(`${label}: lane inferred from the file's module: ${inferred.lane} (name a lane to override)`);
    }
    lanes.push(inferred.lane);
  }
  // Logs are kept, not deleted: a verdict someone doubts is settled by reading the run that produced it.
  const logDir = fs.mkdtempSync(path.join(os.tmpdir(), "a12-mutant-"));
  process.on("SIGINT", () => { interrupted = true; });
  process.on("SIGTERM", () => { interrupted = true; });
  const verdicts: Verdict[] = [];
  for (const [i, mutant] of mutants.entries()) {
    if (interrupted) break;
    const outcome = runOne(mutant, lanes[i], logDir, i + 1);
    report(mutant, outcome);
    verdicts.push(outcome.verdict);
  }
  if (mutants.length > 1) {
    console.log(`\nbatch: ${verdicts.length} of ${mutants.length} run — `
      + (["killed", "survived", "no-verdict"] as const).map((v) => `${verdicts.filter((x) => x === v).length} ${v}`)
        .join(", ") + `; logs in ${logDir}`);
  }
  return interrupted ? 2 : exitCodeFor(verdicts);
}

if (require.main === module) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    // Any unplanned stop is no verdict, never the SURVIVED code: the shell version's first red-control probe
    // aborted under `set -e` on every green control and reported each mutant as surviving unrun (2026-09-25).
    console.error(`mutant: stopped by an unexpected error — no verdict: ${(error as Error).message}`);
    process.exitCode = 2;
  }
}

module.exports = { inferLane, plant, fastFlag, resultsDir, resultFileMatcher, countsIn, judge, exitCodeFor,
  parseBatch, restoreVerified };
