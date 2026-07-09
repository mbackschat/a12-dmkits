---
name: run-work-program
description: Run a multi-session work program with the hybrid model policy and review gate — use when orchestrating work that spans multiple sessions (master session duties, worker-prompt minting, per-item model choice, gating a worker session's landing). Tool-agnostic; graduated 2026-07-09 from the semantics-formalization program.
---

# Running a multi-session work program

The structure: **one BEST-model master session + serialized worker sessions** — one active session per worktree, always (two sessions writing the same tree corrupt each other's state).

## The master session (BEST model)

Owns the program end-to-end:

- **Keeps the program's state doc** — a proposal/design doc whose work-item table (WI rows with status, model assignment, dependencies) is THE inter-session state carrier. Every landing updates it in the same commit (auto-commit each write-back).
- **Mints each worker session's paste-prompt.** A good prompt is self-contained for a cold session: identity + the one-session rule, warm-up reads (the state doc's relevant §§, the ledgers), the item queue with per-item method, the standing constraints (license boundaries, commit policy, build/log-capture rules), and the reporting format. The user pastes it into a fresh session and reports back "X is done" with comments.
- **Makes rulings** (contract/anchor/scope questions a worker escalates) — and treats its own rulings as hypotheses: a gate ruling was once mechanically wrong and a worker's source-read corrected it. Record rulings in the state doc.
- **Runs the review gate on every landing** (below). Graduation is provisional until the gate clears it.

## The worker model is chosen per item

- A **pre-characterized** item — a named construct class + a re-runnable reproducer — runs on the cheaper **REGULAR model** under the hardened drill. The safety net is mechanical: the sweep/differential histogram makes a wrong-locus symptom-patch visible (fixed instances leave siblings standing).
- **Raw/uncharacterized divergences and semantics forks** the sources cannot settle stay **BEST**.
- Switch models only at work-item boundaries, never mid-item.

## The hardened drill (what a worker does per item)

reproduce → mechanism from BOTH kernel strategies' sources → predict + reproduce a **second instance** → fix at the root → differential-lock → re-run the sweep and record the histogram delta.

A worker that finds a new divergence mid-item does **not** triage it in-session: record a minimized repro + a ledger row, finish the item's mechanical scope, and hand it back (the repro is the context handoff).

## The review gate (master, per landing)

Audit checklist — all six, every time:

1. **Mechanism vs symptom** — does the fix explain the class, or patch the reported case?
2. **Locus** — is the change at the shared root (the fix covers siblings)?
3. **Second-instance evidence** — was a predicted second instance reproduced before the fix?
4. **Both-strategies confirmation** — runtime AND codegen, from source where the oracle can't reach.
5. **Histogram delta** — consistent with the claimed class size; no regression in other classes.
6. **Locks re-run independently in the gate session** — never trust the report's green alone.

Plus ledger hygiene: fixed rows deleted (rationale graduates to a finding), code comments repointed, the state doc's program log updated.

## Three gate-proven drill criteria (each caught a real defect)

1. **A source-grounded-but-untested claim in a fix's DECISION LOGIC gets its differential before graduation** — a source read establishes a mechanism; only the differential establishes its interaction (the IF64 catch; again the IF88 DAY_OPTIONAL pin).
2. **An instrumentation correlation is a HYPOTHESIS, never a root cause — minimize before fixing** (the IG44 "tiered selection" overturning: every minimal repro was an unconditional computation; tier-ness was co-occurrence).
3. **Confirm a prior row's hypothesis before building on it** (the IG50 phantom / IG54 wrong-inference corrections).

## Track record

The policy graduated after the semantics-formalization program (2026-07-06 → 2026-07-09): ~30 kernel-exact mechanism fixes (IF55–IF91), ≈20 gate events, 4 gate catches, zero shipped regressions. The full record — including the per-WI outcome log — is the archived program: [`docs/archived/SEMANTICS-FORMALIZATION-PROPOSAL.md`](../../../docs/archived/SEMANTICS-FORMALIZATION-PROPOSAL.md) (its §5 is the policy as proven).
