# interpreter — gaps

> **What this is** — active work for the portable interpreter. Deferred work and accepted limits live in [INTERPRETER-GAP-DISPOSITIONS](INTERPRETER-GAP-DISPOSITIONS.md); authoring gaps live in [DEV-API-GAPS](DEV-API-GAPS.md).
>
> **Lifecycle.** Keep open entries here. On resolution, delete the entry and preserve its rationale in a numbered [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) finding. Move deferred or out-of-scope entries to the companion with their IDs and reopening conditions; moving is not closing.
>
> **Current state.** The Open section is authoritative. When drained, it contains only `_None open._`; resolved history belongs in findings and git.

## 1. Open

- **IG166 — the format-keyed computation store was measured on TIME-kind targets only** (opened 2026-10-13, cold review). [IF397](INTERPRETER-FINDINGS.md#if397) routes every temporal store through `dateStoreFormat`, so it also changes a DATE_TIME-kind target declared `dd.MM.yyyy` and a DATE-kind target declared with a date-time format, neither run against A12. Settles with those two rows in `adapter.laws.FirstFilledDateCrossKindTargetDiffTest`.
- **IG167 — three message-fault shapes are unmeasured** (opened 2026-10-13, cold review). The interpreter renders only the requested locale's text, so a key token that selects no row in another locale's text never faults, and an empty or formally invalid FIELD key in a `.value` token raises `UnmatchedMessageKeyFailure`; A12 is measured on neither. [KF561](KERNEL-FINDINGS.md#kf561)'s "a level nothing binds renders its first row" rests on one carrier, a root rule with a keyed and a starred read. Settles with those rows in `adapter.laws.MessageParameterRowDiffTest`.
- **IG168 — several 2026-10-13 locks are weaker than their findings** (opened 2026-10-13, cold review). The required-empty read ([IF394](INTERPRETER-FINDINGS.md#if394)), the partial row domain ([IF395](INTERPRETER-FINDINGS.md#if395)) and the checked key value ([KF549](KERNEL-FINDINGS.md#kf549)) have no JVM/Node `commonTest` row; the base-view-first message order of `SPEC-2026-10-13-06` is unlocked because the differential compares sorted signatures; and two locks added in `83d726617` and `796e060e4` assert a `.take(1)` or a count where the finding states the values. Settles by adding the `commonTest` rows, one unsorted order assertion and value assertions.
- **IG169 — the read-side `authored` text has no observing control** (opened 2026-10-13, cold review). `Document.formalInput` hands the pre-fold text to `resolveValue`/`cellState`, but the validation and computation paths read (`DmInterpreter.kt` 799/991, `ComputationEngine.kt` 202) first consult `FormalResultCache`, which measures the authored text itself through `formalInputAt`. Replacing it with the folded text survived `adapter.laws.CheckedInputOrderDiffTest` (`scripts/mutant.sh`, 2026-10-13), whose CRLF row reaches the cell through both a rule and a computation. The same duplication reaches `storedValueOf`, which runs the input gates on a computed date although a computed value meets none of them (KF547); the cold review found no caller that hands it a value the gates refuse, without enumerating the callers. Settles with a read the cache does not gate, or by removing the duplicate gate.

## 2. Deferred / ongoing-incremental

See [deferred work and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#deferred).

## 3. Out of scope

See [accepted limits and reopening conditions](INTERPRETER-GAP-DISPOSITIONS.md#3-out-of-scope-wont-build-unless-the-trigger-flips-recorded-so-its-not-re-investigated).
