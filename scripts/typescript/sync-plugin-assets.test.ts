const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { synchronizePluginAssets } = require("./sync-plugin-assets.ts");

type Capture = { out: string[]; error: string[] };

function fixture(t: { after(callback: () => void): void }): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "a12-plugin-sync-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const sources: Readonly<Record<string, { content: string; mode: number }>> = {
    "skill/SKILL.md": { content: "authoring\n", mode: 0o644 },
    "skill/BUG-REPORT-SKILL.md": { content: "bug report\n", mode: 0o644 },
    "scripts/ensure-dmtool.sh": { content: "#!/bin/sh\ninstaller\n", mode: 0o755 },
    "scripts/release-assets.sh": { content: "#!/bin/sh\nassets\n", mode: 0o755 },
  };
  for (const [relative, source] of Object.entries(sources)) {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, source.content);
    fs.chmodSync(file, source.mode);
  }
  return root;
}

function capture(): { messages: Capture; reporter: { out(message: string): void; error(message: string): void } } {
  const messages: Capture = { out: [], error: [] };
  return {
    messages,
    reporter: {
      out: message => messages.out.push(message),
      error: message => messages.error.push(message),
    },
  };
}

test("sync materializes every canonical asset in both plugin trees with source permissions", t => {
  const root = fixture(t);
  const { messages, reporter } = capture();

  assert.equal(synchronizePluginAssets(root, false, reporter), 0);

  const destinations = [
    "skills/a12-dmtool/SKILL.md",
    "skills/a12-dmtool/ensure-dmtool.sh",
    "skills/a12-dmtool/release-assets.sh",
    "skills/a12-dmtool-bug-report/SKILL.md",
  ];
  for (const plugin of ["plugin", "plugin-codex"]) {
    for (const destination of destinations) {
      assert.ok(fs.statSync(path.join(root, plugin, destination)).isFile(), `${plugin}/${destination}`);
    }
    assert.equal(fs.statSync(path.join(root, plugin, "skills/a12-dmtool/ensure-dmtool.sh")).mode & 0o777, 0o755);
    assert.equal(fs.statSync(path.join(root, plugin, "skills/a12-dmtool/SKILL.md")).mode & 0o777, 0o644);
  }
  assert.equal(messages.out.length, 8);
  assert.deepEqual(messages.error, []);
});

test("check reports every stale copy without mutating it and passes after regeneration", t => {
  const root = fixture(t);
  assert.equal(synchronizePluginAssets(root, false, capture().reporter), 0);
  const stale = path.join(root, "plugin/skills/a12-dmtool/SKILL.md");
  fs.writeFileSync(stale, "stale\n");
  const first = capture();

  assert.equal(synchronizePluginAssets(root, true, first.reporter), 1);
  assert.deepEqual(fs.readFileSync(stale, "utf8"), "stale\n");
  assert.ok(first.messages.error.some(message => message.startsWith("STALE: plugin/skills/a12-dmtool/SKILL.md")));

  assert.equal(synchronizePluginAssets(root, false, capture().reporter), 0);
  const second = capture();
  assert.equal(synchronizePluginAssets(root, true, second.reporter), 0);
  assert.equal(second.messages.out.length, 8);
  assert.deepEqual(second.messages.error, []);
});

test("a missing canonical source is a setup error rather than an empty successful sync", t => {
  const root = fixture(t);
  fs.rmSync(path.join(root, "skill/BUG-REPORT-SKILL.md"));
  const { messages, reporter } = capture();

  assert.equal(synchronizePluginAssets(root, true, reporter), 2);
  assert.deepEqual(messages.out, []);
  assert.deepEqual(messages.error, [`canonical bug-report skill not found: ${root}/skill/BUG-REPORT-SKILL.md`]);
});
