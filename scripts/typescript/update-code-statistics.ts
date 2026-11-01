const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

type Counts = { reports?: unknown[]; code?: number; comments?: number; blanks?: number };
type Sources = Record<string, string[]>;
type Section = { heading: string; introduction: string; sources: Sources };
type Artifact = { id: string; target: string; description: string; sections: Section[] };

const JAVA_SOURCES: string[] = [
  "core/src/main/java",
  "rulekit/src/main/java",
  "modelkit/src/main/java",
  "edit/src/main/java",
  "schemakit/src/main/java",
  "adapter/src/main/java",
  "cli/src/main/java"
];
const KOTLIN_INTERPRETER_SOURCES: string[] = [
  "interpreter/src/commonMain/kotlin",
  "interpreter/src/jvmMain/kotlin",
  "interpreter/src/jsMain/kotlin"
];
const KOTLIN_SOURCES: string[] = [
  ...KOTLIN_INTERPRETER_SOURCES,
  "consistency/src/commonMain/kotlin",
  "consistency/src/jsMain/kotlin",
  "predicates/src/commonMain/kotlin",
  "interpreter-bridge/src/main/kotlin",
  "consistency-gate/src/main/kotlin"
];
const TYPESCRIPT_LIBRARY_SOURCES: string[] = [
  "interpreter/typescript/src"
];
const TYPESCRIPT_SHOWCASE_SOURCES: string[] = [
  "interpreter/consumer-tests/browser/src"
];
const JAVA_TEST_SOURCES: string[] = [
  "src/test/java",
  "schemakit/src/test/java",
  "adapter/src/test/java",
  "cli/src/test/java",
  "src/noBackendTest/java",
  "src/releaseGuardTest/java",
  "adapter/src/kernelClosureConsumer/java",
  "adapter/src/testFixtures/java",
  "interpreter/src/jvmTest/java",
  "consistency-gate/src/test/java",
  "interpreter/consumer-tests/kotlin-jvm/src/main/java"
];
const KOTLIN_TEST_SOURCES: string[] = [
  "adapter/src/test/kotlin",
  "interpreter/src/commonTest/kotlin",
  "interpreter/src/jvmTest/kotlin",
  "interpreter/src/jsTest/kotlin",
  "consistency/src/commonTest/kotlin",
  "predicates/src/commonTest/kotlin",
  "interpreter-bridge/src/test/kotlin",
  "consistency-gate/src/test/kotlin",
  "interpreter/consumer-tests/kotlin-jvm/src/main/kotlin",
  "interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin"
];
const KOTLIN_INTERPRETER_TEST_SOURCES: string[] = [
  "interpreter/src/commonTest/kotlin",
  "interpreter/src/jvmTest/kotlin",
  "interpreter/src/jsTest/kotlin",
  "interpreter/consumer-tests/kotlin-jvm/src/main/kotlin",
  "interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin"
];
const TOOLING_JAVA_SOURCES: string[] = [
  "scripts/SourceProvenance.java"
];
const TOOLING_KOTLIN_SOURCES: string[] = [
  "interpreter/consumer-tests/kotlin-jvm/build.gradle.kts",
  "interpreter/consumer-tests/kotlin-jvm/settings.gradle.kts",
  "interpreter/consumer-tests/kotlin-js/build.gradle.kts",
  "interpreter/consumer-tests/kotlin-js/settings.gradle.kts"
];
const TOOLING_JAVASCRIPT_SOURCES: string[] = [
  "eval/benchmark.workflow.js",
  "eval/gap-finder.workflow.js",
  "interpreter/typescript/scripts"
];
const PRODUCTION_JAVASCRIPT_SOURCES: string[] = [
  "cli/src/main/resources/io/github/mbackschat/a12/dm/cli/interp/custom-js-worker.mjs"
];
const TYPESCRIPT_TEST_SOURCES: string[] = [
  "interpreter/consumer-tests/typescript/src",
  "interpreter/consumer-tests/browser/scripts"
];

const production = (sources: Sources): Section => ({ heading: "Production", introduction: "The shipped product.", sources });
const verification = (sources: Sources): Section => ({
  heading: "Verification",
  introduction: "Tests, laws, kernel differentials, fuzzers, conformance replay, and the external consumer programs compiled against the packed artifacts.",
  sources,
});

function artifacts(): Record<string, Artifact> {
  const product: Sources = { Java: JAVA_SOURCES, Kotlin: KOTLIN_SOURCES };
  const verificationSources: Sources = { Java: JAVA_TEST_SOURCES, Kotlin: KOTLIN_TEST_SOURCES };
  const typescript = [...TYPESCRIPT_LIBRARY_SOURCES, ...TYPESCRIPT_SHOWCASE_SOURCES];
  return {
    root: {
      id: "root", target: "README.md", description: "this repository",
      sections: [
        production({ ...product, TypeScript: typescript, JavaScript: PRODUCTION_JAVASCRIPT_SOURCES }),
        verification({ ...verificationSources, TypeScript: TYPESCRIPT_TEST_SOURCES }),
        { heading: "Tooling", introduction: "Build scripts, build-time generators, and the evaluation harness — neither shipped nor a check on what ships.",
          sources: { Java: TOOLING_JAVA_SOURCES, Kotlin: TOOLING_KOTLIN_SOURCES, JavaScript: TOOLING_JAVASCRIPT_SOURCES } },
      ],
    },
    dmtool: {
      id: "dmtool", target: "plugin/README.md", description: "the sources linked into the dmtool artifact",
      sections: [production({ ...product, JavaScript: PRODUCTION_JAVASCRIPT_SOURCES }), verification(verificationSources)],
    },
    interpreter: {
      id: "interpreter-release", target: "interpreter/public-release/README.md",
      description: "the standalone interpreter library, TypeScript facade, and browser showcase",
      sections: [
        production({ Kotlin: KOTLIN_INTERPRETER_SOURCES, TypeScript: typescript }),
        verification({ Kotlin: KOTLIN_INTERPRETER_TEST_SOURCES, TypeScript: TYPESCRIPT_TEST_SOURCES }),
      ],
    },
  };
}

function render(root: string, artifact: Artifact): string {
  const output = [
    `<!-- source-statistics:${artifact.id}:start -->`, "## Source statistics", "",
    `Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from ${artifact.description}. Fixtures, generated output and build trees are excluded from every count.`,
  ];
  for (const section of artifact.sections) {
    const paths = Object.values(section.sources).flat().filter(relative => fs.existsSync(path.join(root, relative)));
    output.push("", `### ${section.heading}`, "", `${section.introduction} Counted from ${paths.map(relative => `\`${relative}\``).join(", ")}.`, "",
      "| Language | Files | Lines | Code | Comments | Blanks |", "|---|---:|---:|---:|---:|---:|");
    for (const [language, sources] of Object.entries(section.sources)) {
      const report: Record<string, Counts> = JSON.parse(execFileSync("tokei", ["--output", "json", "--types", language, ...sources.map(relative => path.join(root, relative))],
        { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 30_000 }));
      const counts = report[language] ?? {};
      const files = counts.reports?.length ?? 0;
      if (files === 0) continue;
      const code = counts.code ?? 0, comments = counts.comments ?? 0, blanks = counts.blanks ?? 0;
      output.push(`| ${language} | ${files} | ${code + comments + blanks} | ${code} | ${comments} | ${blanks} |`);
    }
  }
  output.push("", "Maintainers regenerate these tables with the local statistics updater; both release publishers compare them with fresh counts before any public mutation.",
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
        console.log("Usage: update-code-statistics.sh [--check] [--artifact all|root|dmtool|interpreter]\n\nUpdate public README tables or compare them with fresh Tokei measurements. Requires local tokei.");
        return 0;
      default: throw new UsageError(`unknown argument: ${args[index]}`);
    }
  }
  const registry = artifacts();
  if (selected !== "all" && !Object.hasOwn(registry, selected)) throw new UsageError(`artifact must be all, root, dmtool, or interpreter: ${selected}`);
  const root = path.resolve(__dirname, "../..");
  let failures = 0;
  for (const artifact of selected === "all" ? Object.values(registry) : [registry[selected]]) {
    const target = path.join(root, artifact.target), current = fs.readFileSync(target, "utf8");
    const start = `<!-- source-statistics:${artifact.id}:start -->`, end = `<!-- source-statistics:${artifact.id}:end -->`;
    const lines = current.split("\n"), first = lines.indexOf(start), last = lines.indexOf(end);
    if (first < 0 || last < first || lines.filter(line => line === start).length !== 1 || lines.filter(line => line === end).length !== 1) {
      throw new Error(`${artifact.target} must contain exactly one source-statistics block '${artifact.id}'`);
    }
    const expected = render(root, artifact);
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

if (require.main === module) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) { console.error(`source statistics: ${error instanceof Error ? error.message : String(error)}`); process.exitCode = error instanceof UsageError ? 2 : 1; }
}
