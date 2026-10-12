# schemakit — JSON Schema ⇄ Document Model (spec)

The buildable contract for `:schemakit`, the JSON-Schema import/export subproject. **The rationale — the asymmetry finding, the interop-vs-fidelity fork, the three-tier rule story, the `$ref`→include/typedef split, the missed-potential list — lives in [`JSON-SCHEMA-PROPOSAL.md`](archived/JSON-SCHEMA-PROPOSAL.md); this doc does not repeat it.** The spec holds the *contract*: the dependency boundary, the module shape, the verb surface, the verification gates, and the increment plan.

**Status:** the transcoder is built as a stable in-repo library (package `io.github.mbackschat.a12.dm.schemakit`). The import direction maps or loudly surfaces every JSON Schema feature (§4d); export is best-effort. What is built is shown by git + the `:schemakit` test suite, not a status board here.

---

## 1. The dependency boundary: reach the kernel only through the project's own modules

**`:schemakit` must not link the *raw* A12 kernel jars directly, and its own source must not import kernel types (`com.mgmtp.*`)** (owner constraint, 2026-06-28; refined 2026-06-28 — `:adapter` *is* permitted). It reaches the kernel only through the project's own, **mediated** modules — never past them to the raw internal kernel tier. This is the project's standing kernel-coupling discipline (the D15/N8 boundary, [ARCHITECTURE.md](ARCHITECTURE.md) §1/§4).

```
   :schemakit ──▶ root ` : `   ──api──▶ kernel-md-facade        (PUBLIC: expand/check/serialize)
        │          (dm.core · modelkit · rulekit)
        ├────────▶ :adapter     ──▶ kernel internal-read tier   (MEDIATED: converts to dmkits types
        │          (KernelAdapter.read → ReadResult/Condition)   at the N8 boundary — no raw kernel leaks)
        └────────▶ :interpreter                                  (kernel-FREE; native-safe; KMP jvm/js)

   ✗ schemakit never declares a DIRECT dep on the raw kernel jars, nor imports com.mgmtp.* in its own source.
```

| schemakit may depend on | … and use |
|---|---|
| root `:` | modelkit (`DocumentModelDraft`/`ModelDocument`), rulekit (`Dsl`/`DslRenderer`/`RuleValidator`), `dm.core` (`ModelLoader` — the kernel-accept gate); the kernel only via `kernel-md-facade`, transitively |
| **`:adapter`** | the project's **mediated** internal-read boundary (N8): it reads the kernel's internal APIs but **converts every kernel type to a dmkits type at the boundary** — schemakit sees `ReadResult`/`Condition`/records, never a raw `ICondition`. The clean path for reading a stored rule typed-ly (Tier-A projection) |
| `:interpreter` | kernel-free, native-safe — evaluation (the two-tier coupling, §6 shape 5) + the clean-room DSL parser |

| schemakit must NOT |
|---|
| declare a **direct** dependency on the raw internal kernel jars (`kernel-md-model`, `kernel-md-a12internal`, `parsetree-api`, `tool-api`) |
| import `com.mgmtp.*` / `groovy.*` in its **own source** — it uses dmkits types only ("data out, never kernel objects") |

**Why** — the kernel is reached through controlled, **type-converting** surfaces so no raw kernel object ever leaks into schemakit, keeping its out-surface plain data and the module portable. *Native-safety note:* `:adapter` is JVM (bundled inside the native binary like the CLI; its Groovy/runtime-eval is JVM-only / refused in native), so a future kernel-free native/JS build leans on root `:` + `:interpreter` and avoids `:adapter`; `:adapter` is used where the typed kernel read is the cleaner path.

**How it's enforced** — the two rows above are two different claims and take two different instruments, which is what DG45 was opened over (closed 2026-09-05).

| the claim | the guard | why that instrument |
|---|---|---|
| no `com.mgmtp.*` / `groovy.*` in schemakit's **own code** | `verifySchemakitPurity` (`:schemakit:check`) — `jdeps` over the compiled main classes, the shared `verifyCompiledPurity` the kit and bridge gates use | a compiled edge is the coupling. Its `import`-regex predecessor read only `.java` files and only lines starting with `import`, so a fully-qualified use or a name in a text block was invisible to it |
| no **direct dependency** on the raw kernel jars | `verifyPublishedClasspathsAreKernelFree` (root `check`) — resolves `compileClasspath` + `runtimeClasspath`, the configurations `from components.java` builds the POM from, and refuses any `com.mgmtp.*` module | a dependency reaches a POM by being **declared**, not by being imported. A kernel jar that is present and simply unused is invisible to every source and linkage guard, and lands on the consumer's classpath anyway — the one thing a consumer actually feels |

Both run in CI by name (`ci.yml`), because both hang off a `check` the workflow does not invoke; `GateTierWiringTest.pushCiNamesEveryCheckOnlyGuardHoldingTheKernelFreeClaim` fails if either name is dropped. The resolution guard covers all six published artifacts, not schemakit alone — the claim, the POM mechanism, and the blind spot are identical across the kits and the bridge.

---

## 2. Module & package shape

| Aspect | Decision |
|---|---|
| Gradle unit | `:schemakit` — a JVM/Java module (`java-library`, JDK 21). The name is settled (consistent with rulekit/modelkit); reconsider only if it confuses with the `schema` verb |
| Multiplatform? | **No.** JVM/Java only; native-safe via GraalVM ≠ Kotlin Multiplatform (proposal §10). No KMP, no JS. |
| Target draft | Draft 2020-12 canonical + Draft-07 read-compatibility (proposal §12/§13) |
| Depends on | `implementation project(':')` (the engine) · Jackson (JSON Schema parse/emit) · — and, as shapes need them — `:adapter` (typed rule read for Tier-A projection) + `:interpreter` (evaluation, two-tier) |
| Package | `io.github.mbackschat.a12.dm.schemakit` — public API `JsonSchemaImporter` · `JsonSchemaExporter` · `MappingProfile` · `TranscodeReport`; the synthesis/carriage/projection helpers are package-private internals |
| Extraction | in-repo; extraction to a sibling `a12-dm-json-schema` repo stays a future option (the interpreter discipline), not a prerequisite |

The transcoder operates through dmkits' typed surface (the dogfood rule): **import** builds via modelkit `DocumentModelDraft` and synthesizes rules via the rulekit `Dsl` builder + `DslRenderer`; **export** reads via modelkit `ModelDocument` (kernel-free) and the rulekit AST. The structural validity oracle is the kernel itself, reached only through `ModelLoader` (facade).

The `a12-kernel-mm` metamodels (`MM_Field_1`/`MM_Rule_1`/`MM_Group_1`/`MM_Computation_1`/`CT_MODEL_HEADER_1`, the source-of-truth for valid DM structure) are a **read-only structural oracle** for the mapping — consult them when a config key or slot is in question; never a dependency.

**Reuse-first — do not rebuild what already exists (owner directive, 2026-06-28).** Before writing any capability in schemakit, check whether it is already available in the project (root `:` — modelkit/rulekit/`dm.core`; `:adapter` — typed kernel read/`whereUsed`/diff/rewrite; `:interpreter` — parse/evaluate) and **reuse it**. The only genuinely new code schemakit should carry is the irreducibly JSON-Schema-specific glue (the mapping/profile, the `$ref` resolver, the report). When a needed capability seems to be missing — or duplicates something that exists — **surface it to the owner and let them decide case by case**; do not silently reimplement. This is the dogfood mantra (CLAUDE.md) applied with an explicit stop-and-ask.

---

## 3. CLI surface (in `dmtool`, native-safe)

schemakit is **runtime-callable and wired into `dmtool` from day one** (proposal §10) — not a design-time tool. Because its deps (modelkit/rulekit/Jackson + later the kernel-free interpreter) are native-safe and already in the native binary, the verbs run in the native image.

| Verb | I/O |
|---|---|
| `dmtool model import-jsonschema --schema <schema.json> [--dialect <D>] [--component <name> \| --out-dir <dir>] [profile flags] [-o <model.dm.json>]` | JSON Schema / OpenAPI → DM-JSON artifact (stdout); `--out-dir` imports the whole OpenAPI bundle → one model per component; the transcoding report → stderr; `-o` writes the model + a write envelope |
| `dmtool model export-jsonschema -m <model.dm.json> [--dialect <D>] [--wrap-openapi] [-o <schema.json>]` | DM-JSON → JSON Schema / OpenAPI artifact (stdout); report → stderr; `-o` writes the schema + a write envelope |

Both keep **artifact mode**: the transcode result (DM-JSON / JSON Schema) goes to stdout so it pipes straight into the next tool, and the **transcoding report** — the omission to-dos — rides stderr (loud, never silent); with `-o` the artifact goes to the file and stdout carries a write-confirmation envelope. They are classified `SchemaCommand.CONTRACT_EXEMPT` for the directional shape (no result-envelope contract on the artifact form, like `model new`/`model seed`), while `schema model import-jsonschema` / `schema model export-jsonschema` still serve their operational profiles. **Import** exposes the `MappingProfile` choices as flags — `--strict` (the conservative master switch) plus the per-decision `--number-scale` / `--default-scale` / `--array-of-scalar` / `--map` / `--union` / `--string-format` / `--boolean` / `--enum-labels` / `--recursion-depth` / `--default-repeat-cap` (§4b). **Both verbs** take `--dialect` (JSON Schema vs OpenAPI 3.0/3.1·3.2, §4g); import adds `--component` (the OpenAPI bundle entry point), export adds `--wrap-openapi` (the OpenAPI `components/schemas` envelope).

**The report is the agent's to-do list, so make it machine-readable, not only prose.** The omissions an import leaves are exactly what an agent must complete by hand — its next-step list, not a side note. So beyond the human-readable stderr lines, the `-o` write-envelope carries the **structured report** in `changed.report` (`{mapped, omitted, notes[]}`): the agent's natural path — `-o` writes the model to a file *and* returns the parseable audit trail on stdout — gives it everything in the machine-readable channel it already trusts. (stdout stays the clean artifact in the no-`-o` pipe case; the report cannot also go there without corrupting the artifact, so the structured form rides the `-o` envelope.)

**Naming caveat (proposal §10):** `dmtool schema` is taken (it publishes verb I/O contracts). The JSON-Schema verbs are `import-jsonschema` / `export-jsonschema`, **never** `schema`. Adding the verbs triggered the usual CLI-CONFORMANCE gates (`ManifestVerbTest.EXPECTED_VERBS`, the conformance matrix, `verb-examples.json` + a `<schema.json>` demonstration placeholder) — see [CLI-CONFORMANCE-SPEC.md](CLI-CONFORMANCE-SPEC.md); a native re-capture is needed only before a native build (NATIVE-IMAGE-GRAALVM §5.1).

---

## 4. The mapping contract

**Direction matters (owner decision, 2026-06-28).** **Import → DM aims for TOTAL coverage:** every JSON Schema feature lands in a12 **structure or rules** — directly, via a *composed* rule, or via a *customizable strategy* (§4b). Anything a12 genuinely cannot represent is **omitted, never refused** (§4a). **Export → JSON Schema stays best-effort:** native Tier-A keywords + `x-a12-*` carriage (the asymmetry the proposal describes is real only on export). Every transcode emits a **transcoding report** (proposal §9.1) so the mapping is auditable, never silent.

### Import targets — structure + rules

| Layer | Mapping |
|---|---|
| **Structure** | `object`→group, `array`-of-`object`→repeatable group, scalar→field-kind (the field-type matcher, §4b), `$defs`/`$ref`→typedef (leaf) / inlined group (object; reuse via a separate include child is the deferred multi-model increment, §4e) |
| **Field config** | `minLength`/`maxLength`/`pattern`→string config; `minimum`/`maximum`→number bounds |
| **Rules (direct)** | `required`→requiredness; `enum`→ENUMERATION; `uniqueItems`→`RepetitionNotUnique`/`FieldValuesNotUnique`; `pattern`→`PatternViolated` |
| **Rules (composed), built** | constraints with no field-config home become a12 *rules* — `exclusiveMinimum`/`exclusiveMaximum`, `multipleOf`, `const`, `dependentRequired`, `oneOf`/`anyOf` discriminator, `uniqueItems`, `if {f=v [and …]} then/else {required}` (incl. **multi-property** `if`), **then/else value-constraints** (`g` must satisfy `const`/`enum`/`min`/`max`/`pattern` when the `if` holds, and on the `else` — single- or multi-property), `contains`/`minContains`/`maxContains` (a simple predicate — incl. clauses on a **nested-object** field, e.g. `payment.method` → a filtered-count rule), **`minItems`** (the group-cardinality lower bound → `NumberOfFilledGroups(G*) < N`), `not {const\|enum\|pattern\|minimum\|maximum\|minLength\|maxLength}`, object-level `not {required}`. **Each keyword's exact fire condition is tabulated in §4f.** |
| **Rules — declined** (reported, not enforced) | the *feasible-but-not-built* gaps — **nested/compound** `not`, nested-array `contains` (correlated nested aggregation), and a closed row-local subset of `contains` combinators — are tracked separately in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §3. “Arbitrary combinator subschema” is not promised because some branches have no row-condition form. *Not-feasible* constraints (`not {type}`, §4c) are permanently omitted — gaps §4. (`minItems` left that class 2026-07-09 and is now **synthesized** — §4f.) |
| **Carriage (export, a12↔a12)** | the full rule rides `x-a12-rule`, a computation `x-a12-computation`; `readOnly:true` marks a computed field |
| **Two-tier validation** *(proposal §12.1)* | exported Tier-A keywords feed any stock validator; rules outside that tier ride `x-a12-rule` for a full-rule consumer. Shape 5 proves the stock tier plus carriage; live in-process `:interpreter` execution is deferred in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §3. |

### 4a. Omit, never block (import)

Import **never refuses or aborts.** A feature a12 can't represent is **OMITTED** (`TranscodeReport.Tier.OMITTED`): the element is skipped, the rest imports, and the report carries a **corrective TO-DO** (where + how to add it by hand). The result is always a usable, kernel-valid DM **skeleton** the author completes — the proposal's "import → skeleton an author enriches," made a hard rule. (This **supersedes** the proposal's gate-and-refuse framing *for import*; refuse-and-block would leave the user unable to proceed.)

### 4b. Customizable import decisions — the `MappingProfile`

The choice-driven decisions are pluggable strategies on a `MappingProfile` (CLI flags build one). **The default is best-effort (decided 2026-06-28): import as much as possible and flag every guess in the report — never silently, never blocked.** This matters most for *structural containers*: omitting an array/map would prune its whole nested subtree, so the default reaches in and recurses. **`--strict` (`MappingProfile.strict()`) flips the whole stance** to "omit everything uncertain" for the interop purist; per-decision flags override either base.

| Decision (flag) | Best-effort default | Alternatives | `--strict` |
|---|---|---|---|
| number scale, no `multipleOf` (`--number-scale`) | **`DEFAULT_SCALE`** (a decimal at `--default-scale`, default 2; the scale is a flagged guess) | `INTEGER` (drops decimals) / `OMIT` | `OMIT` |
| array of scalar (`--array-of-scalar`) | **`REPEATABLE_SINGLE`** (a repeatable single-field group) | `MULTI_SELECT` / `VALUE_LIST` / `OMIT` | `OMIT` |
| array/map, no `maxItems`/`maxProperties` (`--default-repeat-cap`) | **cap at 1000 rows** (a chosen bound, flagged) — so the container + its items still import | any `N` / `0` = omit | `0` (omit) |
| `oneOf`/`anyOf` same-kind object (`--union`) | **`SUPERSET_RULE`** (union of fields + discriminator rules) | `FIRST_VARIANT` / `OMIT` | `OMIT` |
| `additionalProperties`/`patternProperties` map (`--map`) | **`REPEATABLE_KEY_VALUE`** (a `{key, value}` group) | `IGNORE` / `OMIT` | `OMIT` |
| `format` (`--string-format`) | **`PATTERN`** (standard regex where known; temporal → native DATE/TIME kinds) | `CUSTOM_TYPE` / `IGNORE` | `IGNORE` |
| boolean (`--boolean`) | `BOOLEAN` | `CONFIRM` | `BOOLEAN` |
| enum labels (`--enum-labels`) | `DERIVE` (label = value) | `X_ENUM_NAMES` / `BLANK` | `DERIVE` |
| recursive `$ref` (`--recursion-depth`) | **expand 1 level** | any depth `N` / `0` = omit | `0` (omit) |

Every flag (and each value's *implication* — e.g. `INTEGER` drops decimals, `--default-repeat-cap 0` omits) is surfaced in `dmtool manifest` / `--help`, so a cold agent discovers not just the names but what each does.

### 4c. The residual omissions (no faithful static a12 form — omitted, never block)

Five cases have no static a12 representation even with a strategy; each is **omitted with a corrective TO-DO** (not refused — examples in the session log + below):

1. **cross-kind type union** — `type:["string","object"]`, `oneOf` across *different kinds* (a field has one kind). *(nullable `type:[X,"null"]` → optional X, and same-kind object unions → §4b superset — both DO map.)*
2. **`unevaluatedProperties`/`unevaluatedItems` with applicators** — dynamic, annotation-dependent closure. *(a plain closed object maps for free.)*
3. **`$dynamicRef`/`$dynamicAnchor`** — runtime-polymorphic references.
4. **unbounded recursive `$ref`** — expanded to `--recursion-depth` levels (default 1), then the deeper recursion is omitted with a to-do; `--strict` / `0` omits it entirely (§4b). (Self-/cyclic *includes* are likewise rejected by the kernel — §4e.)
5. **`not {type}`** — an a12 field has one fixed kind (structural), and there is no runtime type-check operator to negate, so "the value must not be of type X" has no rule form. *(The other `not` shapes — `const`/`enum`/`pattern`/`min`/`max`/`minLength`/`maxLength` — DO map via paired negated operators, §4f.)*

The full impossibility view is consolidated in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §4. (`minItems` left it 2026-07-09 and is now synthesized — §4f.)

---

## 4d. Coverage at a glance

The asymmetry in one place. **Import aims for total coverage** — every JSON Schema feature lands in a12 *or* is surfaced with a corrective to-do (never silent, never blocked). **Export is best-effort** — native Tier-A keywords where they exist, `x-a12-*` carriage otherwise. Read these two tables to see, at a glance, what each direction does and where it loses fidelity; the **exact fire condition** of every synthesized `rule (Tier A+)` row is tabulated in §4f.

### Import — JSON Schema → Document Model

| JSON Schema | a12 import | Mapping |
|---|---|---|
| `object` + `properties`; nested `object` | group; nested group | structure |
| `allOf` of objects | one merged group (props unioned, `required` unioned, `$ref` bases resolved) | structure |
| `array` of `object` (+ `maxItems` ≥ 2) | repeatable group | structure |
| `array` of `$ref` items (`items:{$ref}`) | repeatable mount (bundle) / repeatable inlined group (single) | structure |
| `$ref`/`$defs` → leaf / → object | typeDefinition + ref / inlined group | structure |
| `string` · `integer` · `boolean` · `enum` | STRING · NUMBER(scale 0) · BOOLEAN\|CONFIRM · ENUMERATION | structure (boolean: profile) |
| `type:[X,"null"]` (nullable) | optional `X` | structure |
| `format: date` / `date-time` / `time` | DATE / DATE_TIME / TIME field | structure |
| `required` | field requiredness | structure |
| `minLength` / `maxLength` / `pattern` | string field config; empty pattern remains inactive without a message ([KF543](KERNEL-FINDINGS.md#kf543)) | field-config |
| `minimum` / `maximum` | number value bounds | field-config |
| `format: email` / `uuid` | STRING + standard pattern | field-config (profile: format) |
| `exclusiveMinimum` / `exclusiveMaximum` | strict `<` / `>` rule | rule (Tier A+) |
| `multipleOf` · `const` · `dependentRequired` | modulo rule · `!=` rule · conditional-requiredness rule | rule (Tier A+) |
| `uniqueItems` | `RepetitionNotUnique` rule keyed on the repeatable group's row fields | rule (Tier A+) |
| `if {field = value [, …]}` + `then` / `else {required}` | conditional-requiredness rule per dependent (multi-property `if` → the conjunction; `else` on the negated condition) | rule (Tier A+) |
| `if {f = v}` + `then`/`else {properties:{g:{const/enum/min/max/pattern}}}` | conditional value-constraint rule on `g` (single-property `else` too) | rule (Tier A+) |
| `not {const}` · `{enum}` · `{pattern}` · `{min/max}` · `{minLength/maxLength}` · object `{required}` | `!=` · not-in-list · must-not-match · inverted numeric bound · inverted length bound · not-all-present | rule (Tier A+) |
| `oneOf`/`anyOf` of same-kind objects | superset group + discriminator → enum + per-variant requiredness rules | strategy + rule (profile: union) |
| `array` of scalar | repeatable single-field group (default) / multi-select / value-list | strategy (profile; **imported by default**) |
| `array` / map with no `maxItems` / `maxProperties` | repeatable group capped at `--default-repeat-cap` (default 1000) | strategy (profile; **imported by default**, capped) |
| `additionalProperties` / `patternProperties` (map) | repeatable `{key, value}` group | strategy (profile: map) |
| bare `number` (no `multipleOf`) | NUMBER at the default scale (a flagged guess) | strategy (profile; **imported by default**) |
| `format: <other>` | pattern / custom field type / plain string | strategy (profile: format) |
| recursive `$ref` | expand `--recursion-depth` levels (default 1) | strategy (profile; **imported by default**, 1 level) |
| `minItems: N` | rule `NumberOfFilledGroups(G*) < N` for a required root array; other applicability scopes are explicitly omitted (§4f) | `minItems` (projected back, Tier A) |
| cross-kind `type:[X,Y]` · mixed `oneOf`/`anyOf` | reported | omitted (§4c) |
| `unevaluatedProperties` / `unevaluatedItems` | structure imports; constraint reported — **not enforced** | omitted (§4c) |
| `$dynamicRef` / `$dynamicAnchor` | reported | omitted (§4c) |
| `contains`/`minContains`/`maxContains` | a matching-row count for supported scalar or object-property predicates; unsupported absent/empty distinctions are explicitly omitted (§4f) | rule (Tier A+) / omitted |
| nested/compound `not`; `contains` descending into a nested **array** | structure imports; keyword reported — **feasible, but declined** ([`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §3) | omitted (declined gap) |
| row-local `contains` combinator over otherwise representable clauses | structure imports; the whole `contains` is reported — a closed all-or-nothing translator is feasible, but arbitrary subschemas are not promised ([`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §3) | omitted (declined gap) |
| `not {type}` | structure imports; reported — **not feasible** (a12 field kind is fixed; no type-check operator to negate) | omitted (ledger §4) |

### Export — Document Model → JSON Schema

| a12 construct | JSON Schema export | Fidelity |
|---|---|---|
| group; repeatable group | `object`; `array` + `maxItems` | exact |
| group `indexFieldName` | scalar `x-a12-index-field-name` on the group object, including an array’s `items` object | carriage |
| STRING (+ `minLength`/`maxLength`/`pattern`) | `string` (+ constraints) | exact |
| NUMBER scale 0 / scale > 0 (+ `min`/`max`) | `integer` / `number` (+ `minimum`/`maximum`) | exact |
| BOOLEAN · ENUMERATION | `boolean` · `enum` | exact |
| typeDefinition | `$defs` entry + `$ref` | exact |
| Rule recognized as a field `pattern` (Tier-A) | native `pattern` keyword for a nonempty authored regex; empty regex stays carriage-only ([KF543](KERNEL-FINDINGS.md#kf543)) | projected |
| any other Rule | `x-a12-rule` (a12↔a12 round-trip) | carriage |
| Computation | `x-a12-computation` (a12↔a12 round-trip) | carriage |
| DATE / DATE_TIME / TIME / DATE_RANGE / DATE_FRAGMENT | `string` placeholder + diagnostic | **lossy** (no `format` emitted yet) |
| CONFIRM · CUSTOM | `string` placeholder + diagnostic | **lossy** |

A group's `x-a12-index-field-name` carries A12's unchanged relative `Group.indexFieldName` string, including a spelling such as `../Sku`; it is not a stock uniqueness constraint. Import restores the scalar before rule synthesis, records a `CARRIED` disposition, and leaves semantic admission to the existing consistency gate. Annotation absence leaves the index unset; a nontextual annotation is omitted with a corrective and an `OMITTED` disposition while the structural group survives. Export emits the annotation only for an indexed group, records `CARRIED`, and uses the same group-object placement in bare JSON Schema and wrapped OpenAPI. Enforcement is `JsonSchemaExporter.exportGroup`, `JsonSchemaImporter.populate`/`buildStructure` and `Carriage.restoreIndexFields`; `TierAProjectionShapeTest` locks placement, exact relative spelling, absence/nontextual controls, carried-rule identity, normalized re-export, kernel/portable admission and the wrapped `OPENAPI_31` component. This guarantee uses the existing `/Root` carriage convention and does not add root rebasing or `allOf` conflict reconciliation.

The **mismatch in one line:** import turns rules/strategies/temporal-formats into native a12 constructs; export only round-trips the Tier-A subset natively and carries the rest as `x-a12-*` (or, for temporal/custom *fields*, falls back to `string`). That export-side temporal/custom loss is the main asymmetry — the inverse of the import `format`→kind mapping (§4b).

---

## 4e. Model multiplicity — one schema, one or many models

A single JSON Schema imports to **exactly one** document model today. Multiplicity is not inherent in "a schema" — it appears only when a *reuse* construct (`$defs`, an external `$ref`, or a bundle of schemas) maps onto one of a12's two **reuse vehicles**, and one of those vehicles is itself a separate model. Emitting several models is the deferred multi-model increment (proposal §4.1); this section fixes the contract for when it lands, and states what the importer does in the meantime.

### a12's reuse vehicles

| Vehicle | What it is | Separate model? |
|---|---|---|
| **typeDefinition** (local) | a named reusable *type* — a `Money` decimal, an `Iban` string+pattern, an enum — declared in `content.typeDefinitions[]`, referenced by fields (`FieldTypeSpec.TypeDefRef`) | **No** — lives in the model |
| **type-def-only model** | a `modelType:document` with an empty root carrying *only* `typeDefinitions`, **imported** by others (`DocumentModelDraft.typeDefModel()` / `Builder.importTypeDefs` → header `typeDefImports`) | **Yes** |
| **include / mount** | a group with `modelAlias` → another model's id (header `includes[]` = alias→reference); on expand the kernel splices the child in (`IncludeLevel.SINGLE_RG` = the child becomes one group, `MODEL_ROOT` = its root fields land directly) | **Yes** |

### Which reuse construct fans out into how many models

**Single-import** (`--component`, or a bare schema) collapses all reuse into **one** model (inline); **`--out-dir`** imports the whole OpenAPI bundle as **N** models wired by mounts. The table shows both; the *how* is the behavior table below.

| JSON-Schema construct | a12 vehicle | Single-import | Bundle (`--out-dir`) |
|---|---|---|---|
| leaf `$def` (string/integer) | typeDefinition (local) | 1 | a local typedef per model |
| object `$def` / cross-component ref | include/mount | 1 (inlined) | **mounted** — one model per component |
| recursive `$def` (self-reference) | expand-then-omit | 1 | 1 (expanded, **not** self-mounted) |
| whole OpenAPI document | one model per `components/schemas` entry | 1 (the chosen entry) | **N** + include wiring |

### The clean way to say it

- **typeDefinition** = reuse a *type* → stays in **one** model (promote to a shared **type-def-only model** only when the type is shared *across* schemas).
- **include/mount** = reuse a *structure* → a **separate child model** the host mounts by `modelAlias`; **`--out-dir`** emits these for object cross-references.
- An import yields **multiple** models with **`--out-dir`** over an OpenAPI bundle. **Recursion does *not*** yield a self-mount — the kernel forbids a model including itself, so a self-recursive component is *expanded* (to `--recursion-depth`) then omitted.

### Why a mount beats inlining (and where it can't reach)

- **Reuse without duplication.** An object `$def` referenced in N places inlines the structure N times; a mount references it **once**. Edits — and the included model's own **rules/computations** — stay single-sourced (`MountOptions.excludeRules`/`excludeComputations` opt out per mount).
- **A mounting component still carries its OWN rules.** A bundle component that mounts a sibling *and* declares a rule-synthesizing constraint (an `exclusiveMinimum`, a `const`, an `if/then`) gets that rule synthesized normally — the rule is authored against the component loaded with a resolver over the bundle, so the mount resolves while the host's own typed rule is built. (The host's rules reference only its own fields; a mounted sibling's constraints are synthesized in *that* sibling's model.)
- **Cross-document sharing.** A library of structures shared across many schemas is the common child / type-def-only model — the DRY unit a12 already provides, rather than copying the definition into every model.
- **Recursion is the limit, not a win.** A self-referential schema has *no finite inline form* **and** *no self-include form* — the kernel rejects "a model includes itself." So recursion is bounded by `--recursion-depth` (expand) then reported; neither inlining nor mounting fully represents it.

### What the importer does today

| construct | single-import | `--out-dir` bundle |
|---|---|---|
| leaf `$def` (string/integer) | local `typeDefinition` + ref (`declareLeafTypedefs`) | same, per model |
| object `$def` / cross-component ref | **inlined** as a nested group (`inlineObjectDef`) | **mounted** as an include (`mount` + header `include`) |
| recursive (self) `$ref` | expanded to `--recursion-depth`, then omitted with a to-do | same (the kernel forbids a self-include) |
| whole OpenAPI document | the `--component` entry only | **one model per `components/schemas` entry** |

---

## 4f. Synthesized rules — the exact a12 fire condition (Tier A+)

The keywords with no field-config home become real a12 **rules** (§4 "Rules (composed), built"). A rule **fires on the *error***, so each condition below is the **negation** of the JSON-Schema constraint. `Filled(f)` = the field is present; `[f]` = its value. Every rule is authored through the typed rulekit DSL (the operator in parentheses) and gated by kernel-accept (§5).

| JSON Schema keyword | a12 rule **fires when** (the error condition) | error field | DSL / operator |
|---|---|---|---|
| `exclusiveMinimum: N` | `Filled(f) And [f] ≤ N` | `f` | `num.filled().and(num.le(N))` |
| `exclusiveMaximum: N` | `Filled(f) And [f] ≥ N` | `f` | `num.ge(N)` |
| `multipleOf: N` | `Filled(f) And ([f] − RoundDown([f]/N,0)·N) ≠ 0` | `f` | remainder expr (a12 has no modulo operator) |
| `const: V` | `Filled(f) And [f] ≠ V` | `f` | `str.ne(V)` / `num.ne(V)` |
| `dependentRequired: A → B` | `Filled(A) And Not Filled(B)` | `B` | `fieldFilled` + `fieldNotFilled` |
| `oneOf`/`anyOf` discriminator (`disc = V` ⇒ `R` required) | `[disc] = V And Not Filled(R)` | `R` | `enum.is(V)`/`str.eq(V)` + `fieldNotFilled` |
| `minItems: N` (on a required root `array` → repeatable group `G`) | `NumberOfFilledGroups(G*) < N` | the row's first scalar field (**in-group, unguarded**) | `Dsl.numberOfFilledGroups(group).lt(N)` — fires at `0…N-1` rows **unconditionally**; the in-group error anchor (not a guarded outside field) is deliberate, so an empty guard field can't suppress the "too few rows" error (KERNEL-FINDINGS §9). `maxItems` stays structural (repeatability). `minItems ≤ 0` is a no-op |
| `uniqueItems: true` | `RepetitionNotUnique(row fields …)` | first row field | `Dsl.repetitionNotUnique(…)` |
| `contains {f≈V[, g≈W]}` + `minContains: N` | supported matching-row count `< N`; `maxContains` uses `> M` | an imported scalar row field | Object-property predicates count all instantiated rows minus disjoint first-failing-clause counts. Missing optional properties satisfy their clauses. Scalar predicates count matching filled values; empty-pattern predicates count groups. Supported kinds and omission boundaries follow below. |
| `if {f = V} then {required:[D]}` | `[f] = V And Not Filled(D)` | `D` | `enum.is(V)`/`str.eq(V)` + `fieldNotFilled` |
| `if {f = V} else {required:[E]}` | `Filled(f) And [f] ≠ V And Not Filled(E)` | `E` | `str.ne(V)`/`enum.isNot(V)` (guarded) |
| `if {a = X, b = Y} then {required:[D]}` (multi-property) | `[a] = X And [b] = Y And Not Filled(D)` | `D` | conjunction of equals + `fieldNotFilled` (else of a conjunction is a disjunction → reported) |
| `if {f = V} then {properties:{g:{minimum:N}}}` (then value-constraint) | `[f] = V And [g] < N` | `g` | the if-condition + the *negated* then-keyword — `const`/`enum`/`min`/`max`/`exclusive*`, and `pattern` via `str.violates` (`PatternViolated`) |
| `if {f = V} else {properties:{g:{maximum:M}}}` (else value-constraint) | `Filled(f) And [f] ≠ V And [g] > M` | `g` | the negated condition + the violation mapper; a multi-property `if` → an `Or` of the per-condition negations (`a≠X Or b≠Y`) |
| `not {const: V}` | `Filled(f) And [f] = V` | `f` | `str.eq(V)` / `num.eq(V)` |
| `not {enum: […]}` | `Filled(f) And [f] In […]` | `f` | `str.in(…)` |
| `not {pattern: P}` (nonempty `P`) | `Filled(f) And [f] matches /P/` | `f` | `str.matches(P)` |
| `not {minimum: N}` | `Filled(f) And [f] ≥ N` | `f` | `num.ge(N)` (value must be `< N`) |
| `not {maximum: N}` | `Filled(f) And [f] ≤ N` | `f` | `num.le(N)` (value must be `> N`) |
| `not {minLength: N}` | `Filled(f) And length([f]) ≥ N` | `f` | `str.length().ge(N)` (length `< N`) |
| `not {maxLength: N}` | `Filled(f) And length([f]) ≤ N` | `f` | `str.length().le(N)` (length `> N`) |
| object `not {required:[a, b]}` | `AllFieldsFilled(a, b)` | first field | `Dsl.allFieldsFilled` / `fieldFilled` — a/b must not all be present together |

**Array lower-bound applicability.** A positive `minItems` or `minContains` is synthesized only for an unconditionally required property of the model root. An optional array may be absent while a present empty array violates its lower bound; canonical A12 ingestion erases that distinction. `JsonSchemaImporter.canEnforceArrayMinimum` therefore reports the lower bound as `OMITTED` for optional arrays, nested arrays and conditional union properties, with a corrective to retain source-schema validation. Nested requiredness is a support boundary, not a claim that every nested translation is impossible. Upper `maxContains` bounds remain independent. `SchemaEmptyPatternRuntimeTest.arrayLowerBoundsAreExactOrExplicitlyOmitted` compares absence, empty arrays and populated/empty rows against a stock JSON Schema validator, kernel evaluation and dmtool; its root can also be empty. `ArrayMinimumApplicabilityTest` locks root `allOf`, selected OpenAPI components and nested applicability. Standalone `required` subschemas participate in the `allOf` union even without their own `properties`.

**Object-property `contains` and empty values.** `RuleSynthesis.matchingRows` counts each row failing any clause exactly once, assigning it to its first failure. This preserves missing-property vacuity and is independent of clause order. Numeric and enum-valued predicates use their typed comparisons; STRING `const:""` and STRING enums containing `""` admit missing/unfilled properties and compare the remaining filled values. Nonempty STRING constants, enums excluding `""`, and nonempty STRING patterns are omitted as a whole count with a corrective: the current translator does not preserve their absent/empty behavior. In particular, a missing optional property satisfies `properties:{kind:{const:"express"}}`, while a present empty string does not; ordinary A12 field predicates observe both as unfilled. This is a translation-support boundary, not a claim that every regex has the same empty-input behavior. `SchemaContainsPresenceTest` compares stock JSON Schema validation with kernel and dmtool across thresholds, reversed clause order, missing/empty fields, nested objects and an object property actually named `value`. `SchemaContainsCountDiffTest` additionally compares complete findings against both kernel strategies. A scalar-array predicate is a separate subject: an empty array element is present, so it never inherits object-property absence semantics.

**Empty patterns in composition.** A `then`/`else` value constraint whose sole assertion is `pattern:""` is a reported no-op. Compound value constraints and additional assertions on a `contains` object or nested property are explicitly omitted; simplifying an empty pattern must not discard a sibling assertion. Universal empty-pattern clauses disappear from a `contains` predicate; if none remain, synthesis counts instantiated groups with `NumberOfFilledGroups`, so missing predicate properties and empty rows count. The error anchor comes from an actual imported scalar field, not a property named only in the predicate. When no scalar anchor exists, the current typed synthesis reports an omission. Negated `not {pattern:""}` is also explicitly omitted: A12 value admission cannot preserve the absent-versus-present-empty distinction needed by that constraint. Enforcement is in `RuleSynthesis`; `FieldConstraintsShapeTest` and `SchemaEmptyPatternRuntimeTest` lock the no-op, counting and omission cases. These translations avoid executing the empty authored A12 pattern, whose failure behavior is distinct from JSON Schema's universal regex ([KF543](KERNEL-FINDINGS.md#kf543)).

**Why the conditions look "inverted" — polarity.** An a12 rule produces an error when its condition is **true**, so a *constraint* `field > N` is encoded as the *error condition* `[field] ≤ N`. Every row above is therefore the logical negation of the keyword it implements; reading the table is easiest as "the rule fires exactly on the values the schema forbids."

**The `Filled` guard, and where it sits.** Every *value* constraint is guarded by `Filled(f)`, so it bites only when the value is present — matching JSON Schema, where a keyword constrains a value only *if it is there* (an absent optional field is never in error). The **requiredness** rules (`dependentRequired`, discriminator, `if/then`/`else`) are the deliberate exception — they fire *because* the dependent is **absent**, so there the guard sits on the **trigger/discriminator**, not on the error field.

**Kind resolution + graceful skip.** The synthesis resolves each field's kind from the just-built model and only emits a rule the kind can carry: equality (`const`, discriminator, `if/then`/`else`) on **string + enum**; `not {enum|pattern}` on **string**; numeric bounds (`exclusive*`, `multipleOf`, `not {min|max}`) on **number**. A constraint on an incompatible kind (e.g. `not {enum}` on a number) is **skipped and reported**, never silently dropped (the omit-never-silent rule, §4a).

**The `if/then/else` vacuous-match decision.** Bare `if:{properties:{f:{const:V}}}` matches *vacuously* when `f` is absent, so strict JSON Schema would apply `then` even with `f` missing. The importer instead maps the **pragmatic** reading (`then` fires when `f=V`; `else` fires when `f` is **present and** `≠V` — hence the extra `Filled(f)` guard on the `else` row). This is the near-universal author intent; it is recorded as a deliberate decision in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §2.

**Authored through the typed surface, kernel-gated.** Each rule is built via the rulekit `Dsl`/`BoundModel` typed builder (the dogfood rule), **not** assembled as a condition string, and every imported model is run through the kernel-accept gate (§5) — so a synthesized rule is guaranteed well-formed. Each carries a deterministic name + code (`EXCLUSIVE_MIN_<FIELD>`, `DISCRIMINATED_REQUIRED_<FIELD>_<VALUE>`, `ELSE_REQUIRED_<FIELD>_<VALUE>`, `FORBIDDEN_PATTERN_<FIELD>`, …), `ERROR` severity, and an English message.

---

## 4g. Dialect support — JSON Schema and OpenAPI

OpenAPI's "Schema Object" *is* a dialect of JSON Schema, so most imported/exported schemas come from OpenAPI. schemakit handles three dialects through a `--dialect` knob (the `Dialect` enum) on **both** verbs; the mapping contract (§4–§4f) is otherwise dialect-independent — a dialect only changes the wire encoding of nullability, exclusive bounds, and the `$ref` location.

| `--dialect` | JSON Schema basis | Import reads | Export emits |
|---|---|---|---|
| `JSON_SCHEMA` | Draft 2020-12 | `type:[X,"null"]` nullability, **numeric** `exclusive*`, `$defs` refs | 2020-12 (`$defs`) |
| `OPENAPI_31` (covers 3.2) | 2020-12 + OpenAPI vocab | **+** `components/schemas` refs, explicit `discriminator`, the OpenAPI `format` set | 2020-12; `--wrap-openapi` → `components/schemas` |
| `OPENAPI_30` | a Draft-05 variant | **+** `nullable:true`→optional, **boolean** `exclusive*` (paired with `minimum`/`maximum`) | bare `$defs` (3.0-lacks-`$defs` reported) or `--wrap-openapi` |
| `AUTO` (import default) | detected | the `openapi:` version field (3.0 / 3.1 / 3.2), else assume JSON Schema | — (export defaults to `JSON_SCHEMA`) |

**The three version-independent OpenAPI-isms** (the `OPENAPI_*` column's "+") work for *any* OpenAPI version; only `nullable` + the boolean exclusive encoding are 3.0-specific.

### Import — the bundle entry point

An OpenAPI document is a **bundle**: its schemas live under `components/schemas`, cross-`$ref`-ing. So:

- **Input is JSON *or* YAML** — most OpenAPI specs are YAML, so the importer tries JSON first (exact behavior for JSON), then YAML (a JSON superset). (A new dep, `jackson-dataformat-yaml`; a native-image re-capture trigger — NATIVE-IMAGE-GRAALVM §5.1.)
- **`$ref` resolves by JSON Pointer** against the whole document — both `#/$defs/X` (JSON Schema) and `#/components/schemas/X` (OpenAPI).
- **`--component <Name>`** selects the `components/schemas` entry to import as the model root; its sibling refs resolve. A bare schema needs none; a **multi-schema OpenAPI document with no `--component` (and no `--out-dir`) is refused, listing the components** — choosing an entry point is a precondition, not a feature-omission (so it is a clear error, not an omit-with-to-do).
- **`--out-dir <dir>`** imports the **whole bundle** (§4e): every `components/schemas` entry → its own model (`<dir>/<Component>.dm.json`), and an object **cross-reference becomes an a12 mount** (an include) instead of being inlined — so a shared definition is single-sourced. The kernel forbids cyclic includes, so the importer **breaks cycles**: a **self-recursive** component is expanded (to `--recursion-depth`) then omitted; a **cross-component cycle** (A↔B) has one edge inlined (a DFS back-edge) so the bundle still loads. Mutually exclusive with `--component`.
- **A top-level union component** — a `components/schemas` entry (or bare schema) that is itself a `oneOf`/`anyOf` of objects (a discriminated union, common in OpenAPI) — becomes a **superset at the model root** (the discriminator → an enum + per-variant requiredness rules), instead of being refused for lacking a `type:object` root.
- **An array of `$ref` items** (`items: {$ref: …}`, ubiquitous in OpenAPI) is resolved before the scalar/object dispatch → a **repeatable mount** in bundle mode (cross-component) or a repeatable inlined group otherwise; an array of a leaf typedef → a scalar array of that leaf.
- **AUTO** reads the `openapi:` version and states the detected dialect in the report.
- **OpenAPI 3.0:** `nullable:true` → the field is OPTIONAL (a12 has no present-but-null; a required+nullable field is reported); a boolean `exclusiveMinimum` + `minimum` → a strict-bound rule (and the inclusive `minimum` is suppressed so it isn't doubly enforced).

### Export — the OpenAPI envelope

- **`--wrap-openapi`** wraps the Schema Object in a minimal, valid OpenAPI document (`openapi`, `info`, empty `paths`, `components/schemas`), refs → `#/components/schemas/…` — a drop-in. The bare form keeps `$defs` (valid JSON Schema / OpenAPI 3.1).
- **`x-a12-rule` / `x-a12-computation` carriage is dialect-agnostic** — `x-` extensions are first-class in every OpenAPI version, so the a12↔a12 round-trip survives the OpenAPI trip.
- a12 carries **no null** and **inclusive-only** field bounds (strict bounds live in rules → carriage/projection), so the export dialect mainly drives the ref location + the envelope; `OPENAPI_30` + `$defs` is reported.

The verbs/options are discoverable from the binary (`manifest`/`--help`): import takes `--dialect` + `--component`; export takes `--dialect` + `--wrap-openapi`. Remaining limitations are tracked in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md).

---

## 5. Verification gates

The differential is **round-trip + kernel-accept** (the kernel-accept gate rides the public facade via `ModelLoader`):

1. **Kernel-accept** — every imported model loads clean: `ModelLoader.loadSelfContained` does not throw `ModelConsistencyException`.
1a. **Portable agreement** — the same models, through `dmkits-consistency-gate` with no kernel involved, must not be REFUSED. Only that direction fails: A12 has just accepted the model, so a portable ERROR is the Kernel-free checker refusing what A12 admits — invisible to a consumer, because the kernel is never asked. Its silence is never evidence of the converse, since that checker completes only the families it implements and reports no VALID verdict at all. It rides the one `assertKernelAccepts` seam every shape test already calls, so the arm covers the whole import corpus rather than hand-picked fixtures; `PortableAcceptanceGateTest` supplies its positive controls, without which an arm that fires only on a disagreement would be green whether armed or not. **Measured 2026-09-16: no disagreement across the 135-test corpus.**
2. **Round-trip** — `import(schema) → DM → export → schema'` is structurally equal on `{type, properties, required, $defs}`; and `DM → export → import → DM'` preserves structure + (a12↔a12) rules/computations via `x-a12-*`, plus the unchanged group `indexFieldName` scalar through `x-a12-index-field-name` on its object.
3. **Valid JSON Schema** — exports validate against the JSON-Schema meta-schema.
4. **Report accuracy** — the transcoding report states every disposition; it is asserted against (it doubles as the suite's evidence, proposal §11).
5. **Two-tier boundary** *(shape 5)* — exported Tier-A keywords feed a stock validator; a cross-field rule that the stock tier cannot enforce remains present in `x-a12-rule`. Live `interpreter.validateFull` wiring is deferred in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md) §3.

Tests live in `:schemakit:test`; the kernel-accept gate uses only the public facade (`ModelLoader`), so the structural shapes need no `:adapter` on the test classpath. The portable arm adds `:consistency-gate` at TEST scope for the same reason `:adapter` is test-scoped — it arms the differential, never the published artifact, so `dmkits-schemakit`'s POM is unchanged and `verifySchemakitPurity` (which reads main classes) is untouched. Tier-A projection uses the `ConditionReadBack` provider from `:adapter` at test runtime; the deferred live full-rule tier would add `:interpreter`.

---

## 6. Increments (the de-risking path, proposal §11)

Built test-first, smallest increment first. Progress is git, not a status table here. The five foundation increments below de-risked feasibility; the choice-driven mapping **strategies** (array-of-scalar, map, union, allOf, format, residual-omissions) built on top of them and are catalogued in §4b/§4d.

1. **Flat object** — `object` + string/integer/number/boolean properties + `required` → DM with fields + requiredness; export back; round-trip equal; kernel-accepts.
2. **Nested + array-of-object** — groups + repeatable groups + `minItems`/`maxItems`.
3. **`$defs`/`$ref` both kinds** — leaf `$def`→typedef; object `$def`→inlined group (single-import) or **mounted as an include** (the `--out-dir` bundle, §4e/§4g); recursive `$ref` expanded to `recursionDepth` (default 1), then omitted with a corrective to-do (the kernel forbids a self-include).
4. **Rule carriage** — Tier-A `if/then`+`required`; Tier-B cross-field/aggregate as `x-a12-rule`; a computation as `x-a12-computation`; meta-schema-valid; re-import reconstructs all three.
5. **Tier A+ import & two-tier boundary** — synthesize rules from `dependentRequired`/`exclusiveMinimum`/`multipleOf`/`const`/discriminators; prove stock Tier-A enforcement and full-rule carriage. Live interpreter coupling remains deferred (proposal §12).

> **Open / deferred work** is not tracked here — the stable contract is this spec; the moving edge (import-synthesis gaps + the deferred contract/architecture decisions) lives in [`SCHEMAKIT-GAPS.md`](SCHEMAKIT-GAPS.md).
