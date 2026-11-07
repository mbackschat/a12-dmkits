# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG188 — the interpreter's message pointer has no `semanticIndexValueForUnknownIndexGroup`, so a cause group names a missed selection once rather than once per key (opened 2026-11-07).** <a id="ig188"></a> A12's `PartiallyKnownDocumentMultiPointer` carries the key that selected no row, typed as the index field is (text for a string index, `BigDecimal` for a number one), and its equality includes it; two keys missing one indexed group are therefore two members of one `groupsOfPossiblyCausingEntities` set ([KF683](KERNEL-FINDINGS.md#kf683), `adapter.laws.PossiblyCausingEntitiesLawsTest.aMissedKeyIsNamedOncePerKey`). `A12PartiallyKnownMultiPointer` has no such member, so the interpreter names that entity once, and the differential's `causes` channel compares pointer keys. Settled by adopting the member on the published pointer type — a typed key on a JVM and JS surface, so its representation is the open decision — and measuring which other channels' pointers A12 sets it on.
- **IG189 — the TypeScript boundary copies a duplicate cluster's shared pointers once per finding, so a dense N-row cluster allocates N² objects there (opened 2026-11-07).** <a id="ig189"></a> The engine shares a cluster's `referenced` list and its cause cells across the cluster's findings, and the Kotlin API publishes them without copying ([IF191](INTERPRETER-FINDINGS.md#if191), [IF261](INTERPRETER-FINDINGS.md#if261), `PublishedCauseGroupNoCopyTest`). The JS bridge (`TypeScriptBridgeFinding.kt`) and the TypeScript façade (`evaluation.ts`) map every pointer of every finding into a fresh frozen object, which restores the O(N²) allocation that exhausted the JVM heap at 8,000 rows ([IF256](INTERPRETER-FINDINGS.md#if256)) for `referenced`, `fillToFix` and `possiblyCausing` alike. Not measured on JS. Settled by an allocation probe at 8,000 duplicate rows through the packed package, then by a bridge that crosses a shared list once.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
