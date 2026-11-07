const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { boundaryBreaches, pool, workflowCells } = require("./eval-produce.ts");

const ROOT = path.resolve(__dirname, "../..");

function allTasks(): unknown[] {
  const dir = path.join(ROOT, "eval/tasks");
  return fs.readdirSync(dir, { withFileTypes: true }).filter((e: { isDirectory(): boolean }) => e.isDirectory())
    .flatMap((e: { name: string }) => fs.readdirSync(path.join(dir, e.name))
    .filter((f: string) => f.endsWith(".json"))
    .map((f: string) => JSON.parse(fs.readFileSync(path.join(dir, e.name, f), "utf8"))));
}

/**
 * The prompts mode is the single source of the producer prompts, so it must cover every live task family
 * (a family the produce grid skipped would score 0/k silently), stay unique per cell, and never point a
 * clean agent at the repository.
 */
test("the workflow's prompts mode yields one clean-agent cell per model, task and run", async () => {
  const tasks = allTasks();
  const cells = await workflowCells({ models: ["haiku", "sonnet"], k: 2, cli: "dmtool", efforts: { sonnet: "xhigh" }, tasks });
  assert.equal(cells.length, tasks.length * 2 * 2);
  assert.equal(new Set(cells.map((c: { key: string }) => c.key)).size, cells.length);
  for (const cell of cells) {
    assert.equal(typeof cell.prompt, "string");
    assert.equal(typeof cell.schema, "object");
    assert.equal(cell.effort, cell.model === "sonnet" ? "xhigh" : undefined);
    assert.ok(!/scripts\/|cli\/src|bin\/dmtool/.test(cell.prompt), `${cell.key} names a repository path`);
  }
});

test("a repository, docs or skill path in any tool input is a boundary breach", () => {
  const use = (input: Record<string, unknown>) => JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", name: "Bash", input }] } });
  const transcript = [
    use({ command: "dmtool -m /tmp/rk-eval-sandbox/order.dm.json model describe" }),
    use({ command: "cat /repo/docs/EVAL-SPEC.md" }),
    use({ file_path: "/repo/cli/src/main/java/X.java" }),
    use({ command: "cat SKILL.md" }),
  ].join("\n");
  assert.equal(boundaryBreaches(transcript, "/repo").length, 3);
  const skill = use({ file_path: "/out/material/SKILL.md" });
  assert.equal(boundaryBreaches(skill, "/repo").length, 1, "production mode's skill is a breach unless allowed");
  assert.equal(boundaryBreaches(skill, "/repo", ["/out/material/SKILL.md"]).length, 0);
});

test("the pool keeps input order and never exceeds its limit", async () => {
  let inFlight = 0;
  let peak = 0;
  const result = await pool([1, 2, 3, 4, 5, 6, 7], 3, async (n: number) => {
    inFlight++;
    peak = Math.max(peak, inFlight);
    await new Promise(resolve => setTimeout(resolve, 5 * (8 - n)));
    inFlight--;
    return n * 10;
  });
  assert.deepEqual(result, [10, 20, 30, 40, 50, 60, 70]);
  assert.equal(peak, 3);
});
