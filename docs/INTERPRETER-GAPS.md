# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG181 — three join-planning branches the batch R cold review read as unreachable or order-dependent (opened 2026-10-23).** <a id="ig181"></a> `IterationScopes.bindsRepeatableGroup`'s `group == plan.nested` arm (a selection resolves to a keyed ancestor, never the unkeyed nested level) and `ParallelRowContext.boundLevelsOf`'s nested arm (parallel partial dispatch goes through `ParallelRuleSelection`) may be dead; `prepare` drops a star anchor equal to the target's own level after choosing the deepest, so a depth tie between stars depends on list order; and `prepareParallel` does not refuse a joined group inside another joined group, which KF602's `MVK_MULTIPLE_INDEX_FIELDS` probably makes unauthorable. Settle each with a mutant against the interpreter lane, and delete or lock it.
- **IG182 — per-call recomputation on the nested join (opened 2026-10-23).** <a id="ig182"></a> `Document.parallelContexts` rebuilds the nested level's stored-row map and occupancy per call, `ParallelRowContext` allocates its depth table per nested row although it is call-invariant, and `ComputationEngine` re-plans the document-independent `prepareParallel` per run. No timed row covers a nested join; measure one before changing them.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
