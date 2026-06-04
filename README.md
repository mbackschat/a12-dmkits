# a12-rulekit

A **model-aware**, typed, combinator-style **rule kit** (Java first, TypeScript later) for A12 Kernel validation- and computation-rule conditions: **author** them through a typed builder, **read** existing rule text into the same typed representation, **modify** with reviewable diffs, and **render + validate** against the real kernel — replacing the fragile hand-written `errorCondition` strings. Think *"jOOQ for the A12 validation DSL"* — including the reverse direction, since the kernel's own parser supplies it.

> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's **public** OSS surface (no internal systems); an opt-in Java-only adapter module may read the kernel's internal AST tier under strict guardrails (proposal D15).

## Status

Phase 1 in progress (Java vertical slice). The design is captured in **[`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md)** (**v2**, reworked 2026-06-03) — vision incl. the **bidirectional edit loop** (*ingest → explain → modify → diff → validate*, over any rule in a model), requirements, architecture, the operator-catalog spine, the decision log (§17), and the phased roadmap (§16). The kernel/editor API inventory behind the rework is **[`docs/KERNEL-RULE-API-SURVEY.md`](docs/KERNEL-RULE-API-SURVEY.md)**; verified kernel facts in **[`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md)**.

Working end-to-end today:
- **Model index** (F0) — load DM-JSON → `expand` → `checkConsistency` → index group tree, field kinds, number scale, repetition.
- **Operator catalog** (§9) — bundled JSON spine with verified bilingual keywords; **self-verifying**: every example is checked against the real kernel (N7).
- **Typed condition AST + renderer** — model-bound handles compose immutable `Condition`s; every node carries its **catalog operator-id + model-resolved refs + provenance hint** (the D14 invariant that makes explain/ingest cheap later); the renderer emits canonical EN/DE text with *no keyword knowledge outside the catalog*.
- **Ground-truth validator** (F4) — injects a rendered rule as DM-JSON and runs `checkConsistency`, mapping `MVK_*` to problems.
- **`Rule` + `Computation` authoring objects** (F2/F2a) — `m.rule(group)…build()` / `m.computation(field).when(…).calc(…)…build()` (model-aware entries): computed `errorEntityRelPath`/`computedFieldRelPath`, build-time slot-rule pre-checks with the `MVK_*` code named (error-field-referenced incl. enclosing-group semantics; no computed-field self-reference; operation kind via `Calculation.kind()`; the kernel-illegal mixed/`otherwise` alternative form is unrepresentable; the **locale law** — header locales are arbitrary, a message per declared locale required, undeclared ones rejected), validator bridges.
- **`Dsl.*` static facade + `Condition.transform` + Level-1 navigation** — handle-based factories (incl. n-ary `allOf`/`anyOf`), the bottom-up rewriter (modify #1), and jOOQ-`Meta`-style model navigation (`fields()`/`groups()`/`fieldAt` streams of info handles with strict narrowing — the rule-template enabler); number comparisons take scale-faithful `String`/`BigDecimal` literals.
- **Pattern operators** — `StringRef.matches`/`violates` → `PatternMatched`/`PatternViolated` with the kernel's slot rules made unrepresentable (string field left, string-constant regex right) and a `Pattern.compile` fail-fast; string literals render with the kernel's quote-doubling escaping (no backslash escapes — a latent renderer bug this increment uncovered).
- **Value-list membership** — `in`/`notIn` on `StringRef`/`NumberRef`/`EnumRef` (integer-only numbers by signature; enum domains pre-checked) plus the three field-list quantifiers (`AtLeastOne`/`No`/`NotAllFieldValue(s)IncludedInValueList`) with type-homogeneous lists enforced by the API.
- **Filled-count aggregates** — `numberOfFilledFields` (field and group forms) / `numberOfFilledGroups` as comparable numeric expressions; the kernel's param-size laws live in the method signatures, root-group refs are pre-checked.
- **Flattened repetition lists (`*`, iteration slice 7a)** — `m.flatten("/Order/Items*/Price")` → `FieldListRef`, accepted by the list slots (quantifiers, shape-B value lists, count aggregates) alongside plain refs; scalar slots take `FieldRef` only, so a star in a scalar slot is unrepresentable.
- **Uniqueness (slice 7b)** — `repetitionNotUnique(keys…).from(group)` (composite keys, localized `@From`/`@Von`, repeatable-scope pre-check; the position law — keys repeatable *relative to the rule group* — is kernel-locked) and the `FieldValuesNotUnique` cross-field distinctness predicate.
- **List aggregates (slice 7c)** — `sum`/`maxValue`/`minValue` and `numberOfDifferentValues` as comparable numeric expressions over plain fields or `*`-lists; number-only, type-homogeneity, param-size, and the duplicate-operand laws all pre-checked with the `MVK_*` code named.
- **`Having` filter (slice 7d)** — `flatten(...).having(filter)` narrows a `*`-list before an aggregate/quantifier consumes it; the starred-field requirement is structural and the representable filter-condition exclusions (RepetitionNotUnique, nested Having) are pre-checked. (`$` correlation deferred — it needs field-vs-field comparison.)
- **`CurrentRepetition` (slice 7e)** — `currentRepetition(group)` as a numeric expression (groups-only; the iterating-position law is kernel-locked). Semantic index (`For`/`Fuer`) + parallel iteration are split to 7f (need index-field fixtures).

Not yet built: relative/short path policy (§18 fork 9), message interpolation + DM-JSON write path (F2a/F6), broader operator/type coverage (iteration cluster: 7a–7e ✅; 7f deferred — semantic index/parallel iteration need index-field fixtures; field-vs-field comparison unblocks `$` correlation; then `CustomCondition` and the date family), the model-context surface (F9a: `describe`/`find`/`dump`), the kernel-adapter module with text→AST ingest (F10), explainability (F9), the full modify loop (F8 #2/#3), the TS port.

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21**.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

The suite covers kernel wiring, the model index, the renderer (EN/DE), the validator, the self-verifying catalog (each example checked against the real kernel), and the constraint-mining instruments (slot parity, MVK ledger) — so a green `test` means rulekit's output is confirmed by the engine, not just plausible. With the sibling `a12-kernel-mm` clone present, an opt-in harness additionally loads all 15 of the kernel's own metamodels through the index (skipped when absent).

## License

Deliberately left open for now; a permissive OSS license (Apache-2.0 or MIT) is the working intent — to be settled before first publish. See proposal §19.
