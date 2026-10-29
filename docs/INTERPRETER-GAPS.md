# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG183 — the interpreter evaluates three check-only frame shapes that A12's runtime cannot evaluate (opened 2026-10-30).** With no consistency check in front, both Kernel strategies throw before any rule runs on an entry model lacking `content.modelInfo`, with `header.locales` absent or `null`, or with a `null` supported character, while the interpreter, and so `dmtool model eval`, evaluates each and fires the rule ([KF650](KERNEL-FINDINGS.md#kf650); `adapter.laws.CheckOnlyFrameRuntimeDiffTest`). A missing `modelVersion` is evaluated by both and needs nothing. Settled by an owner decision between refusing the three on the entry model, as the interpreter already refuses a member A12's reader cannot bind, and keeping the leniency as a characterized divergence. Before a refusal lands, two scopes need their own measurement: a model whose `locales` are missing but that has no localized element to expand, and a reached rather than entry model.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
