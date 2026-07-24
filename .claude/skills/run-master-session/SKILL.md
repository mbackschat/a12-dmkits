---
name: run-master-session
description: Run a multi-session work program with the hybrid model policy and review gate — use when orchestrating work that spans multiple sessions (master session duties, worker-prompt minting, per-set tier choice, gating a worker session's landing). Tool-agnostic; graduated 2026-07-09 from the semantics-formalization program.
---

# Running a multi-session work program

The structure: **one BEST-model master session + serialized worker sessions** — one active session per worktree, always (two sessions writing the same tree corrupt each other's state).

## The master session (BEST model)

Owns the program end-to-end:

- **Keeps the program's state doc** — a proposal/design doc whose work-item table (WI rows with status, model assignment, dependencies) is THE inter-session state carrier. Every landing updates it in the same commit (auto-commit each write-back).
- **Mints each worker session's paste-prompt.** A good prompt is self-contained for a cold session: identity + the one-session rule, warm-up reads (the state doc's relevant §§, the ledgers), the item queue with per-item method, the standing constraints (license boundaries, commit policy, build/log-capture rules), and the reporting format — SPELLED OUT, never "report as before" (a fresh session has no shared context; gloss program-internal names with commit hashes or file paths). **Red/probe evidence must survive by construction:** the reporting format requires red and probe logs written to unique per-item files (`build/<set>-<item>-red.log`), NEVER reused for a later green re-run — an overwritten red log forces the gate into a pre-fix reconstruction (a real gate cost, 2026-07-24). **Delivery format:** put a `---` horizontal rule immediately before AND after the prompt's fenced block, with the block containing the prompt alone — fences don't separate visually in a terminal, and prompt-end blending into surrounding prose causes copy ambiguity. The user pastes it into a fresh session and reports back "X is done" with comments.
- **Makes rulings** (contract/anchor/scope questions a worker escalates) — and treats its own rulings as hypotheses: a gate ruling was once mechanically wrong and a worker's source-read corrected it. Record rulings in the state doc.
- **Runs the review gate on every landing** (below). Graduation is provisional until the gate clears it.

## The worker tier is recommended per set (per-item → per-set, 2026-07-15)

A **set** is the batch of work items minted into one worker prompt/session — the program's unit of work. The master recommends a tier per set:

- A **mechanical, well-specified** set — pre-characterized items (a named construct class + a re-runnable reproducer, a fully-specced ledger row) — runs on the cheaper **REGULAR model** under the hardened drill. The safety net is mechanical: the sweep/differential histogram makes a wrong-locus symptom-patch visible (fixed instances leave siblings standing).
- A **truth-critical or design-heavy** set — raw/uncharacterized divergences, semantics forks the sources cannot settle, anything where a plausible-but-wrong answer would survive review — stays **BEST**.
- **Hard design calls are not decided in a worker session** — they return to the owner as options with a recommendation.
- Switch tiers only at set boundaries, never mid-set.

**Direct execution short-circuit (owner-ratified 2026-07-24):** before minting, the master checks two conditions — the **warm-context case** (this session already holds the mechanism, the loci, and the verification context a fresh worker would have to rebuild at real cost) and **boundedness** (a well-scoped set with a defined completion gate, no open design fork). When BOTH hold, the master executes the set directly in-session instead of minting. The review-gate obligations do not relax: the master runs the same locks-fresh/kernel-oracle verification on its own landing that it would demand of a worker, and reports the landing like a handback. One-session-per-worktree still governs — direct execution only when no worker session is live on the tree.

## The hardened drill (what a worker does per item)

reproduce → mechanism from BOTH kernel strategies' sources → predict + reproduce a **second instance** → fix at the root → differential-lock → re-run the sweep and record the histogram delta.

A worker that finds a new divergence mid-item does **not** triage it in-session: record a minimized repro + a ledger row, finish the item's mechanical scope, and hand it back (the repro is the context handoff).

A **discovery/hunt set** (sweeping for instances of a class rather than fixing a named one) runs **until dry**: the stop rule is N consecutive hunts yielding nothing new — a fixed count target misses the tail.

## The review gate (master, per landing)

Audit checklist — all nine, every time:

1. **Mechanism vs symptom** — does the fix explain the class, or patch the reported case?
2. **Locus** — is the change at the shared root (the fix covers siblings)?
3. **Second-instance evidence** — was a predicted second instance reproduced before the fix?
4. **Both-strategies confirmation** — runtime AND codegen, from source where the oracle can't reach.
5. **Histogram delta** — consistent with the claimed class size; no regression in other classes.
6. **Locks re-run independently in the gate session** — never trust the report's green alone.
7. **Advertised-invariant trace (contract-bearing surfaces)** — for every invariant the landing *advertises* (a schema field, a policy, a guarantee in docs/help), name the **enforcement locus** and its **failing test**. "Parsed but never consumed" is a real shipped class (a boundary once parsed a request's declared policy/observation fields and enforced neither — caught only by an external consumer's independent audit). Does-fires prove built things fire; this item proves *claimed* things are built.
8. **Client-protocol supremacy (cross-project work)** — an external client's explicit stop/verify rule is NEVER overridden by internal reinterpretation, however well-documented; a conflict between our state and a client's pin goes BACK TO THE CLIENT as a question before any run proceeds. The shipped class: the master once pre-ruled a client's identity-digest STOP as "the documented pre-freeze upgrade, not drift" using OUR freeze definition — but the client's smoke run had already exercised the identity externally, so one published id briefly meant two byte contracts, and the whole otherwise-flawless run was rejected on version identity. A published identity's freeze status is the CONSUMER'S exercised reality, not the producer's ledger; the one-message round-trip is always cheaper than a rejected handback.
9. **Kernel-oracle verification for runtime-semantics claims in measured material** — a claim about *runtime behavior* (what fires, how often, where an error attaches) destined for `skill/SKILL.md` or another agent-facing surface must be verified against the **kernel oracle** (an adapter differential / `CliTestSupport.runKernel` / a laws lock), never only through the binary — the binary's eval engine is the interpreter, which can carry a known-or-unknown `IG` divergence, so binary-verified ≠ kernel-verified exactly where it matters. The shipped class (2026-07-18, Set SK→FG): a skill bullet's "fires per row, marking every row" was verified through `dmtool model eval` by worker AND gate — both watched the interpreter's IG73 over-fire; the kernel fires once. Accept/reject claims may rest on `rule check` (that IS the kernel); runtime claims may not rest on `model eval`.

Plus ledger hygiene: fixed rows deleted (rationale graduates to a finding), code comments repointed, the state doc's program log updated.

## Three gate-proven drill criteria (each caught a real defect)

1. **A source-grounded-but-untested claim in a fix's DECISION LOGIC gets its differential before graduation** — a source read establishes a mechanism; only the differential establishes its interaction (the IF64 catch; again the IF88 DAY_OPTIONAL pin).
2. **An instrumentation correlation is a HYPOTHESIS, never a root cause — minimize before fixing** (the IG44 "tiered selection" overturning: every minimal repro was an unconditional computation; tier-ness was co-occurrence).
3. **Confirm a prior row's hypothesis before building on it** (the IG50 phantom / IG54 wrong-inference corrections).

## Track record

The policy graduated after the semantics-formalization program (2026-07-06 → 2026-07-09): ~30 kernel-exact mechanism fixes (IF55–IF91), ≈20 gate events, 4 gate catches, zero shipped regressions. The full record — including the per-WI outcome log — is the archived program: [`docs/archived/SEMANTICS-FORMALIZATION-PROPOSAL.md`](../../../docs/archived/SEMANTICS-FORMALIZATION-PROPOSAL.md) (its §5 is the policy as proven).
