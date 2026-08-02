# A12 shape consolidation — the migration plan

> **ARCHIVED 2026-07-28 — this plan is complete and is kept only for its record of how the migration went wrong and was corrected.** Do not read it as a contract: every durable statement has graduated to its owning home — the format spec to [KERNEL-FINDINGS](../KERNEL-FINDINGS.md) "The canonical Document JSON and `DocumentPointer` string", the boundary contracts to [INTERPRETER-API-SPEC](../INTERPRETER-API-SPEC.md) §4, [CLI-SPEC](../CLI-SPEC.md) and [CONFORMANCE-CORPUS-SPEC](../CONFORMANCE-CORPUS-SPEC.md) §4, the standing rule to [CLAUDE.md](../../CLAUDE.md), and the full account to [INTERPRETER-FINDINGS](../INTERPRETER-FINDINGS.md) IF227–IF230.
>
> **What outlived the plan, and what turned out not to.** The 0.9.2 release (plan step 6) is an ordinary release tracked by [INTERPRETER-RELEASE-SPEC](../INTERPRETER-RELEASE-SPEC.md) under [IRG2](../INTERPRETER-GAP-ID-REGISTRY.md#irg2) — a release does not need a migration plan to hold it open. Step 5's kernel-side ingress paths were **first filed as a gap (IG86) and that was wrong**: both sites are test-harness only, in an unpublished module with no production caller, so no user or dev document flows through either; and the cost claimed for them — that an ingress divergence could escape the differential — is false, because `A12JsonIngressDiffTest` compares A12's real `deserializeV2` ingress against ours directly. It is a harness-maturity item and now lives as one row in [CONFORMANCE-CORPUS-SPEC](../CONFORMANCE-CORPUS-SPEC.md) §13. Recorded here because minting a gap to give a retiring plan somewhere to put its leftovers is a tempting and specific mistake.
>
> **Why it is archived rather than deleted** (its own header said "deleted when the last step lands"): the reusable content here is not the plan but the *corrections* — a survey that was shape-true and behaviour-blind, a holdout mechanism budgeted and then measured away, and a mapper matched for one concern. Those are the lessons the next boundary migration should read first.


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

The malformed pass-through survives ([A12K-3638]), and String/Enumeration stay untouched. New locks: `adapter.laws.A12JsonIngressKernelTest` (the kernel's own ingress, 49 rows) and `A12JsonIngressDiffTest` (ours against it, with an exhaustive named residual). Two gaps were opened by the measurement rather than by the change: **IG84** (no formal error for a malformed boolean/confirm token — pre-existing, both regimes; **since fixed as [IF229](INTERPRETER-FINDINGS.md), whose probe also found the kernel's formal check to be case-SENSITIVE, correcting the gap row's own aside**) and **IG85** (A12's two renderings of one stored decimal, which a single stored text cannot both satisfy).

### Step 3 — dmtool CLI document I/O ✅ DONE (2026-07-28)

**Shipped: dmtool has no document codec of its own.** Both directions run through the interpreter's single implementation via the new model-level pair `ModelDef.readDocumentJson` / `Document.toDocumentJson` ([INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) §4.4, [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF227 follow-up). `DocInstance`, `InterpreterModelLoader.walkInstance`, `JsonInstanceSink`, and the adapter fixture guard's third near-identical writer are **deleted**. The `"fields"` wrapper is gone from the code, the published schema, `verb-examples.json`, both demos, the eval judges' prompts, and every test; a document that still carries it fails as unknown model path `/fields` **plus** the migration corrective — a clean break, never a silent unwrap. Contract text: [CLI-SPEC](CLI-SPEC.md) "`--instance` is A12's canonical Document JSON".

**The survey below was wrong on the one point that mattered, and the correction is the reusable lesson.** It concluded "no walker rewrite is needed — it is already the A12 shape", and pinned the only live defect on the CLI's Jackson mapper configuration. Both statements were true about the **shape** and blind to the **behaviour**: the walker stored the authored text verbatim, which is the kernel's V1 regime, not its Document-JSON regime. Measured through the `dmtool` binary on `/Order/Quantity` (`maxFractionalDigits: 0`) against the kernel rows of `A12JsonIngressKernelTest`:

| authored | dmtool before | A12's own ingress |
|---|---|---|
| `1e5` | `zahlHatUngueltigeZeichen` | stores `100000`, no code |
| `"+5"` | `zahlMitPlus` | stores `5`, no code |
| `"007"` | `zahlHatFuehrendeNullen` | stores `7`, no code |

The tool reported formal errors on documents A12 accepts silently. **No mapper setting could have fixed that** — the missing piece was step 2b's value normalization, which lived in the codec the CLI was not using. *Matching a shape in a second implementation is not the same as matching its behaviour; the only durable fix is one implementation.* Locked end-to-end by `cli … DocumentInstanceIngressTest`, which also re-expresses the deleted `DocInstanceNumberFidelityTest`'s scale/precision assertions at the boundary that now owns them (a beyond-double decimal reaches the formal check intact; a small decimal stays plain rather than scientific; an authored trailing zero survives — the last being the known IG85 residual, where the kernel's runtime strips it).

**A follow-up correction (2026-07-28, [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF228): the retired mapper carried a SECOND concern, and that one did leave with it.** "No mapper setting could have fixed that" is right about the number rows and wrong as a verdict on the mapper, because `createObjectMapperForDocSerializer()` also enables `ALLOW_COMMENTS` — and so does A12's *model* mapper. The interpreter's codec parses on kotlinx's **default** `Json`, which refuses a comment, so consolidating onto it made a commented document unreadable where dmtool had read one; the deleted `DocInstanceNumberFidelityTest.commentsAreAcceptedBecauseA12AcceptsThem` had been the only thing holding that. Fixed at the root as one shared `A12_JSON` behind all six A12-shape parse sites (the model-source half was pre-existing and undocumented). **The procedural lesson: when a consolidation deletes a test class, the checklist is the full inventory of what that class locked, not the subset the change was about** — the number rows were re-expressed faithfully and this row was simply not on anyone's list.

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

### Step 4 — corpus conversion ✅ DONE (2026-07-28)

**Shipped: every committed case carries `document`, A12's canonical Document JSON, and the transitional converter is deleted.** The corpus used to store `placements[]`, so a runner built the instance through an *internal* construction path — meaning the corpus, the artifact a third-party reimplementation replays, exercised everything except the **public document reader**. Rationale + the full account: [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF230; the case contract is [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md) §4.

- **Migrated through the codec**, not a converter: each case's placements were replayed on the internal path and re-encoded with `toDocumentJson`, so the committed bytes are what A12's own writer produces (and array-vs-object comes from *declared* repeatability, which a hand-rolled converter cannot know).
- **The holdout mechanism was never needed.** A scan of all 34 cases found **zero** unaddressable coordinates — no repetition below 1, no `UNKNOWN_REP` — so the narrowed rule below collapsed to nothing. Recorded because the plan budgeted for it.
- **`consumer-tests/browser/src/qualification.ts`**: `documentInput` and the `CorpusPlacement` type are deleted; the reader receives the committed bytes verbatim. Its `EMPTY → ""` approximation is corrected to A12's `null`.
- **`PlacementSink` stays, in its proper place** — capture builds a scenario before a case exists, then serializes canonically (`documentJsonOf`). The kernel legs of the differential still take placement records (`placementsForKernelLeg`); moving them onto A12's own `deserializeV2` is step 5, not a residual here.
- **New standing guard**: `CorpusDocumentCanonicalTest` — every committed document is the codec's fixed point. Needed because the reader is permissive, so a non-canonical encoding would replay green and drift invisibly.

**Verification.** Interpreter Kotlin on JVM and Kotlin/JS; the corpus replay through the real kernel (all 34 cases, tri-engine); the full `:adapter:test` estate including the timing floors; `:cli:test`; the corpus schema, semantics-map, and doc guards; and `:interpreter:check` — whose browser lane reported *"Packed interpreter browser qualification passed: 33 corpus cases"*, replaying the corpus in Chrome against the packed artifact through the rewritten `qualification.ts`. (That lane failed twice with `ECONNRESET` against `registry.npmjs.org` before passing. The cause was **network access withdrawn in the environment**, established by the maintainer telling us so — not an inference. Worth recording because the landing session first wrote it up as "a flaky registry", which was a mechanism invented to explain an observation: `ECONNRESET` is equally consistent with no network at all, and nothing distinguished the two. **A browser-lane install failure means "the dependency fetch did not succeed" and nothing more — check whether the environment has network before theorising about the registry.**)

### Step 5 — audit the rest of the boundary

**Partly done (2026-07-28).** `ValidationSeverity` is settled and the audit found a defect nobody had listed:

- **`ValidationSeverity` — NO divergence, verified.** The Kotlin enum is `{ERROR, WARNING, INFO}`, exactly A12's `IMessage.Severity{ERROR, WARNING, INFO}`, name for name. The suspicion below was about the TypeScript surface's lowercase string union — and A12 ships **no** TS severity type (searched `kernel-ts` and the doc snippets), so there is no counterpart to match and a TS-idiomatic union is a legitimate own-surface choice. `ValidationFindingType{VALUE, OMISSION}` maps 1:1 to A12's `MessageType{VALUE_ERROR, OMISSION_ERROR}`; we drop `_ERROR` deliberately, because severity is an independent axis and `VALUE_ERROR` reads wrong on a `WARNING`.
- **The audit's real find: a default Jackson mapper on every model-rewriting surface.** Not a type-shape divergence but a *behaviour* one, and it corrupted user data — an unrelated `field add` rewrote a pre-existing `minValue: 0.50` to `0.5`. Fixed at all four sites; rationale in [API-FINDINGS](API-FINDINGS.md) finding 103, the comment half in [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF228. **The lesson for the rest of this audit: match A12 on I/O *behaviour*, not only on type shape — the boundary types were fine and the parsing underneath them was not.**

Still open — every remaining consumer-visible type: match it to an A12 counterpart or record that none exists.

- **`KernelAdapter.evaluate` / `compute` / `testRule` take `Map<String, Object>`** — a nested field map, i.e. the A12 tree *decoded into Java collections* rather than A12 Document JSON. Left alone by step 3 on purpose (owner call: different boundary, no shipped surface at risk) — production `:cli` no longer parses a document, so the map's only remaining callers are the kernel-side test harness `KernelEval` and `InterpreterRealModelDiffTest`'s kernel leg, both of which feed it the *same* canonical text the interpreter leg reads. The honest A12 route for a kernel-backed adapter is the kernel's **own** `IDocumentV2Serializer.deserializeV2` (already used by `RuntimeLaws.jsonIngress`), which would also put the kernel leg of the differential on A12's real ingress regime instead of the V1 string regime. Decide it as part of this audit, not before.

### Step 6 — release 0.9.2

Follow [`INTERPRETER-RELEASE-SPEC`](../INTERPRETER-RELEASE-SPEC.md). Breaking change: say so plainly in `interpreter/CHANGELOG.md`. Closes [IRG2](../INTERPRETER-GAP-ID-REGISTRY.md#irg2) (the showcase semantics badge, built and gated but deliberately not deployed under v0.9.1) — delete that row in the same commit.

## Small item ✅ DONE

`adapter/.../FieldPointer.java`'s Javadoc no longer implies the render form is a free choice; it points at `A12PointerFormat` and the canonical string (landed with step 2, commit `f387f4fe`).
