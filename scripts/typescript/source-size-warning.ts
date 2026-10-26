const fs = require("node:fs");
const path = require("node:path");

/**
 * The soft half of the source-file size limits: names each staged source above its soft limit, so the file
 * is split while that is still a choice. It only warns. The hard limit is `SourceFileSizeLimitGuardTest`'s,
 * which reads the same `scripts/source-size-limits.json`, so the two halves cannot disagree on a number.
 *
 * A soft limit enforced as a failure would just be a second hard limit with a list of excuses, and a warning
 * printed in some test report is never read. At commit time it reaches the person growing the file.
 */

const ROOT: string = path.resolve(__dirname, "../..");
const POLICY: string = path.join(ROOT, "scripts/source-size-limits.json");
const SOURCE = /\.(java|kt|ts)$/;

type Limits = { soft: number; hard: number };
type Policy = {
  testPathPattern: string;
  limits: { production: Limits; test: Limits };
  legacyCeilings: Record<string, number>;
};

/** Physical lines, counted as the guard counts them: one per line terminator, plus a final unterminated line. */
function physicalLines(text: string): number {
  const terminators = text.split("\n").length - 1;
  return text === "" || text.endsWith("\n") ? terminators : terminators + 1;
}

/** One warning per source above its soft limit; `lines` maps a checkout-relative path to its line count. */
function warnings(policy: Policy, lines: Map<string, number>): string[] {
  const test = new RegExp(policy.testPathPattern);
  const found: string[] = [];
  for (const [file, count] of [...lines].sort(([a], [b]) => a.localeCompare(b))) {
    const kind = test.test(file) ? "test" : "production";
    const { soft, hard } = policy.limits[kind];
    const ceiling = policy.legacyCeilings[file];
    if (ceiling !== undefined) {
      found.push(`${file}: ${count} lines, past the hard limit of ${hard} for ${kind} code and held at ${ceiling}; `
        + "split it before adding to it");
    } else if (count > hard) {
      found.push(`${file}: ${count} lines, past the hard limit of ${hard} for ${kind} code; `
        + "leakGuardTest refuses it, so split it now");
    } else if (count > soft) {
      found.push(`${file}: ${count} lines, past the soft limit of ${soft} for ${kind} code (hard limit ${hard}); `
        + "split it by responsibility before it grows further");
    }
  }
  return found;
}

function main(files: string[]): void {
  const policy: Policy = JSON.parse(fs.readFileSync(POLICY, "utf8"));
  const lines = new Map<string, number>();
  for (const file of files.filter((f) => SOURCE.test(f))) {
    const absolute = path.join(ROOT, file);
    if (fs.existsSync(absolute)) lines.set(file, physicalLines(fs.readFileSync(absolute, "utf8")));
  }
  const found = warnings(policy, lines);
  if (found.length > 0) {
    console.error("\npre-commit: staged source files over a size limit (a warning here; leakGuardTest enforces the hard limit):\n");
    for (const warning of found) console.error(`  ${warning}`);
    console.error("\nThe limits and the shrink-only list live in scripts/source-size-limits.json.\n");
  }
}

if (require.main === module) main(process.argv.slice(2));

module.exports = { physicalLines, warnings };
