# Model performance profiles and profile-driven synthesis

## Purpose

A model performance profile is an identifier-free description of model shape and evaluation work. It lets a private model guide an own-domain synthetic calibration model without committing the source model, its identifiers, or its business content.

The generated model is a workload-shaped fixture, not a behavioral clone.

## CLI contract

The public surface is the top-level `profile` target with three verbs:

```bash
dmtool -m private.dm.json profile extract -o target.profile.json
dmtool profile synthesize --input target.profile.json --seed 42 -o synthetic.dm.json
dmtool -m synthetic.dm.json profile extract -o actual.profile.json
dmtool profile compare --target target.profile.json --actual actual.profile.json
```

- `profile extract` returns the profile under the normal result envelope's `data` field. With `-o`, it writes the bare profile and returns an applied receipt.
- `profile synthesize` accepts a bare profile or extraction envelope and emits deterministic own-domain DM-JSON. With `-o`, it writes the model and returns an applied receipt.
- `profile compare` accepts bare profiles or extraction envelopes and returns `matches` plus every compared axis. The verdict is `data.matches`; a mismatch exits 1. The envelope carries no `valid`/`verification`: those report a consistency gate's arbitration, and comparing two profile artifacts consults no gate.

The live contracts are `schema profile extract`, `schema profile synthesize`, and `schema profile compare`. There is one current format and no `profileVersion` compatibility branch.

## Profile boundary

The artifact has four top-level sections:

| Section | Contents |
|---|---|
| `structure` | field/group counts, group width and depth, field kinds, repeatability, and declared constraint distributions |
| `rules` | count, scope, AST shape, references, operators/constructs, and iteration capacities |
| `computations` | the corresponding program shape plus alternatives, archetypes, scale matching, and dependency depth |
| `wiring` | referenced-field counts and fan-out |

The profile may contain field-kind and operator tokens, construct categories, numeric counts, and distributions. It must not contain model identifiers or paths, literals, enum values, regexes, messages, descriptions, configuration values, or rendered source expressions.

Includes and imported type definitions are resolved through the ordinary model resolver/workspace boundary before extraction. Canonical map and property ordering makes extraction and seeded synthesis reproducible across JVM launches.

## Capacity semantics

The compact format keeps three independent bounded quantities for each rule and computation:

- `scopeCapacity`: the repeatability product of the program's own scope; it is 1 outside repeatable scope.
- `listScanCapacity`: the greatest collection explicitly scanned by an aggregate, quantifier, group-list presence operation, or root-group count. A scalar reference inside a repeatable scope is not a scan.
- `workCapacity`: the saturated product of `scopeCapacity` and `listScanCapacity`.

`scannedScopeCapacity` is the scope distribution restricted to programs with a non-trivial list scan. `parallelIteration` and `outerCorrelation` retain the two cross-list signals from the PR.

Extraction records unbounded repeatable groups. Synthesis refuses a profile whose `unboundedCount` is non-zero instead of inventing a finite workload.

## Input validation

The codec rejects malformed JSON, missing required record sections, unknown record properties, negative integral values, and distributions whose bucket total differs from `n`. Synthesis performs its bounded-repeatability check before model construction.

This is deliberately one compact internal record graph behind the public `ModelProfiles` string boundary. The record types and Jackson representation are not a supported Java API.

## Synthesis

Synthesis uses the typed modelkit, rulekit, and edit surfaces to create structure, rules, and computations. It uses the profile and seed as its only planning inputs, reserves computation fields before rule creation, writes typed programs through the bulk persistence seam, and serializes once.

The synthesizer targets workload shape. It does not reproduce names, values, text, or business semantics, and it is not a general model-authoring verb.

## Comparison and fidelity

Comparison is diagnostic across the complete compact profile. The comparator reports every current profile axis, but only the policies below decide whether the two artifacts match.

| Policy | Rule |
|---|---|
| `EXACT` | actual equals target |
| `AT_LEAST` | actual is greater than or equal to target |
| `BUCKET` | actual is in the same or an adjacent base-two magnitude bucket |
| `INFORMATIVE` | reported without affecting `matches` |

A distribution is one `BUCKET` axis. Its `n`, `min`, `median`, `p90`, and `max` must each be in the same or an adjacent bucket; `mean` and the serialized bucket map remain visible in the target and actual values but do not receive independent verdicts. Two empty distributions match; an empty and non-empty distribution do not.

### Axis classification

| Policy | Paths |
|---|---|
| `EXACT` | `structure.fieldCount`, `structure.groupCount`, `structure.maxNestingDepth`, `structure.repeatability.repeatableGroupCount`, `structure.repeatability.maxRepeatableNestingDepth`, every `structure.repeatability.nestingDepthExact.*`, every `structure.fieldKinds.*`, `rules.count`, `computations.count`, and `computations.maxCascadeDepth` |
| `AT_LEAST` | entries directly under any `operators`, `constructs`, or `archetypes` histogram |
| `INFORMATIVE` | `rules.opaqueCount` and `computations.opaqueCount` |
| `BUCKET` | every remaining numeric leaf or distribution |

`matches` is true exactly when no `EXACT`, `AT_LEAST`, or `BUCKET` axis fails. Axes are sorted by path for deterministic output. A missing histogram key is compared as zero, so dropping a target operator or construct fails its `AT_LEAST` axis.

### Fixed-seed synthesis guarantee

The permanent fixed-seed gate is intentionally narrower than the comparator. It synthesizes each of the three example profiles with seeds `101`, `202`, `303`, `401`, `502`, `603`, `704`, and `805`, re-extracts the result, and requires exact equality for:

- field count, group count, and maximum group nesting depth;
- repeatable-group count, maximum repeatable nesting depth, and the exact repeatable-depth histogram;
- maximum computation cascade depth;
- computation `GROUP_FILL` construct count.

The gate also requires comparison to enumerate multiple axes. It does not require every approximate profile axis to pass: synthesis is a workload-shape generator, and the comparator's broader output is the calibration diagnosis.

The committed `compact`, `breadth-shaped`, and `depth-shaped` artifacts under [`examples/profiles/`](../examples/profiles/) were extracted from own-domain synthesized models. Private source models and private target profiles are not included.

### Change rule

A profile-shape change must update the profile schema, comparator tests, committed profile fixtures, and this document in the same commit. An exact guarantee may be added only with an example-based test over the permanent seed set.

## Required verification

The shipped feature is guarded by:

- example-based extraction, JSON, comparison, synthesis, determinism, and capacity tests;
- the three profile CLI verb tests and schema/conformance guards;
- `:adapter:profileFixtureGuardTest`, which validates all three committed profiles and exercises the fixed seed set;
- `./scripts/verify-demos.sh`, which executes the documented CLI workflow.

The opt-in synthesis and synthesized-model performance tasks remain catastrophe/diagnostic guards, not fidelity certification.
