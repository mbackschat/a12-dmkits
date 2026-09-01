const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { inspectInitScripts } = require("./doctor.ts");

test("init-script inspection recognizes both Gradle DSLs and the repository-owned script", t => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "a12-doctor-"));
  t.after(() => fs.rmSync(home, { recursive: true, force: true }));
  const initDirectory = path.join(home, "init.d");
  fs.mkdirSync(initDirectory, { recursive: true });
  fs.writeFileSync(path.join(initDirectory, "public-repos.init.gradle"), "repositories { mavenCentral() }\n");
  fs.writeFileSync(path.join(initDirectory, "corporate.gradle"), "repositories { maven { url = secret } }\n");
  fs.writeFileSync(path.join(initDirectory, "marker-aware.gradle.kts"), "rootDir.resolve(\".no-global-gradle-repos\")\n");
  fs.writeFileSync(path.join(initDirectory, "ignored.txt"), "not a Gradle init script\n");

  assert.deepEqual(inspectInitScripts(home, "public-repos.init.gradle"), {
    ownsRepositoryInit: true,
    unhonoured: ["corporate.gradle"],
  });
});

test("top-level init scripts are part of the same injection surface", t => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "a12-doctor-"));
  t.after(() => fs.rmSync(home, { recursive: true, force: true }));
  fs.writeFileSync(path.join(home, "init.gradle.kts"), "val injected = true\n");

  assert.deepEqual(inspectInitScripts(home, "public-repos.init.gradle"), {
    ownsRepositoryInit: false,
    unhonoured: ["init.gradle.kts"],
  });
});
