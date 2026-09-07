const fs = require("node:fs");
const { execFileSync } = require("node:child_process");

/**
 * Pre-send checks for an outbound a12-kernel-lean handback letter.
 *
 * WHY A CHECKER FOR A FILE THAT IS NOT IN THIS REPOSITORY. Every other surface here meets something before it
 * reaches anyone: a doc meets the doc guards, code meets a test, the catalog meets N7. The handback letter
 * meets nothing. It deliberately has no in-repo home — a copy creates two heads with no merge base, which was
 * tried and withdrawn — so the letter stays wherever it is written and this reads it BY PATH. That keeps the
 * single-head rule and still puts a gate in front of the one channel that had none.
 *
 * WHAT IT CHECKS, AND WHY THESE THREE. Each is a defect that actually shipped, and each is decidable from the
 * text plus `git`:
 *
 *   (1) STALE REVISION. The letter tells the reader which commit to verify against. On 2026-09-07 that line
 *       named a commit five commits behind HEAD and the maintainer caught it by eye. A cited hash must resolve
 *       and the revision line must name HEAD, because a peer verifying against the wrong tree finds the
 *       measurements missing and the round trip is wasted.
 *
 *   (2) A MECHANISM OUTSIDE THE HYPOTHESIS SECTION. CLAUDE.md: "the payload is the table of (input → observed
 *       output → oracle used); the explanation belongs in a separate section marked as hypothesis". The letter
 *       already carries such a section. On 2026-09-07 a causal sentence sat in a measurement section instead,
 *       was read as a finding, and cost a correcting round trip.
 *
 *   (3) A CAUSAL CLAIM ABOUT THE PEER'S ESTATE. This is the sharp one and the reason the checker exists.
 *       "The confound was the comparand" was written into a section refuting THEIR clauses. No oracle of ours
 *       reaches their fixtures, so that claim was never ours to make — CLAUDE.md calls inferring a peer's cause
 *       from our own code "fabrication", and they refuted it from retained artifacts within a day.
 *
 * WHY THE TRIGGERS ARE NARROW. A widening of the in-repo mechanism guard was measured first and rejected: on
 * the two kernel docs, `the reason is/was` fails 2 of 5 honest paragraphs and `is what made` fails its only
 * hit. A guard that fails honest content gets switched off, so this matches the small set of forms that
 * actually shipped a defect and leaves the rest to review.
 */

type Finding = {
  readonly check: string;
  readonly line: number;
  readonly text: string;
  readonly why: string;
};

/** Sections whose job IS to carry mechanisms; a causal sentence is expected here, not flagged. */
const HYPOTHESIS_HEADING = /mechanism|hypothes|not actionable|speculat/i;

/** Withdraws a sentence from actionable status wherever it appears. */
const HYPOTHESIS_MARKER = /hypothes|conjectur|we (?:suspect|think|believe)|not actionable|unverified|would predict|prediction/i;

/** Bare and contrastive causal assertions alike — the in-repo guard sees only the contrastive ones. */
const CAUSAL = /\bthe (?:confound|cause|mechanism|root cause) (?:was|is|were)\b|\bthe cause is\b|\bthe mechanism is\b|\bnot because\b|\brather than because\b/i;

/** Second person: the peer's estate rather than ours. */
const PEER_SUBJECT = /\byour\b|\byours\b|\bon your side\b/i;

/** A markdown heading, capturing its text. */
const HEADING = /^#{1,4}\s+(.*)$/;

/** A short git hash in backticks — how the letter cites a revision. */
const CITED_HASH = /`([0-9a-f]{7,40})`/g;

const REVISION_LINE = /\*\*Our revision:\*\*\s*`([0-9a-f]{7,40})`/;

function git(args: readonly string[], repo: string): string {
  return execFileSync("git", ["-C", repo, ...args], { encoding: "utf8" }).trim();
}

/**
 * (1) Every OUR-SIDE cited hash resolves, and the revision line names HEAD.
 *
 * <p>A letter cites commits from both estates, and the peer's will never resolve here — the first run of this
 * checker duly flagged their ledger commit. The discriminator is in the sentence itself ("re-derived from
 * YOUR ledger at `…`"), so a line written about their side is exempt from resolution. That is the same signal
 * {@link PEER_SUBJECT} carries for claims, used for the opposite purpose: there it marks what we may not
 * assert, here what we cannot verify.
 */
function checkRevision(lines: readonly string[], repo: string): Finding[] {
  const findings: Finding[] = [];
  let head = "";
  try {
    head = git(["rev-parse", "HEAD"], repo);
  } catch {
    return [{ check: "revision", line: 0, text: "(git unavailable)", why:
      "could not read HEAD, so the revision line was not checked — run this from the repository" }];
  }

  lines.forEach((line, i) => {
    const revision = REVISION_LINE.exec(line);
    const theirs = PEER_SUBJECT.test(line) && !revision;
    const seen = new Set<string>();
    for (const m of line.matchAll(CITED_HASH)) {
      const hash = m[1];
      if (seen.has(hash)) continue; // one finding per hash per line, however often it is repeated
      seen.add(hash);
      if (theirs) continue;
      let resolved = "";
      try {
        resolved = git(["rev-parse", `${hash}^{commit}`], repo);
      } catch {
        findings.push({ check: "revision", line: i + 1, text: hash, why:
          "cited commit does not resolve in this repository — a peer cannot check out what it names. If it is "
          + "THEIRS, say so in the sentence ('your ledger at …') and it is exempt" });
        continue;
      }
      if (revision && revision[1] === hash && resolved !== head) {
        findings.push({ check: "revision", line: i + 1, text: hash, why:
          `the revision line names ${hash} but HEAD is ${head.slice(0, 9)} — the reader would verify against `
          + "a tree that does not carry the rows this letter reports" });
      }
    }
  });
  return findings;
}

/** (2) and (3): a causal sentence in the wrong place, or about the wrong estate. */
function checkClaims(lines: readonly string[]): Finding[] {
  const findings: Finding[] = [];
  let inHypothesisSection = false;

  lines.forEach((line, i) => {
    const heading = HEADING.exec(line);
    if (heading) {
      inHypothesisSection = HYPOTHESIS_HEADING.test(heading[1]);
      return;
    }
    if (!CAUSAL.test(line)) return;
    if (HYPOTHESIS_MARKER.test(line)) return;

    if (PEER_SUBJECT.test(line)) {
      findings.push({ check: "peer-attribution", line: i + 1, text: oneLine(line), why:
        "a causal claim in a sentence about the PEER's estate. No oracle of ours reaches their fixtures, so "
        + "this is not ours to assert — report our measurement and ask, or attribute it to their report" });
      return;
    }
    if (!inHypothesisSection) {
      findings.push({ check: "mechanism-placement", line: i + 1, text: oneLine(line), why:
        "a mechanism stated outside the hypothesis section. Measurements travel; mechanisms carry their basis "
        + "with them — move it, or mark it a hypothesis" });
    }
  });
  return findings;
}

function oneLine(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > 150 ? `${flat.slice(0, 150)}…` : flat;
}

function check(letter: string, repo: string): Finding[] {
  const lines = letter.split("\n");
  return [...checkRevision(lines, repo), ...checkClaims(lines)];
}

module.exports = { check, checkClaims, checkRevision, CAUSAL, PEER_SUBJECT };

if (require.main === module) {
  const [path, repo] = process.argv.slice(2);
  if (!path) {
    process.stderr.write("usage: check-handback.sh <letter.md> [repo]\n");
    process.exit(2);
  }
  const findings = check(fs.readFileSync(path, "utf8"), repo || ".");
  for (const f of findings) {
    process.stdout.write(`${path}:${f.line}: [${f.check}] ${f.text}\n    ${f.why}\n`);
  }
  process.stdout.write(findings.length === 0
    ? "handback-check: no findings\n"
    : `handback-check: ${findings.length} finding(s)\n`);
  process.exit(findings.length === 0 ? 0 : 1);
}
