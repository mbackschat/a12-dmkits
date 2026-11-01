const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

type Counts = { code: number; comments: number; blanks: number; lines: number };
type Source = { path: string; language: string; kind: string; purpose: string; category: string; counts: Counts };
type Report = { metadata: { coverage: { unrecognized: string[] } }; files: Source[] };
type Section = "Production" | "Verification" | "Tooling";
type Artifact = { id: string; target: string; description: string; modules?: string[]; sections: Section[] };

// These are artifact boundaries; .locmetryrc alone decides a file's purpose and category within them.
const ARTIFACTS: Record<string, Artifact> = {
  root: { id: "root", target: "README.md", description: "this repository", sections: ["Production", "Verification", "Tooling"] },
  dmtool: {
    id: "dmtool", target: "plugin/README.md", description: "the dmtool modules and their verification sources",
    modules: ["src", "core", "rulekit", "modelkit", "edit", "schemakit", "adapter", "cli", "interpreter", "consistency", "predicates", "interpreter-bridge", "consistency-gate", "shared-regex"],
    sections: ["Production", "Verification"],
  },
  checker: {
    id: "checker-release", target: "consistency/public-release/README.md",
    description: "the portable checker, predicate dependency, shared regex sources, and browser showcase",
    modules: ["consistency", "predicates", "shared-regex"], sections: ["Production", "Verification"],
  },
  interpreter: {
    id: "interpreter-release", target: "interpreter/public-release/README.md",
    description: "the standalone interpreter, shared regex sources, TypeScript facade, and browser showcase",
    modules: ["interpreter", "shared-regex"], sections: ["Production", "Verification"],
  },
};
const INTRODUCTIONS: Record<Section, string> = {
  Production: "Product implementation and examples.",
  Verification: "Automated tests, laws, differentials, consumer checks, and their support code.",
  Tooling: "Build, verification, release, deployment, and maintainer tooling.",
};

function sectionOf(file: Source): Section | undefined {
  if (file.kind !== "source" || file.category === "generated") return undefined;
  switch (file.purpose) {
    case "test": return "Verification";
    case "other":
      if (file.category !== "unclaimed") return undefined;
      break;
    case "production":
      switch (file.category) {
        case "implementation": case "example": case "plugin": return "Production";
        case "build-tooling": case "verification-tooling": case "distribution": case "deployment":
        case "tooling": case "agent-config": return "Tooling";
      }
  }
  throw new Error(`source has no statistics section: ${file.path} (${file.purpose}/${file.category}); review .locmetryrc`);
}

function measureRepository(root: string): Report {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "locmetry-statistics-"));
  try {
    // The size guard uses this same isolation: personal Git ignores must not change published counts.
    const report: Report = JSON.parse(execFileSync(process.env.LOCMETRY ?? "locmetry", ["--root", root, "--no-user-config", "--files", "--format", "json"],
      { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 30_000, env: { ...process.env, HOME: home, XDG_CONFIG_HOME: home } }));
    const tracked = new Set<string>(execFileSync("git", ["-C", root, "ls-files", "-z"], { encoding: "utf8" }).split("\0")
      .filter((relative: string) => relative && relative !== ".locmetryrc" && !fs.lstatSync(path.join(root, relative)).isSymbolicLink()));
    const seen = new Set([...report.files.map(file => file.path), ...report.metadata.coverage.unrecognized]);
    const missing = [...tracked].filter(relative => !seen.has(relative));
    if (missing.length) throw new Error(`unaccounted tracked files in locmetry report: ${missing.join(", ")}`);
    report.files = report.files.filter(file => tracked.has(file.path));
    for (const file of report.files) sectionOf(file);
    return report;
  } finally {
    fs.rmSync(home, { recursive: true, force: true });
  }
}

function selectSources(report: Report, selected: string, section: Section): Source[] {
  const artifact = ARTIFACTS[selected];
  return report.files.filter(file => sectionOf(file) === section
    && (!artifact.modules || artifact.modules.some(module => file.path.startsWith(`${module}/`)))
    && !(selected === "dmtool" && section === "Production" && file.category === "example"));
}

function render(report: Report, selected: string): string {
  const artifact = ARTIFACTS[selected];
  const policy = selected === "root" ? ".locmetryrc" : `https://github.com/mbackschat/${selected === "dmtool" ? "a12-dmtool-releases" : selected === "checker" ? "a12-consistency-releases" : "a12-interpreter-releases"}/blob/main/.locmetryrc`;
  const output = [
    `<!-- source-statistics:${artifact.id}:start -->`, "## Source statistics", "",
    `Measured with [locmetry](https://github.com/mbackschat/locmetry) from ${artifact.description}, using [\`.locmetryrc\`](${policy}). Only tracked source files are counted; generated code, data fixtures, documentation, third-party wrappers, and build output are excluded. Code excludes comments and blank lines.`,
  ];
  for (const section of artifact.sections) {
    output.push("", `### ${section}`, "", selected === "dmtool" && section === "Production" ? "Product implementation." : INTRODUCTIONS[section], "",
      "| Language | Files | Lines | Code | Comments | Blanks |", "|---|---:|---:|---:|---:|---:|");
    const languages = new Map<string, Counts & { files: number }>();
    for (const file of selectSources(report, selected, section)) {
      const total = languages.get(file.language) ?? { files: 0, lines: 0, code: 0, comments: 0, blanks: 0 };
      total.files++;
      for (const key of ["lines", "code", "comments", "blanks"] as const) total[key] += file.counts[key];
      languages.set(file.language, total);
    }
    const total = { files: 0, lines: 0, code: 0, comments: 0, blanks: 0 };
    for (const [language, n] of [...languages].sort(([a], [b]) => a.localeCompare(b, "en"))) {
      output.push(`| ${language} | ${n.files} | ${n.lines} | ${n.code} | ${n.comments} | ${n.blanks} |`);
      for (const key of ["files", "lines", "code", "comments", "blanks"] as const) total[key] += n[key];
    }
    output.push(`| **Total** | **${total.files}** | **${total.lines}** | **${total.code}** | **${total.comments}** | **${total.blanks}** |`);
  }
  output.push("", "Maintainers regenerate these tables with the latest locmetry; release publishers compare them with fresh counts before any public mutation.",
    `<!-- source-statistics:${artifact.id}:end -->`);
  return output.join("\n");
}

class UsageError extends Error {}

function main(args: string[]): number {
  let check = false, selected = "all";
  for (let index = 0; index < args.length; index++) {
    switch (args[index]) {
      case "--check": check = true; break;
      case "--artifact": selected = args[++index]; break;
      case "-h": case "--help":
        console.log("Usage: update-code-statistics.sh [--check] [--artifact all|root|dmtool|interpreter|checker]\n\nUpdate public README tables or compare them with fresh locmetry measurements. Use the latest locmetry; .locmetryrc defines classification.");
        return 0;
      default: throw new UsageError(`unknown argument: ${args[index]}`);
    }
  }
  if (selected !== "all" && !Object.hasOwn(ARTIFACTS, selected)) throw new UsageError(`artifact must be all, root, dmtool, interpreter, or checker: ${selected}`);
  const root = path.resolve(__dirname, "../..");
  const report = measureRepository(root);
  let failures = 0;
  for (const key of selected === "all" ? Object.keys(ARTIFACTS) : [selected]) {
    const artifact = ARTIFACTS[key];
    const target = path.join(root, artifact.target), current = fs.readFileSync(target, "utf8");
    const start = `<!-- source-statistics:${artifact.id}:start -->`, end = `<!-- source-statistics:${artifact.id}:end -->`;
    const lines = current.split("\n"), first = lines.indexOf(start), last = lines.indexOf(end);
    if (first < 0 || last < first || lines.filter(line => line === start).length !== 1 || lines.filter(line => line === end).length !== 1) {
      throw new Error(`${artifact.target} must contain exactly one source-statistics block '${artifact.id}'`);
    }
    const expected = render(report, key);
    if (lines.slice(first, last + 1).join("\n") === expected) continue;
    if (check) {
      console.error(`stale source statistics: ${artifact.target}; run scripts/update-code-statistics.sh --artifact ${selected}`);
      failures++;
    } else {
      lines.splice(first, last - first + 1, expected);
      fs.writeFileSync(target, lines.join("\n"));
      console.log(`updated ${artifact.target}`);
    }
  }
  if (failures > 0) return 1;
  if (check) console.log(`source statistics are current (${selected})`);
  return 0;
}

module.exports = { measureRepository, selectSources, sectionOf };

if (require.main === module) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) { console.error(`source statistics: ${error instanceof Error ? error.message : String(error)}`); process.exitCode = error instanceof UsageError ? 2 : 1; }
}
