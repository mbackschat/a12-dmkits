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

### Step 2 — interpreter API reads and writes A12 Document JSON

- **Add** `A12DocumentJson.read(model, json, limits): Document` in `commonMain`. Walk the object tree; `ModelAddressValidation.entityKind(model, path)` decides group vs field; `JsonObject` → one row at repetition 1; `JsonArray` → index *i* is repetition *i+1*; `{}` → present-but-unfilled row; field value → `JsonPrimitive.content` so `250.00` survives as `250.00` (matches the kernel's `asText()`); missing key → omitted. Feed the **existing** back end unchanged: `document.group(path, *coords)` / `document.field(path, raw, *coords)`.
- **Add** the writer, model-driven: array vs object comes from declared repeatability, not from what happens to be populated.
- **Delete** `DocumentInputV1` / `GroupPlacementInput` / `FieldPlacementInput` — clean cut, no deprecated bridge (owner: fail-fast over hidden shims; and 0.x permits it).
- **Update** `TypeScriptBridge.kt`, `interpreter/typescript/src/documents.ts`, the 5 consumer-test suites, and the browser showcase (`consumer-tests/browser/src/{main,performance,qualification}.ts`).
- **Regenerate** the API goldens (`./build.sh dumpKitApi` for the kits; the interpreter goldens under `interpreter/api/`) and review the diff.

**Two open questions to settle first, by measurement not assumption:**

1. **`Document` read accessors** — the test needs to assert what was read. Find the real accessors before writing assertions (a draft test invented `rawAt`/`hasGroupAt`; they may not exist).
2. **`JsonNull` mapping** — the kernel keeps a null-valued field (`FieldInstanceV2.ofValue(null)` after `value.isNull() ? null : asText()`), but our `Document.field(path, value, …)` takes a non-null `String`. Verify against the kernel rather than guessing; note `DocumentDeserializationConfig.readEmptyAsNull` (deprecated) is adjacent.

### Step 3 — dmtool CLI document I/O

- `cli/.../DocInstance.java` — drop the `"fields"` wrapper (A12 has no wrapper; root group names are top-level keys), accept native JSON number/boolean.
- `model seed` must **emit** the A12 shape.
- Update every `examples/` demo and its expected output, then `./scripts/verify-demos.sh`.
- Update the JSON schemas + their CI-executed `examples` blocks, and `skill/SKILL.md` if it shows a document (re-read the whole skill and verify every claim through the binary — the standing #1 skill rule).

### Step 4 — corpus conversion (after step 2, for coverage)

Not tidiness: once the ingress is A12 JSON, the corpus is the main regression net for a reader that didn't exist before. If cases keep building documents through the internal path, the new ingress is the least-tested part of the system. **Holdout rule:** keep placement form for cases that deliberately test input A12 cannot represent (out-of-range coordinates, e.g. coordinate 2 on a non-repeatable group), and record the reason per holdout.

### Step 5 — audit the rest of the boundary

Every remaining consumer-visible type: match it to an A12 counterpart or record that none exists. Known candidate: `ValidationSeverity` is `"error"|"warning"|"info"` while A12 model JSON writes `"severity": "ERROR"` — **unverified**, that casing is the rule *definition*, not the validation *result*; read A12's result types before claiming a divergence.

### Step 6 — release 0.9.2

Follow [`INTERPRETER-RELEASE-SPEC`](INTERPRETER-RELEASE-SPEC.md). Breaking change: say so plainly in `interpreter/CHANGELOG.md`. Closes **IRG2** in [`INTERPRETER-RELEASE-GAPS`](INTERPRETER-RELEASE-GAPS.md) (the showcase semantics badge, built and gated but deliberately not deployed under v0.9.1) — delete that row in the same commit.

## Small item, do it anywhere

`adapter/.../FieldPointer.java`'s Javadoc says *"the consumer renders this however it likes (e.g. `/Order/Items[2]/Count`)"*. A canonical form now exists; point at it instead of implying free choice. Doc line only — do not refactor the record.
