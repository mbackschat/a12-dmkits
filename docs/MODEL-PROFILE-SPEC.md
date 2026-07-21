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
- `profile compare` accepts bare profiles or extraction envelopes and returns `matches` plus every compared axis. A mismatch is `valid:false` with exit 1.

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

## Fidelity

[`MODEL-SYNTHESIS-FIDELITY-SPEC.md`](MODEL-SYNTHESIS-FIDELITY-SPEC.md) defines the comparator policies. Comparison is diagnostic across the complete compact profile. The permanent fixed-seed gate is intentionally narrower: it locks only the structural and computation mechanisms the synthesizer claims to reproduce exactly.

The committed `compact`, `breadth-shaped`, and `depth-shaped` artifacts under [`examples/profiles/`](../examples/profiles/) were extracted from own-domain synthesized models. Private source models and private target profiles are not included.

## Required verification

The shipped feature is guarded by:

- example-based extraction, JSON, comparison, synthesis, determinism, and capacity tests;
- the three profile CLI verb tests and schema/conformance guards;
- `:adapter:profileFixtureGuardTest`, which validates all three committed profiles and exercises the fixed seed set;
- `./scripts/verify-demos.sh`, which executes the documented CLI workflow.

The opt-in synthesis and synthesized-model performance tasks remain catastrophe/diagnostic guards, not fidelity certification.
