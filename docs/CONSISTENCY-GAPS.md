# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. The rows below were found by hand before that, so their inputs are kept here until they become sweep seeds. Each row names the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it; none is patched in the family it names.

## 1. Open

- **DG102 — constant index-key pattern certification is incomplete.** The original five residuals and quantified negation are fixed; ninety frozen model inputs agree with the kernel ([KF481](KERNEL-FINDINGS.md#kf481)). Terminal review found existing case-folding omissions for decoded scalar escapes and mixed ranges, plus a nonportable empty-intersection representation. Direct pinned-JDK/Node discriminators establish these defects; the next partition must measure them through the kernel on a typed host, fix scalar/set closure structurally, and replay the full population on JVM and Node. The [bounded-domain contract](CONSISTENCY-API.md#constant-index-keys-bounded-jvm-consistency-semantics) does not excuse wrong judgments within its domain. Native-JS runtime behavior remains a separate assessment.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
