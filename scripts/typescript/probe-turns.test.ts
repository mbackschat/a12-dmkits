const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { probeTurns } = require("./probe-turns.ts");

const PROBE = path.resolve(__dirname, "../../probe");
const read = (name: string): string => fs.readFileSync(path.join(PROBE, name), "utf8");

function turnsOf(task: string): string[] {
  return probeTurns(read("agent-preamble.md"), read(`tasks/${task}`), read("report-pass.md"));
}

/**
 * The session the clean agent receives: the preamble's agent-facing body with TURN 1, each later turn alone, and
 * the report pass last. The runner notes around the preamble and the task's gold must never reach the agent.
 */
test("a task becomes the preamble plus its turns plus the report pass", () => {
  const turns = turnsOf("08-booking-date-ranges.md");
  assert.equal(turns.length, 6);
  assert.match(turns[0], /^You are an automation assistant/);
  assert.match(turns[0], /TURN 1\ncreate a venue booking model/);
  assert.equal(turns[2], "TURN 3\nno two bookings may have overlapping periods.");
  assert.match(turns[5], /^Now, in this same session, produce a \*\*bug-and-findings report\*\*/);
  for (const turn of turns) {
    assert.ok(!/Gold|What to mine|main loop/i.test(turn), turn.slice(0, 80));
  }
});

test("a multi-line turn keeps its lines and loses only the quote marker", () => {
  const turns = turnsOf("14-repeated-computation-outcomes.md");
  assert.equal(turns.length, 3);
  assert.ok(turns[1].includes('\n\n```json\n{"Permit":{"SiteSections":'), turns[1]);
  assert.ok(!turns[1].includes("\n>"), turns[1]);
});

test("every probe task yields at least one turn plus the report pass", () => {
  for (const task of fs.readdirSync(path.join(PROBE, "tasks"))) {
    assert.ok(turnsOf(task).length >= 2, task);
  }
});

test("a sandbox override replaces the default path the preamble names", () => {
  const turns = probeTurns(read("agent-preamble.md"), read("tasks/08-booking-date-ranges.md"), read("report-pass.md"), "/tmp/other-sandbox");
  assert.ok(turns.every((turn: string) => !turn.includes("/tmp/rk-probe-sandbox")));
  assert.ok(turns[0].includes("/tmp/other-sandbox/SKILL.md"));
});

test("a quote after the last turn, in the gold or the notes, never joins that turn", () => {
  const task = "**Turn 1**\n> add a field\n\nSTOP — do not read below.\n\n## Gold\n\n> the field must be a NUMBER\n";
  const turns = probeTurns("x\n---\npreamble\n---\ny", task, "a\n---\nreport");
  assert.deepEqual(turns, ["preamble\n\nTURN 1\nadd a field", "report"]);
});
