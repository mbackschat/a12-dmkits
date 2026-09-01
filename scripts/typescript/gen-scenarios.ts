const fs = require("node:fs");
const path = require("node:path");

type TaskFile = { name: string; content: string };
type Validation = { ok: true; count: number } | { ok: false; message: string };

const HEADER = `# dmtool — example multi-step sessions

<!-- GENERATED — do not edit by hand; regenerate with scripts/gen-scenarios.sh. -->

Realistic, **multi-turn** sessions an author (or a coding agent) drives \`dmtool\` through — each a sequence of plain-language asks, *in order*, that build up or evolve a document model. They show the *kind* of non-trivial, multi-step work the CLI is exercised with: from-scratch authoring, evolving an existing model's rules, refactors, computations, dates and ranges, multi-file workspaces, and more.

These are the **asks**, not worked transcripts. For step-by-step sessions **with the actual \`dmtool\` commands and their output** — re-executed and exact-matched in CI — see the demos in [\`examples/\`](examples/README.md) (e.g. [\`cli-edit-loop\`](examples/cli-edit-loop.md), [\`cli-apply\`](examples/cli-apply.md), [\`cli-structure-edit\`](examples/cli-structure-edit.md)).
`;

function title(firstLine: string): string {
  const separator = firstLine.indexOf("— ");
  const withoutPrefix = separator < 0 ? firstLine : firstLine.slice(separator + 2);
  const qualifier = withoutPrefix.indexOf(" (");
  return qualifier < 0 ? withoutPrefix : withoutPrefix.slice(0, qualifier);
}

function prompts(content: string): string[] {
  const result: string[] = [];
  const lines = content.split(/\r?\n/);
  let inSession = false;
  let collecting = false;
  let parts: string[] = [];
  const flush = (): void => {
    if (parts.length > 0) result.push(parts.join(" "));
    parts = [];
  };

  for (const line of lines) {
    if (line.startsWith("## The session")) {
      inSession = true;
      continue;
    }
    if (inSession && line.startsWith("## ")) {
      flush();
      break;
    }
    if (!inSession) continue;
    if (line.startsWith("**Turn ")) {
      flush();
      collecting = true;
      continue;
    }
    if (collecting && line.startsWith("> ")) {
      parts.push(line.slice(2));
      continue;
    }
    if (collecting && line === ">") continue;
    if (collecting) {
      flush();
      collecting = false;
    }
  }
  if (inSession) flush();
  return result;
}

function renderScenarios(tasks: readonly TaskFile[]): string {
  let output = HEADER;
  for (const task of tasks) {
    const firstLine = task.content.split(/\r?\n/, 1)[0] ?? "";
    output += `\n## ${title(firstLine)}\n\n`;
    prompts(task.content).forEach((prompt, index) => {
      output += `${index + 1}. ${prompt}\n`;
    });
  }
  return output;
}

function validateScenarios(output: string): Validation {
  const count = output.split(/\r?\n/).filter(line => line.startsWith("## ")).length;
  if (count < 1) {
    return { ok: false, message: "gen-scenarios extracted 0 scenarios — did probe/tasks/ structure change?" };
  }
  if (/STOP —|^## (?:Gold|What|Anti-overfitting|Source)|MVK_/m.test(output)) {
    return { ok: false, message: "internal framing (gold/STOP/section/MVK) leaked into the generated scenarios" };
  }
  return { ok: true, count };
}

function readTaskFiles(root: string): TaskFile[] {
  const directory = path.join(root, "probe/tasks");
  if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) return [];
  return fs.readdirSync(directory)
    .filter(name => name.endsWith(".md"))
    .sort()
    .map(name => ({ name, content: fs.readFileSync(path.join(directory, name), "utf8") }));
}

function main(): number {
  const root = path.resolve(__dirname, "../..");
  const output = renderScenarios(readTaskFiles(root));
  if (process.argv[2] !== "--check") {
    process.stdout.write(output);
    return 0;
  }
  const validation = validateScenarios(output);
  if (!validation.ok) {
    console.error(`FAIL: ${validation.message}`);
    return 1;
  }
  console.log(`OK: gen-scenarios produces ${validation.count} scenarios, no internal framing leaked`);
  return 0;
}

module.exports = { renderScenarios, validateScenarios };

if (require.main === module) {
  try {
    process.exitCode = main();
  } catch (error) {
    console.error(`gen-scenarios: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
