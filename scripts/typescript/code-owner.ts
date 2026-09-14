const fs = require("node:fs");
const path = require("node:path");

/**
 * Who already reports an A12 diagnostic class, answered from SOURCE rather than from prose.
 *
 * The prior-art check this exists for is the one that keeps failing by succeeding: a grep for a code
 * returns a confident, on-topic hit describing a NARROWER carrier than the one being asked about, and the
 * enquiry stops there. It cost a duplicated family on 2026-09-14 — `MVK_DUPLICATE_PARAM1` was reported, on
 * six group-list carriers, and the field-list question it was asked about was genuinely open. So the report
 * separates the two answers that prose runs together: which family CONSTRUCTS a finding with the code, and
 * what the ledger CLAIMS about it. A disagreement between those two columns is itself the finding.
 */

const ROOT: string = path.resolve(__dirname, "../..");
const LEDGER: string = path.join(ROOT, "docs/MVK-LEDGER.md");
const CHECKER: string = path.join(ROOT, "consistency/src/commonMain/kotlin");
const KERNEL: string = path.resolve(ROOT, "../a12-kernel");

type Owner = { file: string; family: string | undefined };
type Report = {
  code: string;
  owners: Owner[];
  reportedClaim: boolean;
  disposition: string | undefined;
  locks: string[];
};

function usage(): never {
  console.error("usage: scripts/run-typescript.sh scripts/typescript/code-owner.ts <CODE|CheckClass>...");
  console.error("  CODE       an MVK_/CK_ class — who reports it, what the ledger claims, which locks cite it");
  console.error("  CheckClass a kernel check class — its codes and their coverage, for picking a slice");
  process.exit(2);
}

/** Every `*.kt` under a directory, ignoring build output. */
function sources(dir: string): string[] {
  const found: string[] = [];
  const walk = (at: string): void => {
    for (const entry of fs.readdirSync(at, { withFileTypes: true })) {
      const full = path.join(at, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "build") walk(full);
      } else if (entry.name.endsWith(".kt")) {
        found.push(full);
      }
    }
  };
  if (fs.existsSync(dir)) walk(dir);
  return found;
}

const CACHE = new Map<string, string>();

/**
 * One read per file per process, with comments removed.
 *
 * Stripping is not tidiness: this estate's KDoc routinely names the neighbouring class a family stays SILENT
 * on, so a scan that keeps comments reports those as reports. Measured — keeping them produced four false
 * positives over the checker's sources, and `CheckerLedgerDispositionGuardTest` strips for the same reason.
 */
function readCached(file: string): string {
  const held: string | undefined = CACHE.get(file);
  if (held !== undefined) return held;
  const text: string = withoutComments(fs.readFileSync(file, "utf8"));
  CACHE.set(file, text);
  return text;
}

/**
 * Kotlin block and line comments removed, string literals left intact.
 *
 * A regex cannot do this: `"http://x"` loses half its value to the line-comment rule, which this file's own
 * test caught. So the scan tracks whether it is inside a string — raw `"""` blocks included, since the
 * checker's messages use them — and only then honours a comment opener.
 */
function withoutComments(text: string): string {
  let out = "";
  let at = 0;
  while (at < text.length) {
    const rest: string = text.slice(at);
    if (rest.startsWith("/*")) {
      const close: number = text.indexOf("*/", at + 2);
      at = close < 0 ? text.length : close + 2;
      out += " ";
    } else if (rest.startsWith("//")) {
      const newline: number = text.indexOf("\n", at);
      at = newline < 0 ? text.length : newline;
      out += " ";
    } else if (rest.startsWith('"""')) {
      const close: number = text.indexOf('"""', at + 3);
      const end: number = close < 0 ? text.length : close + 3;
      out += text.slice(at, end);
      at = end;
    } else if (rest.startsWith('"')) {
      let cursor: number = at + 1;
      while (cursor < text.length && text[cursor] !== '"') {
        cursor += text[cursor] === "\\" ? 2 : 1;
      }
      out += text.slice(at, cursor + 1);
      at = cursor + 1;
    } else {
      out += text[at];
      at += 1;
    }
  }
  return out;
}


/**
 * The checker files that CONSTRUCT a finding carrying [code], with the family constant each is wired to.
 *
 * A bare occurrence is not a report: this estate's KDoc routinely names the neighbouring class a family
 * stays silent on, and a message may tell an author which code the reported one is NOT. So the scan takes a
 * code that reaches a `Finding(` construction or a `finding(` helper call — the same narrowing
 * `CheckerLedgerDispositionGuardTest` makes, and for the same reason.
 */
function ownersOf(code: string, files: readonly string[]): Owner[] {
  const owners: Owner[] = [];
  for (const file of files) {
    const text: string = readCached(file);
    const constants = new Map<string, string>();
    for (const match of text.matchAll(/(?:private |internal )?const val ([A-Z_][A-Z0-9_]*) = "([A-Z][A-Z0-9_]*)"/g)) {
      constants.set(match[2], match[1]);
    }
    const alias: string | undefined = constants.get(code);
    const reported = [...text.matchAll(/\b(?:Finding|finding)\s*\(([\s\S]{0,400}?)\)/g)]
      .some((call) => call[1].includes(`"${code}"`) || (alias !== undefined && new RegExp(`\\b${alias}\\b`).test(call[1])));
    if (!reported) continue;
    const family = text.match(/CheckFamily\.([A-Z_]+)/)?.[1];
    owners.push({ file: path.relative(ROOT, file), family });
  }
  return owners;
}

/** The ledger's row for [code]: its disposition cell, whether it claims a report, and the locks it cites. */
function ledgerRow(code: string, ledger: string): Pick<Report, "reportedClaim" | "disposition" | "locks"> {
  const row = ledger.split("\n").find((line) => line.startsWith(`| \`${code}\``));
  if (row === undefined) return { reportedClaim: false, disposition: undefined, locks: [] };
  const locks = [...row.matchAll(/\b([A-Z][A-Za-z0-9]*(?:Laws)?Test)\b/g)].map((m) => m[1]);
  return {
    reportedClaim: row.includes("checker-reported"),
    disposition: row.split("|")[2]?.trim(),
    locks: [...new Set(locks)],
  };
}

/** The A12 classes a kernel check class names, so a slice can be picked by FILE rather than by code. */
function codesThrownBy(checkClass: string): string[] {
  if (!fs.existsSync(KERNEL)) return [];
  const found = new Set<string>();
  const walk = (at: string): void => {
    for (const entry of fs.readdirSync(at, { withFileTypes: true })) {
      const full = path.join(at, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== "build" && !full.includes("delombok")) walk(full);
      } else if (entry.name === `${checkClass}.java`) {
        for (const match of fs.readFileSync(full, "utf8").matchAll(/\b(MVK_[A-Z0-9_]+)\b/g)) found.add(match[1]);
      }
    }
  };
  walk(KERNEL);
  return [...found].sort();
}

function report(code: string, files: readonly string[], ledger: string): Report {
  return { code, owners: ownersOf(code, files), ...ledgerRow(code, ledger) };
}

/** Whether anything already reports this code — the ledger's claim, or a report this scan could see. */
function covered(item: Report): boolean {
  return item.reportedClaim || item.owners.length > 0;
}

function render(item: Report): string {
  const lines: string[] = [];
  const owners = item.owners.length === 0
    ? "  reported by: NOBODY — no checker family constructs a finding with it"
    : item.owners.map((o) => `  reported by: ${o.family ?? "?"}  (${o.file})`).join("\n");
  lines.push(item.code);
  lines.push(owners);
  lines.push(`  ledger: ${item.disposition ?? "NO ROW"}${item.reportedClaim ? "  [claims checker-reported]" : ""}`);
  if (item.locks.length > 0) lines.push(`  locks: ${item.locks.join(", ")}`);
  // The two columns are independent readings of the same fact, so a disagreement is a real defect in one of
  // them rather than a display problem — which is why it is called out rather than reconciled silently.
  if (item.owners.length > 0 && !item.reportedClaim) {
    lines.push("  ** DRIFT: a family reports it and the ledger row does not say so");
  }
  // The reverse direction is deliberately a LEAD rather than a finding. No scan of this kind can settle it
  // without following every helper, and trying it over the 306-row ledger produced 71 false positives —
  // which is the same reasoning `CheckerLedgerDispositionGuardTest` gives for locking one direction only.
  if (item.owners.length === 0 && item.reportedClaim) {
    lines.push("  ?  lead: the ledger claims a report this scan did not find — verify by hand, not by this");
  }
  return lines.join("\n");
}

function main(argv: readonly string[]): void {
  if (argv.length === 0) usage();
  const files = sources(CHECKER);
  const ledger: string = fs.readFileSync(LEDGER, "utf8");
  const blocks: string[] = [];
  for (const argument of argv) {
    if (/^(MVK_|CK_)/.test(argument)) {
      blocks.push(render(report(argument, files, ledger)));
      continue;
    }
    const codes = codesThrownBy(argument);
    if (codes.length === 0) {
      blocks.push(`${argument}: no such kernel check class under ${path.relative(ROOT, KERNEL)}`);
      continue;
    }
    const items = codes.map((code) => report(code, files, ledger));
    // Coverage is read from the LEDGER, not from this file's source scan. The scan cannot follow every
    // indirection — a code parked in an enum constructor and reached as `law.code` is invisible to it — and
    // in file mode a false OPEN is the expensive direction: it sends a session to redo a finished family.
    // The ledger's cell is guard-locked in the reporting direction (`CheckerLedgerDispositionGuardTest`).
    const open = items.filter((item) => !covered(item));
    blocks.push(`${argument}: ${codes.length} classes, ${open.length} not yet reported\n`
      + items.map((item) => `  ${covered(item) ? "covered " : "OPEN    "} ${item.code}`
        + (covered(item) && item.owners.length > 0 ? `  (${item.owners[0].file.split("/").pop()})` : ""))
        .join("\n"));
  }
  console.log(blocks.join("\n\n"));
}

if (require.main === module) main(process.argv.slice(2));

module.exports = { ownersOf, ledgerRow, codesThrownBy, render, sources, withoutComments, covered };
