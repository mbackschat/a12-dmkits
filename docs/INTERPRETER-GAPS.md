# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG164 — a DUPLICATED key's selected message pointer names the first matching row, where A12 names the row its index cache was written with last (opened 2026-10-09).** A fired message references a selected operand at the row its key selects ([KF522](KERNEL-FINDINGS.md#kf522)'s 2026-10-09 extension). With the key duplicated, both kernel strategies name one row that is neither the first nor the last match in general: `CheckCommand` fills the validation cache by iterating a `HashMap` keyed by `RtInternalIdentifier`, and `IndexFieldCache` keeps the last write (source-read; a12-kernel-lean's `SPEC-2026-10-07-02` checkpoint). The two rows measured here both name the first match, and a12-kernel-lean reports one naming the second of two. Settled by reproducing that iteration order from the identifier's hash or by deciding it is out of scope; until then `adapter.laws.SelectedMessageReferenceDiffTest.aDuplicatedKeysPointerIsTheKernelsCacheRow` pins the kernel alone, and the interpreter's `EvalContext.indexPointerRepetitions` names the first match.
- **IG165 — a filtered aggregate does not evaluate its filter on a missing declared slot, where A12 does (opened 2026-10-09).** With no legs stored, `Sum(Legs*/Weight Having [Legs/Weight] > 0 Or [Legs/Stamp] == <gap label>)` reaches the label on both kernel strategies: the computation clears with `berechnungsWertFehler` and a rule over it aborts with VALUE. An empty weight on a missing declared slot leaves the `Or` unknown, so it reads on to the label. The interpreter walks stored rows only, so nothing is reached. The slot walk is unobservable unless the filter can fail at runtime, since a missing slot contributes nothing to the sum. Settled by measuring the slot domain (declared capacity, or [KF517](KERNEL-FINDINGS.md#kf517)'s capacity-product rule, in both phases) and walking it at the shared aggregate row source, as [IF364](INTERPRETER-FINDINGS.md#if364) did for field-fill quantifiers. Until then `adapter.laws.FilterTemporalLiteralFailureDiffTest` pins the row on the kernel alone.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
