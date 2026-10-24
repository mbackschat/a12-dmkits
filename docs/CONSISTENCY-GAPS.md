# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG157 — a group error target beside a parallel iteration (opened 2026-10-24).** The operand-scope family judges only a field error target, so a rule at `/Order` reporting on the group `Lines`, keyed by `LineNo`, under `NoFieldFilled(Lines/Sku, Picks/Ref)` reads clean where A12 answers `MVK_ERROR_FIELD_NO_INDEX`; and the parallel chain read for a group target includes the group's own index, where A12 admits `GroupNotFilled(Lines/Slots) And FieldFilled(Picks/Ref)` on `Lines/Slots` (keyed by `SlotNo`) and refuses its `Or` twin with `MVK_PARALLEL_ITERATION_INVALID`. Measured on the order model of `CheckerIterationGuardAgreementTest` (2026-10-24), A12 reading the group's PARENT chain against the parallel level in every row. Settled by the operand-scope and guard rows in that differential.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
