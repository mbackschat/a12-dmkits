/**
 * Render a `dmtool batch` envelope as one verdict line per op, with a COLUMN PER CHANNEL so the three
 * things a probe can learn cannot be collapsed into one.
 *
 * Why this exists (measured 2026-09-04). A probing session hand-rolled the same
 * `[d.code for d in diagnostics]` projection eighteen times and, four of those times, read
 * `RK_INVALID_VALUE` as a kernel rejection of the MODEL when it was the CLI refusing an ARGUMENT — the
 * envelope's own summary said "--field must be an absolute path (e.g. /Group/Field), got: ../CustomerName".
 * The tool was never wrong; the instrument was, and it was wrong the same way each time because each
 * re-invention independently discarded the same fields. A doc note would not have helped: the fact is
 * already written in `batch.schema.json`, and the session had read it.
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

  // A refusal is the CLI declining the REQUEST — it establishes nothing about the model, so it must not
  // render like a verdict. This is exactly the distinction the hand-rolled filter could not express.
  if (child.outcome === "refused" || diagnostics.some((d) => d.source === "PRECHECK")) {
    const first = diagnostics.find((d) => d.source === "PRECHECK") ?? diagnostics[0];
    const arg = first?.where?.arg ? `${first.where.arg}: ` : "";
    return {
      id,
      channel: "REFUSED",
      verdict: "-",
      detail: `${arg}${first?.summary ?? child.summary ?? "(no summary)"}`,
    };
  }

  if (child.outcome === "rejected" || child.ok === false) {
    return { id, channel: "REJECTED", verdict, detail: codesOf(diagnostics) || (child.summary ?? "") };
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
  void main();
}
