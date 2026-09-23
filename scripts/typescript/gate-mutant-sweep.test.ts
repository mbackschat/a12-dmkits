const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { collectSites, key, report, EXPECTED } = require("./gate-mutant-sweep.ts");

const DSL = "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl";
const TESTS = "src/test/java/io/github/mbackschat/a12/dm/rulekit/dsl";

function fixture(
  files: Readonly<Record<string, string>>,
  tests: Readonly<Record<string, string>> = {},
): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gate-sweep-"));
  fs.mkdirSync(path.join(root, DSL), { recursive: true });
  fs.mkdirSync(path.join(root, TESTS), { recursive: true });
  for (const [name, body] of Object.entries(files)) {
    fs.writeFileSync(path.join(root, DSL, name), body);
  }
  for (const [name, body] of Object.entries(tests)) {
    fs.writeFileSync(path.join(root, TESTS, name), body);
  }
  return root;
}

test("a gate call, an identity arm and a modifier arm are each a site", () => {
  const root = fixture({
    "Carrier.java": "class Carrier { Carrier { DuplicateGate.rejectDuplicates(\"X\", ops); } }",
    "Leaf.java": "record Leaf() { public Optional<String> exactIdentity() { return null; }\n"
      + "  public String overlapModifier() { return \"\"; } }",
  });
  const kinds = collectSites(root).map(key).sort();
  assert.deepEqual(kinds, ["Carrier.call", "Leaf.identity", "Leaf.modifier"]);
});

test("the interface's own declarations are not counted as implementations", () => {
  const root = fixture({
    "DuplicateOperand.java": "interface DuplicateOperand { Optional<String> exactIdentity();\n"
      + "  default String overlapModifier() { return \"\"; } }",
  });
  assert.deepEqual(collectSites(root), [], "the declaring interface is not a decision site");
});

test("DuplicateGate's own calls are not sites — it IS the gate", () => {
  const root = fixture({
    "DuplicateGate.java": "class DuplicateGate { void f() { DuplicateGate.rejectOverlap(\"X\", ops); } }",
  });
  assert.deepEqual(collectSites(root), []);
});

test("a NEW site is reported against the pin — the check that failed twice by hand", () => {
  // One call site where the pin expects ten: the report must say so rather than pass quietly.
  const root = fixture({
    "Carrier.java": "class Carrier { Carrier { DuplicateGate.rejectDuplicates(\"X\", ops); } }",
  });
  const { unknown } = report(collectSites(root));
  assert.ok(
    unknown.some((u: string) => u.includes("call sites: found 1, pinned 10")),
    `the pin must report a moved denominator, got: ${JSON.stringify(unknown)}`,
  );
});

test("a test class naming the carrier is the site's control, and its absence is reported", () => {
  const carrier = { "Carrier.java": "class Carrier { Carrier { DuplicateGate.rejectDuplicates(\"X\", o); } }" };
  const uncontrolled = collectSites(fixture(carrier));
  assert.deepEqual(uncontrolled[0].controls, [], "no test names the carrier");
  assert.ok(
    report(uncontrolled).unknown.some((u: string) => u.includes("Carrier.call: no test class names")),
    "a decision nobody asks about must be named, not merely left with an empty column",
  );

  const controlled = collectSites(
    fixture(carrier, { "CarrierDuplicateGateTest.java": "class CarrierDuplicateGateTest { Carrier c; }" }),
  );
  assert.deepEqual(controlled[0].controls, ["CarrierDuplicateGateTest"]);
});

test("a class whose NAME carries the carrier outranks one that merely references it", () => {
  const root = fixture(
    { "Carrier.java": "class Carrier { Carrier { DuplicateGate.rejectDuplicates(\"X\", o); } }" },
    {
      "AaaBroadSweepTest.java": "class AaaBroadSweepTest { Carrier c; }",
      "CarrierGateTest.java": "class CarrierGateTest { Carrier c; }",
    },
  );
  assert.deepEqual(collectSites(root)[0].controls, ["CarrierGateTest", "AaaBroadSweepTest"],
    "the narrowest filter is what makes a per-site mutant cheap enough to actually run");
});

test("a declared control that names no existing class does not count as a control", () => {
  // The override is the one row whose control is asserted rather than found, so it is the one that can name a
  // class that has been renamed away and still read as covered.
  const root = fixture({
    "ValueListCondition.java": "record ValueListCondition() { ValueListCondition { "
      + "DuplicateGate.rejectDuplicates(\"X\", o); } }",
  });
  const { unknown } = report(collectSites(root));
  assert.ok(
    unknown.some((u: string) => u.includes("ValueListCondition.call: no test class names")),
    `an override pointing at nothing must report uncontrolled, got: ${JSON.stringify(unknown)}`,
  );
});

test("the real tree leaves no decision site uncontrolled and no expectation stale", () => {
  const sites = collectSites(process.cwd());
  assert.ok(sites.length > 0, "the real tree must enumerate, else this file is testing a fixture only");
  assert.deepEqual(report(sites).unknown, [], "the sweep must be green on the tree it ships with");

  const keys = new Set(sites.map(key));
  for (const stale of Object.keys(EXPECTED)) {
    assert.ok(keys.has(stale), `EXPECTED names ${stale}, which is no longer a site — a claim about a gone gate`);
  }
});
