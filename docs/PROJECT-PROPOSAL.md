# a12-rulekit — Project Proposal

> **Status:** draft proposal / design exploration. No code yet. This document captures the vision, requirements, research, and a recommended architecture + phased plan, so we can align before building.
>
> **What it is (one line):** a **model-aware**, typed, combinator-style **builder** (Java first, TypeScript later) that produces **valid A12 Kernel validation- and computation-rule conditions for a specific DocumentModel** — the `errorCondition` / computation text the kernel otherwise expects you to hand-write as fragile strings.
>
> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. Builds only against public OSS sources (no internal systems).
>
> **Resume here:** this document *is* the project so far (Phase 0 in §16 — design, no code). The recommended decisions are in [§17](#17-decision-log); the open questions in [§18](#18-open-questions) were **reviewed and confirmed by the owner on 2026-06-02** — every one adopts its recommendation, including the genuine owner-dependent forks **#1** (source of truth → authoring *new* rules, builder-code-as-truth), **#5** (jOOQ-functional `DSL.*`), **#6** (standalone library), **#8** (consume an existing model only), **#9** (relative-paths-default rendering). The build-shaping settled defaults are **#1**, **#7** (runtime L2a binding) and **#9**. The **§19 license** is **deliberately left open for now** (owner's call, 2026-06-02); the recommendation (Apache-2.0 or MIT) stands and is to be settled before first publish. The one non-negotiable: **model-awareness is foundational** ([§5a](#5a-model-awareness)). Next step: scaffold **Phase 1** (§16).

---

## Table of contents

1. [Motivation — why this exists](#1-motivation)
2. [Vision & goals (the ask)](#2-vision--goals)
3. [Requirements](#3-requirements)
4. [Non-goals / scope boundaries](#4-non-goals)
5. [Background — the A12 Kernel DSL we build a front-end for](#5-background)
    - [The DSL surface — where rule text lives (the slots)](#dsl-slots)
    - [**5a. Model-awareness is foundational**](#5a-model-awareness)
    - [**5b. The type system — operand types are first-class**](#5b-type-system)
6. [The key reframe — "jOOQ for the A12 DSL"](#6-the-key-reframe)
7. [Prior art — what we learn from existing validators](#7-prior-art)
8. [Proposed architecture](#8-proposed-architecture)
9. [The operator catalog (the spine)](#9-operator-catalog)
10. [API design sketch](#10-api-design-sketch)
11. [Validation strategy](#11-validation-strategy)
12. [The "modify" capability](#12-modify)
13. [Polyglot strategy (Java now, TypeScript later)](#13-polyglot)
14. [LLM coding-agent friendliness](#14-llm-friendliness)
15. [Feasibility, risks & mitigations](#15-feasibility-risks)
16. [Phased roadmap](#16-roadmap)
17. [Decision log (recommended defaults)](#17-decision-log)
18. [Open questions](#18-open-questions)
19. [Relationship to sibling repos & build approach](#19-relationship)
20. [Glossary & references](#20-glossary)

---

## 1. Motivation <a name="1-motivation"></a>

The A12 Kernel is a model-driven validation engine: you declare **validation rules** (the *error* scenario) and **computations** (derived fields) as data, and the kernel compiles them to Java/TypeScript/Groovy validators. A rule's logic is carried as a **plain text string** — the `errorCondition` — written in the kernel's bilingual DSL.

That text string is **only checked at code-generation time**, where mistakes surface as cryptic `MVK_*` errors. In building a comprehensive demo suite (sibling repo `a12-kernel-demo`) and studying the ANTLR grammar (captured in `a12-kernel/KERNEL-GRAMMAR.md`), we hit an entire taxonomy of these the hard way:

- field/group named after a DSL keyword (e.g. `Date`) → `MVK_UNEXPECTED_TOKEN`
- the `$` correlation operator must precede the whole path including the leading slash
- `Date(...)` operands require a specific field type/format (`MVK_WRONG_DATE_FORMAT_FOR_OP`, `MVK_NO_DIGIT_PATTERN`)
- a rule's **error field must be referenced** in its own condition (`MVK_ERROR_FIELD_NOT_REFERENCED`)
- a rule **must live in a group** (not at the model root)
- comparing numbers of different decimal scale is itself an error unless rounded
- short field names don't resolve across sibling groups under a wrapper → use a qualified path (relative `../…`, the guide's preference, or absolute `/…`)

Every one of these is a class of bug that a **typed builder** can make *unrepresentable* or catch at *Java-compile time* instead of at codegen time. Writing pure textual DSLs is fragile; a structured builder with reusable, parameterizable building blocks (combinators) is both safer for humans and dramatically more tractable for LLM coding agents.

**Two compounding payoffs:**

1. **Correctness up front** — illegal rules don't compile, or are flagged with actionable messages that mirror the `MVK_*` fixes.
2. **LLM enablement** — a small, consistent, composable, well-named API + an explicit semantic catalog is exactly what lets a coding agent author (and later *modify*) kernel rules reliably.

---

## 2. Vision & goals (the ask) <a name="2-vision--goals"></a>

Captured from the project owner's input, in their framing:

- **A Java builder-based solution** to produce **valid conditional and computation rules**, derived from / faithful to the ANTLR grammar.
- **Combinators**: parts of an expression can be **re-used**, even via **parameterization** (a reusable, parameterized fragment library — `mandatory`, `withinTolerance`, `chronological`, `uniqueBy`, …).
- **LLM coding-agent friendly** as a first-class requirement — so Claude Code (and similar) can work with it efficiently.
- **Full context & explainability for LLMs.** rulekit should hand an LLM *everything it needs to reason about a rule or computation* (including its pre-/common-preconditions): the **rule's context** — referenced fields, the operators/functions used **and their semantics**, the structural setting (group, error field, iteration, severity). Two goals from one capability: **(1)** precisely *understand a given rule* — enough context to reason about it, explain it to a user, and **rewrite** it; **(2)** the same grounding for **building new** rules (and **later: modifying** them). *(Capability can land in a later iteration, but the plan must be prepared for it from day one — → F9, §14, D14.)*
- **Semantics matter as much as grammar.** Expose and explain not just grammar keywords but the **A12 kernel operators** — functions, path syntax, higher-level features (iteration, aggregation, correlation, computations) — and their constraints/gotchas.
- **Field/operand types are first-class.** Operators are type-specific — text vs. number (with decimal scale) vs. date vs. enumeration, etc. — so the builder must know each field's *type* (from the model) and which operators are legal on which kinds, and steer authors away from type-illegal combinations. *(→ §5b.)*
- **Model-bound, on a fully-resolved model.** Rules are authored *into* a specific `DocumentModel`; the builder binds to it for paths, types, repetition/index structure and enum domains — and to an **expanded** model, with `include`d models and imported type-definitions resolved first. *(→ §5a.)*
- **Authoring should not require code generation.** Building, rendering, and the offline pre-checks must never generate or compile code; ground-truth validation against the kernel is a lightweight **parse/check** pass, not a build. *(→ §11.)*
- **Modify, not only build** (possible future feature): editing existing rules could be a huge LLM enabler. Owner's explicit uncertainties: (1) is it feasible? (2) is it a good solution vs. just rebuild/recombine, given blast-radius tradeoffs? (3) is it the right time, or an evolution to merely *prepare* for?
- **Polyglot** (possible future feature): the solution should later be **portable to TypeScript**. Plan for it from the start, at least keep it feasible.
- **Learn from established validators**: Zod (TS), Pydantic (Python), JSON Schema (language-agnostic), Jakarta Bean Validation / Hibernate Validator (Java), FluentValidation (.NET) — and (introduced during design) **jOOQ** as the closest structural precedent.

---

## 3. Requirements <a name="3-requirements"></a>

### Functional
- **F0 (foundational) Bind to a DocumentModel.** The builder is **model-aware**: it consumes a **fully-expanded** `DocumentModel` (`include`d models and imported type-definitions resolved first — see §5a; rulekit accepts an already-expanded model, **or** a model + a caller-supplied `IDocumentModelReferenceResolver` it expands itself), indexes its group tree / field types / repetition & index fields / enum domains, and authors rules *into a specific group*. Paths, operand types, iteration validity, enum domains, and the error-field/keyword cross-checks are all resolved against that model; the renderer derives the correct path form from the rule's group context. **(See §5a — this is what makes the rest safe; it is not optional.)**
- **F0a (foundational) Model the operand type system.** rulekit owns a **type lattice** of the kernel's value kinds (number-with-scale, string, the date family, boolean, confirm, enumeration ±texts/categories, date-range, …) and the legality rules over them (which operator accepts which kind; the comparability matrix). Operator entries declare operand/return kinds against it; the typed AST mirrors it; field handles carry the kind read from the model. **(See §5b — the kernel has no readable type tag, so this lattice is rulekit's to encode; it is core, not refinement.)**
- **F1** Build **boolean `Condition`s** via a typed API — the one kind that renders into a rule's `errorCondition` **and** into computation pre-/common-preconditions (see *The DSL surface* in §5: one AST, many slots).
- **F2** Build **typed `Calculation`s** (value expressions) and assemble **`Computation`s** from them — a computed field + optional `commonPrecondition` + a list of `(precondition, operation)` alternatives — with the computed field's type & decimal scale enforced.
- **F2a** *(candidate — see Q11)* **Author the adjacent slots too**: set the model-aware **path slots** (`errorEntityRelPath` / `computedFieldRelPath`) from typed field handles (near-free once the renderer computes paths), and offer a **typed error-message interpolation helper** (`$Field.value$` / `$Field$`, enforcing the referenced-without-asterisk rule). Full message templating stays optional sugar.
- **F3** **Render** an expression to canonical DSL text (EN by default; DE available — same AST).
- **F3a (model-aware rendering)** The renderer is **model- and rule-group-aware**: it computes the correct/safest **path form** (relative / absolute / short — §18.9) and **auto-quotes keyword-named segments** (`Order/'Date'`), dissolving the **keyword-collision** class and the **common** path-error classes by construction (residual path cases — `..`+`*` must go absolute, semantic-index `For` combinations, short-name ambiguity — are handled by §18.9's path policy, not fully erased). *(See §5a, §11.)*
- **F4** **Validate** a built rule (see §11) via the kernel's lightweight **parse/convert** check (`checkConsistency` / generate-and-discard — **no compile or execute**), and report failures with actionable, `MVK_*`-aware messages.
- **F5** **Combinators**: expressions are first-class, immutable, composable values; users define reusable, **parameterized** fragments (ordinary functions/methods returning expression nodes).
- **F6** **Integrate** with the existing model authoring path — emit the string for `RuleBuilder.errorCondition(...)` / `ComputationBuilder`, and (later) emit whole Rule/Computation nodes.
- **F7** **Expose semantics**: a machine- and human-readable **operator catalog** (names, keywords, arity, operand/return types, constraints, examples, gotchas).
- **F8** **Modify.** v1 ships an immutable, **transformable** AST — the in-session "modify #1" (enabled by N5); broader modify (re-express #2, text→AST round-trip #3) is later (§12). Every modify yields a **reviewable rendered diff**, whatever its kind.
- **F9** **Explainability — full context for a *specific* rule** *(capability scheduled later; **enabled** by the Phase-1 design — see §14, §16, D14)*. Produce a **structured, LLM-ready explanation** of a given rule or computation (including its pre-/common-preconditions): the **referenced fields** (path + model type + role — error field / operand / filter / index), the **operators & functions used** (each with its catalog `meaning`, operand/return kinds, constraints, gotchas), the **structural context** (the rule's group; the error field and **whether & why the rule iterates**; severity — for a computation: the computed field + scale, the common precondition, and each `(precondition, operation)` alternative), and a **natural-language gloss**. This serves **two consumers through one mechanism**: (1) *understanding an existing rule* well enough for an LLM to reason about it, explain it to a user, and **rewrite** it; (2) *grounding the authoring of new rules*. It is **N6 at the per-rule level** — a projection of the catalog (operator semantics) + model index (field types/paths/iteration) onto one AST, so it reuses bundled material and reimplements nothing (N3).

### Non-functional
- **N1 LLM-friendliness** — small consistent surface, static-import factory, rich examples, fail-fast messages, a "validate this rule" entry point, a Claude Code skill, a structured catalog the agent can read.
- **N2 Polyglot-ready** — language-neutral contract; Java now, TS later as a *parallel implementation*, not a rewrite (see §13).
- **N3 Faithful to the kernel** — never reimplement the grammar's truth; lean on the kernel for final validation; pin to a known kernel version; a coverage test asserts catalog ↔ grammar keyword parity for **both EN and DE** terminals, keyed per token (the bundles aren't 1:1).
- **N4 No internal systems / no machine-specific data** — builds from public OSS sources via the same `mavenLocal` bootstrap as the sibling repos; publishable docs/source.
- **N5 Immutability** — all expression nodes immutable (enables safe composition and the cheap form of "modify"; see §12).
- **N6 Semantic self-sufficiency** — rulekit's *exposed API + bundled material* (the operator catalog, a **language-semantics layer**, the skill, examples, and validation messages) must be a **complete, self-contained source for authoring A12 rules**: an LLM (or developer) should be able to author *and explain* a correct rule for any covered construct **without reading the kernel BA/grammar docs**. The rulekit *is* the teacher. (Compatible with N3 — see §14's "describe vs. execute": we *describe* the semantics completely as data; we still don't *reimplement* the engine, and CI validates the catalog's examples against the real kernel so the description can't drift.) **Verified by a self-sufficiency eval** (§14): given *only* rulekit's material, an LLM must author *and* correctly explain a rule for each covered operator without the BA docs — the runnable acceptance test for this requirement.
- **N7 Self-verifying catalog** — every catalog `validExample`/`invalidExample` is executed against the **real kernel** in CI (the invalid ones largely *type* violations), so the bundled semantics (N6) cannot silently drift from the engine. Complements N3's keyword-coverage test.
- **N8 Public-API-only** — depend solely on the kernel's **public** surface (`IDocumentModel` + DM-JSON); never the internal-tier `betterbuilders` (unstable, not the public contract, Java-only — would break polyglot). The internal builder is a *reference*, not a dependency. (See **D9**.)
- **N9 Offline core** — building, rendering, and the model pre-checks run with **no kernel call**; the kernel (on the classpath via `mavenLocal`) is needed only for the opt-in ground-truth validate step (F4, §11).

---

## 4. Non-goals / scope boundaries <a name="4-non-goals"></a>

- **Not a validation engine.** The A12 Kernel is the engine; this is a typed *front-end* that emits the language it consumes. (This is the single most important boundary — see §6.)
- **v1 does not build model *structure*.** It binds to an *existing* `DocumentModel` and produces conditions/computations; assembling groups/fields/locales stays with the kernel's builders / DM-JSON. (Optional later scope may add typed model *construction* — but by emitting public **DM-JSON**, not by depending on the internal `betterbuilders`; see §5a scope and **D9**.)
- **Not a new validation language.** It targets the *existing* A12 DSL 1:1; no new semantics invented.
- **Not (initially) a DSL-text parser.** Reading existing DSL text back into the AST (round-trip) is an explicit later phase, gated on need (see §12).

---

## 5. Background — the A12 Kernel DSL we build a front-end for <a name="5-background"></a>

Authoritative detail lives in the sibling `a12-kernel` repo: `KERNEL-GRAMMAR.md` (grammar + gotchas), `KERNEL-USE.md` (consumer API), and the merged `documentation/_merged/kernel-ba.md` (the validation-language guide). Summary of what the builder must model:

- **"Rules as data."** The portable artifact is the DocumentModel JSON; each rule carries an `errorCondition` text. Our builder is a typed way to author that text correctly.
- **A rule states the *error* scenario** — `true` ⇒ invalid.
- **Construct families** the builder must cover (catalog-driven):
  - **Predicates:** `FieldFilled`/`FieldNotFilled`; field-list quantifiers (`AllFieldsFilled`, `NoFieldFilled`, `AtLeastOneFieldFilled`, `NotExactlyOneFieldFilled`, `NotAllFieldsFilled`, `FieldsNotCollectivelyFilled`); group variants; value-list membership.
  - **Operators:** comparison (`== != > < >= <=`, tolerance `DiffersWithToleranceRangeN`), logic (`And`/`Or` — **no `Not`**, parentheses required when mixing), arithmetic (`+ - * / ^`, `{ }` grouping), the field-value operator `[Field]`.
  - **Functions:** `Length`, `RangeAsString`/`RangeAsNumber`, `Abs`, rounding (`RoundUp/RoundDown/RoundAccounting`), aggregates (`Sum`, `SumOfProducts`, `Max/Min`, `NumberOfFilledFields`, `NumberOfDifferentValues`, `FirstFilledValue`), dates (`Today`, `Date(...)`, `DifferenceIn*`, `Add*`, `Valid`/`Invalid`, date ranges), `PatternMatched`/`PatternViolated`.
  - **Higher-level / structural:** paths (absolute `/a/b`, relative `..`, short names), the asterisk `*`/`**` flatten operators, the `Having` filter, the `$` correlation operator, `CurrentRepetition`, parallel iteration (join by index field), semantic index `For`, `RepetitionNotUnique`, enumeration categories `->`, error-text parameters (`$Field.value$`, `$Field$`, `$index(x)$`, …).
  - **Computations:** `Precondition | Calculation` alternatives (mutually exclusive), with the self-validation caveat (a computed field is re-checked against its formula).
- **Cross-cutting semantics / gotchas** the builder should encode or guard: keyword-name collisions, error-field-referenced, rule-must-live-in-a-group, three-valued null semantics (numbers default to 0; no empty string), decimal-scale discipline, `Date()` operand formats, bilingual keywords via `conditionLanguage`. (Full failure→fix table in `KERNEL-GRAMMAR.md`.)

### The DSL surface — where rule text lives (the slots) <a name="dsl-slots"></a>

DSL text is **not** only a rule's `errorCondition`; the same language appears in several model slots, of **two top-level kinds**:

| Slot | Kind | Returns | Context it needs | Slot-specific rules |
|---|---|---|---|---|
| `Rule.errorCondition` | **condition** | boolean | rule's group; error field | error field referenced; rule lives in a group; no negation |
| `Computation.commonPrecondition` | **condition** | boolean | computed field's group | gates the whole table |
| `ComputationAlternative.precondition` | **condition** | boolean | computed field's group | alternatives **mutually exclusive**; computed field **not** referenced here; no `CustomCondition` |
| `ComputationAlternative.operation` | **calculation** | the *computed field's* type | computed field type **+ decimal scale** | value type & scale **match the computed field**; empty inputs default (0/empty) unless guarded; `Now` self-fires; no `RepetitionNotUnique` |

So the substrate is **one expression language with two kinds — `Condition` (boolean) and `Calculation` (typed value)** — placed into these slots through two typed authoring objects: a **`Rule`** (error-entity + `Condition` + code/severity/message) and a **`Computation`** (computed field + optional `commonPrecondition` + `(precondition, operation)` alternatives + message). Consequences for the design:

- **Combinators reuse across slots for free** — a reusable boolean fragment works identically in a rule `errorCondition` and a computation `precondition` (a direct payoff of the combinator goal, F5).
- **Model-awareness supplies each slot's context** — the rule's group for conditions, the computed field's *type & scale* for `operation` (the `operation`'s validity literally depends on the computed field's declared type/scale — see §5a).
- **Slot-specific validation = per-slot constraints** layered on the two shared kinds (mutual-exclusivity, type/scale-match, etc.), pre-checked locally and confirmed by the kernel.

Two **adjacent** slots also carry mini-languages and are *candidate* (not core) scope — see open question #11: **error-message templates** (`$Field.value$`/`$Field$` interpolation, with their own referenced-without-asterisk rules) and **path slots** (`errorEntityRelPath`, `computedFieldRelPath`).

---

## 5a. Model-awareness is foundational <a name="5a-model-awareness"></a>

**The rulekit is model-bound at its core — not a model-agnostic string builder.** A rule condition is only meaningful relative to a *position in a specific DocumentModel*. The model is load-bearing for:

1. **Rule location** — relative paths, short names, `RuleGroup`, and the error-field relationship all resolve *relative to the rule's group*. The *same logical condition* renders to *different text* depending on where the rule sits.
2. **Path resolution & form** — whether an absolute/relative/short path is valid and unambiguous depends on the group tree (the `MVK_INVALID_ENTITY` / short-name-under-a-wrapper failures are model-resolution failures).
3. **Operand types** — `[Quantity] <= 0` needs a NumberType; `RangeAsString` a string; `Date(d,m,y)` bounded integers; `-> Category` an existing enum category. **Type-safe combinators require field types from the model.**
4. **Repetition / iteration** — `*`, `Having`, the `$` correlation, parallel iteration (needs a *shared index field*), semantic index `For`, `RepetitionNotUnique` all depend on which groups repeat and their index fields. You can't even know *whether a rule iterates* without the model: the kernel iterates a rule iff its **error field is repeatably referenced** (its absolute path passes through a repeatable group) — so iteration is decided by the error field's *position in the model*, not by which fields the condition happens to read. Knowing that (and where each message lands) is impossible model-agnostically.
5. **Enum domains** — allowed values and categories come from the model.
6. **Cross-checks** — error-field-referenced, keyword-name collisions, rule-must-live-in-a-group.

Knowing the model is the **source of the safety**, not a burden: the renderer computes the correct path form from the rule-group context (erasing the *common* path-error classes — a few residuals remain that the renderer handles by choosing an absolute path rather than by erasure: a relative `..` combined with `*` is forbidden (`MVK_INVALID_WILDCARD_REL`), semantic-index `For` has unsupported combinations, and short names can be ambiguous — see §18.9), typed handles make illegal operand combinations impossible to write, and iteration/enum/error-field constraints are checked by construction.

**It's cheap to obtain, and the public API exposes everything the index needs** (verified against `kernel-md-model-api`, kernel `30.8.1` — no internal tier, satisfying N8). The **model index** is defined over the **DocumentModel JSON** (the kernel's portable artifact): group tree (paths, repeatability, **index fields**, nesting); fields (type + config — number scale/min/max, string pattern, enum **values + categories**, date format/precision, custom-type name); plus derived facts (which groups repeat, a short-name uniqueness map, the keyword-collision set). The concrete public accessors that supply this:

- `IGroup.getRepeatability()` (int), `IGroup.getIndexField()` (`Optional<IDocumentModelObjectReference<IField>>`);
- `INumberType.getMaxFractionalDigits()` (the scale), `getMinValue()`/`getMaxValue()` (`Optional<BigDecimal>`), `getTrait()` (`Optional<String>` — amount/percent/permille);
- `IStringType.getPattern()`/`getMinLength()`/`getMaxLength()`; `IEnumerationType.getValues()`/`getCategories()` (+ `IEnumCategory.getName()`/`getValues()`); `IDateType.getFormat()`/`getDatePrecision()` (+ `IDateFragmentType.getFormatOfFragment()`, `IDateRangeType.getFormat()`);
- `IField.getFieldType()` → `IFieldType`. **`IFieldType` is a bare marker (no methods)** — so the kind is recovered by dispatch on the concrete subtype (`instanceof INumberType …`), and `IField.getEffectiveType()` resolves an `ITypeDefType` to its underlying type (after expansion). *(This is the source of the §5b "no readable type tag" fact.)*

**One index, both languages (normative source = the JSON).** The index is built from the DM-JSON content, so Java and a future TS implementation produce the **same** index from the **same** data. The Java reader obtains those facts via `IDocumentModel` (from `DocumentModelSerializer.deserialize(...)`) as a convenience — equivalent to reading the JSON — while a TS implementation reads the JSON directly. That index + the rule's group context is all the AST and renderer need.

**Bind to an *expanded* model.** A DocumentModel may **include** other models and **import type-definitions** (model references) — and these are **not** flattened on `deserialize`. Call `IDocumentModelService.expand(model, resolver)` (then `checkConsistency`) before indexing, so every path, operand type, enum domain, and **`TypeDef` field** resolves against a *self-contained* model. The kernel's own parser/codegen likewise runs on the expanded model — so the C-`#include` intuition holds (the parser sees the full flattened text), but with three differences that matter here: expansion is **explicit** (not automatic on load), **resolver-driven** (the references point at separate artifacts), and **best-effort if a referenced model is invalid**. This is distinct from Q10's "complete model": a model can be complete yet still *unexpanded*.

**v1 entry-shape contract (resolves review #5 — where the resolver comes from).** rulekit accepts **either** an **already-expanded** `IDocumentModel` (use directly), **or** a model **plus a caller-supplied resolver** that rulekit runs through `expand(...)` itself. The resolver is the kernel's `IDocumentModelReferenceResolver` — a **single-method, caller-supplied** interface `IDocumentModel getDocumentModel(String reference)` (returns the *unexpanded* referenced model, or `null` if unknown); the caller owns where the referenced artifacts live (files, classpath, the same `mavenLocal`, a registry). If the model carries references and no resolver is supplied, rulekit **fails fast** with an actionable message rather than indexing a half-resolved model. (Ties to Q8/Q10; the resolver requirement is now noted in **F0**.)

**Two grounding inputs, both portable data:** the **operator catalog** (*what operators exist* — generic) **+** the **DocumentModel** (*what this model contains* — specific). The DocumentModel JSON is already the kernel's language-neutral artifact, so this is fully polyglot-compatible: Java and a future TS implementation build the same model index from the same JSON.

**Levels of binding (how a field reference is expressed).** Three levels, in increasing safety-and-cost; v1 picks **L2a** (see Q7):

- **L1 — model-validated strings.** You pass string paths; rulekit checks them against the model and the renderer fixes the path form. No typed handles — *simplest, but loses the typed-handle ergonomics.*
- **L2a — runtime typed refs** *(the recommended v1 core)*. Load an `IDocumentModel` at runtime and rulekit returns **typed field handles via runtime lookups** — `m.num("/Order/Quantity")` (a number ref), `m.group("/Order/Lines")` (a group handle), `m.enumOf(...)`, `m.date(...)`. The kind is read from the model **at runtime** — **no code generation, no build step** — so it works on any model an LLM loads on the fly. The sweet spot: typed-handle safety + full model-awareness with zero per-model build.
- **L2b — codegen typed refs** *(later — Phase 3)*. Generate Java/TS field/group classes from a *specific* model ahead of time (jOOQ/QueryDSL / the kernel's typed-accessor-generator style) for IDE autocomplete + compile-time drift detection. Costs a per-model build step and is language-specific.

**Scope:** the rulekit **consumes/binds to** a DocumentModel (authoring/modifying rules *into* it). *Building* the model (groups/fields) is optional later scope; rules need a model regardless, so binding is mandatory.

> **This supersedes the earlier "model-agnostic first" stance** — see the revised **D2** in §17. Model-binding is **Phase-1 foundational** (runtime, L2a), not an optional later add-on; codegen typed refs (L2b) is the deferred enhancement.

---

## 5b. The type system — operand types are first-class <a name="5b-type-system"></a>

Field types are not a detail; together with model-awareness they are the **second pillar of the safety story**. The A12 DSL is **strongly, statically typed at code-generation time**, and the bulk of the `MVK_*` family is *type* rejection — which operator may touch which kind of value. `Length` wants a string, tolerance wants two numbers, `Date(d,m,y)` wants bounded integers, ordering (`< >`) is legal only for numbers/amounts/dates, an enumeration compares by its stored value (and a *text-bearing* enum cannot be compared to a plain string), and number `==`/`!=` is illegal across mismatched decimal scales. Encoding this is the core of what makes the typed builder *prevent* mistakes rather than merely *spell* operators.

**A design fact that shapes everything here:** the kernel exposes **no readable value-type tag** — `IFieldType` (model-api) is a bare marker with no kind accessor. Legality is enforced solely by the parser via `MVK_*` and *described* in the BA guide's comparison/operand tables. So **rulekit must own the type lattice itself** (in the catalog), reconstructed from those tables and the `MVK_*` catalog, and kept honest by the CI-validated examples (§9). This is *not* reimplementing the engine (N3) — it is *describing* which operands type-check, which is exactly the knowledge a typed front-end exists to encode.

### The field types (richer than `Num/Str/Date/Bool`)
The model-api defines **twelve authoring-relevant field types** (plus an `@Experimental` `Unspecified`, excluded). The naïve four-kind view is insufficient; the kinds that actually govern legality are:

| Field type (model-api) | Value kind | Sub-type config that changes operator legality |
|---|---|---|
| `IStringType` | string | `pattern`, min/max length |
| `INumberType` | number **+ scale** | **max-fractional-digits = scale** (gates `==`/`!=`), min/max value, `trait` (amount/percent/permille) |
| `IEnumerationType` | enum **value**, **with**- or **without-texts** | `values`, **`categories`** (enable `-> Category`), presence of localized texts (gates comparability) |
| `IBooleanType` | boolean (True / False / unset) | — (unset ⇒ comparison *not evaluated*) |
| `IConfirmType` | confirm (True / unset) | — (unset ⇒ treated as `False`) — a **distinct kind** from Boolean |
| `IDateType` | date | `format`, `datePrecision` (full / day-/month-/year-optional) |
| `IDateTimeType`, `ITimeType` (`extends IDateType`) | date-time, time | the only operands for the `…Hours/Minutes/Seconds` family |
| `IDateFragmentType` | partial date, sub-kinded by `formatOfFragment` (`MM`/`yyyy`/`yyyy-MM`/`MM-dd`) | the fragment format gates comparability + which `Add*` apply |
| `IDateRangeType` | date-range, sub-kinded by format | `format`; supports only `==`/`!=`, cannot be nested |
| `ICustomFieldType` | string-equivalent in conditions | `name` (used by `Valid(F, "Name")`) |
| `ITypeDefType` | **delegates** to the underlying type | the resolved type definition governs legality (known only after model **expansion** — see §5a) |

### The comparability rules (the matrix the builder enforces)
- **Ordering `< > <= >=`** — numbers, amounts, and dates (incl. date-fragments per their year-presence matrix) **only**; strings, enums, booleans, confirms, and date-ranges are **not** orderable (`MVK_INVALID_TYPE_FOR_COMPARISON`).
- **String ↔ Enumeration equality** — string ↔ *textless* enum: yes; **text-bearing enum ↔ string or textless enum: no**; two text-bearing enums require value/text consistency. Enums compare by **stored value**, never display text (`[Country]=="F"`, not `"France"`).
- **Number `==`/`!=`** — requires **equal declared decimal scale** (checked at parse time, from the model; scale grows under `*`). Round to match, or use `[F] <= [G] And [G] <= [F]`. **rulekit pre-checks this only for the cheap, arithmetic-free case** (two declared-scale fields); a *calculation's* result scale is parser-computed (`*` sums, `+`/`-` max, `/` unpredictable, `Round(…,z)`→`z`), so rulekit does **not** predict it — the real kernel arbitrates (N3; see §11, **D13**).
- **Boolean vs Confirm** — distinct: Boolean compares to Boolean/`True`/`False`; Confirm only to Confirm/`True`; they are **not cross-comparable**.
- **Date / DateFragment** — comparable iff both include the year or both omit it (a model **Base Year** lifts this); **DateRange** only between same-format ranges.

### Operators carry operand + return types
Every catalog entry (§9) already declares `operands`/`returns`; the §5b kinds are the vocabulary those fields draw on, and the typed AST nodes mirror them so illegal combinations don't compile. A sample of the mapping the catalog encodes:

| Construct | Operand kind(s) | Returns |
|---|---|---|
| `Length` | string | number |
| `RangeAsString` / `RangeAsNumber` | string + two numbers | string / number |
| `+` | two numbers **or** two strings | number / string |
| `- * / ^`, `Abs`, rounding, `Sum`, `SumOfProducts` | number(s) | number |
| `DiffersWithToleranceRangeN` | two numbers | boolean |
| `Date(d,m,y)` | bounded-integer operands | date |
| `DifferenceIn{Years,Months,Days}` | two dates | number |
| `Add{Years,Months,Days}` | date + integer | date |
| `PatternMatched` / `PatternViolated` | string field + regex constant | boolean |
| `-> Category` | enum (with that category) | enum / category value |
| `FieldFilled` / `FieldNotFilled` | any field | boolean |

### Conversions are explicit; literals are typed (and can ambush you)
There is **no implicit numeric↔string coercion** — crossing kinds requires an explicit conversion (`FieldValueAsString`, `FieldValueAsNumber`, `RangeAsString`/`RangeAsNumber`, `ValueAsDate`), which rulekit exposes as typed conversion nodes. Literals are typed (number; string; `True`/`False`; an enum value; the `DD.MM.YYYY` date constant). The documented hazard — a string literal matching the date format is parsed as a **date constant** (`"30.11." + [Year]` is rejected, while `"30." + "11." + [Year]` is fine) — is a *typing* trap the renderer must avoid by construction. Null/empty handling is itself type-dependent (number→0, confirm→False, others not-evaluated; see the language-semantics layer, §9).

### Consequences for the design
- **The type lattice is a first-class, named part of the catalog** — not implied by a four-node AST. The AST's value nodes mirror the kinds above (number-with-scale, string, the date family, boolean, confirm, enumeration, date-range, …); model-bound handles (`m.num(...)`, `m.str(...)`, `m.enumOf(...)`, `m.date(...)`) carry the kind read from the model.
- **v1 depth (recommended):** make the common kinds first-class (number incl. scale, string, date, boolean, confirm, enumeration incl. categories); treat **Custom** as string-like, **TypeDefinition** as a delegate to its underlying type, and **exclude** the experimental `Unspecified`; cover **DateTime/Time/DateFragment/DateRange** in the catalog and phase their builder surface in with the date/range operators that need them. *(Recorded as **D12** in §17.)*
- **Kept honest the same way as the rest:** the coverage test asserts each operator's declared operand/return kinds exist, and the CI-validated `validExample`/`invalidExample` — whose *invalid* cases are largely **type** violations — prove the lattice matches the engine.

> Net: §5a says *where* a rule sits; §5b says *what kind of value each operand is*. Authoring against the wrong type is the single largest `MVK_*` category, so the type model is **core scope**, not a refinement.

---

## 6. The key reframe — "jOOQ for the A12 DSL" <a name="6-the-key-reframe"></a>

The clarifying analogy: **this project is to the A12 DSL what jOOQ is to SQL.**

- jOOQ = a typed Java DSL that **composes** queries and **renders** them to a correct textual target language (SQL), validated by a **separate engine** (the database), with **dialects**, optional **codegen of typed refs from a schema**, and an optional **parser** built later.
- a12-rulekit = a typed (Java/TS) DSL that composes rule conditions and **renders** them to the A12 DSL text, validated by a **separate engine** (the kernel parser/codegen), with **EN/DE render targets**, optional **typed field refs from a DocumentModel**, and an optional **DSL-text parser** built later.

This contrasts deliberately with Zod/Pydantic, which **are** the engine. We are **not** the engine — so our validation responsibility is to *prevent the representable mistakes and then ask the real engine*. Framing the project as "jOOQ-style" instantly conveys the mental model to any Java developer and any LLM.

---

## 7. Prior art — what we learn from existing validators <a name="7-prior-art"></a>

Each framework teaches a specific layer of our design:

| Framework | Lang | Teaches us | Concrete take-away |
|---|---|---|---|
| **jOOQ** | Java | the reference architecture | typed nodes (`Field<T>`/`Condition`), `DSL.*` static factory, **dialects** (→ EN/DE render seam), schema-codegen of typed refs (→ deferred model-binding), parser-built-later (→ defer round-trip) |
| **Zod** | TS | functional composition + LLM ergonomics + **the TS surface we'll target** | combinators-as-values (`const Email = z.string().email()`), `.refine` escape hatch (→ `CustomCondition`), schemas-are-code (→ "rebuild over round-trip") |
| **JSON Schema** | agnostic | the **portable data contract** (closest in spirit) | "rules as data" + per-language validators = our polyglot model; `$ref`/`$defs` reuse (→ combinators); **its hand-authoring pain & awkward conditionals justify a typed front-end** |
| **Pydantic** | Python | declarative **core-schema → multi-output** | a serializable IR drives validator + JSON-Schema + docs (→ our catalog drives builder + docs + LLM); `Annotated[int, Field(gt=0)]` composable constraints |
| **FluentValidation** | .NET | the **OO fluent surface + rule grouping** (closest to our Java surface) | `RuleFor(x => …)` lambda field refs (→ typed refs), `.When()/.Unless()` (→ guards/preconditions), **`RuleSet`** (→ partial/wizard validation), reusable validators `Include`/`SetValidator` (→ combinators) |
| **Bean Validation / Hibernate** | Java | Java-native mechanics + i18n | programmatic `ConstraintMapping` fluent API (a shipped Java precedent), `ConstraintValidator` (→ `CustomCondition`), `MessageInterpolator` (→ bilingual messages) |
| (QueryDSL) | Java | schema→typed-refs codegen | `Q`-type generation (same lesson as jOOQ codegen for the model-binding phase; note the kernel already ships a typed-accessor generator we could reuse) |

**Synthesis:** borrow Zod/FluentValidation **ergonomics**, Pydantic/JSON-Schema **declarative-IR + portability discipline**, jOOQ **architecture**. Capability-wise we're closer to SQL/jOOQ than to Zod (our target language has iteration/aggregation/correlation), so we adopt their *ergonomics and IR discipline*, not their (flatter) capability model.

**Polyglot strategy that falls out:** same **catalog/AST contract**, two **idiomatic surfaces** — Java feels FluentValidation/jOOQ-like; TS feels Zod-like — both emitting identical canonical DSL.

---

## 8. Proposed architecture <a name="8-proposed-architecture"></a>

Five layers, each independently useful. The **operator catalog** is the language-neutral spine that makes the whole thing polyglot.

```
                      ┌───────────────────────────────────────────────┐
                      │  (1) OPERATOR CATALOG  — language-neutral JSON  │
                      │  names · EN/DE keywords · arity · operand/return │
                      │  types · constraints · gotchas · examples        │
                      └───────────────┬───────────────────────────────┘
            drives / validates        │        drives                 drives
        ┌─────────────────────────────┼───────────────────────┬───────────────┐
        ▼                             ▼                       ▼               ▼
 (2) TYPED COMBINATOR AST     (3) DIALECT RENDERER     docs (KERNEL-      LLM context
  immutable typed-value nodes  AST → DSL text (EN/DE)   GRAMMAR etc.)     (skill + catalog)
  nodes · DSL.* factory ·       (catalog-driven, so
  composable · transformable    logic is shared)
        │                             │
        └──────────────┬──────────────┘
                       ▼
            (4) VALIDATOR  — feed rendered text to the REAL kernel parser/codegen
                              → typed result (ok | MVK_* mapped to actionable advice)
                       │
                       ▼
            (5) INTEGRATION  — RuleBuilder.errorCondition(...) / ComputationBuilder,
                               later whole Rule/Computation nodes
```

- **(1) Catalog** — the single source of truth. Everything operator-specific lives here as data, nothing hard-coded in Java. This is what lets a future TS implementation be a *parallel reading* of the same contract rather than a port of Java logic.
- **(2) Typed AST + combinators** — immutable nodes of the **two kinds** (§5): **`Condition`** (boolean) and **`Calculation`** (a typed *value* — value nodes of the **§5b type kinds** (number-with-scale, string, the date family, boolean/confirm, enumeration, date-range, …) + aggregates). A typed surface so illegal operand combinations don't compile; a `Dsl.*` static-import factory; transformation API (the cheap form of "modify").
- **(3) Renderer** — catalog-driven AST→text; EN default, DE for free; the dialect seam also future-proofs other render targets.
- **(4) Validator** — the real kernel is ground truth, reached via an in-memory **parse/convert pass** (`checkConsistency` on the expanded model, or `generateValidationCode` with the source discarded) — **no compile, no execute** (see §11); the builder prevents the representable mistakes, the engine confirms the rest, and `MVK_*` codes are mapped to the human fixes from `KERNEL-GRAMMAR.md`.
- **(5) Integration + LLM artifacts** — plug into existing builders; ship the skill + catalog + javadoc.

---

## 9. The operator catalog (the spine) <a name="9-operator-catalog"></a>

A serializable (JSON/YAML) registry — one entry per kernel construct — that drives the builder, the renderer, the docs, and the LLM context, and is kept honest by a **coverage test** asserting every grammar lexer terminal is either represented or explicitly excluded with a reason. The parity check covers **both** the EN and DE terminals (`LexerTerminals_en.java` / `_de.java`), keyed on the **shared token constant** — the two bundles are *not* 1:1 (≈112 EN vs ≈125 DE entries), so the test reconciles per token rather than by count, and flags any terminal present in one language but missing from the catalog's other-language keyword.

It captures **semantics, not just surface** — enough that an LLM can reason about *correctness*, not only *spelling* (N6). Illustrative entry shape (not final):

```jsonc
{
  "id": "DiffersWithToleranceRange",
  "kind": "operator",                       // predicate | operator | function | aggregate | path-op | structural
  "keywords": { "en": "DiffersWithToleranceRange{N}", "de": "UngleichMitToleranz{N}" },  // DE verified vs LexerTerminals_de.java
  "variants": [1, 2, 5, 10],                // the fixed tolerance ranges (no arbitrary N)
  "operands": [ { "type": "number" }, { "type": "number" } ],
  "returns": "boolean",
  "meaning": "true (the rule fires) iff |left − right| is greater than N",     // BEHAVIOURAL semantics, plain language
  "edgeCases": [ "an unset number operand defaults to 0 (see the null-semantics layer)" ],
  "constraints": [ "both operands must be number fields/expressions" ],
  "gotchas": [ "no general tolerance N — only the fixed 1/2/5/10 variants exist" ],
  "validExample":   { "dsl": "[Quoted] DiffersWithToleranceRange5 [Paid]", "note": "fires when they differ by > 5" },
  "invalidExample": { "dsl": "[Name] DiffersWithToleranceRange5 [Paid]", "error": "operand type", "fix": "both sides must be numbers" },
  "relatedTo": [ "Abs", "comparison operators" ],
  "docRef": "VL guide → Operators and language constructs → Comparison operations",
  "sinceKernel": "30.8.x"
}
```

The catalog has **two parts**:

1. **Per-operator entries** (above) — `meaning` + `edgeCases` + valid *and* invalid examples are what make it teach correctness, not just syntax.
2. **A language-semantics layer** (cross-cutting, bundled — not a pointer to external docs): the error-scenario inversion (`true` ⇒ invalid); per-type null handling (number→0 *except in min/max*, string/date/enum not-evaluated, confirm→false, no empty string); the **formal-error "unknown" state** (a field with a formal error is *removed from evaluation* — so `FieldFilled(F) Or FieldNotFilled(F)` is **not** a tautology); the no-negation design and the fact that positive/negative predicate pairs are **not** complements; decimal-scale discipline + rounding modes; arithmetic / `^` / div-by-zero behaviour; formal-vs-rule errors; the iteration & relevance model (`*`, `Having`, `$`, `CurrentRepetition`, parallel iteration, `For`, `RepetitionNotUnique`); computation self-validation; error-text parameters; path-resolution + keyword-quoting rules. **This layer already exists as prose in `a12-kernel-demo/KERNEL-SEMANTICS.md` (the evaluation/truth model) and `a12-kernel/KERNEL-GRAMMAR.md` (the parser failure→fix layer)** — rulekit's job is to *structure* that into the catalog and *CI-verify* it, which substantially de-risks N6 (the hard content is already written).

**Self-verifying (ties N6 to N3):** the `validExample`/`invalidExample` in every entry are run against the **real kernel** in CI — so the bundled semantic material cannot silently drift from the engine. The catalog is thus the project's most reusable artifact *and* its correctness anchor: a machine-readable, self-checking spec of the A12 DSL's surface **and semantics** — usable for the builder, the renderer, LLM grounding, docs generation, and editor tooling alike.

---

## 10. API design sketch <a name="10-api-design-sketch"></a>

Illustrative only — conveys the intended shape (Java, FluentValidation/jOOQ-flavored, static-import-friendly). Final names TBD from the catalog. The bare strings (`num("Quantity")`, `"Lines*/Seconds"`) denote **model-resolved field handles**, *not* an endorsement of short-name paths — the renderer derives the actual path form (relative / absolute / short) from the rule's group per §18.9.

```java
import static com.mgmtp.a12.rulekit.Dsl.*;   // package/class names illustrative, TBD

// --- conditions ---
Condition qtyPositive = fieldFilled("Quantity").and(num("Quantity").le(0));
// renders: "FieldFilled(Quantity) And [Quantity] <= 0"

// --- aggregates + filter (Having) ---
Aggregate rushUnits = sum("Lines*/Seconds").having(bool("Lines/Rush").isTrue());
Condition rushHeavy = name("Name").isFilled().and(rushUnits.gt(600));

// --- correlated filter ($) + cross-array ---
Condition onHoliday = fieldFilled("/S/Stops/Eta")
    .and(numberOfFilledFields("/S/Holidays*/HolidayDate")
            .having(date("/S/Holidays/HolidayDate").eq(correlated("/S/Stops/Eta")))   // [$/S/Stops/Eta]
            .gt(0));

// --- computations ---
Computation lineTotal = compute("LineTotal").as(num("UnitPrice").times(num("Quantity")));
Computation bonus = compute("Bonus")
    .when(num("WeekPay").ge(1000)).calc(roundDown(num("WeekPay").times(0.10), 2))
    .when(num("WeekPay").between(500, 1000)).calc(roundDown(num("WeekPay").times(0.05), 2))
    .otherwise(literal("0.00"));

// --- combinators: reusable, parameterized fragments are just methods returning nodes ---
static Condition mandatoryWhen(Path field, Condition when) { return when.and(fieldNotFilled(field)); }
static Condition chronological(String repeatingGroup, String dateField) { /* CurrentRepetition pattern */ }

// --- render / validate / integrate ---
String dsl = qtyPositive.render();                 // canonical EN text
ValidationResult vr = Kernel.validate(qtyPositive, model);   // ground truth via the real parser
ruleBuilder.errorCondition(qtyPositive.render());  // plug into the existing model builder
```

Mapping to the two kinds (§5): `fieldFilled(...)`, `.and(...)`, comparisons → **`Condition`**; `num/str/date/bool(...)`, `sum(...)`/`Aggregate`, `.times(...)`, `literal(...)` → **`Calculation`** (typed value); `compute(...).calc(...)` places a `Calculation` into a computation `operation`, `.when(...)` takes a `Condition` precondition.

Note the deliberate absence of a `.not()` on the comparison example — the kernel has **no negation**, so the builder steers you to `le(0)` (and exposes the positive/negative predicate families directly), encoding a real semantic constraint in the API shape.

---

## 11. Validation strategy <a name="11-validation-strategy"></a>

**Three complementary lines of defense:**

1. **Make mistakes unrepresentable in the typed API** — operand typing (no `string > date`), error-field auto-referenced, paths as typed refs, "no negation" baked in, etc. Catches the bulk at Java-compile time.
2. **Resolve & pre-check against the bound model** (sourced from `KERNEL-SEMANTICS.md`). Because rulekit knows the model, a whole class of `MVK_*` is predictable *before* the kernel: the number-default-0 trap, Boolean-vs-Confirm, enum value/text/category validity, and **decimal-scale mismatch for the cheap case only** — comparing two *declared-scale fields* with no arithmetic between them (the scales come straight from `INumberType.getMaxFractionalDigits()`). Each is reported locally with an actionable message. The renderer also **auto-quotes keyword-named path segments** (`Order/'Date'`) rather than failing, dissolving the keyword-collision class by construction.

   **Where scale pre-checking stops (and N3 takes over):** the *result* scale of an arithmetic expression is computed by the kernel's **parser** (`CheckOperationImpl`: `*` sums operand scales, `+`/`-` take the max, `/` and the general `^` are unpredictable, `Round(…,z)` normalises to `z`). Predicting it would mean reimplementing that engine logic — exactly what **N3** forbids. So rulekit deliberately does **not** pre-check scale once an arithmetic operand is involved; it relies on the real-kernel step (line 3) to report `MVK_INVALID_COMPARE_DEC_PLACES` there, and steers authors toward rounding (`Round*(…, n)`) to make scale explicit. (See §5b; recorded as **D13**.)
3. **Confirm via the real kernel — a parse/convert pass, *not* a build** (ground truth). The rule `MVK_*` are produced during the kernel's model→validation-code *conversion*, **before** any code is compiled or executed — so confirmation never needs the Groovy/Java compiler or a generated validator. Preferred entry: **`IDocumentModelService.checkConsistency(expandedModel, sink)`**, which reports rule-condition `MVK_*` as `RankedNotification`s **without emitting any code** (verified: a `<`-on-a-string rule surfaces `MVK_INVALID_TYPE_FOR_COMPARISON` via the callback, no exception, no code). The alternative is `generateValidationCode(...)` with the returned source **discarded** — still in-memory, still no compile. Either way, map any `MVK_*` to the actionable fix from `KERNEL-GRAMMAR.md`; this avoids reimplementing (and drifting from) the grammar's truth while keeping even the ground-truth step lightweight. *(The dynamic `validateFull` path does compile+run Groovy — but that is for validating **documents**, not for checking a **rule's** legality; don't conflate the two. `checkConsistency` requires the **expanded** model from §5a.)*

**Recommended default:** all three (typed builder + model pre-check + real-parser validation). Steps 1–2 work offline; step 3 requires the kernel on the classpath (the same `mavenLocal` artifacts the sibling repos use).

Rejected alternatives: reimplementing semantic checks (large, drift-prone, duplicates the kernel) and render-only (weak guarantees).

---

## 12. The "modify" capability <a name="12-modify"></a>

"Modify" is **three different capabilities** with very different feasibility, value, and blast radius. Treating them as one is the trap.

| Kind | What | Feasible? | Good vs rebuild? | When |
|---|---|---|---|---|
| **#1 Transform an in-session AST** | edit an AST you just built (add a conjunct, swap a threshold) | **Yes, easily** (immutable AST + `with…`/transform combinators) | Strictly good | **Now** — essentially free if the AST is immutable from day one |
| **#2 Re-express + diff** | rule exists as text/JSON; LLM reads it (rendered + catalog), **rebuilds via the builder**, we **diff old-vs-new rendered text** | Yes, moderate | The pragmatic sweet spot: targeted intent in, validated rule out, reviewable diff — most LLM value without a parser | Phase 2 |
| **#3 Text → AST round-trip** | parse existing DSL text into our AST, edit the subtree, re-render | Conditionally (needs full grammar coverage + the kernel ANTLR parse-tree → our AST mapping); re-render is **lossy on form** (canonicalizes untouched parts) | Biggest enabler for editing *foreign/BA-authored* rules, but real blast-radius cost (noisy diffs); *minimal-edit* re-render is research-grade | Evolution, only if a concrete need forces it |

**The crux is "what is the source of truth?"**
- If the **builder code** is the source of truth → you never modify text; you edit code and rebuild. Clean blast radius. Round-trip unnecessary.
- If the **DSL text / DM-JSON** is the source of truth (legacy, BA-authored) → structural modify needs the round-trip (#3) — biggest enabler, biggest cost.

**Recommendation:** **design for modify now, build only the cheap part now.** Ship #1 (immutable transformable AST). Keep `render`/`parse` cleanly separated so #3 stays possible. Treat builder-code-as-source-of-truth as the default; reserve #3 for a real legacy-editing need; always provide a **rendered diff** so any edit is reviewable. (This mirrors jOOQ, whose parser arrived years after the core.)

---

## 13. Polyglot strategy (Java now, TypeScript later) <a name="13-polyglot"></a>

Polyglot turns "catalog as spine" from nice-to-have into a **hard requirement**, and it is on-brand: the kernel itself compiles one model to Java/TS/Groovy.

**The one rule:** all kernel-operator knowledge lives in the **language-neutral catalog**; nothing operator-specific is hard-coded in Java.

- **Language-neutral (write once, share):** the operator catalog **and** the render rules (AST→text). If the renderer is catalog-driven (data, not per-operator code), the hardest logic is shared, not duplicated.
- **Per-language (thin, idiomatic):** the fluent surface + AST node encoding — Java now (FluentValidation/jOOQ-flavored), TS later (Zod-flavored) — both emitting **identical canonical DSL**.
- **Validation per language:** Java validates via the Java kernel parser (today); TS would validate via the kernel's **TS parser/runtime**. **Feasibility caveat:** the TS kernel runtime is not built in the current OSS setup — the architecture supports TS validation, the dependency isn't there yet.

**Approach:** build Java first, but keep the contract cleanly separated so TS is "implement the same catalog," not "refactor Java." Defer any *codegen of the builders from the catalog* until parity pain justifies it. Do **not** let language-specific type tricks (Java generics, TS template-literal types) leak into the shared contract.

---

## 14. LLM coding-agent friendliness <a name="14-llm-friendliness"></a>

First-class, not an afterthought. Concrete mechanisms:

- **Small, consistent, composable surface** with a `Dsl.*` static-import factory and predictable names mirroring the kernel operators (Zod is the proof that this is what agents thrive on).
- **The operator catalog as structured data** the agent can read directly — the semantic ground truth (names, types, constraints, gotchas, examples).
- **A Claude Code skill** packaging "how to author/modify A12 rules with this library" + the catalog + the `MVK_*` failure→fix table.
- **Fail-fast, actionable errors** that mirror the `KERNEL-GRAMMAR.md` fixes (e.g. "field named `Date` collides with a keyword — rename").
- **A "validate this rule" entry point** so the agent can self-check generated rules against the real engine.
- **Example-rich javadoc/TSdoc**, and a library of reusable parameterized combinators as worked patterns.
- **Polyglot naming parity** so an agent transfers Java↔TS knowledge 1:1.

### The rulekit is the teacher (semantic self-sufficiency — N6)

The goal: an LLM authors *and explains* correct A12 rules from **rulekit's API + bundled material alone**, without reading the kernel BA/grammar docs. That requires capturing semantics at **three tiers**:

- **(A) Per-operator** — the catalog (§9), deepened with `meaning` + edge/null behaviour + valid *and* invalid examples + related operators.
- **(B) Language-level** — the bundled **language-semantics layer** (§9): null handling, the error-scenario inversion, no-negation, decimal discipline, the iteration/relevance model, formal-vs-rule errors, computation self-validation, path resolution, etc. — *shipped in rulekit*, not referenced externally.
- **(C) By construction** — the API *shape* teaches and prevents: typed operands (only valid ops compile), no `.not()`, model-bound field handles (only real paths/types). The shape is itself semantics.

**Describe vs. execute (how N6 and N3 coexist):** we capture a *complete description* of the semantics (data + docs); the kernel stays the sole *executor* and final validator. Describing ≠ reimplementing. Kept honest by the coverage test (catalog ↔ lexer terminals) + real-parser validation + **CI-validated catalog examples**, so the bundled knowledge can't drift.

**Make it measurable — a self-sufficiency eval:** given *only* rulekit's material, can an LLM author + correctly explain a rule for each covered operator without the BA docs? Where it can't, the material is incomplete. This is a concrete, runnable acceptance test for N6.

### Rule explanation & full context — the introspection surface (F9)

The catalog and language-semantics layer above teach the DSL *in general*. The complement an LLM also needs is **full context about one specific rule** — so it can precisely understand that rule, explain it to a user, and safely rewrite it (and, symmetrically, ground the authoring of a new one). Concretely, an `explain(rule, model)` surface assembles, for a given rule or computation:

- **Referenced fields** — every field the condition/calculation touches, each with its **model type** (number-with-scale, enum-with-categories, date-precision, …) and its **role** (error field, plain operand, filter operand, index field, `$`-correlated outer ref).
- **Operators & functions used** — the catalog entry for each (`meaning`, operand/return kinds, constraints, gotchas, related ops), so the *semantics* travel with the rule, not just the spelling.
- **Structural context** — the rule's group; the error field and **whether & why the rule iterates** (the §5a #4 error-field rule — per-row vs once); severity and message; for a computation, the computed field + scale, the common precondition, and each alternative.
- **A natural-language gloss** of what the rule asserts (in error-scenario terms: "fires when …").

**One mechanism, two goals** (the project owner's framing): *(1)* understand an existing rule → reason / explain / **rewrite**; *(2)* understand the building blocks → author new rules. Both are the same projection of *catalog + model index* onto an AST.

**Two source cases (mirrors the modify split, §12):**
- a rule **built via rulekit** — the AST is in hand, so explanation is a **pure, cheap projection** (available the moment the AST exists; no parser);
- a **foreign / BA-authored** rule (only DSL text/JSON) — explaining it needs either the text→AST round-trip (#3, §12) or a lighter "annotate the text against catalog + model" pass. This is the later, harder case, gated like #3.

**Design-now, ship-later (the prepared-for-it requirement).** The *capability* is scheduled later (§16, Phase 2), but it is **only cheap if the Phase-1 AST is built for it**: AST nodes must **retain their catalog operator-id and their model-resolved field references** (not flatten to opaque strings), and the model index must stay queryable. With that invariant held from day one (recorded as **D14**), `explain(...)` is a read over existing structures rather than a re-architecture — and it doubles as the natural input to modify-by-re-express (#2) and the self-sufficiency eval above. *(Format/depth and the foreign-text scope are open question #12.)*

---

## 15. Feasibility, risks & mitigations <a name="15-feasibility-risks"></a>

| Risk | Likelihood | Mitigation |
|---|---|---|
| Grammar / semantics drift (catalog vs kernel) | med | pin a kernel version; coverage test vs lexer terminals; lean on the real parser for truth; **CI-validate the catalog's examples against the kernel** so bundled semantics can't silently drift (N6) |
| Operator coverage gaps block round-trip (#3) | med | catalog-driven, incremental coverage; #3 deferred and gated on need; graceful fallback to text |
| Over-abstracting for polyglot too early | med | build Java first with a clean contract seam; TS is a parallel impl, not a refactor |
| Re-render blast radius (modify) | med | prefer builder-as-source-of-truth + rebuild; always show a rendered diff; minimal-edit render only if truly needed |
| Validation needs the kernel on the classpath | low | same `mavenLocal` bootstrap as siblings; build+render works offline, validate is opt-in |
| Scope creep (huge undertaking) | high | strict phasing (§16); a thin end-to-end vertical slice first |
| Type lattice must be hand-encoded — the kernel exposes no readable type tag (§5b) | med | encode kinds + comparability as catalog data; coverage test asserts operand/return kinds exist; CI-validate the (largely type-violating) `invalidExample`s against the real engine so the lattice can't drift |
| TS validation depends on unbuilt TS kernel runtime | med | architecture supports it; treat TS *validation* as a later milestone, TS *build+render* sooner |

**Overall feasibility:** the core (build + render + validate-via-kernel, Java, core operators) is clearly feasible and high-value. The ambitious parts (full coverage, round-trip, TS) are feasible but should be **earned by need**, not front-loaded.

---

## 16. Phased roadmap <a name="16-roadmap"></a>

- **Phase 0 — Design & catalog skeleton.** This proposal; define the catalog JSON schema; stand up the coverage-test idea; pick the kernel version; scaffold the build against `mavenLocal`.
- **Phase 1 — Java vertical slice (the real near-term win).** Bind to a `DocumentModel` → build a **model index** (L2a runtime typed refs — §5a); catalog → typed combinator AST (immutable + transformable, capability #1) → **model+rule-group-aware** renderer (computes path form; EN/DE) → **model-resolution pass** then validate via the real kernel parser → integrate with `RuleBuilder`/`ComputationBuilder`. Cover **conditions + computations, core operators** (everything the `a12-kernel-demo` showcases use + the common set). Ship the LLM skill + catalog. *(Model-binding is foundational here, not deferred.)* **Explainability invariant (D14):** AST nodes carry their **catalog operator-id + model-resolved field refs** (no flattening to opaque strings), so the F9 `explain(...)` surface is later a cheap read, not a re-architecture — even though the capability ships in Phase 2.
- **Phase 2 — Modify + explain (the LLM edit loop).** **Rule explanation / full-context surface (F9)** for rulekit-built ASTs — referenced fields + types, operators + catalog semantics, iteration/error-field/severity context, NL gloss — which is *also* the natural input to **modify-by-re-express + rendered diff (#2)**. Pairing them is deliberate: an LLM **explains → re-expresses → diffs**. Both are cheap given the Phase-1 invariant. (Explaining *foreign* DSL text — not rulekit-built — is gated on the #3 round-trip in Phase 5.)
- **Phase 3 — Codegen typed refs (L2b) + optional model construction.** Generate Java/TS typed field/group handles from a `DocumentModel` for *compile-time* safety (à la jOOQ/QueryDSL, or reusing the kernel's typed-accessor generator); optionally add typed model *construction* (groups/fields) so model+rules can be authored in one flow.
- **Phase 4 — TypeScript port.** Parallel TS surface over the same catalog/render rules (Zod-flavored). TS *build+render* first; TS *validate* once the kernel TS runtime is available.
- **Phase 5 (evolution, gated) — Text→AST round-trip (#3).** Kernel ANTLR parse-tree → our AST, coverage guarantee, canonical re-render. Only if a concrete legacy-editing need arises.

Each phase is independently shippable and useful; later phases are *earned by need*.

---

## 17. Decision log (recommended defaults) <a name="17-decision-log"></a>

| # | Decision | Recommended default | Rationale |
|---|---|---|---|
| D1 | Validation | typed builder **+ real kernel parser** (ground truth) | no grammar reimplementation/drift; builder prevents the common mistakes |
| D2 | Model-awareness | **model-bound from the core** (runtime `IDocumentModel`, L2a — see §5a); codegen typed refs (L2b) later | a rule is meaningless without its model: location, path resolution/form, operand types, repetition/index structure, enum domains, and the cross-checks all require it — and model-awareness is the *source* of the safety. *(Supersedes the original "model-agnostic first" — that was wrong.)* |
| D3 | v1 scope | **conditions + computations, core operators**, immutable/transformable AST | the whole near-term win; catalog-driven coverage grows |
| D4 | Source of truth | **builder code** (rebuild over round-trip); reserve #3 for legacy text | cleanest blast radius; matches Zod's model |
| D5 | Modify posture | ship **#1 now**, plan **#2**, keep seam open for **#3** | design-for-it, build-the-cheap-part |
| D6 | Polyglot | **catalog = language-neutral contract**; Java now, TS later as parallel impl; data-driven renderer; dialect seam day one | makes TS a parallel reading, not a rewrite |
| D7 | Framing | position explicitly as **"jOOQ-style"** | instantly conveys the mental model to devs + LLMs |
| D8 | LLM artifacts | catalog-as-data + skill + javadoc + validate-entry-point + `MVK_*`-aware messages | first-class N1 requirement |
| D9 | Relationship to the existing model builder | **Don't depend on the internal `betterbuilders`.** Bind to the **public `IDocumentModel`** (from DM-JSON); emit condition/computation *strings* into the existing `RuleBuilder.errorCondition(...)` or into DM-JSON; if we ever build full models, **emit public DM-JSON** (the internal builder is a *reference*, not a dependency). | the `betterbuilders` are internal-tier (unstable, not the public contract), Java-only (breaks polyglot), and leave `errorCondition` a raw string anyway — the exact fragility we remove. Public `IDocumentModel` + DM-JSON is stable, polyglot, and the kernel's own "good-citizen" path. |
| D10 | Project name | **a12-rulekit** (renamed from the initial `a12-dsl-builder`); **keep it despite the broadened scope** | "builder" undersold the scope and "dsl" misread as *building a DSL*; "rulekit" = a kit for authoring A12 *rules* (the kernel's umbrella term for **both** validation and computation rules), and reads well as a Java/TS package id. It survives the broadening because (a) the center of gravity is still *authoring rules* — the catalog, model-awareness, validation, modify and polyglot are all *enablers* of that; (b) "kit" already absorbs that breadth; (c) precedent — jOOQ is named for *querying* yet also does schema codegen, dialects, and a parser. The one part it undersells (the operator catalog as a reusable *semantic spec of the DSL*) is the **spine, not the headline**. **Re-evaluate the name only if Q8 lands** — i.e. the rulekit becomes a first-class way to *construct DocumentModels* (groups/fields), at which point it'd be a model+rules kit and a broader name (e.g. a model-authoring framing) would be warranted. |
| D11 | Semantic self-sufficiency (N6) | the bundled material — deepened catalog (`meaning`/edge/valid+invalid examples) **+ a language-semantics layer + skill + CI-validated examples** — is the **complete authoring source**; an LLM shouldn't need the kernel BA docs | maximises LLM autonomy + correctness; the rulekit *is the teacher*. Compatible with D1/N3 because we **describe** semantics (data), not **execute** them — and CI validates the examples against the real kernel so the description can't drift |

| D12 | Type-model ownership & depth (§5b) | rulekit **owns the type lattice** in the catalog (the kernel exposes no readable type tag); v1 makes the common kinds first-class (number-with-scale, string, date, boolean, confirm, enumeration incl. categories), treats **Custom** as string-like and **TypeDefinition** as a delegate to its underlying type, **excludes** experimental `Unspecified`, and phases **DateTime/Time/DateFragment/DateRange** in with their operators | operand-type legality (which operator works on which kind, the comparability matrix) is the largest `MVK_*` class and the core of "make mistakes unrepresentable"; since it can't be read off the model-api, it must be encoded as data and CI-verified against the engine |
| D13 | Decimal-scale pre-checking boundary (§11/§5b) | pre-check scale **only for the arithmetic-free case** (comparing two declared-scale fields, scales read from `INumberType.getMaxFractionalDigits()`); for any expression involving arithmetic, **do not predict** the result scale — defer to the real-kernel `checkConsistency` step and steer authors to explicit `Round*` | the kernel's *parser* computes result scale (`*` sums, `+`/`-` max, `/`/general `^` unpredictable, `Round(…,z)`→`z`); predicting it would reimplement engine truth (**N3**). Owning only the cheap field-vs-field case keeps the pre-check honest and drift-free |
| D14 | Explainability (F9, §14) — design now, ship later | Treat a structured **`explain(rule, model)`** surface (referenced fields + types + roles, operators + catalog semantics, iteration/error-field/severity context, NL gloss) as a **first-class goal scheduled for Phase 2**, but make the **Phase-1 AST carry catalog operator-ids + model-resolved field refs** so it's a cheap projection, not a rebuild. Explaining *rulekit-built* ASTs is Phase 2; explaining *foreign DSL text* is gated on the #3 round-trip (Phase 5). | understanding precedes rewriting/modifying — explanation is the same catalog+model projection that powers re-express (#2) and the self-sufficiency eval (N6); it is near-free *iff* the AST keeps its structure, so the only thing that must happen now is the invariant, not the feature |

*(Recommended defaults. The entries mirrored by a now-decided §18 question are locked accordingly — D2↔#7, D4/D5↔#1, D6↔#5/#6, D12↔#4, D13, D14↔#12; the rest stand as the working design unless revisited.)*

---

## 18. Open questions <a name="18-open-questions"></a>

Each carries a **recommendation**, its **rationale**, and the **alternatives** considered.

> **Reviewed and confirmed by the owner on 2026-06-02 — all twelve adopt their recommendation.** The recommendation/rationale/alternatives below are retained as the design record (the *why* behind each choice and what was rejected); the ⮕ markers flag which were the genuine owner-dependent forks rather than confirmations. The owner explicitly chose to **leave the §19 license open for now** — it is the sole deferred item; everything in §17/§18 is locked.

1. **Source of truth in practice** — is the goal purely *new* rule authoring, or must we eventually edit *existing BA-authored DSL/JSON*? ⮕ **fork — decided: recommendation adopted** (it's what flips #3 from someday to must-have).
   - **Recommendation:** default to **new-authoring, builder-code-as-source-of-truth** (rebuild); keep the render/parse seam open so the #3 round-trip *can* be added later.
   - **Scope clarification (this is about *rules*, not the model).** Binding to an **existing `DocumentModel`** is *always* supported and is the only mode — F0/§5a: v1 consumes an existing model and never builds model structure. Q1 concerns only the **rules' source of truth**: authoring/replacing rules *through the builder* (recommended) vs. reading back **pre-existing rule *text*** you didn't author via rulekit (the #3 round-trip, deferred). So "work on an existing model, (re)authoring its rules with the builder" is the mainline; only round-tripping foreign rule strings is deferred.
   - **Rationale:** covers the near-term LLM value (authoring + in-AST transform #1 + re-express #2) cheaply; #3 is the expensive, drift-prone, blast-radius-heavy capability — don't pay for it speculatively.
   - **Alternatives:** (a) commit to #3 round-trip now (right only if editing legacy/foreign models is a primary goal); (b) treat text/JSON as source of truth but do editing via #2 re-express+diff (no parser) — the middle path.

2. **Validation dependency** — must the kernel be on the classpath to validate, or must build+render be fully standalone?
   - **Recommendation:** **kernel-on-classpath for the *validate* step; build+render standalone** (validation opt-in).
   - **Rationale:** real-engine validation (D1) is the correctness anchor with no reimplementation; the kernel is already needed for model binding + CI; offline build+render still delivers the typed-API + model-pre-check value. Note the *validate* step is a lightweight **parse/convert** call (`checkConsistency` / generate-and-discard — **no compile or execute**; §11), not a heavyweight build, so "kernel on the classpath" is the only cost.
   - **Alternatives:** (a) bundle a pinned kernel jar so validation is always zero-config; (b) never call the kernel — rely only on typed API + model pre-checks (weaker guarantees; rejected by D1).

3. **Catalog authorship** — hand-curate vs. generate from the grammar?
   - **Recommendation:** **hand-curated entries + a generated coverage check vs lexer terminals + CI-validated examples.**
   - **Rationale:** generated-from-grammar APIs are low-level and awkward (the ergonomics need curation); the coverage test + CI examples keep curation honest against drift.
   - **Alternatives:** (a) fully generate catalog/builder from the grammar (max sync, poor ergonomics); (b) pure hand-authoring with no coverage test (drifts silently; rejected).

4. **Computation surface depth** — how much of computations in v1?
   - **Recommendation:** v1 covers **simple + tiered/conditional (mutually-exclusive preconditions) + common precondition**; defer parallel-iteration computations and repeatable-computed-field subtleties.
   - **Rationale:** that covers `ComputationDemo` and the bulk of real use; the deferred cases are rarer and carry the self-validation/decimal-scale subtleties best handled once the core is proven (catalog-driven, so coverage grows).
   - **Alternatives:** (a) full computation coverage in v1 (more upfront, over-build risk); (b) conditions-only v1, computations later (faster first ship, defers half the goal).

5. **Naming / ergonomic style** — jOOQ-functional `DSL.*` vs FluentValidation-OO `RuleFor(...)`? ⮕ **fork — decided: recommendation adopted** (partly taste; affects the TS twin).
   - **Recommendation:** **jOOQ-functional `DSL.*` static-import core**, with optional FluentValidation-style sugar; optimise for a clean Zod-flavored TS twin.
   - **Rationale:** functional value-combinators compose best (matches the combinator/parameterization requirement + immutable AST), are the most LLM-legible, and map most directly to TS (Zod is functional). We bind to a *DocumentModel* via field handles, not a typed POJO, so `RuleFor(x -> …)` fits less naturally.
   - **Still current under modern Java (25):** no Java feature supersedes the static-import-factory + immutable-fluent-node idiom (no operator overloading / extension methods / named args), so jOOQ/QueryDSL remain the exemplars. Modern Java changes the *internals, not the surface*: model the AST as **sealed interfaces + records** and dispatch the renderer with **pattern-matching `switch`** — keep the jOOQ-style authoring surface on top.
   - **For the TS twin:** *not* a literal jOOQ port — TS re-skins to a **Zod-flavored** surface (a `z`-like namespace / standalone functions + chaining, not Java static imports) over the **same catalog + AST contract**. The portable part is the **functional combinator core** that jOOQ and Zod share; the per-language skin differs (§13). Keep language-specific type tricks (Java generics, TS template-literal types) out of the shared contract.
   - **Alternatives:** (a) FluentValidation-OO primary (familiar, but less TS-portable); (b) both surfaces first-class (more API to maintain, parity burden).

6. **Distribution** — standalone library, or contribute into the kernel? ⮕ **fork — decided: recommendation adopted** (intent: personal tool vs contribution).
   - **Recommendation:** **standalone library alongside `kernel-md-facade`** for now; revisit upstreaming only if broadly useful and the maintainers want it.
   - **Rationale:** standalone = fast iteration, decoupled release cycles, matches the "personal/unofficial" status; upstreaming is a later governance decision, not a technical blocker.
   - **Alternatives:** (a) propose into the kernel from the start (max reach/official, but slow + couples to kernel releases); (b) keep private/personal-only.

7. **Binding level for v1 (see §5a)** — L2a runtime refs vs L2b codegen refs? *(**L2a** = typed field handles looked up from a loaded model at runtime, no codegen; **L2b** = typed handles **generated** from a specific model ahead of time, à la jOOQ; **L1** = plain strings validated against the model. Full definitions in §5a.)*
   - **Recommendation:** **L2a runtime typed refs from a loaded `IDocumentModel`** for v1; defer **L2b codegen typed refs** to Phase 3.
   - **Rationale:** L2a needs no build step, works on any model an LLM loads at runtime (ideal for the dynamic/LLM use case), and delivers the model-awareness safety immediately; L2b is a pure compile-time-autocomplete enhancement with a per-model codegen cost.
   - **Alternatives:** (a) L2b codegen from the start (best IDE autocomplete + compile-time drift detection, but a per-model build step and Java-specific); (b) L1 model-*validated* only (string paths checked against the model — simplest, but loses the typed-handle ergonomics).

8. **Consume vs build the model** — does the rulekit also construct models? ⮕ **fork — decided: recommendation adopted** (timing).
   - **Recommendation:** **v1 consumes/binds to an existing public `IDocumentModel` only**; if model *construction* is added later, **emit public DM-JSON** (per D9), and only when a concrete one-flow need appears.
   - **Rationale:** rules need a model regardless → binding is mandatory and sufficient for v1; building models is adjacent scope, and DM-JSON keeps the public/polyglot/stable foundation.
   - **Alternatives:** (a) add typed model construction in v1 for a single model+rules flow (more scope, but a complete authoring story); (b) wrap the internal `betterbuilders` for construction (fast, but inherits internal-tier instability + Java-only; rejected by D9).

9. **Path-rendering policy** — relative vs absolute vs short paths? ⮕ **fork — decided: recommendation adopted** (diff/readability tradeoff).
   - **Recommendation:** default to **relative paths from the rule group** (the kernel guide's *own* preference — shortest, and they survive group moves), falling back to **absolute** when a relative path can't be proven unambiguous against the model **or whenever the path combines `..` up-navigation with the `*` asterisk** (which the kernel forbids in relative form — `MVK_INVALID_WILDCARD_REL`); use **short names** only when the model sets `fieldRefByShortNameAllowed` *and* the name is unique; **auto-quote keyword segments** (`Order/'Date'`) regardless.
   - **Rationale:** relative is what the guide favours (it says relative notations are shorter and survive a Rule-Group move) and yields the cleanest diffs (good for modify/#2); absolute is the safe, unambiguous escape *and is mandatory for up-navigation + asterisk* (which is why cross-group aggregate rules use it); short names are the fiddly, setting-gated form. Being model-aware, the renderer can *prove* uniqueness and pick the safest readable form. *(Independently verified against the BA guide — Rule Group / Paths sections + the `MVK_INVALID_WILDCARD_REL` parser message; `KERNEL-SEMANTICS.md` §10 is correct.)*
   - **Alternatives:** (a) always-absolute (max determinism/safety, noisier vs hand-authored, ignores the guide's preference); (b) preserve-author-intent / minimal-edit (only meaningful with #3 round-trip; research-grade).

10. **Incomplete models** — author against a half-built model, or only a complete one?
    - **Recommendation:** **v1 requires a complete, resolvable model**; don't support authoring against a half-built model initially.
    - **Rationale:** the model-awareness guarantees (paths/types/iteration) need the model resolved; partial models mean degraded checks and ambiguous errors.
    - **Alternatives:** (a) support incremental/partial models with deferred resolution (re-check when complete) — flexible for the one-flow case (Q8), but complex and weaker mid-build; (b) a "finalize/snapshot" step that freezes the model before rule authoring.

11. **Adjacent DSL slots — in scope?** (see *The DSL surface*, §5) — beyond the core condition/calculation slots, should v1 also cover **error-message templates** (`$Field.value$`/`$Field$` interpolation, with their own referenced-without-asterisk rules) and **path slots** (`errorEntityRelPath`, `computedFieldRelPath`)?
    - **Recommendation:** **paths yes, message-templates light.** The renderer already computes paths (it must, to render conditions), so setting `errorEntityRelPath`/`computedFieldRelPath` from typed field handles is essentially free and worth including. Offer a **typed error-message helper** (interpolate a field handle → emit a valid `$Field.value$`, enforcing the no-asterisk rule) but treat full message authoring as optional sugar.
    - **Rationale:** paths are the same model-aware machinery as conditions (low marginal cost, removes another hand-written-string class); message interpolation is lower-risk and lower-frequency, so a small helper suffices without committing to a full templating surface.
    - **Alternatives:** (a) cover all four (conditions, calculations, paths, message-templates) as first-class (most complete; more surface); (b) core condition/calculation only, leaving paths/messages to the caller (smallest; leaves two string classes unguarded).

12. **Explainability — output shape & scope (F9, §14, D14).** What does `explain(rule, model)` return, and what does v1 cover?
    - **Recommendation:** emit **structured data first** (a JSON/object bundle: referenced-fields-with-types-and-roles, operators-with-catalog-semantics, iteration/error-field/severity context) **plus an optional rendered NL gloss**, and scope v1 to **rulekit-built ASTs only**. Build the **Phase-1 AST invariant** (catalog-ids + model refs) now regardless; ship the surface in Phase 2.
    - **Rationale:** structured output is what an LLM consumes most reliably and what feeds modify-by-re-express (#2) and the self-sufficiency eval; the AST-built case is a cheap, drift-free projection. Foreign-text explanation needs the #3 round-trip (or a lighter annotator) and rides that phase.
    - **Alternatives:** (a) prose-only explanation (friendlier to read, weaker for machine reasoning/round-trip); (b) cover foreign DSL text in v1 (pulls the #3 round-trip forward — large, drift-prone; rejected for v1); (c) defer explainability entirely and don't hold the AST invariant (cheap now, but a costly retrofit later — the project owner's "prepare the plan for it" explicitly rejects this).

---

## 19. Relationship to sibling repos & build approach <a name="19-relationship"></a>

- **`a12-kernel`** — the engine + its grammar/semantics docs (`KERNEL-GRAMMAR.md`, `KERNEL-USE.md`, merged BA guide). This project depends on it for the validate step and tracks its grammar.
- **`a12-kernel-demo`** — the runnable feature showcases; the operators its demos exercise define the **Phase-1 catalog coverage target** (the showcase set as of 2026-06-02: `DslTourDemo`, `IterationDemo`, `ComputationDemo`, `CrossArrayDemo`, `LanguageDemo`, `ExtensionDemo`, plus `CodegenDemo`/`DocumentApiDemo`/`PartialValidationDemo`). Its **`KERNEL-SEMANTICS.md`** (the evaluation/truth model — the prose basis for the catalog's *language-semantics layer*, §9), `KERNEL-GLOSSARY.md`, and `SHOWCASE.md` feed the LLM context. It also ships a working **typed-accessor codegen** path (`kernel-md-typed-accessor-gen`'s `TypedAccessorGenerator` CLI, `--mode overlay`) — the concrete precedent rulekit reuses for **L2b codegen typed refs (Phase 3)**.
- **`a12-kernel-bootstrap`** — the dedicated, project-independent kit (extracted from the demo on 2026-06-02; MIT-licensed) that publishes the kernel + its A12 dependencies to **`mavenLocal`** from public sources only. rulekit consumes its output exactly as `a12-kernel-demo` does (run `./bootstrap.sh` once; idempotent). It builds **Java artifacts only** — there is **no TypeScript runtime build**, which is the concrete reason TS *validation* is a later milestone (§13, §15).
- **The existing model builder (`…internal.betterbuilders.*`)** — used by the demo to assemble model *structure* (groups/fields/rules), but it is **internal-tier** (unstable), **Java-only**, and still takes the rule's `errorCondition` as a raw string. a12-rulekit treats it as a **reference, not a dependency** (see **D9**): it binds to the public `IDocumentModel` and produces the *condition*; any model *construction* it adds emits public DM-JSON. (a12-rulekit thus also fills a real gap — the kernel has no *public* in-code model builder.)
- **Build:** Java 21 toolchain, Gradle 9.0.0, resolving the kernel from **`mavenLocal`** — published from public sources by the dedicated **`a12-kernel-bootstrap`** kit (above). rulekit binds to the **public consumer surface only**: `kernel-md-facade` (→ `DocumentModelServiceFactory` / `IDocumentModelService` for `expand`/`checkConsistency`), the public `IDocumentModel`, and the DM-JSON serializer. It **must not** depend on `kernel-md-model` / `kernel-md-a12internal` — those carry the internal-tier `betterbuilders` that **D9/N8** exclude (the demo, which *does* build model structure, pulls them deliberately; rulekit does not). **No internal mgm systems; no machine-specific data in committed files** (hard rules inherited from the sibling repos).
- **Kernel version:** pin to the line the siblings target (currently `30.8.1` / 2025.06-ext5); the catalog records `sinceKernel` per operator.
- **Licensing & IP.** rulekit ships **no kernel source** — it only *emits* the kernel's DSL text and *binds* to the kernel's **public** artifacts (`IDocumentModel` / DM-JSON, resolved from `mavenLocal`). The bundled operator catalog is rulekit's *own* structured description of the public DSL surface (CI-validated against the kernel — N7), not copied kernel material; quoted `MVK_*` codes and keyword strings are interface identifiers, not source. **License: deliberately left open for now** (owner's call, 2026-06-02) — the recommendation stands: a permissive OSS license (e.g. Apache-2.0 or MIT), consistent with the "publishable / unofficial, public-sources-only" status (N4) and matching `a12-kernel-bootstrap`'s MIT precedent; to be settled before first publish.

---

## 20. Glossary & references <a name="20-glossary"></a>

- **Catalog** — the language-neutral JSON registry of kernel operators/semantics; the project's spine.
- **AST** — the immutable typed expression tree the builder constructs.
- **Value kind / operand type** — the kernel's notion of *what kind of value* an expression yields (number-with-scale, string, date/date-time/time, date-fragment, date-range, boolean, confirm, enumeration ±texts, custom); it determines which operators are legal on it (§5b).
- **Type lattice** — rulekit's encoded model of the value kinds + the legality/comparability rules over them (which operator accepts which kind). Owned by the catalog because the kernel exposes no readable type tag (§5b).
- **Render / Dialect** — turning the AST into canonical DSL text (EN/DE).
- **Explain / introspection (F9)** — the inverse of render: projecting the catalog (operator semantics) + model index (field types/paths/iteration) onto a rule's AST to produce a structured, LLM-ready account of it (referenced fields + roles, operators + semantics, iteration/error-field/severity context, NL gloss). Enables an LLM to understand, explain, and rewrite a rule; scheduled Phase 2, enabled by the Phase-1 AST invariant (D14).
- **Combinator** — a reusable, often parameterized, expression fragment (an ordinary function/method returning an AST node).
- **Condition** — the **boolean** expression kind; renders into a rule's `errorCondition` *and* into computation pre-/common-preconditions (§5 *The DSL surface*).
- **Calculation** — the **typed value** expression kind (value nodes of the §5b type kinds + aggregates); renders into a computation `operation`, and supplies operands inside conditions.
- **Slot** — a place in the DocumentModel where DSL text lives (the four in §5's table); each slot expects a *kind* (Condition/Calculation) plus model context.
- **Rule / Computation** — the two typed authoring objects rulekit produces: a `Rule` (error-entity + `Condition` + code/severity/message) and a `Computation` (computed field + optional `commonPrecondition` + `(precondition, operation)` alternatives).
- **Language-semantics layer** — the bundled, cross-cutting semantics (null handling, no-negation, decimal discipline, iteration/relevance, formal-vs-rule errors, …) shipped *inside* rulekit so it is self-sufficient (N6), not a pointer to external docs.
- **Semantic self-sufficiency (N6)** — the property that rulekit's API + bundled material is enough to author and explain correct rules without the kernel BA docs ("the rulekit is the teacher").
- **Round-trip (#3)** — parsing existing DSL text back into the AST.
- **MVK_*** — the kernel parser/codegen error codes (a documented contract; failure→fix table in `a12-kernel/KERNEL-GRAMMAR.md`).

**References (by name, in sibling repos / public docs):** `a12-kernel/KERNEL-GRAMMAR.md`, `a12-kernel/KERNEL-USE.md`, `a12-kernel/documentation/_merged/kernel-ba.md` (validation-language guide) and `kernel-dev.md` (developer guide), `a12-kernel-demo/KERNEL-SEMANTICS.md` (the evaluation/truth model) + `SHOWCASE.md` + `KERNEL-GLOSSARY.md` + `COMPARISON.md`, `a12-kernel-bootstrap/README.md` (the from-source `mavenLocal` build); the public A12 docs & forum at geta12.com. External: jOOQ, Zod, Pydantic (v2 core-schema), JSON Schema, Jakarta Bean Validation / Hibernate Validator, FluentValidation, QueryDSL.
