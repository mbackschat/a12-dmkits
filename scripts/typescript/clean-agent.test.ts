const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { checkTranscript, cleanAgentArgs, CLEAN_ENV, contextFilesAbove, isolationProblems, readTranscript, refusals, TurnFeeder, grantedTools, sessionPlan } = require("./clean-agent.ts");

/** The init event a clean run printed on Claude Code 2.1.319 (measured 2026-11-08), trimmed to the checked keys. */
const CLEAN_INIT = {
  type: "system", subtype: "init", tools: ["Bash"], skills: [], slash_commands: [], mcp_servers: [],
  plugins: [{ name: "cc-plugin-telemetry", path: "builtin", source: "cc-plugin-telemetry@builtin" }],
};

test("a clean init event passes", () => {
  assert.deepEqual(isolationProblems(CLEAN_INIT, ["Bash"]), []);
});

/**
 * Each row is one way the measured contamination reached an eval agent: the skill listing, a slash command,
 * an installed plugin (what --safe-mode still loaded), an MCP server, or a tool set wider than asked for.
 */
test("every inherited-context channel is refused", () => {
  const rows: Array<[string, Record<string, unknown>]> = [
    ["skills", { skills: ["run-dmtool-eval"] }],
    ["slash commands", { slash_commands: ["loop"] }],
    ["installed plugin", { plugins: [{ name: "a12-ticket-approval", path: "~/.claude/plugins/cache/a", source: "a@m" }] }],
    ["MCP server", { mcp_servers: [{ name: "docs", status: "connected" }] }],
    ["tool set", { tools: ["Bash", "Agent"] }],
  ];
  for (const [label, override] of rows) {
    const problems = isolationProblems({ ...CLEAN_INIT, ...override }, ["Bash"]);
    assert.equal(problems.length, 1, `${label}: ${problems.join("; ")}`);
  }
});

/** An Agent-tool subagent's transcript carries no init event, so the probe audit refuses it outright. */
test("a finished transcript is re-checked from its file", (t: { after(f: () => void): void }) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "clean-agent-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const clean = path.join(dir, "clean.jsonl");
  const inherited = path.join(dir, "inherited.jsonl");
  fs.writeFileSync(clean, JSON.stringify(CLEAN_INIT) + "\n");
  fs.writeFileSync(inherited, JSON.stringify({ type: "user", message: { content: "hi" } }) + "\n");
  assert.deepEqual(checkTranscript(clean, ["Bash"]), []);
  assert.deepEqual(checkTranscript(inherited, ["Bash"]), ["the transcript carries no init event"]);
});

test("a missing init event is refused rather than read as clean", () => {
  assert.deepEqual(isolationProblems(undefined, ["Bash"]), ["the transcript carries no init event"]);
});

test("a context file in the working directory or any ancestor is reported", (t: { after(f: () => void): void }) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "clean-agent-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const work = path.join(root, "a", "b");
  fs.mkdirSync(work, { recursive: true });
  assert.deepEqual(contextFilesAbove(work), []);
  fs.writeFileSync(path.join(root, "AGENTS.md"), "x");
  assert.deepEqual(contextFilesAbove(work), [path.join(fs.realpathSync(root), "AGENTS.md")]);
});

test("the launch arguments carry every isolation switch", () => {
  const args = cleanAgentArgs({ model: "sonnet", effort: "xhigh", tools: ["Bash", "Read"], schema: { type: "object" }, writable: ["/tmp/a", "/tmp/b"] });
  for (const flag of ["-p", "--disable-slash-commands", "--no-session-persistence", "--strict-mcp-config"]) {
    assert.ok(args.includes(flag), flag);
  }
  assert.equal(args[args.indexOf("--setting-sources") + 1], "project");
  assert.deepEqual(JSON.parse(args[args.indexOf("--settings") + 1]), {
    autoMemoryEnabled: false,
    sandbox: { enabled: true, autoAllowBashIfSandboxed: true, allowUnsandboxedCommands: false },
    permissions: { deny: ["Edit(~/**)", "Write(~/**)"] },
  });
  assert.equal(args[args.indexOf("--permission-mode") + 1], "bypassPermissions");
  assert.deepEqual(args.flatMap((a: string, i: number) => (a === "--add-dir" ? [args[i + 1]] : [])), ["/tmp/a", "/tmp/b"]);
  assert.equal(args[args.indexOf("--tools") + 1], "Bash,Read");
  assert.equal(args[args.indexOf("--input-format") + 1], "stream-json");
  assert.equal(args[args.indexOf("--effort") + 1], "xhigh");
  assert.deepEqual(JSON.parse(args[args.indexOf("--json-schema") + 1]), { type: "object" });
  assert.equal(CLEAN_ENV.CLAUDE_CODE_DISABLE_CLAUDE_MDS, "1");
  assert.equal(CLEAN_ENV.CLAUDE_CODE_DISABLE_AUTO_MEMORY, "1");
});

test("the transcript yields the init event, the structured output and the result", () => {
  const lines = [
    JSON.stringify(CLEAN_INIT),
    JSON.stringify({ type: "assistant", message: { content: [{ type: "text", text: "working" }] } }),
    JSON.stringify({ type: "result", subtype: "success", is_error: false, result: "done", structured_output: { ok: true } }),
  ];
  const read = readTranscript(lines.join("\n") + "\n");
  assert.deepEqual(read.init, CLEAN_INIT);
  assert.deepEqual(read.structured, { ok: true });
  assert.equal(read.resultText, "done");
  assert.equal(read.isError, false);
});

/**
 * A probe session is several analyst turns, and the agent must not see turn n+1 before it finished turn n
 * (probe/agent-preamble.md). So the feeder sends one user message per `result` event and closes the input
 * after the last turn's result, never earlier.
 */
test("the turn feeder sends the next turn only after the previous turn's result", () => {
  const sent: string[] = [];
  let ended = false;
  const feeder = new TurnFeeder(["first", "second"], (line: string) => sent.push(line), () => { ended = true; });
  feeder.start();
  assert.deepEqual(sent.map(line => JSON.parse(line).message.content), ["first"]);
  feeder.onLine(JSON.stringify({ type: "assistant", message: { content: [] } }));
  assert.equal(sent.length, 1, "an assistant event is not the end of a turn");
  feeder.onLine(JSON.stringify({ type: "result", subtype: "success" }));
  assert.deepEqual(sent.map(line => JSON.parse(line)), [
    { type: "user", message: { role: "user", content: "first" } },
    { type: "user", message: { role: "user", content: "second" } },
  ]);
  assert.equal(ended, false);
  feeder.onLine(JSON.stringify({ type: "result", subtype: "error_during_execution" }));
  assert.equal(ended, true, "the input closes after the last turn's result, even a failed one");
  assert.equal(sent.length, 2);
});

/**
 * A clean session has no allow rules, so whatever the OS sandbox does not cover is refused; on 2026-11-08 six
 * probe sessions ran with every dmtool call refused and still exited 0. A refusal is therefore an instrument
 * failure that must surface, never a quiet turn of the measured agent.
 */
test("a call the harness refused is reported, a failing tool call is not", (t: { after(f: () => void): void }) => {
  const result = (content: string, is_error = true) => JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", is_error, content }] } });
  const lines = [
    result("This command requires approval"),
    result("Claude requested permissions to read from /tmp/s/SKILL.md, but you haven't granted it yet."),
    result("Output redirection to '/tmp/s/work/R.md' needs approval."),
    result("<tool_use_error>File is in a directory that is denied by your permission settings.</tool_use_error>"),
    JSON.stringify({ type: "system", subtype: "permission_denied", tool_name: "Bash", decision_reason: "Contains brace with quote character (expansion obfuscation)", message: "Contains brace with quote character (expansion obfuscation)" }),
    result("dmtool: rule check rejected the condition"),
    result("Exit code 1"),
  ];
  assert.equal(refusals(lines.join("\n")).length, 5);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "clean-agent-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, "t.jsonl");
  fs.writeFileSync(file, [JSON.stringify(CLEAN_INIT), lines[0]].join("\n") + "\n");
  assert.deepEqual(checkTranscript(file, ["Bash"]), ["the harness refused a tool call: This command requires approval"]);
});

/** `--json-schema` makes Claude Code add its own StructuredOutput tool; it is the answer channel, not wider context. */
test("a schema run expects the structured-output tool beside the requested ones", () => {
  assert.deepEqual(grantedTools({ model: "haiku", tools: ["Bash"] }), ["Bash"]);
  assert.deepEqual(grantedTools({ model: "haiku", tools: ["Bash"], schema: { type: "object" } }), ["Bash", "StructuredOutput"]);
  assert.deepEqual(isolationProblems({ ...CLEAN_INIT, tools: ["Bash", "StructuredOutput"] }, grantedTools({ model: "haiku", tools: ["Bash"], schema: {} })), []);
});

/**
 * Concurrent sessions under the OS sandbox all get TMPDIR=/tmp/claude-<uid>, so on 2026-11-08 three eval
 * producers wrote `$TMPDIR/ops.json` within a second and one validated another's file. Each session therefore
 * gets its own temp directory, which must also be writable.
 */
test("every session gets a private, writable temp directory", () => {
  const plan = sessionPlan({ model: "haiku", tools: ["Bash"], writable: ["/tmp/work"] }, "/tmp/private-1");
  assert.equal(plan.env.CLAUDE_CODE_TMPDIR, "/tmp/private-1");
  assert.equal(plan.env.CLAUDE_CODE_DISABLE_CLAUDE_MDS, "1");
  assert.deepEqual(plan.args.flatMap((a: string, i: number) => (a === "--add-dir" ? [plan.args[i + 1]] : [])), ["/tmp/work", "/tmp/private-1"]);
});
