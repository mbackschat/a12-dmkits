const assert = require("node:assert/strict");
const { test } = require("node:test");

const { renderBatch, verdictOfOp } = require("./probe-verdicts.ts");

/**
 * The claim under test is narrow and is the whole reason the module exists: a CLI refusal and a kernel
 * verdict must never render as the same shape of line. Both fixtures below are verbatim envelopes the tool
 * emitted on 2026-09-04 — the refusal is the one a session misread four times.
 */

const REFUSED_CHILD = {
  target: "rule",
  op: "check",
  outcome: "refused",
  ok: false,
  summary: "--field must be an absolute path (e.g. /Group/Field), got: ../CustomerName",
  diagnostics: [
    {
      severity: "ERROR",
      source: "PRECHECK",
      code: "RK_INVALID_VALUE",
      summary: "--field must be an absolute path (e.g. /Group/Field), got: ../CustomerName",
      where: { arg: "--field" },
      fix: "give the value the type the schema declares for this key",
    },
  ],
  written: false,
};

const KERNEL_INVALID_CHILD = {
  target: "rule",
  op: "check",
  outcome: "read",
  ok: true,
  valid: false,
  diagnostics: [{ severity: "ERROR", source: "KERNEL", code: "MVK_NO_WILDCARD", summary: "no wildcard" }],
};

const ADMITTED_CHILD = { target: "rule", op: "check", outcome: "read", ok: true, valid: true, diagnostics: [] };

function batchOf(...results: unknown[]): string {
  return JSON.stringify({ outcome: "completed", data: { dispatched: true, results } });
}

test("a CLI refusal names the ARGUMENT and never renders as a verdict", () => {
  const line = renderBatch(batchOf({ op: 0, id: "typo-row", verb: "rule", exit: 2, result: REFUSED_CHILD }));

  assert.equal(line.split("\t")[1], "REFUSED");
  assert.equal(line.split("\t")[2], "-", "a refusal establishes nothing about the model");
  assert.match(line, /--field: --field must be an absolute path/);
});

test("a negative kernel verdict is INVALID on the RAN channel, because the op did run", () => {
  const line = renderBatch(batchOf({ op: 0, id: "repeat-outside", verb: "rule", exit: 1, result: KERNEL_INVALID_CHILD }));

  assert.deepEqual(line.split("\t"), ["repeat-outside", "RAN", "INVALID", "MVK_NO_WILDCARD"]);
});

test("the two failing channels differ in their FIRST column, which is the whole point", () => {
  const refused = verdictOfOp({ id: "a", result: REFUSED_CHILD }).channel;
  const invalid = verdictOfOp({ id: "b", result: KERNEL_INVALID_CHILD }).channel;

  assert.notEqual(refused, invalid);
  // And the code column alone cannot tell them apart, which is what the projection bug relied on.
  assert.ok(REFUSED_CHILD.diagnostics[0].code.startsWith("RK_"));
  assert.ok(KERNEL_INVALID_CHILD.diagnostics[0].code.startsWith("MVK_"));
});

test("a PRECHECK code is tagged with its source when it appears beside kernel ones", () => {
  const mixed = {
    outcome: "read",
    ok: true,
    valid: false,
    diagnostics: [
      { source: "KERNEL", code: "MVK_NO_WILDCARD" },
      { source: "LINT", code: "RK_SMELL" },
    ],
  };
  assert.equal(verdictOfOp({ id: "m", result: mixed }).detail, "MVK_NO_WILDCARD,RK_SMELL(LINT)");
});

test("an admitted op is VALID with no detail", () => {
  assert.deepEqual(renderBatch(batchOf({ op: 0, id: "ok", result: ADMITTED_CHILD })).split("\t"),
    ["ok", "RAN", "VALID", ""]);
});

test("one line per op, in the table's own order", () => {
  const lines = renderBatch(batchOf(
    { op: 0, id: "first", result: ADMITTED_CHILD },
    { op: 1, id: "second", result: KERNEL_INVALID_CHILD },
    { op: 2, id: "third", result: REFUSED_CHILD },
  )).split("\n");

  assert.equal(lines.length, 3);
  assert.deepEqual(lines.map((l) => l.split("\t")[0]), ["first", "second", "third"]);
});

test("an op with no id falls back to its index, so a row is always addressable", () => {
  assert.equal(verdictOfOp({ op: 7, result: ADMITTED_CHILD }).id, "op7");
});

test("a child that produced no envelope is an ERROR, never a silent pass", () => {
  const thrown = verdictOfOp({ op: 0, id: "boom", exit: 1, stderr: "op threw: something" });
  assert.equal(thrown.channel, "ERROR");
  assert.match(thrown.detail, /op threw/);

  const nonJson = verdictOfOp({ op: 1, id: "raw", exit: 1, stdout: "Exception in thread \"main\"" });
  assert.equal(nonJson.channel, "ERROR");
});

test("an empty or unparsable batch is an ERROR — the dangerous default is reading it as clean", () => {
  assert.match(renderBatch(batchOf()), /^ERROR\tno ops/);
  assert.match(renderBatch("not json at all"), /^ERROR\tthe input is not a dmtool envelope/);
  assert.match(renderBatch(""), /^ERROR/);
});
