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
- **Structured diagnostics** (F4a, first increment) — kernel problems are promoted to an LSP-shaped `Diagnostic` (`severity·source·code·summary·where·fix·explain`); a **catalog-join** reuses the matching operator's curated, N7-locked `fix`/`meaning` (no drift), and `ValidationResult.toJson()` emits an agent-parseable array. The gotcha/INFO channel, "did you mean", and AST span-pointing follow.
- **Lint layer** (F4a, first increment) — `lint.Linter` flags valid-but-suspicious conditions the kernel accepts but an LLM shouldn't have written (`source=LINT`/`WARNING`/`RK_*`, the offending operator in `where.operator`): a contradictory presence conjunction (`FieldFilled(X) And FieldNotFilled(X)`), the same pair under `Or` (a false tautology — the two aren't strict complements), and a duplicate And/Or operand. Pure-AST today; the model-context lints (unguarded negative under a repeatable group, inverted requiredness) follow.
- **Gotcha/INFO channel** (F4a) — `lint.Advisor` surfaces the catalog `gotchas` for the operators a rule *uses* as `INFO`/`RK_GOTCHA` notes (the third diagnostic kind: correct-but-surprising, which `checkConsistency` is silent on). A reusable `dsl.OperatorUsage` walker descends into nested `Calculation`s, so authoring `AddYears` surfaces the leap-day disagreement with `AddMonths` even buried in a comparison. Each catalog gotcha carries a `GotchaKind` (`SURPRISE`/`AUTHORING`/`RENDERING`); the channel surfaces the first two and suppresses rendering trivia.
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
- **`CustomCondition` (item 8)** — `customCondition(name)`, the app-defined escape hatch; the bare-identifier name is validated and both forbidden contexts (`Having` filters, computation rules) are pre-checked.
- **Date family (item 9, slice 9a)** — `m.date(path)` → `DateRef` with orderable date comparisons (`before`/`after`/`onOrAfter`/`eq`, field-vs-field and vs `today()`), `differenceIn{Days,Months,Years}` (→ number), `add{Days,Months,Years}` (→ date). Date constants + `Date(d,m,y)` construction deferred to 9b.

Corpus operator backlog complete through its core (items 1–6, 8, 9a ✅; iteration 7a–7e ✅). **Field-vs-field comparison (finding 18)** — `m.str(a).eq/ne(m.str(b))` and `m.num(a).{lt,le,gt,ge,eq,ne}(m.num(b))`; the number `==`/`!=` pre-check equal scale (`MVK_INVALID_COMPARE_DEC_PLACES`, ordering exempt). Enum-vs-enum deferred (text-bearing rules + fixture). Iteration cluster complete: 7a–7f ✅ (7f = semantic index `forIndex` + parallel iteration, on adapted index-field fixtures). Deferred: 9b (date constants/`Date()` construction), `$` correlation, enum/string `forIndex`.
- **DM-JSON write path (F6)** — `BoundModel.ofJson(json[, resolver])` then `add(Rule)` → a new binding (snapshot, F0), `toJson()`/`writeTo(path)`. Writes edit the **authored** JSON so includes/`modelReferences` are preserved (never inlined); an **includes round-trip** is fixture-proven (`storefront`→`catalog`): expand makes included fields addressable, load+save is identity on the base, the included model stays separate. Re-validates on write; the deserializer/serializer write gate is mined (instrument 4). Follow-ups: a written rule *referencing* an included field (expanded→authored path translation), Computation write, `replaceCondition`/`removeRule`. Phase-1 remainder: F4a's later increments (model-context lints + "did you mean" + span-pointing + gotcha-relevance curation — the structured `Diagnostic` + catalog-join + JSON, the first lint increment, and the gotcha/INFO channel are done), F9a `describe`/`find`/`dump` artifacts, fork-9 path policy, message interpolation; then the kernel-adapter module with text→AST ingest (F10), explainability (F9), the full modify loop (F8 #2/#3), the TS port.

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
