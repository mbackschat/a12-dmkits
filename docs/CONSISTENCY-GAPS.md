# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG143 — the checker does not lex a computation's `computedFieldRelPath`** (found 2026-10-21, batch C3). A computed field named with a no-break space makes A12 answer `MVK_LEXER_STANDARD_ERROR` at the computation, because the relative path it reads is lexed as a condition operand is; the checker states only the field's own path and name classes (`CheckerComputedPathAgreementTest`, the no-break row it leaves out). It under-reports a model A12 refuses. Settles with the path's lexer gate measured across the characters [KF575](KERNEL-FINDINGS.md#kf575) measured, on both reference slots a declaration carries, then one agreement row per carrier.
- **DG135 — a TypeDef-typed body is skipped with no `CoverageGap` of its own** (found 2026-10-13, cold review). Settles with a gap or a reading on the typed body.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
