# Model synthesis fidelity specification

This document defines the compact comparison contract used by `profile compare`. The comparator reports every current profile axis, but only the policies below decide whether the two artifacts match.

## Policies

| Policy | Rule |
|---|---|
| `EXACT` | actual equals target |
| `AT_LEAST` | actual is greater than or equal to target |
| `BUCKET` | actual is in the same or an adjacent base-two magnitude bucket |
| `INFORMATIVE` | reported without affecting `matches` |

A distribution is one `BUCKET` axis. Its `n`, `min`, `median`, `p90`, and `max` must each be in the same or an adjacent bucket; `mean` and the serialized bucket map remain visible in the target/actual values but do not receive independent verdicts. Two empty distributions match; an empty and non-empty distribution do not.

## Axis classification

| Policy | Paths |
|---|---|
| `EXACT` | `structure.fieldCount`, `structure.groupCount`, `structure.maxNestingDepth`, `structure.repeatability.repeatableGroupCount`, `structure.repeatability.maxRepeatableNestingDepth`, every `structure.repeatability.nestingDepthExact.*`, every `structure.fieldKinds.*`, `rules.count`, `computations.count`, and `computations.maxCascadeDepth` |
| `AT_LEAST` | entries directly under any `operators`, `constructs`, or `archetypes` histogram |
| `INFORMATIVE` | `rules.opaqueCount` and `computations.opaqueCount` |
| `BUCKET` | every remaining numeric leaf or distribution |

`matches` is true exactly when no `EXACT`, `AT_LEAST`, or `BUCKET` axis fails. Axes are sorted by path for deterministic output. A missing histogram key is compared as zero, so dropping a target operator or construct fails its `AT_LEAST` axis.

## Fixed-seed synthesis guarantee

The committed fidelity gate synthesizes each of the three example profiles with seeds `101`, `202`, `303`, `401`, `502`, `603`, `704`, and `805`, re-extracts the result, and requires exact equality for:

- field count, group count, and maximum group nesting depth;
- repeatable-group count, maximum repeatable nesting depth, and the exact repeatable-depth histogram;
- maximum computation cascade depth;
- computation `GROUP_FILL` construct count.

The gate also requires comparison to enumerate multiple axes. It does not require every approximate profile axis to pass: synthesis is a workload-shape generator, and the comparator's broader output is the calibration diagnosis.

## Change rule

A profile-shape change must update the profile schema, comparator tests, committed profile fixtures, and this document in the same commit. An exact guarantee may be added only with an example-based test over the permanent seed set.
