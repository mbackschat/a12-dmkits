# schemakit — JSON Schema ⇄ Document Model (spec)

The buildable contract for `:schemakit`, the JSON-Schema import/export subproject. **The rationale — the asymmetry finding, the interop-vs-fidelity fork, the three-tier rule story, the `$ref`→include/typedef split, the missed-potential list — lives in [`JSON-SCHEMA-PROPOSAL.md`](JSON-SCHEMA-PROPOSAL.md); this doc does not repeat it.** The spec holds the *contract*: the dependency boundary, the module shape, the verb surface, the verification gates, and the spike increments.

**Status:** spike in progress (in-repo under a `.spike` package, per the extraction discipline below). What is built is shown by git + the `:schemakit` test suite, not a status board here.

---

## 1. The one non-negotiable: depend on the kernel only through `kernel-md-facade`

**`:schemakit`'s import/export MUST NOT depend on the A12 kernel beyond the public `kernel-md-facade` API** (owner constraint, 2026-06-28). This is the same boundary the core library already holds (the D15/N8 *public-kernel-only* invariant, [ARCHITECTURE.md](ARCHITECTURE.md) §1/§4) — schemakit inherits it by construction:

```
   :schemakit  ──implementation──▶  root ` : `  ──api──▶  kernel-md-facade   (PUBLIC: expand/check/serialize)
        │                            (dm.core · modelkit · rulekit)
        │
        ├──(joins at Tier-A projection / shape 5)──▶  :interpreter   (kernel-FREE; KMP jvm)
        │
        └──✗ NEVER  :adapter · kernel-md-model · kernel-md-a12internal · parsetree-api · tool-api
```

| Allowed | Forbidden |
|---|---|
| root `:` (modelkit `DocumentModelDraft`/`ModelDocument`, rulekit `Dsl`/`DslRenderer`/`RuleValidator`, `dm.core` `ModelLoader`) | `:adapter` (the internal-read tier) |
| `kernel-md-facade` **transitively**, via root `:` (the kernel-accept gate `ModelLoader.loadSelfContained` rides it) | `kernel-md-model`, `kernel-md-a12internal`, `parsetree-api`, `tool-api`, `groovy.*` |
| `:interpreter` (kernel-free, native-safe) for condition parsing + the two-tier coupling | constructing or reading kernel AST directly |

**Why it's a hard rule, not a preference** — the same three reasons the interpreter is clean-room (INTERPRETER-SPEC §B): **license** (the internal kernel tier is EUPL-1.2 copyleft; the facade is the public consumer surface), **native-safety** (schemakit ships in the `dmtool` native binary — §3 — and the internal tier's Groovy can't go native), and **portability** (a future extraction to `a12-dm-json-schema` stays a lift, not a rewrite).

**How it's enforced** — two guards, mirroring the interpreter's `verifyInterpreterPurity`:

1. **By construction:** schemakit's `build.gradle` declares no internal kernel jar and no `:adapter`. The internal tier is simply not on its classpath, so a forbidden call cannot compile.
2. **By lint:** `verifySchemakitPurity` (wired into `check`) fails the build on a `com.mgmtp.*` or `groovy.*` import in `src/main` — schemakit's own source stays free of kernel types and emits plain data (the "data out, never kernel objects" invariant). The facade is reached *only* transitively through root `:`.

---

## 2. Module & package shape

| Aspect | Decision |
|---|---|
| Gradle unit | `:schemakit` — a JVM/Java module (`java-library`, JDK 21) |
| Multiplatform? | **No.** JVM/Java only; native-safe via GraalVM ≠ Kotlin Multiplatform (proposal §10). No KMP, no JS. |
| Depends on | `implementation project(':')` (the engine) · Jackson (JSON Schema parse/emit) · **later** `implementation project(':interpreter')` |
| Package | `io.github.mbackschat.a12.dm.schemakit` — spike work under **`…schemakit.spike`** until promoted |
| Extraction | spike in-repo under `.spike`; extract to `a12-dm-json-schema` before promotion (the interpreter discipline) |

The transcoder operates through dmkits' typed surface (the dogfood rule): **import** builds via modelkit `DocumentModelDraft` and synthesizes rules via the rulekit `Dsl` builder + `DslRenderer`; **export** reads via modelkit `ModelDocument` (kernel-free) and the rulekit AST. The structural validity oracle is the kernel itself, reached only through `ModelLoader` (facade).

The `a12-kernel-mm` metamodels (`MM_Field_1`/`MM_Rule_1`/`MM_Group_1`/`MM_Computation_1`/`CT_MODEL_HEADER_1`, the source-of-truth for valid DM structure) are a **read-only structural oracle** for the mapping — consult them when a config key or slot is in question; never a dependency.

---

## 3. CLI surface (in `dmtool`, native-safe)

schemakit is **runtime-callable and wired into `dmtool` from day one** (proposal §10) — not a design-time tool. Because its deps (modelkit/rulekit/Jackson + later the kernel-free interpreter) are native-safe and already in the native binary, the verbs run in the native image.

| Verb | I/O |
|---|---|
| `dmtool model import-jsonschema -i <schema.json> [-o <model.dm.json>]` | JSON Schema → DM-JSON + transcoding report |
| `dmtool model export-jsonschema -m <model.dm.json>` | DM-JSON → JSON Schema + transcoding report |

**Naming caveat (proposal §10):** `dmtool schema` is taken (it publishes verb I/O contracts). The JSON-Schema verbs are `import-jsonschema` / `export-jsonschema`, **never** `schema`. Adding the verbs triggers the usual CLI-CONFORMANCE gates (`ManifestVerbTest.EXPECTED_VERBS`, the conformance matrix, `verb-examples.json`, a native re-capture) — see [CLI-CONFORMANCE-SPEC.md](CLI-CONFORMANCE-SPEC.md). The verbs return the result envelope; the report is `data`.

---

## 4. The mapping contract (summary — full inventory in the proposal)

Three tiers, *project-or-carry* (proposal §6); the structural layer is the feasible ~90% (proposal §4); every transcode emits a **transcoding report** (proposal §9.1) so the lossiness is auditable, never silent.

| Layer | Rule |
|---|---|
| **Structure** | `object`↔group, `array`-of-`object`↔repeatable group, scalar↔field-kind via the field-type matcher (proposal §5), `$defs`/`$ref`↔include (object `$def`) / typedef (leaf `$def`) (proposal §4.1) |
| **Rules — Tier A** | a small native subset → `required`/`pattern`/`enum`/`minimum`/`maximum`/`if-then` |
| **Rules — Tier A+** *(import, proposal §12.2)* | synthesize a12 rules from `dependentRequired`/`if-then`+`required` (conditional requiredness), `uniqueItems` (cross-row uniqueness), `minItems` (group-fill count), `oneOf`/`not`-of-`const` (mutual exclusion) |
| **Rules — Tier B** | everything else → carry the rendered DSL in `x-a12-rule` (lossless a12↔a12) |
| **Computations — Tier C** | `x-a12-computation` only (no JSON-Schema analog); `readOnly:true` projects the *fact* a field is computed |
| **Two-tier validation** *(proposal §12.1)* | exported Tier-A keywords = the field-local tier (any JSON-Schema validator); `:interpreter` = the full-rule tier. `dmtool` wires both. |

**Refusal, not silent degrade:** recursive `$ref` and (v1) arbitrary `allOf`/`anyOf`/`oneOf` composition are **refused with a corrective diagnostic** in the report — the gate-and-refuse discipline (proposal §4.1, CLI-SPEC §6).

---

## 5. Verification gates

The differential is **round-trip + kernel-accept**, all through the public facade (no `:adapter`):

1. **Kernel-accept** — every imported model loads clean: `ModelLoader.loadSelfContained` does not throw `ModelConsistencyException`.
2. **Round-trip** — `import(schema) → DM → export → schema'` is structurally equal on `{type, properties, required, $defs}`; and `DM → export → import → DM'` preserves structure + (a12↔a12) rules/computations via `x-a12-*`.
3. **Valid JSON Schema** — exports validate against the JSON-Schema meta-schema.
4. **Report accuracy** — the transcoding report states every disposition; it is asserted against (it doubles as the spike's evidence, proposal §11).
5. **Two-tier** *(shape 5)* — exported Tier-A keywords feed a stock validator while `interpreter.validateFull` enforces the full rule set on the same instance.

Tests live in `:schemakit:test` (public-facade kernel-accept needs no `:adapter`). A kernel *runtime* differential, if ever needed for the two-tier demo, would go in `:adapter` (which has the kernel) — never pulling the kernel into `:schemakit`.

---

## 6. Spike increments (the de-risking path, proposal §11)

Built test-first, smallest increment first. Progress is git, not a status table here.

1. **Flat object** — `object` + string/integer/number/boolean properties + `required` → DM with fields + requiredness; export back; round-trip equal; kernel-accepts. *(the first increment)*
2. **Nested + array-of-object** — groups + repeatable groups + `minItems`/`maxItems`.
3. **`$defs`/`$ref` both kinds** — object `$def`→include/mount, leaf `$def`→typedef; recursive `$ref` **refused** with a diagnostic.
4. **Rule carriage** — Tier-A `if/then`+`required`; Tier-B cross-field/aggregate as `x-a12-rule`; a computation as `x-a12-computation`; meta-schema-valid; re-import reconstructs all three.
5. **Tier A+ import & interpreter coupling** — synthesize rules from `dependentRequired`/`uniqueItems`; two-tier validation holds (proposal §12).

Success = all five succeed; the suite settles feasibility (proposal §11).

---

## 7. Open items

- **Module name** — `:schemakit` (consistent with rulekit/modelkit). Reconsider only if it confuses with the `schema` verb.
- **Array-of-scalar default strategy** — multi-select group vs value-list (proposal §9).
- **Number scale policy** *(found in shape 1)* — an a12 number always carries a **bounded max scale, default 0** (`DocumentModelDraft` emits `maxFractionalDigits`, defaulting null→0), so **scale 0 = integer** and there is no "unbounded decimal" number. JSON `integer` round-trips cleanly; JSON `number` has no faithful target without a scale signal. Shape 1 **reports** a bare `number` rather than silently mapping it to an integer; the field-type matcher (proposal §5) resolves it via `multipleOf`→scale + a default-scale policy.
- **Target draft** — Draft 2020-12 canonical + Draft-07 read-compat (proposal §12/§13).
- **`ExternalSchemaProfile` seam** — keep the core profile non-JSON-Schema-specific so OpenAPI/TS/SQL/Protobuf become later profiles (proposal §12.2 #4).
