const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { checkTranscript, cleanAgentArgs, CLEAN_ENV, contextFilesAbove, isolationProblems, readTranscript } = require("./clean-agent.ts");

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
  const args = cleanAgentArgs({ model: "sonnet", effort: "xhigh", tools: ["Bash", "Read"], schema: { type: "object" } });
  for (const flag of ["-p", "--disable-slash-commands", "--no-session-persistence", "--strict-mcp-config"]) {
    assert.ok(args.includes(flag), flag);
  }
  assert.equal(args[args.indexOf("--setting-sources") + 1], "project");
  assert.deepEqual(JSON.parse(args[args.indexOf("--settings") + 1]), { autoMemoryEnabled: false });
  assert.equal(args[args.indexOf("--tools") + 1], "Bash,Read");
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
