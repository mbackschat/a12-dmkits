const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

/**
 * Decide whether a Bash command is a REPEAT unfiltered Gradle lane, and if so demand that it state the
 * question it answers.
 *
 * WHY THIS IS A SCRIPT AND NOT A RULE. CLAUDE.md says "run TARGETED tests, never the full suite, for each
 * minor change", and "a red run does not create a debt for a green one". On 2026-09-06 a session held both
 * sentences in context, quoted them back accurately when challenged, and had still run the full suite four
 * times in a row — once justifiably, then three times to re-confirm classes a targeted command had already
 * cleared. Nothing fires at the moment the command is typed, which is the whole problem: the pre-commit
 * doc-guard hook caught the same session three times that day because it does fire there.
 *
 * HOW THIS DIFFERS FROM `scripts/lane-guard.sh`, which is adjacent and deliberately unwired. That guard asks
 * whether the change can REACH the lane; this one asks whether the lane has already RUN. They catch disjoint
 * failures: the 2026-09-06 waste was a lane the change could plainly reach, re-run for no new information, so
 * a reachability guard would have permitted every one of those runs.
 *
 * THE MODEL is deliberately soft, which is the lesson `lane-guard.sh`'s own header records after a cold review
 * found it refusing justified work: over-permitting costs one run, over-refusing costs trust and gets the
 * guard disabled. So this never refuses. It asks for `# why: <question>` in the command, which is satisfiable
 * in one edit and is exactly the discipline CLAUDE.md already states ("Before any command, state the question
 * it answers"). The first wide lane of a session is free, because that one is usually the legitimate
 * end-of-session backstop.
 */

type Judgement = {
  readonly decision: "allow" | "block";
  /** The wide lanes this command runs, in the order they appear. Empty unless it is an unfiltered lane run. */
  readonly lanes: readonly string[];
  readonly message?: string;
};

/** Options whose OPERAND is a value, never a task selector — `lane-guard.sh` defect #1, avoided here. */
const VALUE_OPTIONS: ReadonlySet<string> = new Set([
  "--tests", "--console", "-x", "--exclude-task", "--max-workers", "--project-dir", "-p",
  "--include-build", "--init-script", "-I", "-g", "--gradle-user-home", "--priority",
]);

/**
 * A lane is a task whose FINAL segment is exactly `test` or `check` — `:cli:test`, `:adapter:test`,
 * `:interpreter:check`, bare `test`. Everything narrower is left alone on purpose: `:docGuardTest` and its
 * siblings are prescribed WITHOUT `--tests` by the pre-commit hook's own fix line, and a guard that fights
 * another guard is the second defect `lane-guard.sh` records against itself.
 */
function isWideLane(token: string): boolean {
  if (token.startsWith("-")) return false;
  const segments = token.split(":");
  const last = segments[segments.length - 1];
  return last === "test" || last === "check";
}

/**
 * Does this command invoke Gradle at a COMMAND POSITION? Anchored to start-of-string or a shell operator
 * (`;` `|` `&` `(` `!` or a newline), never to a bare space — the same anchoring the sibling `pgrep` check in
 * this hook uses, and for the same reason: `echo \'run ./build.sh :cli:test next\'` merely MENTIONS the
 * command, and a guard that blocks a grep or a commit message about a lane is the false-positive class that
 * got `scripts/lane-guard.sh` unwired. This under-matches on purpose — `time ./build.sh …` is not seen — and
 * under-matching fails open, which is the safe direction.
 */
function invokesGradle(command: string): boolean {
  return /(^|[;|&(!\n])\s*(\.\/)?(build\.sh|gradlew)(\s|$)/.test(command);
}

/** The justification token. Any non-empty text after `# why:` counts — the point is to make you write it. */
function statesItsQuestion(command: string): boolean {
  return /#\s*why:\s*\S/i.test(command);
}

/**
 * The wide lanes a command runs, or `[]`. Returns `[]` when `--tests` is present anywhere: that is a filtered
 * run, which is the behaviour being encouraged and must never be nagged.
 */
function lanesOf(command: string): readonly string[] {
  if (!invokesGradle(command)) return [];
  const tokens = command.split(/\s+/).filter((t) => t.length > 0);
  const lanes: string[] = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (token === "--tests" || token.startsWith("--tests=")) return [];
    if (VALUE_OPTIONS.has(token)) {
      i += 1; // skip the operand, so `--console plain` cannot be read as a task
      continue;
    }
    if (isWideLane(token)) lanes.push(token);
  }
  return lanes;
}

/**
 * The judgement keys PER LANE, and that is the load-bearing decision.
 *
 * A first cut blocked the second unfiltered lane of the SESSION, which is wrong in the expensive direction:
 * CLAUDE.md's own end-of-session checkpoint is six distinct lanes (`:cli:check`, root `:test`,
 * `:adapter:test`, `:interpreter:check`, `:schemakit:test`, `:interpreter-bridge:check`), and a change can
 * genuinely need two of them. Making lanes 2..6 ask permission would fight the instruction this guard exists
 * to serve, and the cheapest way to satisfy a nagging guard is to skip the lane — so it would have bought
 * saved CPU with lost coverage. A DIFFERENT lane is therefore always free.
 *
 * What actually wasted the 2026-09-06 session was re-running the SAME lane for no new information. That is
 * the only shape blocked, and the narrower instrument it points at (`--tests`) is never blocked at all.
 */
function judge(command: string, priorLanes: readonly string[]): Judgement {
  const lanes = lanesOf(command);
  if (lanes.length === 0) return { decision: "allow", lanes: [] };
  const repeats = lanes.filter((lane) => priorLanes.includes(lane));
  if (repeats.length === 0) return { decision: "allow", lanes };
  if (statesItsQuestion(command)) return { decision: "allow", lanes };
  return {
    decision: "block",
    lanes,
    message:
      `Blocked: ${repeats.join(" ")} already ran UNFILTERED in this session, and nothing in this command `
      + `narrows it. A red run does not create a debt for a green one — that run already reported every other `
      + `class, and only your fix's blast radius can invalidate it. Prefer the narrowest command that can `
      + `discriminate: --tests '*TheClass*' (plus -Pfast to drop the guard prerequisites --tests does not `
      + `filter), which this guard never blocks. A DIFFERENT lane is never blocked either, so an end-of-session `
      + `checkpoint across several lanes runs untouched. If you do need this same lane again — a blast radius `
      + `no filter covers, or a regression backstop after many commits — say so and it runs: `
      + `append  # why: <the question this run answers>`,
  };
}

// ---- session state; losing it is harmless, which is why it lives in the OS temp dir and not in the repo ----

function stateFile(sessionId: string): string {
  const safe = sessionId.replace(/[^A-Za-z0-9_-]/g, "_") || "unknown";
  return path.join(os.tmpdir(), "a12-gradle-lane-guard", `${safe}.json`);
}

function readPriorLanes(file: string): readonly string[] {
  try {
    const parsed: unknown = JSON.parse(fs.readFileSync(file, "utf8"));
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { lanes?: unknown }).lanes)) {
      return (parsed as { lanes: unknown[] }).lanes.filter((l): l is string => typeof l === "string");
    }
  } catch {
    // No state, unreadable state, corrupt state — all mean "nothing has run yet". Never block on a read.
  }
  return [];
}

function recordLanes(file: string, lanes: readonly string[]): void {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const merged = Array.from(new Set([...readPriorLanes(file), ...lanes]));
    fs.writeFileSync(file, `${JSON.stringify({ lanes: merged }, null, 2)}\n`);
  } catch {
    // A write failure degrades the guard to "first run is always free". That is the safe direction.
  }
}

async function main(): Promise<void> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(Buffer.from(chunk));

  let command = "";
  let sessionId = "";
  try {
    const hook = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    command = String(hook?.tool_input?.command ?? "");
    sessionId = String(hook?.session_id ?? "");
  } catch {
    return; // A payload we cannot parse is never a reason to block a command.
  }
  if (command === "") return;

  const file = stateFile(sessionId);
  const verdict = judge(command, readPriorLanes(file));
  if (verdict.decision === "block") {
    // The sentinel, not the exit code, is what blocks — so a missing Node or a crashed guard fails OPEN.
    process.stdout.write(`BLOCK:${verdict.message}\n`);
    return;
  }
  if (verdict.lanes.length > 0) recordLanes(file, verdict.lanes);
}

module.exports = { judge, lanesOf, isWideLane, invokesGradle, statesItsQuestion };

if (require.main === module) {
  main().catch(() => {
    // Fail open, silently: a guard that breaks the tool it guards is worse than no guard.
  });
}
