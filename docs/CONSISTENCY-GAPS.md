# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG164 — a frame value A12 builds and then refuses by a class or sentence the checker does not state (opened 2026-10-29).** A12 draws `MVK_INVALID_NAME_4_GENERATED_CODE` on a `content.modelInfo` without a `name`, `MVK_INVALID_TIME_ZONE` on a `timeZone` it does not know (`"x"`, `5`, `true`), and [KF70](KERNEL-FINDINGS.md#kf70)'s version-mismatch sentence on a `modelVersion` that is not one (`"x"`, `5`, `true`) (the Kernel gate, `adapter.laws.CheckerModelFrameAgreementTest`; [KF650](KERNEL-FINDINGS.md#kf650)), and the checker reports each model valid. Settled by a family stating the generated-code name law, the accepted-zone-id contract and KF70's version rule; until then that test's `LEFT_TO_A12` pins the silence.
- **DG165 — a supported-character entry that is neither one character nor a range (opened 2026-10-29).** A12 builds no model for `["ab"]`, `[""]` or `[true]`, the boolean bound as the text `true` (the Kernel gate, `adapter.laws.CheckerModelFrameAgreementTest`; [KF650](KERNEL-FINDINGS.md#kf650)), and the checker reports the model valid. The entry grammar behind the refusal is unmeasured beyond these rows: settled by a measured statement of it from the codegen's own reader, beside [KF560](KERNEL-FINDINGS.md#kf560)'s surrogate rows; until then `LEFT_TO_A12` pins the silence.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
