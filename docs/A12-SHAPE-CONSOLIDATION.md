# A12 shape consolidation — the migration plan

**Goal.** Every document JSON at an **official API/CLI boundary** is A12's canonical shape. Owner directive, 2026-07-28: *"OUR PROJECT MUST NOT INVENT ITS OWN SHAPE"*, scoped to *"whenever document JSON is at official API/CLI boundary"*. The standing rule lives in [`../CLAUDE.md`](../CLAUDE.md) ("NEVER invent a shape A12 already defines"); the format spec lives in [`KERNEL-FINDINGS`](KERNEL-FINDINGS.md) ("The canonical Document JSON and `DocumentPointer` string"). **Read both before starting.** This document is only the ordered work plan; it is deleted when the last step lands.

## What was wrong

Five shapes for one A12 concept, found by sweep on 2026-07-28:

| Where | Invented shape | In scope? |
|---|---|---|
| `:interpreter` public API | `DocumentInputV1{groups:[{path,coordinates}], fields:[{path,coordinates,raw}]}` | **yes** — boundary ✅ removed in step 2 |
| `:cli` (dmtool) `DocInstance` | `{"fields":{…}}` wrapper, text-only values | **yes** — boundary ✅ removed in step 3 |
| `:adapter` `FieldPointer` | `record(String path, List<Integer> reps)` | no — internal |
| corpus cases | `placements:[{kind,path,reps}]` | no — internal (but see step 4) |
| interpreter Kotlin/TS pointers | `path` + `coordinates[]` | no — internal |

**Internals stay.** The kernel's own `DocumentPointerImpl` holds `List<PathPart>` = `(name, repetitionIndex)` pairs and renders the string only in `toString()`. Our internal form already matches the kernel's internal form; only the *serialization* diverged. Converting internals to strings would add parsing to hot paths against the interpreter's stated exactness+performance priorities. Do not do it.

## Order of work

Steps 2 and 3 are the breaking ones and land together in **0.9.2** (owner approved a new release; more fixes of theirs are queued for the same one).

### Step 1 — canonical pointer form ✅ DONE

`A12PointerFormat` in `interpreter/src/commonMain/.../A12PointerFormat.kt`, 13 tests green (`A12PointerFormatTest`). Parses/formats A12's `Group/Sub[3]/Field`: `[1]` implicit and omitted, `[0]` = wildcard on the last part only, leading `/` stripped, trailing `/` rejected. Used by later steps for any pointer string that crosses the boundary.

### Step 2 — interpreter API reads and writes A12 Document JSON ✅ DONE

`A12DocumentJson` in `commonMain` reads and writes the canonical shape; `PreparedModel.documents.readDocument(json)` / `encodeDocument(snapshot)` are the boundary; `DocumentInputV1`/`GroupPlacementInput`/`FieldPlacementInput` are deleted with no bridge. Details + the design decisions recorded in [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF227, the contract in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) §4.

Both open questions were settled by measurement, not assumption:

1. **`Document` read accessors** — the real ones are `cellStateAt(path, reps)` (`Absent`/`PresentEmpty`/`PresentValue(raw)`), `filledAt`, `valuedFields()` plus module-internal `instantiatedRows`/`placementsSnapshot`. There is no `rawAt`/`hasGroupAt`; the discarded draft had invented them.
2. **`JsonNull`** — a null keeps the field **present with no value** (`FieldInstanceV2.ofValue(null)`, reached through `parseStringValueCommon`), which is `Document.emptyField`. No nullable `field(...)` overload was needed. Recorded in KERNEL-FINDINGS beside the format spec.

Two by-products worth knowing for the remaining steps: the **read path stays exactly as permissive as A12's reader** (an array is accepted at a non-repeating group, so an over-repetition is still representable at the boundary — no capability was lost), and two duplicated surfaces were removed rather than migrated (`DocumentPlacementKind` → the existing `ModelEntityKind`; `maxCoordinatesPerPlacement` → `maxModelNestingDepth`, with a new pre-parse `maxDocumentCodeUnits`).

### Step 2b — the transport becomes VALUE-NORMALIZING for every typed kind ✅ DONE (owner decision, 2026-07-28)

**Decided: option A — match A12 exactly** rather than record a divergence class. A12's document stores `String | Boolean | BigDecimal | Instant | InstantRange` and re-renders every text from the parsed value; ours was text-preserving, so read→write differed for any input whose text is not already the canonical rendering of its value.

**Shipped:** `A12DocumentJson.normalizedCellValue` normalizes at read for every typed kind. The rules and their measurement live in [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "The canonical Document JSON …" (the kernel side) and [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF227 (ours). Two things the measurement changed versus the plan as first written, both recorded there:

- **The number target is the PLAIN form (`100000`), not the `asText()` form (`1E+5`).** The "scale is observable" argument for `asText()` had an unmeasured link — the runtime does not read the stored decimal's scale, it reads `FormatDefinitionZahl.convertFromBasicType`'s plain rendering. Probing it settled the question: `1e5` on a scale-0 field draws no error at all in the kernel, which `1E+5` could not produce.
- **The DATE family needs no normalization** — the unmeasured cell in the old table. `ValidationDateParser` is non-lenient, so every non-canonical spelling *fails* conversion and is kept verbatim; anything that parses already is the model format.

The malformed pass-through survives ([A12K-3638]), and String/Enumeration stay untouched. New locks: `adapter.laws.A12JsonIngressKernelTest` (the kernel's own ingress, 49 rows) and `A12JsonIngressDiffTest` (ours against it, with an exhaustive named residual). Two gaps were opened by the measurement rather than by the change: **IG84** (no formal error for a malformed boolean/confirm token — pre-existing, both regimes) and **IG85** (A12's two renderings of one stored decimal, which a single stored text cannot both satisfy).

### Step 3 — dmtool CLI document I/O ✅ DONE (2026-07-28)

**Shipped: dmtool has no document codec of its own.** Both directions run through the interpreter's single implementation via the new model-level pair `ModelDef.readDocumentJson` / `Document.toDocumentJson` ([INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) §4.4, [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF227 follow-up). `DocInstance`, `InterpreterModelLoader.walkInstance`, `JsonInstanceSink`, and the adapter fixture guard's third near-identical writer are **deleted**. The `"fields"` wrapper is gone from the code, the published schema, `verb-examples.json`, both demos, the eval judges' prompts, and every test; a document that still carries it fails as unknown model path `/fields` **plus** the migration corrective — a clean break, never a silent unwrap. Contract text: [CLI-SPEC](CLI-SPEC.md) "`--instance` is A12's canonical Document JSON".

**The survey below was wrong on the one point that mattered, and the correction is the reusable lesson.** It concluded "no walker rewrite is needed — it is already the A12 shape", and pinned the only live defect on the CLI's Jackson mapper configuration. Both statements were true about the **shape** and blind to the **behaviour**: the walker stored the authored text verbatim, which is the kernel's V1 regime, not its Document-JSON regime. Measured through the `dmtool` binary on `/Order/Quantity` (`maxFractionalDigits: 0`) against the kernel rows of `A12JsonIngressKernelTest`:

| authored | dmtool before | A12's own ingress |
|---|---|---|
| `1e5` | `zahlHatUngueltigeZeichen` | stores `100000`, no code |
| `"+5"` | `zahlMitPlus` | stores `5`, no code |
| `"007"` | `zahlHatFuehrendeNullen` | stores `7`, no code |

The tool reported formal errors on documents A12 accepts silently. **No mapper setting could have fixed that** — the missing piece was step 2b's value normalization, which lived in the codec the CLI was not using. *Matching a shape in a second implementation is not the same as matching its behaviour; the only durable fix is one implementation.* Locked end-to-end by `cli … DocumentInstanceIngressTest`, which also re-expresses the deleted `DocInstanceNumberFidelityTest`'s scale/precision assertions at the boundary that now owns them (a beyond-double decimal reaches the formal check intact; a small decimal stays plain rather than scientific; an authored trailing zero survives — the last being the known IG85 residual, where the kernel's runtime strips it).

Three consequences of reading through the canonical codec, all deliberate. (1) The boundary is **strict where it was silent**: a declared group carrying a value (previously dropped, evaluating as if the group were absent), a field carrying an object, and an unknown key are now located by their canonical A12 pointer, and the decode ceilings apply. (2) An invalid instance stays on the plain-text `fail` channel (exit 2) that `cannot read model:` already uses — a structured `refused` envelope is for a precheck on a resolvable target, not for input that could not be read — so `diagnostic.schema.json` and the result envelope are untouched. (3) `model seed` output gains A12's canonical key order and native number/boolean values, and is pretty-printed by the CLI rather than by the codec, because A12 itself takes the pretty-printer as caller configuration.

**`skill/SKILL.md` needed no edit — verified, not assumed:** it never mentions `--instance`, `model eval`, `model compute`, `model seed`, or a document instance at all (greps over the whole file for each). It carries no claim about this shape that could go stale, so the #1 skill re-read rule was not triggered by this step.

<details>
<summary>The pre-work survey (2026-07-28), kept for its measurements — read the correction above first</summary>

1. **The reader already consumes A12's tree.** `InterpreterModelLoader.walkInstance` maps a JSON array to rows at index+1, a nested object to a group at repetition 1, `null`/`""` to `emptyField`, and any scalar to `field(value.toString())`. *(Shape-true, behaviour-blind — see the correction above.)*
2. **The `"fields"` wrapper is already optional in CODE but mandatory in the CONTRACT.** `DocInstance.readFields` falls back to the root object (`root.has("fields") ? … : root`), while `document-instance.schema.json` declares `required: ["fields"]` with `additionalProperties: false`. Accurate: the published schema and the demos were the bulk of the wrapper work.
3. **`model seed` already emits the tree shape.** `JsonInstanceSink` writes an array per repeatable group and pads short arrays with `{}`. The three named deltas (the wrapper, string-only values, an omitted empty key) were all real — and all disappeared rather than being re-implemented, because the canonical writer already consults the field kinds.
4. **The instance reader's mapper configuration.** *(Fixed in commit `4b144f89`, before this step.)* A **default `ObjectMapper`** builds a `DoubleNode`, and *both* `asText()` and `convertValue(...).toString()` then return damaged text — probe-measured on the pinned Jackson 2.18.2:

   | value `250.00` / `1234567890123456789012.5` | node | `asText()` | `convertValue` → `toString()` |
   |---|---|---|---|
   | **default mapper** | `DoubleNode` | `250.0` | `250.0`, and `1.2345678901234568E21` |
   | **kernel-configured mapper** | `DecimalNode` | `250.00` | `250.00`, and `1234567890123456789012.5` |

   The lever is `USE_BIG_DECIMAL_FOR_FLOATS` enabled **with `STRIP_TRAILING_BIGDECIMAL_ZEROES` disabled** — exactly `ObjectMapperProvider.createObjectMapperForDocSerializer()`, which the kernel annotates *"we want to preserve trailing zeroes and the scale of the value"*, and the same pair [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5 already requires for `dm.core.ModelReader`. The CLI no longer parses a document at all, so this now applies only to the kernel-side test harness (which keeps it) and to `dm.core.ModelReader`. Calling this *the* live defect was the survey's error: it was one, and not the one that changed evaluation.

   Note the direction that is **not** a problem: A12 does not serialize numbers as strings. `DocumentJsonAndXmlSerializationInfoImpl.toJsonFieldNode` emits `factory.numberNode(BigDecimal)` / `booleanNode`, so a real A12 document carries `"Grams": 250` unquoted, and the kernel's reader copes only because of the mapper configuration above.

</details>

### Step 4 — corpus conversion (after step 2, for coverage)

Note from step 2: the browser qualification now lifts corpus placements into A12 JSON in `consumer-tests/browser/src/qualification.ts` (`documentInput`) so it can replay through the public ingress. That converter is **transitional** — delete it when the corpus itself carries A12 JSON.


Not tidiness: once the ingress is A12 JSON, the corpus is the main regression net for a reader that didn't exist before. If cases keep building documents through the internal path, the new ingress is the least-tested part of the system.

**Holdout rule — narrower than first written.** Step 2 measured that A12's reader accepts an array at a *non-repeating* group, so a non-repeatable group's coordinate 2 **is** representable in A12 JSON and needs no holdout (the browser qualification already replays such cases through the public ingress). A holdout is justified only for a coordinate the shape genuinely cannot address — a repetition below 1, or the `UNKNOWN_REP` sentinel — and each one records its reason.

### Step 5 — audit the rest of the boundary

Every remaining consumer-visible type: match it to an A12 counterpart or record that none exists. Two known candidates:

- **`ValidationSeverity`** is `"error"|"warning"|"info"` while A12 model JSON writes `"severity": "ERROR"` — **unverified**, that casing is the rule *definition*, not the validation *result*; read A12's result types before claiming a divergence.
- **`KernelAdapter.evaluate` / `compute` / `testRule` take `Map<String, Object>`** — a nested field map, i.e. the A12 tree *decoded into Java collections* rather than A12 Document JSON. Left alone by step 3 on purpose (owner call: different boundary, no shipped surface at risk) — production `:cli` no longer parses a document, so the map's only remaining callers are the kernel-side test harness `KernelEval` and `InterpreterRealModelDiffTest`'s kernel leg, both of which feed it the *same* canonical text the interpreter leg reads. The honest A12 route for a kernel-backed adapter is the kernel's **own** `IDocumentV2Serializer.deserializeV2` (already used by `RuntimeLaws.jsonIngress`), which would also put the kernel leg of the differential on A12's real ingress regime instead of the V1 string regime. Decide it as part of this audit, not before.

### Step 6 — release 0.9.2

Follow [`INTERPRETER-RELEASE-SPEC`](INTERPRETER-RELEASE-SPEC.md). Breaking change: say so plainly in `interpreter/CHANGELOG.md`. Closes **IRG2** in [`INTERPRETER-RELEASE-GAPS`](INTERPRETER-RELEASE-GAPS.md) (the showcase semantics badge, built and gated but deliberately not deployed under v0.9.1) — delete that row in the same commit.

## Small item ✅ DONE

`adapter/.../FieldPointer.java`'s Javadoc no longer implies the render form is a free choice; it points at `A12PointerFormat` and the canonical string (landed with step 2, commit `f387f4fe`).
