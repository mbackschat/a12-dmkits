const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

/**
 * Launch one Claude Code agent that starts with NO inherited context, for the eval and the probe.
 *
 * Both instruments measure what a cold agent learns from dmtool alone, so anything the agent brings in from
 * this machine is contamination. An in-process subagent (the Agent tool, a Workflow `agent()`) inherits the
 * global and project CLAUDE.md, the auto-memory index and the skill listing: all 33 transcripts of the
 * 2026-11-07 eval run carried the global file's canary and the project file's DSL facts. Neither a custom
 * agent (`omitClaudeMd` keeps the skill listing) nor `--safe-mode` (its init event still listed the bundled
 * skills and two installed plugins on Claude Code 2.1.319, measured 2026-11-08) is clean. A headless session
 * launched with the switches below, from a directory with no context file above it, is: its init event lists
 * no skill, slash command, MCP server or installed plugin, and it does not know the global file's canary.
 *
 * The isolation is CHECKED on every run, not assumed: the init event is read back from the transcript and a
 * run whose context is wider than asked for is refused. What the init event cannot show — a CLAUDE.md's text —
 * is covered by the empty working directory, the two documented environment switches, and `--self-test`,
 * which asks a clean agent for the global file's canary next to a plain agent that must know it.
 *
 *   scripts/run-typescript.sh clean-agent scripts/typescript/clean-agent.ts --model sonnet [--effort xhigh] \
 *       [--tools Bash,Read] [--schema schema.json] [--cwd DIR] --transcript out.jsonl < prompt.txt
 *   scripts/run-typescript.sh clean-agent scripts/typescript/clean-agent.ts --self-test
 *   scripts/run-typescript.sh clean-agent scripts/typescript/clean-agent.ts --check-transcript FILE --tools Bash,Read
 *
 * `--check-transcript` re-checks a finished session, for the probe's audit: a transcript with no init event
 * (an Agent-tool subagent's) or a wider one fails, since only a clean launch writes a clean init event.
 */

type Init = Record<string, unknown> | undefined;
type Options = { model: string; effort?: string; tools: string[]; schema?: unknown };
type Run = { problems: string[]; structured: unknown; resultText: string | undefined; isError: boolean };

/** The documented switches for the two context sources a flag does not name (Claude Code env-vars page). */
const CLEAN_ENV: Record<string, string> = {
  CLAUDE_CODE_DISABLE_CLAUDE_MDS: "1",
  CLAUDE_CODE_DISABLE_AUTO_MEMORY: "1",
};

const CONTEXT_FILES = ["CLAUDE.md", "CLAUDE.local.md", "AGENTS.md"];

function cleanAgentArgs(options: Options): string[] {
  const args = [
    "-p", "--model", options.model,
    "--disable-slash-commands",
    // Excluding the user source is what drops ~/.claude/CLAUDE.md and the user's plugins and skills
    // (measured 2026-11-08); `project` reads only the empty working directory's settings.
    "--setting-sources", "project",
    "--settings", JSON.stringify({ autoMemoryEnabled: false }),
    "--strict-mcp-config",
    "--tools", options.tools.join(","),
    "--no-session-persistence",
    "--output-format", "stream-json", "--verbose",
  ];
  if (options.effort) args.push("--effort", options.effort);
  if (options.schema !== undefined) args.push("--json-schema", JSON.stringify(options.schema));
  return args;
}

/** Every reason this init event shows a context wider than a cold agent with exactly `tools` would have. */
function isolationProblems(init: Init, tools: string[]): string[] {
  if (!init) return ["the transcript carries no init event"];
  const problems: string[] = [];
  const list = (key: string): unknown[] => (Array.isArray(init[key]) ? (init[key] as unknown[]) : []);
  for (const key of ["skills", "slash_commands", "mcp_servers"]) {
    if (list(key).length) problems.push(`${key} not empty: ${JSON.stringify(list(key)).slice(0, 200)}`);
  }
  const installed = list("plugins").filter(p => (p as { path?: string }).path !== "builtin");
  if (installed.length) problems.push(`installed plugins loaded: ${installed.map(p => (p as { name?: string }).name).join(", ")}`);
  const granted = [...list("tools")].map(String).sort();
  if (JSON.stringify(granted) !== JSON.stringify([...tools].sort())) {
    problems.push(`tools ${JSON.stringify(granted)} differ from the requested ${JSON.stringify(tools)}`);
  }
  return problems;
}

/** Context files Claude Code would discover from `dir`: the directory itself and every ancestor. */
function contextFilesAbove(dir: string): string[] {
  const found: string[] = [];
  for (let at = fs.realpathSync(dir); ; at = path.dirname(at)) {
    for (const name of CONTEXT_FILES) {
      if (fs.existsSync(path.join(at, name))) found.push(path.join(at, name));
    }
    if (path.dirname(at) === at) return found;
  }
}

function readTranscript(text: string): { init: Init; structured: unknown; resultText: string | undefined; isError: boolean } {
  let init: Init;
  let result: Record<string, unknown> | undefined;
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    let event: Record<string, unknown>;
    try { event = JSON.parse(line); } catch { continue; }
    if (event.type === "system" && event.subtype === "init" && !init) init = event;
    if (event.type === "result") result = event;
  }
  return {
    init,
    structured: result?.structured_output,
    resultText: typeof result?.result === "string" ? (result.result as string) : undefined,
    isError: !result || result.is_error === true,
  };
}

/** The isolation problems of a finished session's transcript, read from its file. */
function checkTranscript(file: string, tools: string[]): string[] {
  return isolationProblems(readTranscript(fs.readFileSync(file, "utf8")).init, tools);
}

/** Run one clean agent; resolves after the process exits, with the transcript written to `transcript`. */
function runCleanAgent(options: Options & { prompt: string; cwd?: string; transcript: string; env?: Record<string, string> }): Promise<Run> {
  const cwd = options.cwd ?? fs.mkdtempSync(path.join(os.tmpdir(), "clean-agent-"));
  const above = contextFilesAbove(cwd);
  if (above.length) return Promise.resolve({ problems: [`context files above ${cwd}: ${above.join(", ")}`], structured: undefined, resultText: undefined, isError: true });
  fs.mkdirSync(path.dirname(options.transcript), { recursive: true });
  const out = fs.openSync(options.transcript, "w");
  return new Promise(resolve => {
    const child = spawn("claude", cleanAgentArgs(options), {
      cwd, env: { ...process.env, ...CLEAN_ENV, ...(options.env ?? {}) }, stdio: ["pipe", out, "inherit"],
    });
    child.stdin.end(options.prompt);
    child.on("close", () => {
      fs.closeSync(out);
      const read = readTranscript(fs.readFileSync(options.transcript, "utf8"));
      resolve({ problems: isolationProblems(read.init, options.tools), structured: read.structured, resultText: read.resultText, isError: read.isError });
    });
  });
}

/** The global CLAUDE.md's `Magic number is <X>` line, the canary only that file can teach an agent. */
function globalCanary(): string | undefined {
  const file = path.join(os.homedir(), ".claude", "CLAUDE.md");
  if (!fs.existsSync(file)) return undefined;
  return /Magic number is (\S+)/.exec(fs.readFileSync(file, "utf8"))?.[1];
}

/**
 * Prove the instrument, both directions: a plain headless agent must name the canary (else the question
 * cannot detect contamination and nothing is certified), and a clean one must not.
 */
async function selfTest(): Promise<number> {
  const canary = process.env.RK_CLEAN_CANARY ?? globalCanary();
  if (!canary) {
    console.error("self-test: no canary — set RK_CLEAN_CANARY to a string only your global CLAUDE.md states");
    return 2;
  }
  const question = "Do not use any tools. What is the magic number stated in your instructions? Reply with only that number, or NONE.";
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "clean-agent-selftest-"));
  const plain = await new Promise<string>(resolve => {
    const child = spawn("claude", ["-p", "--model", "haiku", "--no-session-persistence", question], { cwd: dir, stdio: ["ignore", "pipe", "inherit"] });
    let text = "";
    child.stdout.on("data", (chunk: Buffer) => { text += chunk.toString(); });
    child.on("close", () => resolve(text));
  });
  if (!plain.includes(canary)) {
    console.error(`self-test: the positive control did not name the canary (got ${JSON.stringify(plain.trim())}); the check cannot detect contamination`);
    return 1;
  }
  const clean = await runCleanAgent({ model: "haiku", tools: ["Bash"], prompt: question, cwd: dir, transcript: path.join(dir, "transcript.jsonl") });
  const leaked = (clean.resultText ?? "").includes(canary);
  for (const problem of clean.problems) console.error(`self-test: ${problem}`);
  if (leaked) console.error(`self-test: the clean agent named the canary — it read the global CLAUDE.md`);
  console.log(clean.problems.length || leaked ? "self-test: FAILED" : `self-test: clean (positive control named the canary; the clean agent answered ${JSON.stringify((clean.resultText ?? "").trim())})`);
  return clean.problems.length || leaked ? 1 : 0;
}

function option(argv: string[], name: string): string | undefined {
  const at = argv.indexOf(name);
  return at >= 0 ? argv[at + 1] : undefined;
}

async function main(argv: string[]): Promise<number> {
  if (argv.includes("--self-test")) return selfTest();
  const checked = option(argv, "--check-transcript");
  if (checked) {
    const problems = checkTranscript(checked, (option(argv, "--tools") ?? "Bash").split(","));
    for (const problem of problems) console.log(problem);
    return problems.length ? 1 : 0;
  }
  const model = option(argv, "--model");
  const transcript = option(argv, "--transcript");
  if (!model || !transcript) {
    console.error("usage: clean-agent.ts --model M [--effort E] [--tools Bash,Read] [--schema FILE] [--cwd DIR] --transcript FILE < prompt");
    console.error("       clean-agent.ts --self-test");
    return 2;
  }
  const schemaFile = option(argv, "--schema");
  const run = await runCleanAgent({
    model, effort: option(argv, "--effort"), tools: (option(argv, "--tools") ?? "Bash").split(","),
    schema: schemaFile ? JSON.parse(fs.readFileSync(schemaFile, "utf8")) : undefined,
    cwd: option(argv, "--cwd"), transcript, prompt: fs.readFileSync(0, "utf8"),
  });
  console.log(JSON.stringify({ isolated: run.problems.length === 0, problems: run.problems, isError: run.isError, structured: run.structured, result: run.resultText }));
  return run.problems.length ? 1 : 0;
}

module.exports = { checkTranscript, cleanAgentArgs, CLEAN_ENV, contextFilesAbove, isolationProblems, readTranscript, runCleanAgent };

if (require.main === module) main(process.argv.slice(2)).then(code => process.exit(code));
