const { spawnSync } = require("node:child_process");
const path = require("node:path");

/**
 * Behavioural guard for the PreToolUse Bash hook: it must FIRE on each anti-pattern and STAY QUIET otherwise.
 *
 * The does-not-fire half carries as much weight as the does-fire half: a guard that refuses justified work
 * gets disabled rather than corrected, and the ALLOW rows are what keep this one from going that way.
 *
 * Drives the hook exactly as the harness does: hook JSON on stdin, exit 2 = blocked, exit 0 = allowed.
 */

type Case = { readonly blocked: boolean; readonly label: string; readonly command: string };

const ROOT = path.resolve(__dirname, "..", "..");
const HOOK = path.join(ROOT, ".claude", "hooks", "guard-long-run-antipatterns.sh");

const CASES: readonly Case[] = [
  // (1) a job-control & hand-detaches a child outside harness tracking
  { blocked: true, label: "detached &", command: "./build.sh :test > log 2>&1 &" },
  { blocked: false, label: "&& is not a detach", command: "./build.sh :test && echo done" },
  { blocked: false, label: "2>&1 is not a detach", command: "./build.sh :test > log 2>&1" },

  // (2) pgrep -f matches its own command line, so it cannot gate liveness
  { blocked: true, label: "pgrep -f gate", command: "until ! pgrep -f gradle; do sleep 5; done" },
  { blocked: false, label: "pgrep without -f", command: "pgrep gradle" },
  { blocked: false, label: "merely mentioning it", command: 'git commit -m "why pgrep -f self-matches"' },

  // (3) a recursive rm of a Gradle output path
  { blocked: true, label: "module build subdir", command: "rm -rf adapter/build/kotlin" },
  { blocked: true, label: "after a cd", command: "cd /repo && rm -rf adapter/build/classes/kotlin" },
  { blocked: true, label: "repo-root build/", command: "rm -rf build/tmp" },
  { blocked: true, label: "flag order -fr", command: "rm -fr ./build/classes" },
  { blocked: true, label: "separated flags", command: "rm -r -f adapter/build/tmp" },
  { blocked: true, label: "absolute path", command: "rm -rf /srv/checkout/interpreter/build" },
  { blocked: true, label: "trailing build", command: "rm -rf adapter/build" },
  { blocked: true, label: "quoted target", command: 'rm -rf "adapter/build/kotlin"' },
  // Deliberately NOT exempt: CLAUDE.md names deleting test-results only as the fallback for reading a run's
  // XMLs, and scripts/test-evidence.sh is the primary and needs no deletion.
  { blocked: true, label: "test-results is not exempt", command: "rm -rf build/test-results/test" },

  { blocked: false, label: "non-recursive single file", command: "rm -f build/run.log" },
  { blocked: false, label: "no build segment", command: "rm -rf /tmp/scratch/work" },
  { blocked: false, label: "a dir merely named rebuild", command: "rm -rf tools/rebuild" },
  { blocked: false, label: "a sibling dir, not build", command: "rm -rf adapter/bin" },
  { blocked: false, label: "the clean task itself", command: "./build.sh :adapter:clean" },
  { blocked: false, label: "mentioned in a message", command: 'git commit -m "hook: refuse rm -rf build/ paths"' },
  { blocked: false, label: "mentioned in an echo", command: 'echo "do not rm -rf build/kotlin by hand"' },
];

function verdict(command: string): number {
  const result = spawnSync("bash", [HOOK], {
    input: JSON.stringify({ tool_input: { command } }),
    encoding: "utf8",
  });
  return result.status ?? -1;
}

function main(): void {
  const failures: string[] = [];

  for (const testCase of CASES) {
    const want = testCase.blocked ? 2 : 0;
    const got = verdict(testCase.command);
    if (got !== want) {
      failures.push(`[${testCase.label}] expected exit ${want}, got ${got} for: ${testCase.command}`);
    }
  }

  // A parse failure must never block: the hook degrades to allow when the command field is absent.
  const degraded = spawnSync("bash", [HOOK], { input: "not json", encoding: "utf8" });
  if ((degraded.status ?? -1) !== 0) {
    failures.push(`[degrades on unparseable input] expected exit 0, got ${degraded.status}`);
  }

  const checks = CASES.length + 1;
  console.log(`selftest-bash-hook: ${checks} checks run, ${failures.length} failed`);
  for (const failure of failures) {
    console.error(`SELFTEST FAIL ${failure}`);
  }
  if (failures.length > 0) {
    process.exit(1);
  }
}

main();
