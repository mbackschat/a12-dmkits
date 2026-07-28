# A12 shape consolidation — the migration plan

**Goal.** Every document JSON at an **official API/CLI boundary** is A12's canonical shape. Owner directive, 2026-07-28: *"OUR PROJECT MUST NOT INVENT ITS OWN SHAPE"*, scoped to *"whenever document JSON is at official API/CLI boundary"*. The standing rule lives in [`../CLAUDE.md`](../CLAUDE.md) ("NEVER invent a shape A12 already defines"); the format spec lives in [`KERNEL-FINDINGS`](KERNEL-FINDINGS.md) ("The canonical Document JSON and `DocumentPointer` string"). **Read both before starting.** This document is only the ordered work plan; it is deleted when the last step lands.

## What was wrong

Five shapes for one A12 concept, found by sweep on 2026-07-28:

| Where | Invented shape | In scope? |
|---|---|---|
| `:interpreter` public API | `DocumentInputV1{groups:[{path,coordinates}], fields:[{path,coordinates,raw}]}` | **yes** — boundary |
| `:cli` (dmtool) `DocInstance` | `{"fields":{…}}` wrapper, text-only values | **yes** — boundary |
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

### Step 3 — dmtool CLI document I/O

- `cli/.../DocInstance.java` — drop the `"fields"` wrapper (A12 has no wrapper; root group names are top-level keys), accept native JSON number/boolean.
- `model seed` must **emit** the A12 shape.
- Update every `examples/` demo and its expected output, then `./scripts/verify-demos.sh`.
- Update the JSON schemas + their CI-executed `examples` blocks, and `skill/SKILL.md` if it shows a document (re-read the whole skill and verify every claim through the binary — the standing #1 skill rule).

**Surveyed 2026-07-28 (source-read, before any edit) — the CLI is much closer to A12 than the sweep suggested, and the risk sits somewhere else than expected:**

1. **The reader already consumes A12's tree.** `InterpreterModelLoader.walkInstance` maps a JSON array to rows at index+1, a nested object to a group at repetition 1, `null`/`""` to `emptyField`, and any scalar to `field(value.toString())`. No walker rewrite is needed — it is already the A12 shape.
2. **The `"fields"` wrapper is already optional in CODE but mandatory in the CONTRACT.** `DocInstance.readFields` falls back to the root object (`root.has("fields") ? … : root`), while `document-instance.schema.json` declares `required: ["fields"]` with `additionalProperties: false`. So the code delta is deleting one fallback; the real change is the published schema (whose `examples` block is CI-executed) and the demos.
3. **`model seed` already emits the tree shape.** `JsonInstanceSink` writes an array per repeatable group and pads short arrays with `{}` — canonical already. Three deltas remain: `result()` adds the wrapper; `field()` always writes a **string** (A12 writes native JSON for number/boolean, so the sink needs the field **kinds**, which it is not given today — it receives only `repeatableGroups`); and `emptyField()` **omits** the key where A12 writes `null` for a present-but-valueless cell.
4. **A LIVE scale-loss defect in the instance reader — fix it in the same step.** *(Corrected 2026-07-28: first written as a latent trap that step 3 "would activate", with the fix pinned on the wrong line. Probe-measured on the pinned Jackson 2.18.2 — see the table below — it is neither latent nor located where I said.)* `DocInstance` parses with a **default `ObjectMapper`**, and nothing in the CLI rejects an unquoted number, so a document that already writes `"Quantity": 5.50` today reaches the document as `"5.5"`. The loss happens at **`readTree`**, not at the `convertValue` step: a default mapper builds a `DoubleNode`, and *both* `asText()` and `convertValue(...).toString()` then return the damaged text. Trailing zeros are the mild case; a decimal beyond double precision is mangled outright.

   | value `250.00` / `1234567890123456789012.5` | node | `asText()` | `convertValue` → `toString()` |
   |---|---|---|---|
   | **default mapper** (today's `DocInstance`) | `DoubleNode` | `250.0` | `250.0`, and `1.2345678901234568E21` |
   | **kernel-configured mapper** | `DecimalNode` | `250.00` | `250.00`, and `1234567890123456789012.5` |

   So the **only** lever is the mapper configuration — `USE_BIG_DECIMAL_FOR_FLOATS` enabled **with `STRIP_TRAILING_BIGDECIMAL_ZEROES` disabled**. That is exactly `ObjectMapperProvider.createObjectMapperForDocSerializer()`, which the kernel annotates *"we want to preserve trailing zeroes and the scale of the value"*, and the same pair [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §5 already requires for `dm.core.ModelReader`. Choosing `asText()` over `convertValue` is **not** an alternative fix and does nothing on its own. Lock it with a value of `250.00` on a scale-2 field plus one beyond double precision; integer-only cases cannot see either failure. *(Basis: Jackson probe on the 2.18.2 pin + source read of the three-step `readTree` → `convertValue` → `child.toString()` path. Not yet reproduced end-to-end through the `dmtool` binary — the confirming step for whoever lands step 3.)*

   Note the direction that is **not** a problem: A12 does not serialize numbers as strings. `DocumentJsonAndXmlSerializationInfoImpl.toJsonFieldNode` emits `factory.numberNode(BigDecimal)` / `booleanNode`, so a real A12 document carries `"Grams": 250` unquoted, and the kernel's reader copes only because of the mapper configuration above.
5. `skill/SKILL.md` shows **no** document instance (no `"fields"` occurrence), so the skill's #1 re-read rule may not be triggered by this step at all — verify before assuming it is.

### Step 4 — corpus conversion (after step 2, for coverage)

Note from step 2: the browser qualification now lifts corpus placements into A12 JSON in `consumer-tests/browser/src/qualification.ts` (`documentInput`) so it can replay through the public ingress. That converter is **transitional** — delete it when the corpus itself carries A12 JSON.


Not tidiness: once the ingress is A12 JSON, the corpus is the main regression net for a reader that didn't exist before. If cases keep building documents through the internal path, the new ingress is the least-tested part of the system.

**Holdout rule — narrower than first written.** Step 2 measured that A12's reader accepts an array at a *non-repeating* group, so a non-repeatable group's coordinate 2 **is** representable in A12 JSON and needs no holdout (the browser qualification already replays such cases through the public ingress). A holdout is justified only for a coordinate the shape genuinely cannot address — a repetition below 1, or the `UNKNOWN_REP` sentinel — and each one records its reason.

### Step 5 — audit the rest of the boundary

Every remaining consumer-visible type: match it to an A12 counterpart or record that none exists. Known candidate: `ValidationSeverity` is `"error"|"warning"|"info"` while A12 model JSON writes `"severity": "ERROR"` — **unverified**, that casing is the rule *definition*, not the validation *result*; read A12's result types before claiming a divergence.

### Step 6 — release 0.9.2

Follow [`INTERPRETER-RELEASE-SPEC`](INTERPRETER-RELEASE-SPEC.md). Breaking change: say so plainly in `interpreter/CHANGELOG.md`. Closes **IRG2** in [`INTERPRETER-RELEASE-GAPS`](INTERPRETER-RELEASE-GAPS.md) (the showcase semantics badge, built and gated but deliberately not deployed under v0.9.1) — delete that row in the same commit.

## Small item, do it anywhere

`adapter/.../FieldPointer.java`'s Javadoc says *"the consumer renders this however it likes (e.g. `/Order/Items[2]/Count`)"*. A canonical form now exists; point at it instead of implying free choice. Doc line only — do not refactor the record.
