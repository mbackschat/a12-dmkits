# Model performance profiles and profile-driven synthesis

> **Status:** proposed contract for G52. It graduates to `MODEL-PROFILE-SPEC.md` when the three `profile` operations and their performance workflow are implemented and guarded.

## 1. Purpose

Real production models are the best calibration material for `dmtool model report` and the interpreter, but the source models themselves cannot be committed as fixtures. A performance profile preserves the model properties that determine parsing, reporting, validation, computation, iteration, and memory cost while removing its domain expression.

The intended workflow is:

```text
private real model ──profile extract──▶ private target profile
                                              │
                                              ▼
                                  profile synthesize
                                              │
                                              ▼
                                  own-domain synthetic model
                                              │
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
             profile extract                                      large documents
                     │                                                 │
                     ▼                                                 ▼
                profile′                                      report + interpreter runs
                     │
                     ▼
             profile compare
```

The profile is a performance-calibration artifact, not a behavioral description. A synthesized model is a shape twin that exercises comparable work; it is not a replacement for the source model and does not reproduce its business meaning.

## 2. One cohesive CLI target

The public surface is one `profile` target with three independently schema-described operations:

```bash
dmtool -m private.dm.json profile extract -o target.profile.json
dmtool profile synthesize --input target.profile.json --seed 42 -o synthetic.dm.json
dmtool -m synthetic.dm.json profile extract -o actual.profile.json
dmtool profile compare --target target.profile.json --actual actual.profile.json
```

`profile extract` prints the normal result envelope with the profile under `data`; `-o` writes the bare profile artifact and prints an applied envelope. `profile synthesize` prints a model artifact, matching `model seed`'s artifact convention; `-o` writes it and prints an applied envelope. `profile compare` emits a result envelope containing every classified fidelity axis, its target and actual values, its policy, and its pass/fail result.

Keeping synthesis under `profile` avoids presenting it as general model authoring. The typed dmkits remain the implementation path: generated structure, rules, and computations are authored through `DocumentModelDraft`/`EditSession` and their typed references, never through hand-written condition or operation strings.

## 3. Profile format

### 3.1 One current format

The unmerged PR is research material, not a released compatibility contract. This implementation defines one corrected profile format from current `main`; it has no legacy parser, version branch, compatibility adapter, or migration surface. `profile synthesize` and `profile compare` accept both the current result envelope and its bare `data` artifact.

The three PR example profiles are clean `profile′` artifacts: they were extracted from own-domain synthesized models after the private real-model target had driven synthesis. They remain committed as compact, breadth-shaped, and depth-shaped calibration fixtures. The private target profiles and source models are not included.

The example profiles are adapted to the final current format before landing. Removed or superseded PR fields are not revived merely to consume the examples.

### 3.2 Identifier-free boundary

The extractor emits counts, distributions, field-kind tokens, operator identifiers, construct categories, constraint-presence flags, and numeric capacities. It never emits field/group/rule/computation/typedef names or paths, literals, enum values, regexes, messages, descriptions, configuration values, or canonical rule/computation text.

“Identifier-free” describes the format, not an automatic publication license. The repository policy is concrete: private real-model target profiles stay local; the three reviewed PR examples may be committed because they are `profile′` outputs from synthesized own-domain models.

A metamorphic guard renames every model identifier and changes literals, regexes, enum values, messages, and descriptions while preserving shape, then requires byte-identical profile output.

### 3.3 Determinism and validation

The same expanded model must produce byte-identical profile JSON across separate JVM launches. Every map is canonicalized explicitly; tied histogram counts use a stable key tie-break. `Map.copyOf` encounter order is never used as serialization order.

Profile input is schema-validated and then invariant-validated before allocation or synthesis. Counts and bucket values are non-negative, distribution totals agree, bucket labels are canonical and unique, enum-like keys belong to their declared vocabulary, capacities use `long`, and internally related totals agree. Invalid input fails with a precise structured diagnostic; it never reaches `Random.nextInt`, a division, or an allocation loop.

Unbounded repeatability is represented explicitly. It is never silently converted to capacity one. Extraction can report it, but synthesis refuses a profile containing an unbounded scope or scan until an explicit bounded calibration policy is supplied.

## 4. Performance metric boundary

The profile records only an axis with a named consumer in report or interpreter performance work. Derived heuristics such as the PR's overflow-prone `interactionIndex` are omitted; consumers can derive a ranking from the lossless axes they need.

### 4.1 Structure and report cost

- Field, group, rule, and computation counts.
- Group depth, fields-per-group distribution, field-kind histogram, and constraint-presence/scale/cardinality distributions.
- Repeatable-group count, exact repeatable depth histogram, bounded max-repetition distribution, bounded root-to-node chain capacities, unbounded counts, and sibling-repeatable fan.
- Wiring density computed from one model-wide inverse: referenced-field count and field fan-out, including opaque elements. Extraction must parse each rule/computation at most once.

### 4.2 AST and dispatch cost

- Per-rule and per-computation node count, depth, maximum Boolean arity, maximum variadic arity, and distinct field fan-in.
- Deterministic operator and intrinsic-arithmetic histograms.
- Construct presence, construct combinations, and construct-by-scope joints.
- Every current sealed condition/calculation subtype is either handled or classified opaque; a parity guard fails when a new subtype is omitted.

The walk is iterative or otherwise stack-safe and visits each AST node once. Same-operator chain arity is accumulated during that walk, not by rescanning each subtree.

### 4.3 Evaluation multiplicity

The current format separates three quantities that the PR conflated:

- `scopeCapacity`: how many rule/computation contexts its own placement can create.
- `listScanCapacity`: how many cells/rows an explicit collection operand can scan. Only actual list operands count: flattened field/group refs, the starred filled-group count, and root-group count. A plain scalar reference inside a repeatable scope is not a list scan.
- `workCapacity`: the bounded product of scope capacity and the greatest explicit scan capacity, with an explicit unbounded state.

The profile also records scope × construct and scope × scan joints, maximum explicit star depth, filtered-scan count/filter complexity, walk-up distance, group-fill width/capacity, semantic-index target capacity, uniqueness key capacity, sibling-branch parallel-reference count, and every syntactically effective outer-correlation form.

Outer correlation includes scalar field values, correlated field presence/list operands, enum-category access, and current-repetition access. Flags ignored by the renderer are not inferred as work.

### 4.4 Computation-specific work

The computation profile records computed-field kinds, alternative/precondition counts, tolerance-band histogram, aggregate result-vs-operand scale match, operation archetype, computed-on-computed feeding count, and maximum cascade depth. Aggregate scale matching examines the aggregated cell, not an unrelated filter reference.

## 5. Synthesizer architecture

The synthesizer has two phases:

1. `SynthesisPlanner` validates and normalizes the profile, then produces one deeply immutable, complete plan containing structure, field kinds/configuration, rule/computation placement and shape, constants, correlations, cascades, and deterministic names.
2. `SynthesisExecutor` mechanically renders that plan through the typed dmkits. It makes no feasibility choices, consumes no random numbers, steals no reserved placements, and mutates no planning budget.

The plan is the only authoritative representation. Its records defensively copy collections; large or same-typed construction surfaces use builders. The plan is not published as a supported library API.

The executor builds one model tree/session, appends planned elements in bulk, and serializes once. It must not reparse and pretty-print the whole growing model for each rule or computation.

Given the same normalized profile and seed, synthesis is byte-identical across separate JVM launches, including tied multi-kind histograms. The synthetic namespace and values are coherent own-domain material and contain nothing from the private source model.

## 6. Fidelity contract

[`MODEL-SYNTHESIS-FIDELITY-SPEC.md`](MODEL-SYNTHESIS-FIDELITY-SPEC.md) classifies every profile axis as `EXACT`, `AT_LEAST`, `BUCKET`, or `INFORMATIVE`. `profile compare` derives its output and pass/fail decision from that classification; the implementation cannot silently omit a profile field.

Performance-critical mechanism presence and tail capacities are never allowed to under-represent the target. Structural cardinalities that determine report size are exact. Validity-constrained distributions use declared bucket tolerances. Opaque constructs are reported, never faked.

## 7. Performance workflow and acceptance

The committed profile′ fixtures exercise compact, breadth, and depth shapes without including their private targets. The fidelity gate synthesizes each fixture under a fixed seed set, re-extracts it, and runs `profile compare`.

For end-to-end measurement, the workflow generates large document instances at declared row scales, then measures:

- `ModelReport.of` and the public `dmtool model report` process over the synthesized model.
- Interpreter model preparation, first answer, warm evaluation, and N/4N growth through the existing semantic-preflighted performance harness.
- Dynamic-Groovy and generated-Java oracle agreement before any against-kernel timing.
- Model synthesis itself, with a sub-quadratic growth guard that catches whole-model rewrite-per-element behavior.

Generated models, documents, profiles, and reports go under ignored build output unless the artifact is one of the three reviewed committed profile′ fixtures.

G52 is complete only when all three operations are discoverable and schema-described, the profile/fidelity guards are green, the native CLI surface is recaptured and verified, the huge-report/interpreter workflow runs from the committed profile′ fixtures, and the proposal has graduated to `MODEL-PROFILE-SPEC.md`.
