const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { runCleanAgent } = require("./clean-agent.ts");

/**
 * The eval's Produce phase, run as clean headless agents instead of in-process workflow agents.
 *
 * The prompts are not restated here: the benchmark workflow is executed in its `prompts` mode, with an
 * `agent()` that refuses to run, and returns one cell per (model × task × run). Each cell goes to a clean
 * agent (`clean-agent.ts`) that has only Bash, Read and Write, `dmtool` on its PATH, and no inherited context;
 * the run is refused if any agent's init event shows wider context or its transcript touches the
 * repository, the docs or a skill file. The structured outputs land in `produced.json`, which the workflow
 * then scores in its default mode (EVAL-SPEC §8c).
 *
 *   scripts/run-typescript.sh eval-produce scripts/typescript/eval-produce.ts --tasks tasks.json \
 *       --models haiku,sonnet [--efforts '{"sonnet":"xhigh"}'] [--k 1] [--concurrency 4] [--skill FILE] --out DIR
 *
 * `--skill` is production mode (EVAL-SPEC §2a): the file is copied to DIR/material/SKILL.md, outside the
 * repository, and that one path is the only `.md` a producer may read.
 */

const ROOT: string = path.resolve(__dirname, "../..");
const WORKFLOW = path.join(ROOT, "eval/benchmark.workflow.js");
const SANDBOX = "/tmp/rk-eval-sandbox";
const TOOLS = ["Bash", "Read", "Write"];
// Producers edit the sandbox's model copies and write their op files under /tmp; everything else, the
// repository included, stays read-only under the OS sandbox.
const WRITABLE = ["/tmp"];

type Cell = { key: string; label: string; model: string; effort?: string; prompt: string; schema: unknown };

/** Run the workflow script in `prompts` mode; any attempt to spawn an agent is a defect in that mode. */
async function workflowCells(args: Record<string, unknown>, script: string = WORKFLOW): Promise<Cell[]> {
  const source = fs.readFileSync(script, "utf8").replace(/^export const meta/m, "const meta");
  const refuse = (): never => { throw new Error("the prompts mode must not spawn an agent"); };
  const run = new Function("agent", "parallel", "pipeline", "log", "phase", "args", "budget", "workflow",
    `return (async () => { ${source} })()`);
  const out = await run(refuse, refuse, refuse, () => {}, () => {}, { ...args, mode: "prompts" }, {}, refuse);
  if (!out || !Array.isArray(out.cells)) throw new Error("the workflow's prompts mode returned no cells");
  return out.cells;
}

/** Every tool input in a transcript that names a path the material boundary forbids. */
function boundaryBreaches(transcript: string, root: string = ROOT, allowed: string[] = []): string[] {
  const breaches: string[] = [];
  for (const line of transcript.split("\n")) {
    if (!line.includes('"tool_use"')) continue;
    let event: { message?: { content?: Array<{ type?: string; input?: Record<string, unknown> }> } };
    try { event = JSON.parse(line); } catch { continue; }
    for (const block of event.message?.content ?? []) {
      if (block.type !== "tool_use") continue;
      const input = allowed.reduce((text, path) => text.split(path).join(""), JSON.stringify(block.input ?? {}));
      if (input.includes(root) || /\.md\b|\/docs\/|SKILL/.test(input)) breaches.push(input.slice(0, 200));
    }
  }
  return breaches;
}

/** Run `work` over `items` with at most `limit` in flight, keeping input order in the result. */
async function pool<T, R>(items: T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const lanes = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const at = next++;
      results[at] = await work(items[at]);
    }
  });
  await Promise.all(lanes);
  return results;
}

function option(argv: string[], name: string): string | undefined {
  const at = argv.indexOf(name);
  return at >= 0 ? argv[at + 1] : undefined;
}

async function main(argv: string[]): Promise<number> {
  const tasksFile = option(argv, "--tasks");
  const models = option(argv, "--models");
  const out = option(argv, "--out");
  if (!tasksFile || !models || !out) {
    console.error("usage: eval-produce.ts --tasks FILE --models a,b [--efforts JSON] [--k N] [--concurrency N] --out DIR");
    return 2;
  }
  if (!fs.existsSync(path.join(SANDBOX, "order.dm.json"))) {
    console.error(`eval-produce: ${SANDBOX} is not ready — run \`bash eval/prep-sandbox.sh\` first`);
    return 2;
  }
  const skillFile = option(argv, "--skill");
  const material = skillFile ? path.join(path.resolve(out), "material", "SKILL.md") : undefined;
  if (skillFile && material) {
    fs.mkdirSync(path.dirname(material), { recursive: true });
    fs.copyFileSync(skillFile, material);
  }
  const javaHome = execFileSync("sh", ["-c", ". scripts/gradle-environment.sh && a12_pinned_jdk21_home"], { cwd: ROOT, encoding: "utf8" }).trim();
  const cells = await workflowCells({
    models: models.split(","), k: Number(option(argv, "--k") ?? 1), cli: "dmtool",
    efforts: JSON.parse(option(argv, "--efforts") ?? "{}"), tasks: JSON.parse(fs.readFileSync(tasksFile, "utf8")),
    ...(material ? { skillPath: material } : {}),
  });
  const env = { PATH: `${path.join(ROOT, "bin")}:${process.env.PATH}`, JAVA_HOME: javaHome };
  console.error(`eval-produce: ${cells.length} cell(s), at most ${option(argv, "--concurrency") ?? 4} at a time`);
  const runs = await pool(cells, Number(option(argv, "--concurrency") ?? 4), async cell => {
    const transcript = path.join(out, "transcripts", `${cell.key.replace(/[^A-Za-z0-9._-]+/g, "_")}.jsonl`);
    const run = await runCleanAgent({ model: cell.model, effort: cell.effort, tools: TOOLS, schema: cell.schema, turns: [cell.prompt], writable: WRITABLE, transcript, env });
    const breaches = boundaryBreaches(fs.readFileSync(transcript, "utf8"), ROOT, material ? [material] : []);
    console.error(`  ${cell.label}: ${run.isError ? "error" : "done"}${run.problems.length + breaches.length ? " — REFUSED" : ""}`);
    return { cell, run, breaches };
  });
  const produced: Record<string, unknown> = {};
  const refused: Record<string, string[]> = {};
  for (const { cell, run, breaches } of runs) {
    produced[cell.key] = run.structured ?? null;
    if (run.problems.length || breaches.length) refused[cell.key] = [...run.problems, ...breaches.map(b => `boundary: ${b}`)];
  }
  fs.writeFileSync(path.join(out, "produced.json"), JSON.stringify(produced, null, 2) + "\n");
  fs.writeFileSync(path.join(out, "report.json"), JSON.stringify({
    cells: cells.length, missing: Object.keys(produced).filter(k => produced[k] === null), refused,
  }, null, 2) + "\n");
  if (Object.keys(refused).length) {
    console.error(`eval-produce: ${Object.keys(refused).length} cell(s) refused — the run is invalid; see ${path.join(out, "report.json")}`);
    return 1;
  }
  console.error(`eval-produce: all ${cells.length} cell(s) clean; outputs in ${path.join(out, "produced.json")}`);
  return 0;
}

module.exports = { boundaryBreaches, pool, workflowCells };

if (require.main === module) main(process.argv.slice(2)).then(code => process.exit(code));
