/**
 * Render a `dmtool batch` envelope as one verdict line per op, with a COLUMN PER CHANNEL so the three
 * things a probe can learn cannot be collapsed into one.
 *
 * Why this exists: a hand-rolled `[d.code for d in diagnostics]` projection discards the fields that
 * separate a kernel rejection of the MODEL from the CLI refusing an ARGUMENT, so `RK_INVALID_VALUE` reads as
 * the former when the envelope's own summary says "--field must be an absolute path". Every re-invention of
 * that projection discards the same fields, and a doc note does not help: `batch.schema.json` already says it.
 *
 * The three channels, which that projection lost:
 *   - did the op RUN, and was the request accepted at all?   `outcome` / `ok`
 *   - what did the SUBJECT verdict establish?                 `valid`
 *   - WHY?                                                    `diagnostics[].code` + `.source`
 * `batch.schema.json` states this directly ("A NONZERO CHILD IS NOT NECESSARILY A FAILED CHILD, AND `ok`
 * ALONE DOES NOT SEPARATE THEM … classify a child by what its own envelope ESTABLISHED"), so exit codes
 * cannot substitute for any of the three either.
 *
 * Why a formatter over `batch` rather than a runner of our own: `batch` is a public, schema-pinned,
 * CI-executed path that already takes `[{id, verb, args}]` and runs the whole table in ONE process,
 * echoing each `id` with its exact exit. A twelve-row probe table was twelve JVM starts by hand; it is one
 * here. This module invokes nothing itself, so it cannot drift from the tool's contract.
 *
 * Usage:
 *   dmtool batch probes.json | scripts/run-typescript.sh "probe verdicts" scripts/typescript/probe-verdicts.ts
 *
 * Output, one tab-separated line per op:
 *   <id>  RAN|REFUSED|REJECTED|ERROR   VALID|INVALID|-   <codes, or the refusal's own summary>
 */

interface Diagnostic {
  source?: string;
  code?: string;
  summary?: string;
  where?: { arg?: string };
}

interface ChildEnvelope {
  outcome?: string;
  ok?: boolean;
  valid?: boolean;
  summary?: string;
  diagnostics?: Diagnostic[];
}

interface OpResult {
  op?: number;
  id?: string;
  verb?: string;
  exit?: number;
  result?: ChildEnvelope;
  stdout?: string;
  stderr?: string;
}

interface Verdict {
  id: string;
  channel: "RAN" | "REFUSED" | "REJECTED" | "ERROR";
  verdict: "VALID" | "INVALID" | "-";
  detail: string;
}

/** Codes tagged with their source, so a PRECHECK code is never mistaken for a kernel one at a glance. */
function codesOf(diagnostics: Diagnostic[]): string {
  return diagnostics
    .filter((d) => d.code)
    .map((d) => (d.source && d.source !== "KERNEL" ? `${d.code}(${d.source})` : d.code))
    .join(",");
}

function verdictOfOp(entry: OpResult): Verdict {
  const id = entry.id ?? `op${entry.op ?? "?"}`;
  const child = entry.result;

  if (!child) {
    // No parsable child envelope: a throw, a non-JSON stdout, or a malformed op. Never silently "fine".
    const why = entry.stderr?.trim() || entry.stdout?.trim().split("\n", 1)[0] || "(no output)";
    return { id, channel: "ERROR", verdict: "-", detail: why.slice(0, 160) };
  }

  const diagnostics = child.diagnostics ?? [];
  const verdict: Verdict["verdict"] = child.valid === true ? "VALID" : child.valid === false ? "INVALID" : "-";

  // THE ENVELOPE'S OWN `outcome` DECIDES THE CHANNEL. A diagnostic's `source` may not override it: a REJECTED
  // subject also carries PRECHECK diagnostics, so testing `diagnostics.some(source === "PRECHECK")` would render
  // every spec-key rejection as a refused request — collapsing the very distinction this module draws
  // (`rule check --field ../X` refuses the request, exit 2; `rule add`'s `field: ../X` rejects the subject,
  // exit 1). `source` only picks WHICH diagnostic to quote.
  const refusalSummary = (): string => {
    const first = diagnostics.find((d) => d.source === "PRECHECK") ?? diagnostics[0];
    const arg = first?.where?.arg ? `${first.where.arg}: ` : "";
    return `${arg}${first?.summary ?? child.summary ?? "(no summary)"}`;
  };

  if (child.outcome === "refused") {
    // The CLI declined the REQUEST — it establishes nothing about the model, so it carries no verdict.
    return { id, channel: "REFUSED", verdict: "-", detail: refusalSummary() };
  }

  if (child.outcome === "rejected" || child.ok === false) {
    // The subject was READ and rejected. A pre-check rejection names the offending key, which the codes alone
    // do not, so it is quoted the same way a refusal is; a kernel rejection is best identified by its codes.
    const precheck = diagnostics.some((d) => d.source === "PRECHECK") && !diagnostics.some(
      (d) => d.source === "KERNEL");
    return {
      id,
      channel: "REJECTED",
      verdict,
      detail: precheck ? refusalSummary() : codesOf(diagnostics) || (child.summary ?? ""),
    };
  }
  return { id, channel: "RAN", verdict, detail: codesOf(diagnostics) };
}

function renderBatch(raw: string): string {
  let envelope: { data?: { results?: OpResult[] }; outcome?: string; summary?: string };
  try {
    envelope = JSON.parse(raw);
  } catch {
    const head = raw.trim().split("\n", 1)[0] ?? "";
    return `ERROR\tthe input is not a dmtool envelope: ${head.slice(0, 120)}`;
  }
  const results = envelope.data?.results;
  if (!results || results.length === 0) {
    // An empty table reads as "nothing wrong" in every count-based instrument; say so instead.
    return `ERROR\tno ops in the envelope (outcome=${envelope.outcome ?? "?"}): ${envelope.summary ?? ""}`;
  }
  return results
    .map(verdictOfOp)
    .map((v) => `${v.id}\t${v.channel}\t${v.verdict}\t${v.detail}`)
    .join("\n");
}

async function main(): Promise<void> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(Buffer.from(chunk));
  }
  process.stdout.write(`${renderBatch(Buffer.concat(chunks).toString("utf8"))}\n`);
}

module.exports = { renderBatch, verdictOfOp };

if (require.main === module) {
  // A stdin read failure must surface as one line, not as an unhandled rejection — the sibling reporter
  // (test-evidence.ts) does the same, and a probe instrument that dies noisily is read as "the probe failed".
  main().catch((error: unknown) => {
    console.error(`probe-verdicts: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
