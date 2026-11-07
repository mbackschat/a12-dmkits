# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG187 — a computation that suppresses `MVK_ERROR_INVALID_ENUM_CALCULATION` is uncharacterized at runtime (opened 2026-11-07).** <a id="ig187"></a> Kernel 31.3.1 admits a text computed into an enumeration field once the computation's preamble suppresses that code ([KF298](KERNEL-FINDINGS.md#kf298); `rulekit.validate.laws.ComputationParserInformationLawsTest`). No differential has measured what either kernel strategy stores for such a computation, nor what the interpreter does with the model, so `Computation.Builder.suppressWarnings` refuses the code. Settled by a runtime differential over an enumeration target fed a declared S-value, an undeclared text and an empty text, after which the builder's subset lifts ([KERNEL-31.3-UPGRADE-PROPOSAL](KERNEL-31.3-UPGRADE-PROPOSAL.md) B4).

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
