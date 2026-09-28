const fs = require("node:fs");
const { execFileSync, spawnSync } = require("node:child_process");
const gapId = "(?:[A-Z]*G|MR)[0-9]+";

function entries(text: string): Map<string, string> {
  const result = new Map<string, string>();
  let id: string | undefined;
  let depth = 0;
  let section = "";
  let lines: string[] = [];
  const finish = () => { if (id) result.set(id, lines.join("\n").trim()); };
  for (const line of text.split(/\r?\n/)) {
    const headingDepth = line.match(/^(#{1,6}) /)?.[1].length;
    const nextId = line.match(new RegExp(`^(?:- \\*\\*|#{2,6} )(${gapId})\\b`))?.[1];
    if (nextId || (headingDepth !== undefined && headingDepth <= (depth || 6)) || (depth === 0 && /^[-*] /.test(line))) {
      finish();
      id = undefined;
      lines = [];
    }
    if (headingDepth === 2) section = line;
    if (nextId) {
      id = nextId;
      depth = headingDepth ?? 0;
      lines = section ? [section] : [];
    }
    if (id) lines.push(line);
  }
  finish();
  return result;
}

function checkMessage(message: string, before: Map<string, string>, after: Map<string, string>): string[] {
  const text = message.split(/\r?\n/).filter(line => !line.startsWith("#")).join("\n");
  const [subject, ...rest] = text.split("\n");
  const ids = (value: string): Set<string> => new Set(value.match(new RegExp(`\\b${gapId}\\b`, "g")) ?? []);
  const cited = ids(text);
  const errors: string[] = [];
  for (const id of new Set([...before.keys(), ...after.keys()])) {
    if (before.get(id) !== after.get(id) && !cited.has(id)) errors.push(`changed gap entry ${id} must be cited in the commit message`);
  }
  const placement = cited.size === 1 ? ids(subject) : ids(rest.join("\n"));
  for (const id of cited) {
    if (!placement.has(id)) errors.push(`${id} must appear in the ${cited.size === 1 ? "subject for a single gap" : "body for multiple gaps"}`);
  }
  for (const closing of text.matchAll(new RegExp(`\\bCloses:?\\s+(${gapId}(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)${gapId})*)`, "gi"))) {
    for (const id of ids(closing[1].toUpperCase())) {
      if (!before.has(id) || after.has(id)) errors.push(`Closes ${id} requires removing its entry from all staged gap ledgers in this commit; use Advances for partial work`);
    }
  }
  return errors;
}

function git(...args: string[]): string {
  return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function snapshot(revision: "HEAD" | ""): Map<string, string> {
  const files = revision ? git("ls-tree", "-r", "--name-only", "-z", revision, "--", "docs") : git("ls-files", "--cached", "-z", "--", "docs");
  const result = new Map<string, string>();
  for (const file of files.split("\0").filter(file => /^docs\/[^/]+-GAPS\.md$/.test(file)).sort()) {
    for (const [id, body] of entries(git("show", `${revision}:${file}`))) {
      // Keep the owning path: moving an unchanged entry still needs a traceable commit (G-TRACE).
      result.set(id, (result.get(id) ?? "") + `${file}\n${body}\n`);
    }
  }
  return result;
}

if (require.main === module) {
  try {
    git("rev-parse", "--git-dir");
    const head = spawnSync("git", ["rev-parse", "--verify", "--quiet", "HEAD"], { encoding: "utf8" });
    if (head.status !== 0 && head.status !== 1) throw new Error(head.stderr || "cannot resolve HEAD");
    const errors = checkMessage(fs.readFileSync(process.argv[2], "utf8"), head.status === 0 ? snapshot("HEAD") : new Map(), snapshot(""));
    for (const error of errors) console.error(`commit-msg: ${error}`);
    process.exitCode = errors.length ? 1 : 0;
  } catch (error) {
    console.error(`commit-msg: cannot check gap references: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
module.exports = { entries, checkMessage };
