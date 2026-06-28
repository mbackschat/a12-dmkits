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

**How it's enforced** — `verifySchemakitPurity` (wired into `check`, mirroring the interpreter's purity lint) fails the build on a `com.mgmtp.*` / `groovy.*` import in `src/main`, so schemakit's source stays free of raw kernel types regardless of which modules sit on its classpath.

---

## 2. Module & package shape

| Aspect | Decision |
|---|---|
| Gradle unit | `:schemakit` — a JVM/Java module (`java-library`, JDK 21) |
| Multiplatform? | **No.** JVM/Java only; native-safe via GraalVM ≠ Kotlin Multiplatform (proposal §10). No KMP, no JS. |
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
| `dmtool model import-jsonschema --schema <schema.json> [profile flags] [-o <model.dm.json>]` | JSON Schema → DM-JSON artifact (stdout); the transcoding report → stderr; `-o` writes the model + a write envelope |
| `dmtool model export-jsonschema -m <model.dm.json> [-o <schema.json>]` | DM-JSON → JSON Schema artifact (stdout); report → stderr; `-o` writes the schema + a write envelope |

Both keep **artifact mode**: the transcode result (DM-JSON / JSON Schema) goes to stdout so it pipes straight into the next tool, and the **transcoding report** — the omission to-dos — rides stderr (loud, never silent); with `-o` the artifact goes to the file and stdout carries a write-confirmation envelope. They are classified `SchemaCommand.CONTRACT_EXEMPT` (no result-envelope contract on the artifact form, like `model new`/`model seed`). **Import** exposes the `MappingProfile` choices as flags — `--strict` (the conservative master switch) plus the per-decision `--number-scale` / `--default-scale` / `--array-of-scalar` / `--map` / `--union` / `--format` / `--boolean` / `--enum-labels` / `--recursion-depth` / `--default-repeat-cap` (§4b).

**The report is the agent's to-do list, so make it machine-readable, not only prose.** The omissions an import leaves are exactly what an agent must complete by hand — its next-step list, not a side note. So beyond the human-readable stderr lines, the `-o` write-envelope carries the **structured report** in `changed.report` (`{mapped, omitted, notes[]}`): the agent's natural path — `-o` writes the model to a file *and* returns the parseable audit trail on stdout — gives it everything in the machine-readable channel it already trusts. (stdout stays the clean artifact in the no-`-o` pipe case; the report cannot also go there without corrupting the artifact, so the structured form rides the `-o` envelope.)

**Naming caveat (proposal §10):** `dmtool schema` is taken (it publishes verb I/O contracts). The JSON-Schema verbs are `import-jsonschema` / `export-jsonschema`, **never** `schema`. Adding the verbs triggered the usual CLI-CONFORMANCE gates (`ManifestVerbTest.EXPECTED_VERBS`, the conformance matrix, `verb-examples.json` + a `<schema.json>` demonstration placeholder) — see [CLI-CONFORMANCE-SPEC.md](CLI-CONFORMANCE-SPEC.md); a native re-capture is needed only before a native build (NATIVE-IMAGE-GRAALVM §5.1).

---

## 4. The mapping contract

**Direction matters (owner decision, 2026-06-28).** **Import → DM aims for TOTAL coverage:** every JSON Schema feature lands in a12 **structure or rules** — directly, via a *composed* rule, or via a *customizable strategy* (§4b). Anything a12 genuinely cannot represent is **omitted, never refused** (§4a). **Export → JSON Schema stays best-effort:** native Tier-A keywords + `x-a12-*` carriage (the asymmetry the proposal describes is real only on export). Every transcode emits a **transcoding report** (proposal §9.1) so the mapping is auditable, never silent.

### Import targets — structure + rules

| Layer | Mapping |
|---|---|
| **Structure** | `object`→group, `array`-of-`object`→repeatable group, scalar→field-kind (the field-type matcher, §4b), `$defs`/`$ref`→typedef (leaf) / include-or-inline (object) |
| **Field config** | `minLength`/`maxLength`/`pattern`→string config; `minimum`/`maximum`→number bounds |
| **Rules (direct)** | `required`→requiredness; `enum`→ENUMERATION; `uniqueItems`→`RepetitionNotUnique`/`FieldValuesNotUnique`; `pattern`→`PatternViolated` |
| **Rules (composed), built** | `exclusiveMinimum`/`exclusiveMaximum`→strict `<`/`>`; `multipleOf`→modulo `{[x]−RoundDown([x]/N,0)·N} ≠ 0`; `const`→`!=` / single-value; `dependentRequired`→conditional requiredness; `oneOf`/`anyOf` discriminator→per-variant requiredness rules; `uniqueItems`→`RepetitionNotUnique`; `if {field=value} then {required}`→conditional requiredness; `not {const}`→`!=`, `not {enum}`→not-in-list |
| **Rules — not yet built** (reported, not enforced) | `contains`/`minContains`→`Having`+count; *general* `if`/`then`/`else` (then-value-constraints, `else`, arbitrary schemas) and complex `not` (non-const/enum). (`minItems` has **no clean a12 rule form** — §4c; reported, not enforced.) |
| **Carriage (export, a12↔a12)** | the full rule rides `x-a12-rule`, a computation `x-a12-computation`; `readOnly:true` marks a computed field |
| **Two-tier validation** *(proposal §12.1)* | exported Tier-A keywords = field-local tier (any validator); `:interpreter` = full-rule tier |

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
| `format` (`--format`) | **`PATTERN`** (standard regex where known; temporal → native DATE/TIME kinds) | `CUSTOM_TYPE` / `IGNORE` | `IGNORE` |
| boolean (`--boolean`) | `BOOLEAN` | `CONFIRM` | `BOOLEAN` |
| enum labels (`--enum-labels`) | `DERIVE` (label = value) | `X_ENUM_NAMES` / `BLANK` | `DERIVE` |
| recursive `$ref` (`--recursion-depth`) | **expand 1 level** | any depth `N` / `0` = omit | `0` (omit) |

Every flag (and each value's *implication* — e.g. `INTEGER` drops decimals, `--default-repeat-cap 0` omits) is surfaced in `dmtool manifest` / `--help`, so a cold agent discovers not just the names but what each does.

### 4c. The residual omissions (no faithful static a12 form — omitted, never block)

Four cases have no static a12 representation even with a strategy; each is **omitted with a corrective TO-DO** (not refused — examples in the session log + below):

1. **cross-kind type union** — `type:["string","object"]`, `oneOf` across *different kinds* (a field has one kind). *(nullable `type:[X,"null"]` → optional X, and same-kind object unions → §4b superset — both DO map.)*
2. **`unevaluatedProperties`/`unevaluatedItems` with applicators** — dynamic, annotation-dependent closure. *(a plain closed object maps for free.)*
3. **`$dynamicRef`/`$dynamicAnchor`** — runtime-polymorphic references.
4. **unbounded recursive `$ref`** — expanded to `--recursion-depth` levels (default 1), then the deeper recursion is omitted with a to-do; `--strict` / `0` omits it entirely (§4b).

---

## 4d. Coverage at a glance

The asymmetry in one place. **Import aims for total coverage** — every JSON Schema feature lands in a12 *or* is surfaced with a corrective to-do (never silent, never blocked). **Export is best-effort** — native Tier-A keywords where they exist, `x-a12-*` carriage otherwise. Read these two tables to see, at a glance, what each direction does and where it loses fidelity.

### Import — JSON Schema → Document Model

| JSON Schema | a12 import | Mapping |
|---|---|---|
| `object` + `properties`; nested `object` | group; nested group | structure |
| `allOf` of objects | one merged group (props unioned, `required` unioned, `$ref` bases resolved) | structure |
| `array` of `object` (+ `maxItems` ≥ 2) | repeatable group | structure |
| `$ref`/`$defs` → leaf / → object | typeDefinition + ref / inlined group | structure |
| `string` · `integer` · `boolean` · `enum` | STRING · NUMBER(scale 0) · BOOLEAN\|CONFIRM · ENUMERATION | structure (boolean: profile) |
| `type:[X,"null"]` (nullable) | optional `X` | structure |
| `format: date` / `date-time` / `time` | DATE / DATE_TIME / TIME field | structure |
| `required` | field requiredness | structure |
| `minLength` / `maxLength` / `pattern` | string field config | field-config |
| `minimum` / `maximum` | number value bounds | field-config |
| `format: email` / `uuid` | STRING + standard pattern | field-config (profile: format) |
| `exclusiveMinimum` / `exclusiveMaximum` | strict `<` / `>` rule | rule (Tier A+) |
| `multipleOf` · `const` · `dependentRequired` | modulo rule · `!=` rule · conditional-requiredness rule | rule (Tier A+) |
| `uniqueItems` | `RepetitionNotUnique` rule keyed on the repeatable group's row fields | rule (Tier A+) |
| `if {field = value}` + `then {required}` | conditional-requiredness rule per dependent | rule (Tier A+) |
| `not {const}` · `not {enum}` | `!=` rule · not-in-list rule | rule (Tier A+) |
| `oneOf`/`anyOf` of same-kind objects | superset group + discriminator → enum + per-variant requiredness rules | strategy + rule (profile: union) |
| `array` of scalar | repeatable single-field group (default) / multi-select / value-list | strategy (profile; **imported by default**) |
| `array` / map with no `maxItems` / `maxProperties` | repeatable group capped at `--default-repeat-cap` (default 1000) | strategy (profile; **imported by default**, capped) |
| `additionalProperties` / `patternProperties` (map) | repeatable `{key, value}` group | strategy (profile: map) |
| bare `number` (no `multipleOf`) | NUMBER at the default scale (a flagged guess) | strategy (profile; **imported by default**) |
| `format: <other>` | pattern / custom field type / plain string | strategy (profile: format) |
| recursive `$ref` | expand `--recursion-depth` levels (default 1) | strategy (profile; **imported by default**, 1 level) |
| `minItems` | reported — **not enforced** (no a12 cardinality rule, KERNEL-FINDINGS) | omitted |
| cross-kind `type:[X,Y]` · mixed `oneOf`/`anyOf` | reported | omitted (§4c) |
| `unevaluatedProperties` / `unevaluatedItems` | structure imports; constraint reported — **not enforced** | omitted (§4c) |
| `$dynamicRef` / `$dynamicAnchor` | reported | omitted (§4c) |
| `contains`/`minContains`; *general* `if`/`then`/`else` + complex `not` | structure imports; keyword reported — **not yet mapped** (needs starred-aggregate / arbitrary-schema synthesis) | omitted (gap) |

### Export — Document Model → JSON Schema

| a12 construct | JSON Schema export | Fidelity |
|---|---|---|
| group; repeatable group | `object`; `array` + `maxItems` | exact |
| STRING (+ `minLength`/`maxLength`/`pattern`) | `string` (+ constraints) | exact |
| NUMBER scale 0 / scale > 0 (+ `min`/`max`) | `integer` / `number` (+ `minimum`/`maximum`) | exact |
| BOOLEAN · ENUMERATION | `boolean` · `enum` | exact |
| typeDefinition | `$defs` entry + `$ref` | exact |
| Rule recognized as a field `pattern` (Tier-A) | native `pattern` keyword | projected |
| any other Rule | `x-a12-rule` (a12↔a12 round-trip) | carriage |
| Computation | `x-a12-computation` (a12↔a12 round-trip) | carriage |
| DATE / DATE_TIME / TIME / DATE_RANGE / DATE_FRAGMENT | `string` placeholder + diagnostic | **lossy** (no `format` emitted yet) |
| CONFIRM · CUSTOM | `string` placeholder + diagnostic | **lossy** |

The **mismatch in one line:** import turns rules/strategies/temporal-formats into native a12 constructs; export only round-trips the Tier-A subset natively and carries the rest as `x-a12-*` (or, for temporal/custom *fields*, falls back to `string`). That export-side temporal/custom loss is the main asymmetry — the inverse of the import `format`→kind mapping (§4b).

---

## 5. Verification gates

The differential is **round-trip + kernel-accept** (the kernel-accept gate rides the public facade via `ModelLoader`):

1. **Kernel-accept** — every imported model loads clean: `ModelLoader.loadSelfContained` does not throw `ModelConsistencyException`.
2. **Round-trip** — `import(schema) → DM → export → schema'` is structurally equal on `{type, properties, required, $defs}`; and `DM → export → import → DM'` preserves structure + (a12↔a12) rules/computations via `x-a12-*`.
3. **Valid JSON Schema** — exports validate against the JSON-Schema meta-schema.
4. **Report accuracy** — the transcoding report states every disposition; it is asserted against (it doubles as the suite's evidence, proposal §11).
5. **Two-tier** *(shape 5)* — exported Tier-A keywords feed a stock validator while `interpreter.validateFull` enforces the full rule set on the same instance.

Tests live in `:schemakit:test`; the kernel-accept gate uses only the public facade (`ModelLoader`), so the structural shapes need no `:adapter` on the test classpath. Tier-A projection and the two-tier shape pull in `:adapter`/`:interpreter` as those increments land.

---

## 6. Increments (the de-risking path, proposal §11)

Built test-first, smallest increment first. Progress is git, not a status table here. The five foundation increments below de-risked feasibility; the choice-driven mapping **strategies** (array-of-scalar, map, union, allOf, format, residual-omissions) built on top of them and are catalogued in §4b/§4d.

1. **Flat object** — `object` + string/integer/number/boolean properties + `required` → DM with fields + requiredness; export back; round-trip equal; kernel-accepts.
2. **Nested + array-of-object** — groups + repeatable groups + `minItems`/`maxItems`.
3. **`$defs`/`$ref` both kinds** — object `$def`→include/mount, leaf `$def`→typedef; recursive `$ref` expanded to `recursionDepth` (default 1), then omitted with a corrective to-do.
4. **Rule carriage** — Tier-A `if/then`+`required`; Tier-B cross-field/aggregate as `x-a12-rule`; a computation as `x-a12-computation`; meta-schema-valid; re-import reconstructs all three.
5. **Tier A+ import & interpreter coupling** — synthesize rules from `dependentRequired`/`exclusiveMinimum`/`multipleOf`/`const`/discriminators; two-tier validation holds (proposal §12).

---

## 7. Open items

The customizable strategies and the residual omissions are settled in §4b/§4c. What's still genuinely open:

- **Module name** — `:schemakit` (consistent with rulekit/modelkit). Reconsider only if it confuses with the `schema` verb.
- **Target draft** — Draft 2020-12 canonical + Draft-07 read-compat (proposal §12/§13).
- **`ExternalSchemaProfile` seam** — keep the core profile non-JSON-Schema-specific so OpenAPI/TS/SQL/Protobuf become later profiles (proposal §12.2 #4).
- **Live Tier-2 (DECIDED 2026-06-28: deferred)** — running the interpreter as the real full-rule tier (not just demonstrating carriage + complementarity) needs the DM-JSON→`ModelDef` loader, which currently lives in **`:cli`** (`interp.InterpreterModelLoader`). Reuse-first forbids reimplementing it; depending on `:cli` from `:schemakit` is a backwards edge. **Owner decision: deferred** — live Tier-2 stays in the CLI; revisit only if a kernel-free in-process Tier-2 round-trip is needed (then move/expose the loader in `:interpreter`). Shape 5 proves Tier-1 (stock validator) + that the cross-field rule is carried for Tier-2.
