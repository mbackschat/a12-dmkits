const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { ownersOf, ledgerRow, render, withoutComments } = require("./code-owner.ts");

function fixture(name: string, body: string): string {
  const dir: string = fs.mkdtempSync(path.join(os.tmpdir(), "code-owner-"));
  const file: string = path.join(dir, name);
  fs.writeFileSync(file, body);
  return file;
}

test("a code reaching a Finding construction names its file as an owner", () => {
  const file = fixture("Family.kt", `
    fun x() = Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, "MVK_REAL", "m", "/A")
  `);
  assert.deepEqual(ownersOf("MVK_REAL", [file]).length, 1);
});

test("a code reaching the file-local finding helper counts too", () => {
  // The blind spot that cost fourteen lagging ledger rows: a family writes the constructor once and calls
  // its own helper everywhere after, so a scan keyed on the capitalised name sees none of its codes.
  const file = fixture("Helper.kt", `
    private fun finding(code: String, explain: String, at: String) = Finding(code, explain, at)
    fun x() = finding("MVK_VIA_HELPER", "m", "/A")
  `);
  assert.deepEqual(ownersOf("MVK_VIA_HELPER", [file]).length, 1);
});

test("a code held in a const the file passes on counts", () => {
  const file = fixture("Aliased.kt", `
    private const val DIRECT = "MVK_ALIASED"
    private fun finding(code: String, explain: String, at: String) = Finding(code, explain, at)
    fun x() = finding(DIRECT, "m", "/A")
  `);
  assert.deepEqual(ownersOf("MVK_ALIASED", [file]).length, 1);
});

test("a code named only in a comment is not a report", () => {
  // The opposite claim, and the common one here: KDoc names the class a family deliberately stays silent on.
  const file = fixture("Silent.kt", `
    /** Silent where A12 answers MVK_MENTIONED instead. */
    fun x() = Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, "MVK_OTHER", "m", "/A")
  `);
  assert.deepEqual(ownersOf("MVK_MENTIONED", [file]), []);
});

test("comment stripping leaves string literals alone", () => {
  assert.match(withoutComments('val a = "http://x" // gone'), /http:\/\/x/);
  assert.doesNotMatch(withoutComments('val a = "k" // gone'), /gone/);
});

test("the ledger row is read for its claim, its bucket and its locks", () => {
  const ledger = "| `MVK_X` | pre-checked / checker-reported | law (locked: `FooLawsTest`, `BarTest`) |";
  const row = ledgerRow("MVK_X", ledger);
  assert.equal(row.reportedClaim, true);
  assert.equal(row.disposition, "pre-checked / checker-reported");
  assert.deepEqual(row.locks, ["FooLawsTest", "BarTest"]);
});

test("a family reporting a code its row does not claim is called out as drift", () => {
  const rendered: string = render({
    code: "MVK_X",
    owners: [{ file: "consistency/Family.kt", family: "SOME_FAMILY" }],
    reportedClaim: false,
    disposition: "kernel-delegated",
    locks: [],
  });
  assert.match(rendered, /DRIFT: a family reports it and the ledger row does not say so/);
});

test("the reverse direction is offered as a lead, never as drift", () => {
  const rendered: string = render({
    code: "MVK_X", owners: [], reportedClaim: true, disposition: "checker-reported", locks: [],
  });
  assert.match(rendered, /lead:/);
  assert.doesNotMatch(rendered, /DRIFT/);
});
