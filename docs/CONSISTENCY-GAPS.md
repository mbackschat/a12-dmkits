# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG146 — the iteration-guard law: `MVK_NEG_CONDITION_IN_ITERATION` and `MVK_PARALLEL_ITERATION_INVALID` (opened 2026-10-22).** Input: an iterating rule that can fire for a repetition nobody filled, such as a rule at `/Policy` reporting on `../Claims/Amount` with `FieldNotFilled(Claims/Amount)` and no positive guard on the row. A12 refuses it with `MVK_NEG_CONDITION_IN_ITERATION` (kernel 30.8.1, `dmtool model check`), and this checker names nothing; every completed report says so in a model-wide gap (`IterationValidityGapTest`). Both codes are one kernel mechanism ([MVK-LEDGER](MVK-LEDGER.md)), so they ship together. Settled by the clean-room port in [ITERATION-GUARD-PORT-PROPOSAL](ITERATION-GUARD-PORT-PROPOSAL.md), locked by a kernel differential that shows no over-rejection; the port then deletes this row and the report's gap.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
