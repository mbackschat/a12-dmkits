# Model corpora analysis — operator usage & transform backlog

A read-only **analysis** of two real A12 model corpora, used to prioritize what rulekit builds next and to *pattern-transform* (not copy) realistic rules into our own fixtures. This file records **facts about DSL usage** (operator names, frequencies, abstract skeletons) — **never** the corpora's domains, field names, or rules. The two corpora are complementary: **A12 Tools** (Corpus 1) shows *everyday application* rules; **`a12-kernel-mm`** (Corpus 2) shows *power-user / type-system* rules and is far denser in the advanced operators.

# Corpus 1 — A12 Tools application models

## Source & provenance (read-only; do not commit corpus content)

- **Source:** the `workspaces/` tree of an *A12 Tools* product distribution (proprietary sample/business models) — **not** the OSS kernel repos and **not** `a12-kernel-demo`. Path is machine-specific (a mounted volume).
- **Rule (from `CLAUDE.md`):** never copy domains/fields/rules from non-demo sources. We treat this strictly as a **read-only oracle**: mine *patterns and frequencies*, then author original fixtures in our own domains, kernel-validated via N7. The histogram and skeletons below are usage facts, not their content.
- **Version:** all 68 models are `modelVersion` **28.4.0** and the distribution is **2025.06-ext5 = kernel 30.8.1** — identical to rulekit's pinned version. No version tolerance needed.

## Corpus stats

- **68 document models**, **150 rule conditions**, **27 models reference other models** (`modelReferences` — the `expand` + resolver path, **now integration-tested via F-RES**, see below).
- **Ingest re-measured with the directory resolver (F-RES, 2026-06-06): 107 document models, 106 load, 1289 rules → 839 converted (65.1%), 450 opaque.** (Supersedes the stale ~55% from before the F10 build-out + before references resolved.) The single load failure is a genuine model-consistency error, not a resolver miss. The opaque tail is **all F10 operator coverage** (no resolver gaps left): the big buckets are unmodeled comparison forms (~257 `IComparisonCondition`), the **`$` outer-iteration correlation (~127, correctly preserved verbatim** — D13, not silently converted), and group/list aggregates (~57). Next coverage lever = the comparison bucket. Measured by `adapter.Corpus1IngestCoverageTest` (opt-in, `-Da12.corpus1.dir=…`, proprietary — prints, asserts nothing, commits nothing).
- Rule-dense models reach ~29 rules each; conditions are frequently **multi-line** and contain `;;` **comments**, and keywords appear in **case variants** (`and` / `AND` as well as `And`).
- **Header locale form: the bare, language-only code dominates here** — **268** models declare `[{"code":"en"},{"code":"de"}]` against **107** with full `en_US` (the opposite of our fixtures and `a12-kernel-mm`, which use the full `language_COUNTRY` form). **Practical bearing:** these bare-locale models **deserialize** but our validate/author path **rejects** them — `checkConsistency` doesn't support the bare validation language `en` (KERNEL-FINDINGS §13 supplement; `dsl.BareLocaleTest`). So validating/authoring against a workspace model needs the locale normalized to a supported full code (`en`→`en_US`) first — a real prerequisite if we ever target this corpus, not just a message-tagging detail.

## Operator / construct frequency (across the 150 conditions)

| Count | Construct | rulekit coverage |
|--:|---|---|
| 194 | `And`/`and`/`AND` | ✅ (renders `And`; case variants are read-only concern) |
| 87 / 83 | `FieldFilled` / `FieldNotFilled` | ✅ |
| 56 | `GroupFilled` | ✅ (`GroupRef` + group quantifier family) |
| 39 / 15 | `==` / `!=` | ✅ |
| 28 | `*` flatten | ✅ (`flatten()` → `FieldListRef`, slice 7a) |
| 21 | `AllFieldsFilled` | ✅ (quantifier family done) |
| 21 | `$` correlation | ❌ deferred — needs field-vs-field comparison (D13) |
| 19 / 11 / 11 / 7 | `<=` / `<` / `>` / `>=` | ✅ (symbols; catalog has `<=`) |
| 14 | `Or`/`or`/`OR` | ✅ |
| 13 / 10 / 8 / 6 / 5 | `NotExactlyOneFieldFilled` / `NotAllFieldsFilled` / `FieldsNotCollectivelyFilled` / `AtLeastOneFieldFilled` / `NoFieldFilled` | ✅ (quantifier family done) |
| 11 | `NumberOfFilledFields` (aggregate) | ✅ (`CountAggregate` → comparable `NumberExpr`) |
| 11 / 3 | `True` / `False` (boolean & confirm literals) | ✅ (`BoolRef.isTrue/isFalse`, `ConfirmRef.confirmed/notConfirmed`) |
| 8 / 8 / 8 / 6 / 4 | `CurrentRepetition` ✅ / `For` (semantic index) ✅ / `Today` ✅ / `Having` ✅ / `RepetitionNotUnique` ✅ | `For` ✅ 7f, `CurrentRepetition` ✅ 7e, `Having` ✅ 7d, `RepetitionNotUnique` ✅ 7b, `Today` ✅ 9a |
| 6 / 1 / 1 / 1 | `Date(...)` / `DifferenceInDays` ✅ / `DifferenceInYears` ✅ / `AddMonths` ✅ | `DifferenceIn*`/`Add*` ✅ 9a; `Date(...)` → 9b |
| 5 | `->` (enum category) | ✅ (`EnumRef.category(...)`) |
| 4 / 4 / 3 / 1 / 1 | `RangeAsString` / (group: `AtLeastOneGroupFilled` ✅) / `PatternViolated` ✅ / `GroupNotFilled` ✅ / `Length` ✅ | partial (`RangeAsString` ❌) |
| ~2 | value-list membership (`IncludedInValueList`, `NoFieldValue…InValueList`) | ✅ (`in`/`notIn` + quantifier statics) |

## Abstract pattern inventory (generic skeletons — to transform into our own domains)

- **Boolean-gated requiredness:** `[Bool] == True And FieldNotFilled(G)` / `[Bool] == True And NotAllFieldsFilled(a, b, c)`
- **Guarded cross-field comparison:** `AllFieldsFilled(F1, F2) And [F1] <op> [F2]`
- **Cross-group address equality:** `[GroupA/X] == [GroupB/X] And …`
- **Uniqueness over a repeating group:** `GroupFilled(G) And RepetitionNotUnique(G/Key)`
- **Enum-category navigation:** `[Field -> Category] == "X"`
- **Date thresholds:** `AllFieldsFilled(Start, End) And DifferenceInDays(Start, End) > N`; `[Date] >= Today`
- **Aggregate-driven:** `NumberOfFilledFields(a, b, c) <op> N`
- **Filtered aggregate / correlation:** `Sum(G*/X) Having (…)`, `[$/Other/Field]` correlated refs

## Reference scheme & the directory resolver (F-RES, 2026-06-06)

A multi-file app's rules reach into other models — so loading one needs the others. The mechanism (verified against the workspaces, kernel `30.8.1`):

- **`header.modelReferences[]`** entries carry `{ alias, modelType, purpose, reference }`. The **`reference`** value is the target's **`header.id`** (a bare model id, not a filename/path/hash); `alias` is the local name the condition text uses.
- **Two `purpose` kinds matter for loading:** `include` pulls in a **full document model's structure** (groups/fields/rules); `typeDefinitions` imports the **type definitions** from a referenced model. (Other purposes — `data binding`, `bindingReference`, `…-for-overview/tree/query` — bind non-document model types and aren't resolved through the document-only resolver.) Both loading kinds funnel through the **same** `IDocumentModelReferenceResolver.getDocumentModel(reference)` — `expand` drives includes, `extractImportedTypeDefinitions` drives typedef imports.
- **Detect document models by `header.modelType == "document"`** — *not* by a filename convention. (The `_DM`/`_TDM`/`_CDM` suffixes happen to be the `document`-typed files and `_OM`/`_FM`/`_TM`/… the others, but the suffix is incidental; `modelType` is the contract.) A type-definition model is itself `modelType: "document"`, so one id-keyed index of all `document` models serves both `include` and `typeDefinitions`.

`ReferenceResolvers.directory(root…)` implements exactly this: walk the tree, index every `modelType=="document"` file by `header.id`, and deserialize a fresh instance per `getDocumentModel` call (expansion mutates in place). `KernelAdapter.ofJson(json, resolver)` / `BoundModel.ofJson(json, resolver)` thread it through both the MVK bootstrap and the index. All Corpus-1 references resolve within the one tree (no external/system targets).

## Parser-relevant findings (for the future text→AST round-trip, #3)

Real conditions include `;;` line comments, `\r\n` line breaks, and case-variant keywords (`and`/`AND`). A future parser must tolerate all three; the catalog's keyword set must include the case variants (distinct lexer tokens `UND1`/`UND2`/`UND3`).

# Corpus 2 — `a12-kernel-mm` metamodels (advanced operators)

## Source & provenance (read-only; do not commit)

- **Source:** `a12-kernel-mm/documentModels/` — the kernel's own **metamodels** (MM_Common, MM_Computation, MM_Typedef, MM_CombinationModel, SelectionModel, CT_* custom-type attribute models). Internal type-system models, not application domains. A **kernel OSS repo → do-not-copy** (`CLAUDE.md`); read-only, transform *shapes* only.
- **Volume:** 59 JSON files = the same logical models in render variants (`a12-`/`codegen-`/`none-`) × current/`-previous`. Deduped to **15 canonical models** (one per directory, preferring `a12-`, else `codegen-` — `MM_Common_v2` ships only `codegen-`) carrying **547 rule conditions** — far rule-denser than Corpus 1. *(Corrected 2026-06-04 by the shipped harness; the earlier 13/355 figures used a selection that missed codegen-only directories.)*
- **Version:** header `modelVersion` **28.3.0** (one minor below Corpus 1's 28.4.0). Within the 28.x line the kernel reads; expected to load tolerantly, **not yet verified through `ModelLoader`** — a good check for the resolver/expand integration test.

## Operator frequency (canonical set, 355 conditions)

| Count | Construct | rulekit coverage |
|--:|---|---|
| 500 / 29 | `And` / `Or` | ✅ |
| 233 / 54 | `==` / `!=` | ✅ |
| 202 / 105 | `FieldFilled` / `FieldNotFilled` | ✅ |
| 137 | `*` flatten | ✅ (slice 7a) |
| 70 | `Length` | ✅ |
| 57 | `RepetitionNotUnique` | ✅ (slice 7b) |
| 54 | `->` enum-category | ✅ |
| 53 | `AllFieldsFilled` (quantifier family) | ✅ |
| 46 | `GroupFilled` | ✅ |
| 45 | `CustomCondition` | ✅ (`Dsl.customCondition(name)`) |
| 23 | `True` / `False` literals | ✅ |
| 23 | value-list membership (`…InValueList`) | ✅ (literal lists; `*`-path lists ride the iteration cluster) |
| 14 / 13 | `PatternMatched` / `PatternViolated` | ✅ (`StringRef.matches`/`violates`) |
| 7 | `NumberOfFilledFields` | ✅ |

**Insight:** the metamodels are the heavy users of the *advanced* cluster (`*`, `->`, `RepetitionNotUnique`, `Pattern*`, `CustomCondition`, value-lists) that Corpus 1 barely touches. The kernel team's own rules are the strongest evidence of which advanced operators matter — and they pervasively use `;;` comments and multi-line conditions (reinforcing the #3 parser findings above).

# Combined transform backlog (both corpora, frequency × effort)

Counts shown as C1 (app) + C2 (metamodel). Order balances combined frequency against effort.

1. ~~**Boolean (+ confirm) literals & comparison**~~ — ✅ **done 2026-06-03** (`BoolLiteral` + `BoolRef`/`ConfirmRef`; confirm-vs-False made unrepresentable — `MVK_INVALID_COMPARE_TO_YES`/`_YESNO` verified and N7-locked).
2. ~~**Group predicates**~~ — ✅ **done 2026-06-04** (all seven: `GroupFilled`/`GroupNotFilled` + five group-list quantifiers; `MVK_NO_FIELDS_ALLOWED`/`MVK_NO_GROUPS_ALLOWED` made unrepresentable; enclosing-group-counts-as-error-field-reference verified).
3. ~~**Enum-category navigation**~~ — ✅ **done 2026-06-04** (`EnumRef.category(...)` → validated `CategoryRef`; `MVK_CATEGORY_UNKOWN`/`MVK_NO_ENUMERATION`/`MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` made unrepresentable or fail-fast).
4. ~~**Pattern operators**~~ — ✅ **done 2026-06-04** (`StringRef.matches/violates` → `PatternCondition`; left-side bare-string-field + right-side string-constant slot rules made unrepresentable; `Pattern.compile` fail-fast for `MVK_INVALID_PATTERN` with the portable-subset gate kernel-delegated — and the increment uncovered + fixed the renderer's string-literal escaping: quote-doubling, not backslashes; KERNEL-FINDINGS §7).
5. ~~**Value-list membership**~~ — ✅ **done 2026-06-04** (all five operators: `in`/`notIn` on `StringRef`/`NumberRef`/`EnumRef` + the three `Dsl` field-list quantifiers; integer-only numbers, kind-matching, type-homogeneous field lists, and the bool/confirm exclusion all unrepresentable; enum domains pre-checked. Field-valued lists + `*`-path field lists deferred to the iteration cluster).
6. ~~**`NumberOfFilledFields` aggregate**~~ — ✅ **done 2026-06-04** (`Dsl.numberOfFilledFields` field/group forms + `numberOfFilledGroups` → `CountAggregate` as a comparable `NumberExpr`; the param-size laws live in the signatures, root groups pre-checked. Sibling `NumberOfDifferentValues` ✅ landed with slice 7c).
7. **Iteration / structural cluster** — its own multi-step phase (slices 7a–7e), in progress:
   - 7a ~~`*` flatten~~ (28 + 137) — ✅ **done 2026-06-04** (`BoundModel.flatten` → `FieldListRef`; list slots take the `FieldOperand` union, scalar slots stay `FieldRef` — `MVK_NO_WILDCARDS_ALLOWED` erased, `MVK_INVALID_WILDCARD` pre-checked; negativity law + `MVK_NO_WILDCARD` pre-check deliberately kernel-delegated, see KERNEL-FINDINGS §9).
   - 7b ~~`RepetitionNotUnique`~~ (4 + 57, + `@From` scope) — ✅ **done 2026-06-04** (`Dsl.repetitionNotUnique(keys…).from(group)`; keys are plain `FieldRef`s by signature, `@From` repeatability pre-checked, the position law kernel-delegated + locked; **`FieldValuesNotUnique` rode along** as a `Quantifier` family member; order fixture gained repeatable `Items{Sku, Count}`).
   - 7c ~~`Sum`/`MaxValue`/`MinValue` + `NumberOfDifferentValues`~~ — ✅ **done 2026-06-04** (`Dsl.sum/maxValue/minValue` → `ListAggregate`, `numberOfDifferentValues` joins `CountAggregate`; number-only, homogeneity, param-size, and the newly found duplicate-operand law (`MVK_DUPLICATE_PARAM1`) all pre-checked; a single `*`-operand satisfies the param law. `SumOfProducts` + `FirstFilledValue` deferred; Max/Min date variant rides item 9).
   - 7d ~~`Having` filter~~ — ✅ **done 2026-06-04** (`FieldListRef.having(filter)`; starred-field requirement structural, works in aggregate + quantifier slots, filter-condition exclusions pre-checked for the representable constructs (RNU + nested Having), rest kernel-delegated). **`$` correlation deferred** — it needs field-vs-field comparison (`[inner] == [$outer]`, D13 not built); `MVK_INVALID_OUTER_ITERATION` recorded, pre-check rides that feature.
   - 7e ~~`CurrentRepetition`~~ (8) — ✅ **done 2026-06-04** (`Dsl.currentRepetition(group)` → numeric expression; groups-only structural, iterating-position law kernel-delegated + locked).
   - 7f ~~`For`/`Fuer` semantic index + parallel iteration~~ — ✅ **done 2026-06-04** (own-domain index-field fixtures `logistics.dm.json` + `order.dm.json` `Items`, adapted from the workspace `indexFieldName` mechanism): `NumberRef.forIndex("value")` → `[Field For "value"]` (string-constant form; entity/`$` form kernel-delegated, enum/string `forIndex` deferred); parallel iteration supported by non-interference (existing handles over two index-keyed groups validate). The `MVK_NO_WILDCARD` missing-star + RNU-position pre-checks are **confirmed kernel-delegated** — parallel iteration makes plain cross-group refs legal, so a naive pre-check false-positives.
8. ~~**`CustomCondition`**~~ — ✅ **done 2026-06-04** (`Dsl.customCondition(name)` → `CustomConditionRef`; bare-identifier name validated; both forbidden contexts pre-checked — inside `Having` (`MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION`) and in computation rules (§14)).
9. **Date family** — ~17 + ~0; app-only.
   - 9a ~~`Today` + date ordering + `DifferenceIn{Days,Months,Years}` + `Add{Days,Months,Years}`~~ — ✅ **done 2026-06-04** (`DateRef`/`DateOperand`/`DateExpr`; date field-vs-field ordering needs no scale machinery; `Today` composes as comparison + function operand; bare-vs-bracketed-by-position rendering; number-vs-date rejection N7-locked. Order fixture gained `OrderDate`/`DeliveryDate`).
   - **9b (deferred — format-finicky / special operands):** date constant literals (`31.12.2020` — fragment/format-dependent), `Date(d,m,y)` construction + `Valid`/`Invalid` (need bounded-integer operand fields), `Now`, `DateRange`/`DateRangesOverlap`, `DateTime`/`Time`. Low marginal demand (KERNEL-FINDINGS §6).
10. **`Valid(Field, "Type")` / `Ungueltig`** — *low priority, post-backlog.* The `predefinedTypeValidityCondition` predicate: "is the field a valid instance of a registered **custom field type**" (kernel ships a verifier library — `kernel-rt/kernel-core-customfieldtype-impl`: Steuernummer, BIC/IBAN, IdNr, date formats, …). Distinct from `CustomCondition` (item 8 = app-registered *boolean*); this delegates to a custom-*type* validator and runs at runtime, so it's mostly a *formal-validation* (field-type) concern, not a rule concern. **Never appears in either corpus histogram** — apps rely on formal type validation, not the `Valid(...)` predicate — so it's noted here only so it isn't forgotten; build on real demand. rulekit already classifies such fields as `ValueKind.CUSTOM`. (EUPL/German-domain verifiers: reference-only, never vendored.)

11. **`RangeAsString`** — *uncovered; low-medium priority.* Appears in Corpus 1 (count 4) and has **no rulekit handle** (the only Corpus-1 operator still ❌ — surfaced 2026-06-04 by the F4a catalog-coverage cross-check). Renders a number/amount field's value as a formatted string (locale-aware), typically to feed a string comparison or message. Build when a concrete need appears; until then it's the one acknowledged gap in the everyday-application operator set. **Now catalogued as a `PLANNED` stub** (proposal §9 stub-entries policy, 2026-06-04) — keywords grounded in the lexer bundle (`RangeAsString`/`BereichAlsString`), no example, exempt from N7 until built. The other deferreds are stubs too: `Date(...)` construction, `Now`, `DateRange`/`DateRangesOverlap`, `SumOfProducts`, `FirstFilledValue`, `Valid`/`Invalid` (items 7/9/10). `$` correlation stays a non-stub deferral (it's a syntax form, not an operator keyword).

## How this drives the work

Each backlog item is built as a normal increment: catalog entries (verified EN/DE keywords) + AST node(s) + typed handles + renderer, with examples authored in **our own** fixtures and **kernel-validated by N7**. The corpora only tell us *what to build and in what order*; correctness comes from the kernel, originality from our own domains.

**Decided sourcing policy:**
- **Corpus 1 (A12 Tools, proprietary):** read-only analysis only. Transform via **one-time pattern-inspired original authoring** — our own coherent fixtures exercising the patterns; never commit or structurally migrate their content.
- **Corpus 2 (`a12-kernel-mm`, EUPL-1.2 OSS):** *referenced*, not vendored. The bootstrap already clones it as a sibling, so an **opt-in integration test** reads `../a12-kernel-mm/documentModels` (env-overridable, skipped if absent). Not committed — EUPL copyleft would conflict with rulekit's intended permissive license (§19), and referencing ≠ copying.

~~That opt-in test … needs a filesystem resolver for kernel-mm's multi-variant reference scheme~~ — **shipped 2026-06-04** (`KernelMmIntegrationTest`, opt-in, skips when the sibling clone is absent): all 15 canonical metamodels load, expand, and pass `checkConsistency` under the pinned 30.8.1 kernel (28.3.0 tolerance **verified**), and every field classifies to a concrete kind (typedef-heavy — zero UNKNOWN). The resolver assumption was wrong: **none of the 59 files carries `modelReferences`** — variants are file naming, not references; `selfContained()` suffices. Schema-oracle findings in `KERNEL-FINDINGS.md`.

## The corpora as the ingest round-trip oracle (Phase 2 — noted 2026-06-03)

With the text→AST read path now core scope (proposal v2, F10/§12), the corpora gain a second job beyond prioritization: **a ready-made, real-world coverage harness for the converter.** An opt-in test sweeps every readable corpus condition through *kernel parse → converter → rulekit AST → re-render → kernel `formatConditionText` comparison* and reports, per condition: converted cleanly / fail-fast construct gap (with the missing construct named) / canonical-form mismatch. 505 real conditions (150 app + 355 metamodel) measure converter coverage against actual usage instead of synthetic fixtures — and the fail-fast gap report *is* the converter backlog, ordered by real frequency. Sourcing boundaries unchanged: `a12-kernel-mm` via the sibling clone (env-overridable, skipped if absent), the *A12 Tools* workspaces via the machine-specific mount (local-only, never CI, never committed); nothing of either corpus enters the repo — the harness reads, converts, compares, and reports counts.
