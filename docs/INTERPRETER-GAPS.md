# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG182 — per-call recomputation on the nested join (opened 2026-10-23).** <a id="ig182"></a> `Document.parallelContexts` rebuilds the nested level's stored-row map and occupancy per call, `ParallelRowContext` allocates its depth table per nested row although it is call-invariant, and `ComputationEngine` re-plans the document-independent `prepareParallel` per run. No timed row covers a nested join; measure one before changing them.
- **IG183 — the core custom-condition view omits group instances (opened 2026-10-25).** <a id="ig183"></a> A12's core `IData` iterator lists every non-root group instance besides the field instances, and its `getValue` answers a present repeatable group instance with the placeholder `NonEmptyString` ([KF622](KERNEL-FINDINGS.md#kf622), `ValidationData.REPEATABLE_GROUPINSTANCE_PLACEHOLDER`). `CustomConditionData.fields()` and the `KernelCustomConditions` bridge list fields only, and a group pointer reads null or `""`, so a shipped core condition that walks the iterator or probes a group sees less than on A12. Settle with a kernel-oracled differential beside `adapter.laws.CustomConditionEmptyCellDiffTest`; it blocks nothing measured so far.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
