# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. The rows below were found by hand before that, so their inputs are kept here until they become sweep seeds. Each row names the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it; none is patched in the family it names.

## 1. Open

- **DG83 — a message class beside a refused condition.** `FieldFilled(Reference) And Abs([Premium], [Premium]) > 0` with no message is `MVK_ERROR_MESSAGE_MISSING,MVK_UNEXPECTED_TOKEN` in A12; the checker adds or substitutes a message class, and a tab in the text draws `INVALID_CHARACTERS` where A12 names only the condition. `AddDays([Premium], 1)` is the operand-kind twin (`validate.laws.MessageParameterConditionDependencyLawsTest`, [KF338](KERNEL-FINDINGS.md#kf338)). A condition that fails withholds its rule's whole message check ([CHECKER-RECONSTRUCTION-PROPOSAL](CHECKER-RECONSTRUCTION-PROPOSAL.md) §1); the checker withholds only where a family detects the condition's defect. Settled by the message stage's place in R0's map, with R3a's syntax verdict.
- **DG102 — a constant key the checker cannot judge.** A12 tests a constant key against the index field's value validation ([KF481](KERNEL-FINDINGS.md#kf481)). The checker matches only a regex-free pattern subset (`consistency.KeyPattern`), so beside `.{2}`, an escape, a group or an alternation the key is unjudged (`adapter.laws.CheckerIndexKeyConstantAgreementTest`). A type-definition or custom index field is unjudged and unmeasured. Settled by a Java-compatible pattern judge on JVM and JS and by P3's typedef and custom-index hosts; independent of the rebuild.
- **DG113 — element names against A12's path alphabet.** A group `Parté` and a field `Codeé` are `MVK_PATH_INVALID` in A12, whose group alphabet takes the German umlauts and `ß` and no other non-ASCII letter ([KF315](KERNEL-FINDINGS.md#kf315)); the checker is silent. A field `Co.1de` is `MVK_PATH_INVALID` alone, and the checker names `MVK_NAME_INVALID_DOT_COLON_A12_DV` (`validate.laws.FieldTypeMessageTextLawsTest.anInvalidPathStopsTheCheck`). Settled by R4 with P3's name edits.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
