# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG188 — a missed key on a date or custom-typed index is not carried on the interpreter's cause pointer (opened 2026-11-07; narrowed 2026-11-07).** <a id="ig188"></a> The interpreter carries `semanticIndexValueForUnknownIndexGroup` for a text, number and enumeration index, selected or joined, measured against both kernel strategies ([KF683](KERNEL-FINDINGS.md#kf683), `adapter.laws.UnknownIndexKeyLawsTest`). For a date index A12 converts the key to a `java.util.Date` (`convertWithOnlyBasicTypeCheck`), whose instant depends on a time zone not yet measured, and a custom-typed index's conversion is not measured at all; the interpreter names such a missed row once, unkeyed, and the shared differential leaves a date key out of its `causes` comparison (`DocumentDifferential.causeMember`), where A12's pointer equality (read from source, not measured for these kinds) would name it once per key. Settled by a differential over a date-indexed and a custom-typed group in the shape of `UnknownIndexKeyLawsTest`, which also decides the published type of a date key on the JVM and in TypeScript.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
