# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None._`; resolved history belongs in findings and git.

## 1. Open

- **IG161 — measure declared-range work in mixed filtered positive thresholds (2026-10-03).** `Interpreter.filteredFillScope` computes declared-range flags even when its caller needs only instantiated counts. A filled direct operand before a coordinate-filtered star can decide `AtLeastOneFieldFilled`, while the shared scope summary still visits the declared range. Source inspection during [IF354](INTERPRETER-FINDINGS.md#if354)'s review identified this extra traversal; no timing or kernel-performance comparison has been made. Measure row visits with fixed instantiated content and varying finite capacity, then separate count-only consumption if the control confirms unnecessary work. Preserve [KF512](KERNEL-FINDINGS.md#kf512)'s missing-slot and polarity outcomes; nested/unbounded semantics remain outside that measurement.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
