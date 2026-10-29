# Portable consistency checker — gaps

> **What this is** — where the kernel-free `:consistency` checker ([CONSISTENCY-API](CONSISTENCY-API.md)) reports a model differently from A12's `checkConsistency`: a class it leaves unnamed, or one it names where A12 names another. Each row gives the input, A12's measured class, the kernel law that measured it, and the plan item that settles it. Ids are `DG<n>`, drawn from the chunks declared once in [DEV-API-GAPS](DEV-API-GAPS.md)'s `dg-id-chunks` line, which serves both ledgers.
>
> **Lifecycle.** A gap lives here only while open. When it ships, its row is DELETED in the same commit: the measured behaviour already lives in its [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry, and the sweep ledger turns the divergence into an agreement. No shipped list, ever.
>
> **Current state.** Since 2026-09-26 the checker's divergences are discovered by the near-miss sweep, and its committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) is their list. A row found by hand names its input, the missing structure behind it and the stage rebuild in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) that settles it, and is kept here until it becomes a sweep seed.

## 1. Open

- **DG163 — two header locales the JDK writes as one language tag (opened 2026-10-29).** A12 draws `MVK_SUPP_LANGUAGES_DUPLICATE` on `en_US` beside `en_US_abcdefghi` (the Kernel gate, `validate.laws.ModelHeaderSlotLawsTest`; [KF649](KERNEL-FINDINGS.md#kf649)), and the checker reports the model valid. The rule behind the class, which pairs collide and in what order two such tags are named, is unmeasured: a12-kernel-lean's SPEC-2026-10-29-02 measures eleven colliding pairs and leaves the rule unclaimed. Settled by a measured statement of that rule against the JDK's `Locale.toLanguageTag`; until then the checker stays silent, which `adapter.laws.CheckerLocaleSpellingAgreementTest.twoLocalesTheJdkTagsAlikeAreLeftToA12` pins.

## 2. Out of scope

- **What a document's evaluation fires.** The checker judges a model statically; runtime behaviour is the interpreter's, and its divergences are [INTERPRETER-GAPS](INTERPRETER-GAPS.md)'.
