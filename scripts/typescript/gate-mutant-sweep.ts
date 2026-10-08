const fs = require("node:fs");
const path = require("node:path");

/**
 * Derive the duplicate-gate's DECISION SITES from source, and check the list against what is known.
 *
 * WHY THIS EXISTS. Twice in one slice a gate decision shipped with nothing able to catch its removal, and
 * neither time was it visible to any static check. Once a gate CALL could not fire at all — it ran over a list
 * the constructor had just forced empty, so a call-site scan reported the carrier as guarded. Once a refactor
 * MOVED three decisions onto a new type and their controls did not move with them, so a mutant emptying any of
 * the three survived the whole lane. Only mutation answers "can this site's removal be noticed", and only a
 * DERIVED site list answers "is there a site nobody is asking about" — which is the half that failed, because
 * both gaps were sites no one remembered to name.
 *
 * So this does not run the lane by default. It enumerates the sites from the source tree, fails when one turns
 * up that the table below does not know, and prints the exact per-site `mutant.sh` command. Running them is a
 * separate, slower act; knowing the denominator is the cheap part and the part that was missing.
 */

const DSL = "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl";

type Site = {
  readonly file: string;
  readonly kind: "call" | "identity" | "modifier";
  readonly detail: string;
  /** Test classes naming this site's carrier. Zero is a hard failure: nothing is asking about the site. */
  readonly controls: readonly string[];
};

/**
 * The site count, per kind, pinned. This is the half that failed twice: both gaps were decisions nobody had
 * named, so a list maintained by hand could not report them missing. A count cannot say WHICH site appeared —
 * only that one did, and that the table below needs a human look. Over-triggering costs one reading.
 */
const PINNED: Readonly<Record<string, number>> = { call: 12, identity: 8, modifier: 5 };

/**
 * What each site's mutation is expected to do, and why when it is not "killed".
 *
 * An expected survivor is a decision whose input no locus can currently author, so no test can reach it. Each
 * one names the gate that pre-empts it; none is a licence to leave a reachable decision uncontrolled.
 */
const EXPECTED: Readonly<Record<string, string>> = {
  "FirstFilledOperand.modifier":
    "survives: the field-keyed and $-correlated arms are refused by the semantic-index iteration gate before " +
    "the duplicate arm speaks (measured, DuplicateOverlapCarrierSweepLawsTest)",
  "IndexedFieldValue.modifier":
    "survives in part: the key's $ fold has no authorable pair — a correlated index key draws " +
    "MVK_INVALID_OUTER_ITERATION at a plain rule locus (measured)",
  "IndexedGroupValue.modifier":
    "survives in part: same as IndexedFieldValue's key fold",
};

const TESTS = "src/test/java";

function javaFiles(root: string): string[] {
  return fs
    .readdirSync(path.join(root, DSL))
    .filter((n: string) => n.endsWith(".java"))
    .sort();
}

/** Every {@code *.java} under the root test source set, as (basename, text). */
function testSources(root: string): { name: string; text: string }[] {
  const found: { name: string; text: string }[] = [];
  const walk = (dir: string) => {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith(".java")) {
        found.push({ name: path.basename(entry.name, ".java"), text: fs.readFileSync(full, "utf8") });
      }
    }
  };
  walk(path.join(root, TESTS));
  return found;
}

/**
 * Controls the reference scan CANNOT see, because the test reaches the carrier through the fluent surface and
 * never names the record. The scan's recall limit, written down rather than papered over by widening it.
 */
const CONTROLS: Readonly<Record<string, readonly string[]>> = {
  // `StringRef.in(StringRef…)` / `NumberRef.in(…)` build the record; the test authors through `name.in(name)`.
  ValueListCondition: ["ScalarValueListDuplicateGateTest", "ValueListDuplicateGateTest"],
  // The shape gate `ListAggregate` and `DateListAggregate` share; the tests author through `Dsl.maxValue`/`latestOf`.
  EntityListRoute: ["TemporalAggregateOverlapGateTest", "AggregateDuplicateWildcardTest"],
};

/**
 * The control classes for one carrier, narrowest first.
 *
 * A class whose NAME carries the carrier is preferred over one that merely references it, because that is the
 * filter a per-site mutant should run — a broad reference set makes the mutant expensive, which is what stops
 * anyone from running it.
 */
function controlsFor(carrier: string, tests: { name: string; text: string }[]): string[] {
  const declared = CONTROLS[carrier];
  if (declared) {
    // An override names a class; a name is not a class. Intersecting with the tree is what stops the override
    // from becoming the one row that claims a control without one existing.
    const present = new Set(tests.map((t) => t.name));
    return declared.filter((name) => present.has(name));
  }
  const referencing = tests.filter((t) => new RegExp(`\\b${carrier}\\b`).test(t.text)).map((t) => t.name);
  const named = referencing.filter((n) => n.includes(carrier));
  return [...named.sort(), ...referencing.filter((n) => !named.includes(n)).sort()];
}

function collectSites(root: string): Site[] {
  const sites: Site[] = [];
  const tests = testSources(root);
  for (const name of javaFiles(root)) {
    const file = path.join(DSL, name);
    const carrier = path.basename(name, ".java");
    const text = fs.readFileSync(path.join(root, file), "utf8");
    const controls = controlsFor(carrier, tests);
    if (name !== "DuplicateGate.java") {
      for (const m of text.matchAll(/DuplicateGate\.(reject\w+)\(/g)) {
        sites.push({ file, kind: "call", detail: m[1], controls });
      }
    }
    if (/Optional<String> exactIdentity\(\)/.test(text) && name !== "DuplicateOperand.java") {
      sites.push({ file, kind: "identity", detail: "exactIdentity", controls });
    }
    if (/String overlapModifier\(\)/.test(text) && name !== "DuplicateOperand.java") {
      sites.push({ file, kind: "modifier", detail: "overlapModifier", controls });
    }
  }
  return sites;
}

function key(site: Site): string {
  return `${path.basename(site.file, ".java")}.${site.kind}`;
}

function report(sites: Site[]): { lines: string[]; unknown: string[] } {
  const lines: string[] = [];
  const unknown: string[] = [];
  const byKind = { call: 0, identity: 0, modifier: 0 };
  for (const site of sites) {
    byKind[site.kind] += 1;
    const k = key(site);
    const note = EXPECTED[k];
    lines.push(`${k.padEnd(40)} ${(site.controls[0] ?? "NO CONTROL").padEnd(44)} `
      + `${note ? note : "expected: killed"}`);
    if (site.controls.length === 0) {
      unknown.push(`${k}: no test class names its carrier — nothing is asking about this decision.`);
    }
  }
  for (const kind of ["call", "identity", "modifier"] as const) {
    if (byKind[kind] !== PINNED[kind]) {
      unknown.push(
        `${kind} sites: found ${byKind[kind]}, pinned ${PINNED[kind]} — a decision site appeared or went. ` +
          `Name its expected outcome, then update the pin.`,
      );
    }
  }
  lines.unshift(`${"site".padEnd(40)} ${"candidate control".padEnd(44)} mutation expectation`);
  lines.push(
    `\nsites: ${sites.length} — ${byKind.call} gate call(s), ${byKind.identity} identity arm(s), ` +
      `${byKind.modifier} modifier arm(s)`,
  );
  return { lines, unknown };
}

module.exports = { collectSites, key, report, EXPECTED };

if (require.main === module) {
  const root = process.cwd();
  const sites = collectSites(root);
  const { lines, unknown } = report(sites);
  for (const line of lines) console.log(line);
  if (sites.length === 0) {
    console.error("no decision sites found — the enumeration lost the tree, which is a false clean sweep");
    process.exit(1);
  }
  for (const line of unknown) console.error(line);
  if (unknown.length > 0) process.exit(1);
  console.log("\nper-site mutation — substitute the decision and its collapse; the lane is inferred:");
  for (const site of sites) {
    console.log(`  ./scripts/mutant.sh ${site.file} '<decision>' '<collapse>' '*${site.controls[0]}*'`);
  }
}
