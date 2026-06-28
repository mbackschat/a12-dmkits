# JSON Schema ⇄ Document Model — feasibility proposal

**Status: research / proposal. Nothing is built. No module exists yet.** This doc assesses whether a JSON-Schema import/export capability for a12 Document Models (structure **and** rules) is feasible, and if so, what shape it should take — as an **isolated subproject**, mirroring how `:interpreter` was built ([INTERPRETER-SPEC.md](INTERPRETER-SPEC.md) — spike-in-repo, then extract; §8/§10).

It is written to be read top-to-bottom, but §2 is the headline: the feasibility answer is **asymmetric**, and that asymmetry drives every other decision.

---

## 1. The problem

We want to move document models across the JSON-Schema boundary:

- **Import** — take a JSON Schema (a third party's data contract, an OpenAPI component, an existing form spec) and produce a usable a12 Document Model skeleton, so an author starts from structure instead of a blank page.
- **Export** — render an a12 Document Model as a JSON Schema, so external tools (validators, form generators, OpenAPI pipelines, code generators) can consume the *shape* of our documents.

The naïve expectation is "it's just a structural transcoding." It is not, because a Document Model is not a data schema — it is **structure + a rule/computation engine**. JSON Schema has structure and a *small* declarative-validation vocabulary, and **no computation vocabulary at all**. So the interesting question is not "can we map `object`→group" (we can) but **"what happens to the rules — the crown jewel?"**

## 2. The headline finding: the boundary is asymmetric

JSON Schema and the a12 DM overlap **strongly on structure, weakly on validation logic, and not at all on computation.**

```
                       a12 Document Model
   ┌─────────────────────────────────────────────────────────┐
   │  STRUCTURE            RULES (conditions)     COMPUTATIONS │
   │  fields/groups/       three-valued logic,    value-       │
   │  enums/typedefs/      cross-field, aggregates,producing   │
   │  includes             iteration, dates       function     │
   │                                              tables       │
   └────────┬───────────────────┬──────────────────────┬──────┘
            │ ~90% lossless      │ ~15% native,         │ 0% native
            │ both ways          │ rest via carriage    │ carriage only
            ▼                    ▼                      ▼
   ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐
   │ JSON Schema      │  │ JSON Schema      │  │ JSON Schema      │
   │ type/properties/ │  │ pattern/enum/    │  │ (nothing —       │
   │ items/$defs/$ref │  │ min*/max*/       │  │  not a compute   │
   │                  │  │ if-then/dependent│  │  language)       │
   └─────────────────┘  └──────────────────┘  └──────────────────┘
```

The consequence: **structural import/export is genuinely feasible and valuable; rule fidelity is only achievable two ways, and you must pick which one you mean before designing anything else** (§8). This is the single most important decision in the whole feature.

## 3. What each side can express (the vocabulary inventory)

### 3.1 JSON Schema (target: Draft 2020-12, the current draft; Draft-07 is the most-deployed and OpenAPI 3.1 uses 2020-12)

| Group | Keywords |
|---|---|
| **Type/structure** | `type` (string/number/integer/boolean/object/array/null), `properties`, `required`, `additionalProperties`, `patternProperties` |
| **Arrays** | `items`, `prefixItems`, `minItems`, `maxItems`, `uniqueItems`, `contains`/`minContains` |
| **String** | `minLength`, `maxLength`, `pattern`, `format` (date, date-time, time, email, uri, uuid, …) |
| **Number** | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` |
| **Value sets** | `enum`, `const` |
| **Reuse** | `$defs`, `$ref`, `$id`, `$anchor` |
| **Composition** | `allOf`, `anyOf`, `oneOf`, `not` |
| **Conditional** | `if`/`then`/`else`, `dependentRequired`, `dependentSchemas` |
| **Annotation** | `title`, `description`, `default`, `examples`, `readOnly`, `deprecated`, `$comment`, and **`x-*` vendor extensions** |

The critical limits for us: JSON Schema is **per-instance and largely field-local** (its conditional/dependent keywords are object-local at best), it has **no severity model**, **no localized messages**, **no notion of a value the schema *produces*** (computations), and its cross-field reach (`if/then`, `dependentRequired`) is shallow compared to a12 conditions.

### 3.2 a12 Document Model (from the structural + rule inventories)

- **Structure**: 12 field kinds (`ValueKind`: STRING, NUMBER, DATE/TIME/DATE_TIME/DATE_FRAGMENT/DATE_RANGE, BOOLEAN, CONFIRM, ENUMERATION, CUSTOM), each with type-specific config; nested + repeatable **groups** (repeatability = 1 or a bounded max ≥2); **enumerations** (stored value + per-locale labels + categories); **typeDefinitions** (reusable field types via `TypeDefRef`); **includes/mounts** (reusable sub-models); rich field/model metadata (labels, descriptions, locales, annotations).
- **Rules**: ~109-operator condition language — logical, comparison (ordering/equality/tolerance/pattern), presence (field/group), value-list membership, uniqueness, enum-category, numeric & date functions, aggregates with filtering/iteration, plus a `CustomCondition` host escape hatch. Three-valued truth algebra, type-specific empty handling, per-row iteration.
- **Computations**: a *value-producing function table* (preconditioned alternatives) that assigns a computed field **and** validates any externally-supplied value equals the computed result. **No JSON-Schema analog exists.**
- **Messages**: one localized message per declared model locale, with severity (ERROR/WARNING/INFO) and `$Field$` interpolation.

## 4. Structural mapping — the feasible ~90% (both directions)

This is the part that "just works," with a handful of well-defined choices.

| JSON Schema | → a12 DM | Notes / lossiness |
|---|---|---|
| `object` + `properties` | group | clean |
| `array` of `object` | repeatable group | `maxItems`→repeatability max; `minItems`→a required/`GroupFilled` rule (a12 repeatability has no min slot) |
| `array` of scalar | **strategy choice** (§9): multi-select group, value-list, or 1-field repeatable group | genuine fork — config-driven |
| `string` | STRING (`minLength`/`maxLength`/`pattern` direct) | `pattern` needs an a12 pattern-message per locale — synthesize one |
| `string`+`format:date`/`date-time`/`time` | DATE / DATE_TIME / TIME | clean |
| `string`+`format:email`/`uri`/`uuid`/custom | STRING+pattern **or** CUSTOM | config-driven (§5) |
| `integer` | NUMBER scale 0 | clean |
| `number` | NUMBER; `multipleOf:0.01`→scale 2 | scale inference is config-driven |
| `minimum`/`maximum` | NUMBER min/max **or** a comparison rule | structural config vs rule — a choice |
| `boolean` | BOOLEAN or CONFIRM | three-state vs checkbox — config default + override |
| `enum`(strings) | ENUMERATION | **labels missing** — JSON Schema enums carry values only; derive labels or read `x-enumNames` |
| `const` | single-value enum, or a rule | choice |
| `required` | field requiredness | clean |
| `title`/`description`/`default` | label / description / default | locale: JSON Schema is monolingual → maps to one locale |

The reverse (DM → JSON Schema) is the mirror and is **cleaner**, because we're going from a richer source to a poorer target — every structural feature has a home; we only lose nothing structurally and *project* the rules (§6).

### 4.1 Includes & typedefs — the `$ref`/`$defs` mapping *(your follow-up #1)*

a12 has **two** reuse mechanisms and JSON Schema has **one** (`$ref`→`$defs`). The clean rule is to **split by the *kind* of the referenced schema**:

```
   JSON Schema $ref → a $def that is …
   ┌─────────────────────────────┬──────────────────────────────┐
   │ an OBJECT schema             │ a LEAF/scalar schema          │
   │ (has properties)            │ (string/number + constraints) │
   ├─────────────────────────────┼──────────────────────────────┤
   │ → a12 INCLUDE / MOUNT        │ → a12 typeDefinition          │
   │   (the $def becomes a        │   + TypeDefRef                │
   │    separate sub-model,       │   (a reusable field TYPE,     │
   │    mounted with repeatability)│   e.g. "IBAN string")        │
   └─────────────────────────────┴──────────────────────────────┘
```

| Direction | Object `$def` | Leaf `$def` |
|---|---|---|
| **Import** (JSON Schema→DM) | emit a child model + an `include`; each `$ref` site → a `mount` (`$ref` inside an array → mount with repeatability) | emit a `typeDefinitions` entry; each `$ref` site → a field with `TypeDefRef` |
| **Export** (DM→JSON Schema) | each included/mounted sub-model → a `$def` (object) + `$ref` at each mount | each `typeDefinition` → a `$def` (leaf) + `$ref` at each referencing field |

This is principled and bidirectional. **Constraints to flag:**

- **Recursive `$ref`** (a tree node referencing its own type) has no clean a12 include analog (includes expand; infinite recursion isn't expressible). → **refuse with a corrective diagnostic** rather than silently flatten — consistent with the project's refactoring-safety-gate philosophy.
- a12 mounts carry options foreign schemas can't express (`excludeRules`, `excludeComputations`, `includeLevel SINGLE_RG`/`MODEL_ROOT`). On export these ride `x-a12-mount-*`; on import they default.
- `$ref` shared N times is exactly what typedef/include reuse is for — this is the *happy* path, not a problem.

## 5. The field-type mapping config — yes, it's necessary, and it's the core seam *(your follow-up #2)*

**A static `jsonType → FieldKind` table is provably insufficient.** The target field kind is a function of **(type, `format`, sibling keywords)**, not of `type` alone:

- `string` alone → STRING; `string`+`format:date` → DATE; `string`+`enum` → ENUMERATION; `string`+`format:iban` → CUSTOM(iban). Same `type`, four kinds.
- `number`+`multipleOf:0.01` → NUMBER scale 2; bare `number` → unbounded fractional digits. Scale must be *inferred*.
- `boolean` → BOOLEAN or CONFIRM — a real semantic choice (three-state vs checkbox).
- `enum` → ENUMERATION but **labels** have no JSON Schema source; the label source (derive-from-value / `x-enumNames` / blank) is a policy.

So the mapping config is an **ordered list of matchers**:

```
match (jsonType, format?, keywordPredicate)  →  FieldKindMapping {
    kind,                       // STRING | NUMBER | DATE | ENUMERATION | CUSTOM | …
    configDerivation,           // how to fill minLength/scale/precision/values from the schema node
    reverse                     // DM kind+config → JSON Schema node (for export)
}
```

Shipped as a **default profile** (covers the common Draft 2020-12 shapes) plus **user overrides** (the domain-specific `format`s, the boolean policy, the enum-label policy, the array-of-scalar strategy). This is exactly the *default-strategies-plus-custom-strategies* split you asked for in the original brief, and making each matcher carry its `reverse` is what makes the field layer **bidirectional**.

> **Vendor-extension carriage is the other half of the seam.** a12 facts with no JSON Schema home (severity, localized messages, condition-language, mount options, and — crucially — rules/computations, §6) export as **`x-a12-*`** keywords. They keep the document a *valid* JSON Schema (foreign tools ignore unknown `x-*`) while making a12→JSON Schema→a12 **lossless**. The config decides which `x-a12-*` to emit/consume.

## 6. The crown jewel — rules & computations (where you said you're clueless)

Here is the honest framework. Sort every rule into **three tiers** by whether JSON Schema has a native home for it:

### Tier A — native JSON Schema keyword (lossless both ways, but a *small* subset)

| a12 rule shape | JSON Schema | Caveat |
|---|---|---|
| field requiredness | `required` | clean |
| `PatternMatched(F,"…")` on a string | `pattern` | clean (anchoring differs slightly) |
| value-list / enum membership | `enum` | clean |
| `[F] >= N` / `<= M` against a **literal**, standalone | `minimum`/`maximum` | only if the rule is *exactly* field-op-literal, no other clause |
| `Length(F) >= N` | `minLength`/`maxLength` | clean |
| **conditional requiredness** — `When(tier=="PRO").and(fee.notFilled())` | `if/then`+`required`, or `dependentRequired` | the one genuinely interesting crossover; only for shallow single-gate forms |

The moment a rule is cross-field beyond a single gate, uses ordering between two fields, tolerance bands, aggregates, uniqueness beyond `uniqueItems`, date arithmetic, iteration/`$` correlation, or `CustomCondition` — **it leaves Tier A.** Realistically Tier A captures on the order of **10–20%** of real-world a12 rules.

### Tier B — `x-a12-rule` carriage (lossless a12↔a12, opaque to foreign tools)

For everything past Tier A, **do not translate the logic — carry it.** The kernel already renders a condition to a canonical DSL string and rulekit already parses it back (the renderer round-trips). So export each rule as:

```json
"x-a12-rule": {
  "name": "...", "code": "...", "severity": "ERROR",
  "errorField": "/Subscription/Fee",
  "condition": "FieldFilled(/Subscription/Tier) And FieldNotFilled(/Subscription/Fee)",
  "messages": { "en": "...", "de": "..." }
}
```

On import back into a12, parse `condition` via rulekit. **This is the pragmatic answer to "how do I handle rules":** project the Tier-A subset into native keywords *for foreign readers*, and carry the full rule as `x-a12-rule` *for fidelity*. A foreign validator sees a valid schema and ignores the extension; an a12 round-trip reconstructs the rule exactly.

### Tier C — computations: `x-a12-computation` only

Computations have **no** JSON Schema expression — JSON Schema cannot *produce* a value. Carry them as `x-a12-computation` (rendered DSL alternatives + computed field + preconditions + messages). They survive **a12↔a12 round-trips only**; a *foreign* schema never contains a computation to import. (Subtlety: because a computation also *validates* `external == computed`, importing a foreign schema can never reconstruct one — there's no source — which is fine and expected.)

### The net rule-fidelity picture

```
   a12 → JSON Schema → a12      (round-trip, with x-a12)   : LOSSLESS (structure + rules + computations)
   a12 → JSON Schema → foreign  (interop export)           : structure + Tier-A; rules/computations = ignored x-a12
   foreign → JSON Schema → a12  (interop import)           : structure + Tier-A reconstructed; NO rules beyond Tier-A, NO computations
```

## 7. Bidirectionality, honestly

| Layer | a12 ↔ a12 (via x-a12) | a12 → foreign | foreign → a12 |
|---|---|---|---|
| Structure (fields/groups/enums) | lossless | lossless | lossless |
| Includes / typedefs | lossless | lossless (`$defs`/`$ref`) | lossless |
| Field constraints | lossless | Tier-A native | Tier-A native |
| Rule logic | lossless (carriage) | lost (becomes ignored `x-a12`) | only Tier-A reconstructable |
| Computations | lossless (carriage) | lost | none (no source) |

## 8. The fork you must resolve first: interop vs. round-trip fidelity

Two legitimate goals pull the design in opposite directions:

| | **Goal I — Interop** | **Goal II — Round-trip fidelity** |
|---|---|---|
| Meaning | produce/consume *generic* JSON Schema foreign tools understand | use JSON Schema as an *exchange container for a12 models themselves* |
| Rules | only Tier-A survives; the rest is documentation | everything via `x-a12-*` |
| Honest critique | rules — our crown jewel — mostly *don't cross* | "then why not just use DM-JSON, which already round-trips losslessly?" |
| Highest value | **import**: foreign schema → DM skeleton an author then enriches | niche; DM-JSON already does lossless a12↔a12 |

**Recommendation.** Lead with **Goal I, import-first.** The defensible, high-value capability is: *import a foreign JSON Schema → a clean DM **skeleton** (structure + Tier-A constraints + includes/typedefs), which an author then enriches with the rules/computations JSON Schema could never carry.* Export is the natural mirror and worth shipping, but be explicit that **export-to-foreign is structure + best-effort constraints**, and that full fidelity is a *byproduct* of `x-a12` carriage, not the selling point — because for pure a12↔a12 round-tripping, DM-JSON is already the better container. Treat "JSON Schema as a12's serialization format" as a **non-goal** (§13).

## 9. Mapping strategy architecture

A single `MappingProfile` value object, composed of pluggable, bidirectional strategies — default profile shipped, user overrides layered on:

```
MappingProfile
 ├─ fieldTypeMatchers : ordered (jsonType,format?,keywordPred) → FieldKindMapping{toDm, toSchema}   (§5)
 ├─ refStrategy       : object-$def → include/mount ; leaf-$def → typedef ; recursion → refuse       (§4.1)
 ├─ arrayStrategy     : array-of-scalar → {multiSelect | valueList | repeatableSingleField}
 ├─ enumLabelStrategy : deriveFromValue | xEnumNames | blank
 ├─ booleanStrategy   : BOOLEAN | CONFIRM
 ├─ ruleProjection    : which Tier-A keywords to emit on export; whether to also carry x-a12-rule     (§6)
 └─ localeStrategy    : which model locale ↔ the schema's monolingual title/description
```

Each strategy is a **lens** (a `toDm`/`toSchema` pair) wherever the mapping is invertible, so bidirectionality is a property of the strategy, not bolted on afterward. "Custom strategies" = the user supplies/overrides any of these.

### 9.1 The transcoding report — make the lossiness auditable, not silent

Because this mapping is **inherently lossy and choice-driven** (Tier-A vs carried vs refused per §6; object-vs-leaf `$ref` per §4.1; which field-type matcher fired per §5; locale collapse; scale inference), a transcode that just emits the artifact and stays quiet would hide exactly the information the user needs to trust it. So **every import and export emits a structured report alongside the artifact** — the same way the project refuses silent truncation elsewhere. This is the single best ergonomic affordance for a feature whose defining property is partial fidelity.

The report is produced in **two renderings from one model**: **JSON** (machine-readable — for CI gates, diffing, programmatic checks) and **Markdown** (human-readable — for an author reviewing what an import gave them, or what an export dropped). Same data, two views.

What it records:

| Section | Contents |
|---|---|
| **Summary** | counts per disposition: fields/groups mapped, rules projected to Tier-A, rules carried as `x-a12`, computations carried, elements refused/dropped; an overall **fidelity verdict** (lossless round-trip / lossy-to-foreign / refused) |
| **Per-element disposition** | one row per field/group/rule/computation/`$ref`: source location → target, which **strategy/matcher fired**, and the **tier** (native keyword / `x-a12` carriage / dropped) |
| **Diagnostics** | every non-obvious decision and refusal: recursive `$ref` refused, enum labels derived-from-value (no source), locale collapsed to one, number scale *inferred* from `multipleOf`, boolean→CONFIRM vs BOOLEAN choice taken, array-of-scalar strategy applied, a rule that *could not* reach Tier-A and was carried |
| **What a foreign reader loses** (export only) | the explicit list of `x-a12-*` a generic JSON-Schema tool will ignore — so "export to an external consumer" never *looks* lossless when it isn't |

Design notes: the report is **first-class output, not logging** — returned as a value (and writable to a file), so it's testable; the JSON form is the **CI contract** (a round-trip spike asserts against it); and on **import**, refusals (recursive `$ref`) appear here *and* block, consistent with the gate-and-refuse discipline (§4.1). It also doubles as the spike's own evidence (§11): a report that accurately states every disposition *is* the proof the framework works.

## 10. Subproject shape (mirroring the interpreter spike — but simpler)

| Aspect | `:interpreter` | proposed `:schemakit` (working name) |
|---|---|---|
| Nature | runtime *evaluator* | design-time *transcoder* |
| Multiplatform? | **yes** (KMP + Node — JS evaluation) | **no** — JVM-only build/design-time tool. **This is a big simplification: no KMP, no Node, no `expect/actual`.** |
| Kernel dep | kernel-free (purity lint) | depends on **`:` core** (modelkit `DocumentModelDraft` to build models; rulekit to parse/render rule DSL). Kernel only in **test scope** via `:adapter` |
| Verification oracle | `RuntimeLaws` differential vs kernel | **round-trip + kernel-accept**: import schema → DM → `RuleValidator`/`checkConsistency` accepts → export → re-import → assert structural equality; and DM fixture → export → validate against a JSON-Schema meta-schema → re-import → assert equality |
| Extraction trigger | before shipping / native-eval wiring | same discipline: spike in-repo under a `.spike` package; extract to `a12-dm-json-schema` before promotion |
| CLI surface | runtime verbs | a verb family — **naming caveat:** `dmtool schema` is already taken (publishes verb I/O schemas). Use e.g. `dmtool model import-jsonschema` / `export-jsonschema`, **not** `dmtool schema` |
| Native image | native-safe, in eval | design-time; keep **out** of the native binary/eval until promoted |

Because it's JVM-only and leans on existing modelkit/rulekit, **the engineering cost is materially lower than the interpreter spike** — no multiplatform toolchain, and the hard rule machinery (parse/render DSL) already exists and is reused, not rebuilt.

## 11. The spike (de-risking first step)

Prove the asymmetry-handling end-to-end on **four shapes**, import **and** export, validated by the kernel oracle:

1. **Flat object** — `object` with string/integer/boolean/enum properties + `required` → DM with fields + requiredness; export back; round-trip equal.
2. **Nested + array-of-object** — exercises groups + repeatable groups + `minItems`/`maxItems`.
3. **`$defs`/`$ref` both kinds** — one object `$def` (→ include/mount) and one leaf `$def` (→ typedef); proves §4.1 both ways; and a recursive `$ref` that the importer **refuses** with a diagnostic.
4. **Rule carriage** — an a12 fixture with (a) a Tier-A conditional-requiredness rule and (b) a Tier-B cross-field/aggregate rule and (c) a computation → export → confirm (a) appears as native `if/then`+`required`, (b)/(c) appear as `x-a12-*`, the schema validates against the JSON-Schema meta-schema, and re-import reconstructs all three exactly.

Success = all four round-trip, the kernel accepts every imported model, exports are valid JSON Schema, **and the transcoding report (§9.1) accurately states every disposition** — the report is asserted against, so it doubles as the spike's evidence. That single suite settles feasibility.

## 12. Open decisions (the narrow, measurable ones)

- **Target draft**: Draft 2020-12 canonical + Draft-07 read-compat? (recommend yes — covers OpenAPI 3.1 and the deployed base.)
- **Module name**: `:schemakit`? `:json-schema`? (avoid collision with the existing `dmtool schema` verb.)
- **Where the round-trip/differential tests live** — `:adapter` (reuse the kernel oracle, like the interpreter's option C) vs a self-contained `:schemakit:test` with a test-scope kernel dep.
- **Array-of-scalar default strategy** — multi-select group vs value-list.
- **How aggressively to project Tier-A** on export (more native keywords = friendlier to foreign tools but more ways to be subtly lossy).

## 13. Non-goals

- **JSON Schema as a12's serialization format.** DM-JSON already round-trips losslessly; don't reinvent it. (Re-open only if an external system *mandates* JSON Schema as the wire format.)
- **Translating rule *logic* into JSON Schema.** Past Tier-A we *carry* (x-a12), we don't translate. No attempt to encode aggregates/iteration/date-arithmetic/three-valued logic in `allOf`/`if`-trees.
- **Importing computations from foreign schemas** (no source exists).
- **Multiplatform.** This is a JVM design-time tool.
- **`$ref` recursion / arbitrary `allOf`/`anyOf`/`oneOf` composition** in v1 — refuse with diagnostics; revisit if a real corpus needs it.

## 14. Licensing

The JSON Schema specs and meta-schemas are openly licensed (permissive); transcoding against them raises no copyleft concern. The a12 side uses our own modelkit/rulekit. As with the interpreter spike, the rule to hold: if the implementation ever reads EUPL-licensed kernel source (e.g. grammar) rather than calling published APIs, settle provenance before extraction. Default expectation: clean.

## 15. Recommendation

**Feasible and worth a spike — scoped as import-first, structure-and-constraints, with `x-a12` carriage for fidelity.** The structural layer (including the `$ref`→include/typedef split, §4.1) and the field-type mapping config (§5) are both tractable and are the real engineering. The rules question resolves not by translation but by the **three-tier project-or-carry** framework (§6): a small native subset, everything else carried losslessly in `x-a12-*`. The honest caveat — and the reason to lead with *import* and treat foreign *export* as best-effort — is that the crown-jewel rules mostly **do not cross** into generic JSON Schema, and for a12↔a12 fidelity DM-JSON is already the better container. Build the §11 four-shape spike before committing to a full module.
