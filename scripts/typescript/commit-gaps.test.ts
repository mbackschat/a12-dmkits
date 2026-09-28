const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync, spawnSync } = require("node:child_process");
const { test } = require("node:test");
const { entries, checkMessage } = require("./commit-gaps.ts");

const oldEntries = new Map([["IG159", "old"], ["DG69", "old"]]);
const newEntries = new Map([["IG159", "new"], ["DG69", "old"]]);

test("numbered entries own continuation text, not cross-references or adjacent sections", () => {
  assert.deepEqual(entries("# Gaps\n## Open\n- **IG159 — messages.** See DG69.\n\nMore.\n- **Unnumbered.**\ntext\n### IAG1 — load\nOld.\n## Deferred\nNone.\n"),
    new Map([["IG159", "## Open\n- **IG159 — messages.** See DG69.\n\nMore."], ["IAG1", "## Open\n### IAG1 — load\nOld."]]));
});

test("heading entries retain bullets, nested headings and acceptance text", () => {
  const before = "## Open\n### IRG1 — runtime\n- no kernel\n#### Acceptance\nMust pass.\n### IAG1 — load\n- resolve\n\n**Acceptance:** read.\n";
  for (const [oldText, newText, id] of [["no kernel", "kernel allowed", "IRG1"], ["Must pass.", "May fail.", "IRG1"], ["Acceptance:** read.", "Acceptance:** write.", "IAG1"]]) {
    const changed = before.replace(oldText, newText);
    assert.notDeepEqual(checkMessage("docs: revise acceptance", entries(before), entries(changed)), [], id);
    assert.deepEqual(checkMessage(`docs: revise acceptance (${id})`, entries(before), entries(changed)), []);
  }
});

test("moving an unchanged entry between sections requires its ID", () => {
  const before = entries("## Open\n- **IG159 — messages.** Pending.\n## Deferred\n");
  const after = entries("## Open\n## Deferred\n- **IG159 — messages.** Pending.\n");
  assert.notDeepEqual(checkMessage("docs: defer messages", before, after), []);
  assert.deepEqual(checkMessage("docs: defer messages (IG159)", before, after), []);
});

test("Oxford-comma closure lists check every ID, including the MR family", () => {
  for (const last of ["MG1", "MR1"]) {
    const before = new Map([["IG159", "old"], ["DG69", "old"], [last, "old"]]);
    const message = `fix: finish gaps\n\nCloses IG159, DG69, and ${last}.`;
    assert.notDeepEqual(checkMessage(message, before, new Map([[last, "old"]])), []);
    assert.deepEqual(checkMessage(message, before, new Map()), []);
  }
  assert.notDeepEqual(checkMessage("docs: record review", new Map(), entries("### MR1 — review\nOld.")), []);
  assert.deepEqual(checkMessage("docs: record review (MR1)", new Map(), entries("### MR1 — review\nOld.")), []);
});

test("changed entries require their own ID in the single-gap subject", () => {
  for (const message of ["fix: preserve messages", "fix: preserve messages (DG69)", "fix: preserve messages\n\nAdvances IG159.", "fix: preserve messages\n\n# IG159"]) {
    assert.notDeepEqual(checkMessage(message, oldEntries, newEntries), [], message);
  }
  assert.deepEqual(checkMessage("fix: preserve messages (IG159)\n\nAdvances IG159.", oldEntries, newEntries), []);
});

test("multiple cited gaps must all appear in the body", () => {
  const changed = new Map([["IG159", "new"], ["DG69", "new"]]);
  assert.notDeepEqual(checkMessage("fix: preserve operands (IG159, DG69)", oldEntries, changed), []);
  assert.notDeepEqual(checkMessage("fix: preserve operands (IG159)\n\nAdvances DG69.", oldEntries, changed), []);
  assert.deepEqual(checkMessage("fix: preserve operands\n\nAdvances IG159 and DG69.", oldEntries, changed), []);
});

test("Closes requires retirement in this commit; Advances permits a retained entry", () => {
  for (const message of ["fix: preserve messages (IG159)\n\nCloses IG159.", "fix: preserve messages (IG159)\n\ncloses: IG159."]) {
    assert.notDeepEqual(checkMessage(message, oldEntries, newEntries), []);
    assert.deepEqual(checkMessage(message, oldEntries, new Map([["DG69", "old"]])), []);
    assert.notDeepEqual(checkMessage(message, new Map(), new Map()), []);
  }
  assert.notDeepEqual(checkMessage("fix: preserve operands\n\nCloses DG69 and IG159.", oldEntries, new Map([["IG159", "old"]])), []);
  assert.deepEqual(checkMessage("fix: preserve operands\n\nCloses DG69, IG159.", oldEntries, new Map()), []);
});

test("added entries require an ID; unchanged entries and unrelated commits need none", () => {
  assert.notDeepEqual(checkMessage("docs: record a gap", new Map(), newEntries), []);
  assert.deepEqual(checkMessage("chore: maintain hooks", oldEntries, oldEntries), []);
  assert.deepEqual(checkMessage("test: extend coverage (IG159)", oldEntries, oldEntries), []);
});

function repository(t: { after(callback: () => void): void }): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "a12-commit-gaps-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const file of [".githooks/commit-msg", "scripts/run-typescript.sh", "scripts/typescript/commit-gaps.ts"]) {
    const dest = path.join(dir, file);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(path.resolve(__dirname, "../..", file), dest);
  }
  fs.mkdirSync(path.join(dir, "docs"));
  git(dir, "init", "--quiet");
  return dir;
}

function git(dir: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd: dir, encoding: "utf8" });
}

function hook(dir: string, message: string): { status: number; stderr: string } {
  const file = path.join(dir, "message.txt");
  fs.writeFileSync(file, message + "\n");
  return spawnSync("dash", [".githooks/commit-msg", file], {
    cwd: dir, encoding: "utf8", env: { ...process.env, A12_NODE: process.execPath }, timeout: 10_000,
  });
}

test("Dash hook uses the index through add, partial stage, cross-ledger move and retirement", t => {
  const dir = repository(t);
  const ledger = path.join(dir, "docs/INTERPRETER-GAPS.md");
  fs.writeFileSync(ledger, "## Open\n- **IG159 — messages.** Old.\n");
  git(dir, "add", "docs");
  assert.equal(hook(dir, "docs: record messages").status, 1);
  assert.equal(hook(dir, "docs: record messages (IG159)").status, 0);
  git(dir, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "test fixture");
  fs.writeFileSync(ledger, "## Open\n- **IG159 — messages.** New.\n");
  assert.equal(hook(dir, "chore: unrelated edit").status, 0, "unstaged edit does not count");
  fs.writeFileSync(ledger, "## Open\n_None open._\n");
  fs.writeFileSync(path.join(dir, "docs/DEV-API-GAPS.md"), "## Open\n- **IG159 — messages.** Old.\n");
  git(dir, "add", "docs");
  assert.equal(hook(dir, "docs: move messages").status, 1, "identical entry changes only its owning file");
  assert.equal(hook(dir, "docs: move messages (IG159)").status, 0);
  git(dir, "rm", "--cached", "docs/DEV-API-GAPS.md");
  fs.writeFileSync(ledger, "## Open\n- **IG159 — messages.** New.\n");
  git(dir, "add", "docs");
  fs.writeFileSync(ledger, "## Open\n_None open._\n");
  const refused = hook(dir, "fix: messages (IG159)\n\nCloses IG159.");
  assert.equal(refused.status, 1, "unstaged deletion cannot justify closure");
  assert.match(refused.stderr, /IG159/);
  fs.writeFileSync(path.join(dir, "docs/DEV-API-GAPS.md"), "## Deferred\n- **IG159 — messages.** New.\n");
  git(dir, "add", "docs");
  assert.equal(hook(dir, "docs: defer messages (IG159)\n\nCloses IG159.").status, 1);
  assert.equal(hook(dir, "docs: defer messages (IG159)").status, 0);
  git(dir, "rm", "--cached", "docs/DEV-API-GAPS.md");
  assert.equal(hook(dir, "fix: messages (IG159)\n\nCloses IG159.").status, 0);
});

test("Dash hook retains conventional refusals, generated subjects and fails without Git evidence", t => {
  const dir = repository(t);
  assert.equal(hook(dir, "Unstructured subject").status, 1);
  assert.equal(hook(dir, "fix: improve tooling\n\n- detail").status, 1);
  assert.equal(hook(dir, "fix: improve tooling\n\n10 tests passed").status, 1);
  assert.equal(hook(dir, 'Revert "fix: earlier change"').status, 0);
  assert.equal(hook(dir, "chore: maintain tooling").status, 0);
  fs.renameSync(path.join(dir, ".git"), path.join(dir, "hidden-git"));
  assert.notEqual(hook(dir, "chore: maintain tooling").status, 0);
});
