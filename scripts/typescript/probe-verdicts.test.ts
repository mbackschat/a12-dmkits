const assert = require("node:assert/strict");
const { test } = require("node:test");

const { renderBatch, verdictOfOp } = require("./probe-verdicts.ts");

/**
 * The claim under test is narrow and is the whole reason the module exists: the three ways an op can fail must
 * never render as the same shape of line. A REFUSED request establishes nothing about the model, a REJECTED
 * subject was read and judged, and an ERROR produced no envelope at all.
 *
 * <p>The fixtures are shaped after envelopes the tool emitted on 2026-09-04 — the refusal is the one a session
 * misread four times — but they are NOT byte-verbatim: `REFUSED_CHILD.fix` still carries the generic corrective
 * that the next commit replaced, which is why this note says "shaped after" rather than "verbatim". A cold
 * review caught the original wording claiming otherwise. Nothing here asserts on `fix`.
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
  // And the CODE COLUMN alone cannot tell them apart — the projection this module replaces kept only that
  // column, so it is asserted through the renderer's own output rather than by inspecting the fixtures.
  const refusedLine = renderBatch(batchOf({ op: 0, id: "r", result: REFUSED_CHILD }));
  const invalidLine = renderBatch(batchOf({ op: 0, id: "i", result: KERNEL_INVALID_CHILD }));
  assert.notEqual(refusedLine.split("\t")[1], invalidLine.split("\t")[1]);
});

/**
 * The REJECTED channel, which had no fixture at all and so was wholly uncovered — the branch a cold review
 * found collapsed into REFUSED, because a spec-key rejection also carries PRECHECK diagnostics.
 *
 * These two are the same author mistake reported on two channels, and telling them apart is the point: a bad
 * `--field` OPTION refuses the request (exit 2), while a bad `field` SPEC KEY rejects the subject (exit 1).
 */
const SPEC_REJECTED_CHILD = {
  target: "rule",
  op: "add",
  outcome: "rejected",
  ok: false,
  summary: "field must be an absolute path with no empty segment (e.g. /Group/Field), got: ../CustomerName",
  diagnostics: [
    {
      severity: "ERROR",
      source: "PRECHECK",
      code: "RK_INVALID_VALUE",
      summary: "field must be an absolute path with no empty segment (e.g. /Group/Field), got: ../CustomerName",
      where: { arg: "field" },
    },
  ],
  written: false,
};

const KERNEL_REJECTED_CHILD = {
  target: "computation",
  op: "add",
  outcome: "rejected",
  ok: false,
  diagnostics: [{ severity: "ERROR", source: "KERNEL", code: "MVK_PARAMSIZE_INVALIDN", summary: "bad arity" }],
};

test("a REJECTED subject is not a REFUSED request, even though both carry PRECHECK diagnostics", () => {
  const rejected = verdictOfOp({ op: 0, id: "spec", exit: 1, result: SPEC_REJECTED_CHILD });
  const refused = verdictOfOp({ op: 1, id: "opt", exit: 2, result: REFUSED_CHILD });

  assert.equal(rejected.channel, "REJECTED", "the envelope's own outcome decides the channel");
  assert.equal(refused.channel, "REFUSED");
  assert.notEqual(rejected.channel, refused.channel,
    "collapsing these was the defect: a source override made every spec rejection read as a refusal");
  // A pre-check rejection still names the offending KEY, which the codes alone do not.
  assert.match(rejected.detail, /^field: field must be an absolute path/);
});

test("a KERNEL rejection is identified by its codes, not by a quoted summary", () => {
  const line = renderBatch(batchOf({ op: 0, id: "kernel", exit: 1, result: KERNEL_REJECTED_CHILD }));
  assert.deepEqual(line.split("\t"), ["kernel", "REJECTED", "-", "MVK_PARAMSIZE_INVALIDN"]);
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
