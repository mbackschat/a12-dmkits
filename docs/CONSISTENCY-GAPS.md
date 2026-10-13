# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG130 — three length readers can judge a constant against a length A12 does not treat as a bound** (found 2026-10-13, beside the hint-list fix). `IndexKeyConstant.stringAdmits` takes any positive `minLength`/`maxLength`, and `FieldValueOperatorOperands.conversionDefect` and `DateConstructionSlots` read the declared `maxLength` as it stands. A12 judges nothing with a member outside 1 to 99,999 ([KF556](KERNEL-FINDINGS.md#kf556), measured for the type's own laws and its hint list), so beside such a member the checker may add one class to a model A12 already refuses with the range sentence; it cannot refuse a model A12 takes. Settles with one agreement row per reader, then `lengthInRange` at each that differs.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
