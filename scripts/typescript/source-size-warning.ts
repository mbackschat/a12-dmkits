const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

/**
 * The soft half of the source-file size limits: names each staged source above its soft limit, so the file
 * is split while that is still a choice. It only warns. The hard limit is `SourceFileSizeLimitGuardTest`'s,
 * which reads the same `scripts/source-size-limits.json` and the same locmetry report, so the two halves cannot
 * disagree on a number or on which files are test code.
 *
 * A soft limit enforced as a failure would just be a second hard limit with a list of excuses, and a warning
 * printed in some test report is never read. At commit time it reaches the person growing the file.
 */

const ROOT: string = path.resolve(__dirname, "../..");
const POLICY: string = path.join(ROOT, "scripts/source-size-limits.json");
/** The purposes held to a limit, the same closed set as the guard's `Kind`; any other purpose is exempt. */
const HELD = ["production", "test"];

type Limits = { soft: number; hard: number };
type Policy = {
  limits: Record<string, Limits>;
  legacyCeilings: Record<string, number>;
};
type SourceRecord = { path: string; purpose: string; code: number };
type Report = {
  metadata: { locmetry_version: string };
  files: { path: string; kind: string; purpose: string; counts: { code: number } }[];
};

/** The source-size policy is independent of the counter release; always run the latest locmetry. */
function sourceRecords(report: Report): SourceRecord[] {
  return report.files.filter((file) => file.kind === "source")
    .map((file) => ({ path: file.path, purpose: file.purpose, code: file.counts.code }));
}

/** One warning per held source above its soft limit; a purpose outside [HELD], such as `other`, is exempt. */
function warnings(policy: Policy, records: SourceRecord[]): string[] {
  for (const purpose of HELD) {
    if (policy.limits[purpose] === undefined) throw new Error(`scripts/source-size-limits.json has no limits.${purpose}`);
  }
  const found: string[] = [];
  for (const { path: file, purpose, code } of [...records].sort((a, b) => a.path.localeCompare(b.path))) {
    if (!HELD.includes(purpose)) continue;
    const { soft, hard } = policy.limits[purpose];
    const ceiling = policy.legacyCeilings[file];
    if (ceiling !== undefined && code > ceiling) {
      found.push(`${file}: ${code} code lines, grown past its legacy ceiling of ${ceiling}; leakGuardTest refuses it, `
        + "so split it");
    } else if (ceiling !== undefined && code <= hard) {
      found.push(`${file}: ${code} code lines, within the hard limit of ${hard}; delete its legacyCeilings entry`);
    } else if (ceiling !== undefined && code < ceiling) {
      found.push(`${file}: ${code} code lines, below its legacy ceiling of ${ceiling}; lower the ceiling to ${code}`);
    } else if (ceiling !== undefined) {
      found.push(`${file}: ${code} code lines, past the hard limit of ${hard} for ${purpose} code and held at ${ceiling}; `
        + "split it before adding to it");
    } else if (code > hard) {
      found.push(`${file}: ${code} code lines, past the hard limit of ${hard} for ${purpose} code; `
        + "leakGuardTest refuses it, so split it now");
    } else if (code > soft) {
      found.push(`${file}: ${code} code lines, past the soft limit of ${soft} for ${purpose} code (hard limit ${hard}); `
        + "split it by responsibility before it grows further");
    }
  }
  return found;
}

function main(files: string[]): void {
  const policy: Policy = JSON.parse(fs.readFileSync(POLICY, "utf8"));
  // An empty HOME keeps the user's global Git excludes out of the selection, as the guard does.
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "locmetry-home-"));
  const run = spawnSync(process.env.LOCMETRY ?? "locmetry", ["--no-user-config", "--files", "--format", "json", "--output", "-"],
    { cwd: ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024, env: { ...process.env, HOME: home, XDG_CONFIG_HOME: home } });
  fs.rmdirSync(home);
  if (run.error !== undefined || run.status !== 0) {
    console.error(`pre-commit: locmetry is not runnable here, so the source-size warning is skipped `
      + "(leakGuardTest still needs it; install with: brew install mbackschat/tap/locmetry)");
    return;
  }
  let records: SourceRecord[];
  try {
    records = sourceRecords(JSON.parse(run.stdout));
  } catch (error) {
    console.error(`pre-commit: ${(error as Error).message}, so the source-size warning is skipped`);
    return;
  }
  const staged = new Set(files);
  const found = warnings(policy, records.filter((record) => staged.has(record.path)));
  if (found.length > 0) {
    console.error("\npre-commit: staged source files over a size limit (a warning here; leakGuardTest enforces the hard limit):\n");
    for (const warning of found) console.error(`  ${warning}`);
    console.error("\nThe limits and the shrink-only list live in scripts/source-size-limits.json.\n");
  }
}

if (require.main === module) main(process.argv.slice(2));

module.exports = { sourceRecords, warnings };
