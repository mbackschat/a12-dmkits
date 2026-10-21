# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG179 — a correlated column holding one unavailable empty cell takes the nested tally, O(rows) per row** (opened 2026-10-21, [KF552](KERNEL-FINDINGS.md#kf552)). Both kernel strategies are quadratic on this shape too and the interpreter stays about 1.4× ahead of the generated Java ([checkpoint](INTERPRETER-PERFORMANCE-RESULTS.md)), so `RequiredColumnCorrelationPerfTest` holds it to the kernel's class rather than to the sub-quadratic floor. The keyed and ordered tallies are additive per kept row and are exact for every available row, so a scope could keep them and re-read only its unavailable rows nested, O(rows × unavailable rows). That would beat A12's own complexity rather than match it, and it has to settle three cases the indexed path never meets: the outer row itself unavailable under a self-exclusion, an unavailable join field, and the ordered plan. Settles with the owner's decision whether to go below the kernel's class here; a yes needs a differential over those three cases before it lands.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
