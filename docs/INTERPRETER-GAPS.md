# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG187 — a computation that suppresses `MVK_ERROR_INVALID_ENUM_CALCULATION` is uncharacterized at runtime (opened 2026-11-07).** <a id="ig187"></a> Kernel 31.3.1 admits a text computed into an enumeration field once the computation's preamble suppresses that code ([KF298](KERNEL-FINDINGS.md#kf298); `rulekit.validate.laws.ComputationParserInformationLawsTest`). No differential has measured what either kernel strategy stores for such a computation, nor what the interpreter does with the model, so `Computation.Builder.suppressWarnings` refuses the code. Settled by a runtime differential over an enumeration target fed a declared S-value, an undeclared text and an empty text, after which the builder's subset lifts ([KERNEL-31.3-UPGRADE-PROPOSAL](KERNEL-31.3-UPGRADE-PROPOSAL.md) B4).

- **IG188 — the interpreter's message pointer has no `semanticIndexValueForUnknownIndexGroup`, so a cause group names a missed selection once rather than once per key (opened 2026-11-07).** <a id="ig188"></a> A12's `PartiallyKnownDocumentMultiPointer` carries the key that selected no row, typed as the index field is (text for a string index, `BigDecimal` for a number one), and its equality includes it; two keys missing one indexed group are therefore two members of one `groupsOfPossiblyCausingEntities` set ([KF683](KERNEL-FINDINGS.md#kf683), `adapter.laws.PossiblyCausingEntitiesLawsTest.aMissedKeyIsNamedOncePerKey`). `A12PartiallyKnownMultiPointer` has no such member, so the interpreter names that entity once, and the differential's `causes` channel compares pointer keys. Settled by adopting the member on the published pointer type — a typed key on a JVM and JS surface, so its representation is the open decision — and measuring which other channels' pointers A12 sets it on.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
