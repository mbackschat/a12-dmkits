const fs = require("node:fs");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");

type Demo = { path: string; content: string };
type VerifyResult = { path: string; ok: boolean; output: string };

const COMMAND_WIDTH_GOAL = 120;
const COMMAND_WIDTH_BUDGET: Readonly<Record<string, number>> = {
  "cli-apply.md": 164,
  "cli-custom-types.md": 103,
  "cli-discover.md": 128,
  "cli-edit-loop.md": 207,
  "cli-jsonschema.md": 149,
  "cli-mcp.md": 110,
  "cli-public-contract.md": 106,
  "cli-review.md": 173,
  "cli-runtime.md": 122,
  "cli-structure-edit.md": 232,
  "cli-tour.md": 142,
  "cli-version.md": 109,
  "cli-workspace.md": 125,
};

function discoverDemos(root: string): string[] {
  const directory = path.join(root, "examples");
  if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) return [];
  return fs.readdirSync(directory)
    .filter(name => name.endsWith(".md") && name !== "README.md")
    .sort()
    .filter(name => fs.readFileSync(path.join(directory, name), "utf8").includes("```output"))
    .map(name => `examples/${name}`);
}

function loadDemos(root: string, relativePaths: readonly string[]): Demo[] {
  return relativePaths.map(relative => ({ path: relative, content: fs.readFileSync(path.join(root, relative), "utf8") }));
}

function fencedHttpDemos(demos: readonly Demo[]): string[] {
  return demos.filter(demo => {
    let fenced = false;
    for (const line of demo.content.split(/\r?\n/)) {
      if (line.startsWith("```")) {
        fenced = !fenced;
        continue;
      }
      if (fenced && /mcp\s+--http/.test(line)) return true;
    }
    return false;
  }).map(demo => demo.path);
}

function longestBashLine(content: string): number {
  let fenced = false;
  let language = "";
  let longest = 0;
  for (const line of content.split(/\r?\n/)) {
    if (line.startsWith("```")) {
      if (!fenced) language = line.slice(3).trim();
      fenced = !fenced;
      continue;
    }
    if (fenced && language === "bash") longest = Math.max(longest, line.length);
  }
  return longest;
}

function commandWidthFindings(demos: readonly Demo[]): string[] {
  const findings: string[] = [];
  for (const demo of demos) {
    const name = path.basename(demo.path);
    const longest = longestBashLine(demo.content);
    const allowed = COMMAND_WIDTH_BUDGET[name] ?? COMMAND_WIDTH_GOAL;
    if (longest <= allowed) continue;
    findings.push(`${name}: longest bash line ${longest} > ${allowed}`
      + (name in COMMAND_WIDTH_BUDGET ? "" : ` (the bar for a new demo is ${COMMAND_WIDTH_GOAL})`));
  }
  return findings;
}

function parseConcurrency(value: string | undefined): number {
  const candidate = value ?? "6";
  if (!/^[1-9][0-9]*$/.test(candidate)) throw new Error(`VERIFY_JOBS must be a positive integer, got '${candidate}'`);
  return Number(candidate);
}

async function runBounded<T, R>(
  values: readonly T[],
  cap: number,
  worker: (value: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = [];
  for (let offset = 0; offset < values.length; offset += cap) {
    results.push(...await Promise.all(values.slice(offset, offset + cap).map(worker)));
  }
  return results;
}

function verifyDemo(root: string, relative: string, environment: Record<string, string | undefined>): Promise<VerifyResult> {
  return new Promise(resolve => {
    const child = spawn("uvx", ["showboat@0.6.1", "verify", relative], {
      cwd: root,
      env: environment,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.on("data", (data: Buffer) => { output += data.toString(); });
    child.stderr.on("data", (data: Buffer) => { output += data.toString(); });
    child.on("error", (error: Error) => resolve({ path: relative, ok: false, output: `${output}${error.message}\n` }));
    child.on("close", (status: number | null) => resolve({ path: relative, ok: status === 0, output }));
  });
}

async function main(): Promise<number> {
  const root = path.resolve(__dirname, "../..");
  console.log("==> building the JVM CLI launcher (installDist — always current; native is never used here)");
  const build = spawnSync("./build.sh", ["--no-daemon", ":cli:installDist"], { cwd: root, stdio: "inherit" });
  if (build.error) throw build.error;
  if (build.status !== 0) return build.status ?? 1;

  const environment: Record<string, string | undefined> = {
    ...process.env,
    PATH: `${path.join(root, "bin")}${path.delimiter}${process.env.PATH ?? ""}`,
    DMTOOL_CLI: path.join(root, "cli/build/install/dmtool/bin/dmtool"),
  };
  delete environment.RK_NATIVE;

  const relativePaths = discoverDemos(root);
  if (relativePaths.length === 0) {
    console.error("no Showboat demos found under examples/ — nothing to verify");
    return 1;
  }
  const demos = loadDemos(root, relativePaths);
  const hangs = fencedHttpDemos(demos);
  if (hangs.length > 0) {
    console.error(`demo(s) carry a fenced \`mcp --http\` block, which never returns: ${hangs.join(" ")}`);
    console.error("indent that command by four spaces instead of fencing it (see the verifier source)");
    return 1;
  }
  const widths = commandWidthFindings(demos);
  if (widths.length > 0) {
    console.error("command lines got wider — break them at the pipes with a trailing backslash:");
    console.error(`  ${widths.join("\n  ")}`);
    return 1;
  }

  const cap = parseConcurrency(process.env.VERIFY_JOBS);
  console.log(`==> verifying ${demos.length} demos in parallel (cap ${cap})`);
  const results = await runBounded(relativePaths, cap, relative => verifyDemo(root, relative, environment));
  let failed = false;
  for (const result of results) {
    const name = path.basename(result.path, ".md");
    if (result.ok) console.log(`==> verify ${name} ... OK`);
    else {
      failed = true;
      console.log(`==> verify ${name} ... DRIFT`);
      process.stdout.write(result.output);
    }
  }
  if (failed) {
    console.error("");
    console.error("A demo drifted from the live CLI. Re-run the demo's command, paste the new output into its");
    console.error("examples/*.md block, and re-run this script until green — BEFORE committing (CLI-TOUR-SPEC).");
    return 1;
  }
  console.log(`==> all ${demos.length} discovered demos match the live CLI`);
  return 0;
}

module.exports = { commandWidthFindings, discoverDemos, fencedHttpDemos, parseConcurrency, runBounded };

if (require.main === module) {
  main().then(status => { process.exitCode = status; }).catch(error => {
    console.error(`verify-demos: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
