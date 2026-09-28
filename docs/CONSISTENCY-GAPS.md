# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. The rows below were found by hand before that, so their inputs are kept here until they become sweep seeds. Each row names the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it; none is patched in the family it names.

## 1. Open

- **DG102 — legal constant index keys outside the portable pattern domain remain unjudged.** A12 tests a key against the index field's value validation ([KF481](KERNEL-FINDINGS.md#kf481)). The 27-input typed population now judges twenty-one keyed cells and leaves five legal SILENT cells (three admitted, two refused): class non-space, scoped Unicode case and final-line anchors. A separate 17-input predictive Java-Pattern population now agrees completely. The checker certifies the pattern and key together, using measured character domains where Java and JS differ; unsupported domains, including captured backreferences, remain INCONCLUSIVE on both targets. This is the JVM consistency oracle, not native A12 JavaScript runtime behavior. Close this gap only after the residual mechanisms are certified or explicitly disposed under the conformance plan; finite population agreement is not universal Java-pattern conformance.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
