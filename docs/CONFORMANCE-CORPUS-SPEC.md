# Conformance corpus — case format & runner contract

The portable, kernel-independent record of A12 document-model **runtime semantics as data**: each case is one JSON file holding a reference to a document model, one document instance in A12's canonical Document JSON, one operation, and the kernel-verified expected outcome — the models themselves are **standalone DM-JSON files** the cases share. Any engine that claims kernel-compatible evaluation — the in-repo interpreter (JVM and JS), a kernel codegen strategy, or a third-party reimplementation — demonstrates it by replaying the corpus and matching the expectations. The contract below is what a runner implements; the *why* behind the program lives in the [formalization proposal §7](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md).

Contract fixed 2026-07-06 (WI-3a); amended 2026-07-06 (owner): models moved out of the case files into standalone `models/*.json` referenced by `modelRef` — envelope-free, so they open in the A12 modeling tools and feed `dmtool` directly, and one model serves many cases. The reference capture harness and JVM runner are specified as obligations in [§13](#13-reference-implementation-obligations); the proposal's §4 tracks what is built.

## 1. Design tenets

| Tenet | Consequence |
|---|---|
| **Semantics as data, not as tests** | A case asserts observable behavior only: `code`, polarity type, pointer. No engine internals, no API shapes. |
| **DM-JSON is the key** | The model ships as a **standalone production DM-JSON file** (no wrapper envelope — openable in the A12 modeling tools, consumable by `dmtool` directly) with rules embedded as **stored-English condition strings** — so the corpus is a **parser conformance suite for free**: a runner that cannot parse a stored condition fails, it does not skip. |
| **The anchor is the kernel's Groovy-dynamic strategy** | `expected` records what the production-normative strategy does (the [proposal's](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md) Phase-0 finding S3). Another kernel strategy that differs is a recorded *divergence* ([§11](#11-strategy-divergences)), never the expectation. |
| **No message text** | Kernel message text is German-only and EUPL-licensed (interpreter finding IF3), and text is locale-bearing. The observation stops at `code|type|pointer` — the parts a consumer acts on. |
| **Deterministic by construction** | A single wall-clock instant when time is involved ([§9](#9-the-clock)), a fixed locale ([§10](#10-locale--language)), sorted-multiset comparison ([§6](#6-validation-expectations--signatures)) — a case has exactly one correct outcome. |

## 2. Corpus layout

```
corpus/
  README.md                    # how to consume; points here
  models/<name>.json           # standalone DM-JSON models, shared across cases
  cases/<family>/<id>.json     # one case per file
```

| Rule | Contract |
|---|---|
| **family** | kebab-case directory naming the primary semantic area (`aggregates`, `comparison`, `dates`, `iteration`, `partial`, `compute`, …). A grouping aid for partial engines; the normative taxonomy tag is `meta.sections`. |
| **id** | `meta.id` equals the file stem and is unique corpus-wide (guard-checked). |
| **model files** | `models/<name>.json` is **pure DM-JSON with no wrapper envelope** — that is what keeps it openable in the modeling tools and directly consumable by `dmtool`. Domain-named (`order-basic`), shared across cases, stable canonical formatting so shared-model diffs stay reviewable. Two **SME-openability** normalizations are guaranteed by the writer and guard-checked: (1) its **identity (`header.id` + `content.modelInfo.name`) equals its file-stem name**, so each model is a **uniquely-named workspace item** (models derived from one base would otherwise share the base's id and collide in the SME; the file-stem id matches the A12 model-name grammar and is unique corpus-wide); (2) every date-ish field's **`format` is one of the SME-canonical values** (`yyyy-MM-dd` / `yyyy-MM-dd'T'HH:mm:ss` / `HH:mm:ss`) — the runtime tolerates a localized format like `dd.MM.yyyy` in `format`, but the SME does not ([KERNEL-FINDINGS §6](KERNEL-FINDINGS.md)). **No orphans**: a model file lands with its first referencing case (guard-checked). |
| **file form** | UTF-8, LF, pretty-printed JSON. One case per file. |
| **content lane** | Everything committed here is **own-domain original material** (the project's license-safe rule: mechanism, never expression). Findings from live sweeps over EUPL/proprietary corpora are re-expressed in our own domain before they land here; nothing from those corpora is persisted. |

## 3. The case document

| Key | Required | Contract |
|---|---|---|
| `meta.id` | yes | Unique case id, equals the file stem. |
| `meta.kernelVersion` | yes | The kernel version the expectations were captured against (e.g. `"30.8.1"`). |
| `meta.source` | yes | `fuzz-seed` \| `curated` \| `facet-generated`. |
| `meta.sections` | yes | The kernel-semantics taxonomy sections this case exercises (e.g. `["§9"]` — the numbering [KERNEL-FINDINGS](KERNEL-FINDINGS.md) mirrors); the join key for the [semantics navigation map](SEMANTICS-MAP.md), whose resolve-guard cross-checks it. |
| `meta.tags` | no | Free labels (`partial`, `clock`, `phantom-row`, …). Cases with a `validatePart` op MUST carry `partial`. |
| `meta.seed`, `meta.scenario` | no | Provenance for `fuzz-seed` cases: the generator scenario + seed that produced the instance, so a case can be regenerated. |
| `meta.notes` | no | One human sentence on what the case pins. |
| `modelRef` | yes | Corpus-root-relative path of the case's model file (e.g. `"models/order-basic.json"`). The file is **expanded, self-contained DM-JSON** (includes resolved upstream by the kernel's `expand()`), valid at `meta.kernelVersion`, with rules/computations embedded as stored-English condition strings. |
| `clock` | iff needed | Present exactly when evaluation touches `Today`/`Now` — see [§9](#9-the-clock). |
| `document` | yes | The document instance as **A12's canonical Document JSON** — see [§4](#4-the-document). |
| `op` | yes | The operation to run — see [§8](#8-operations). |
| `expected` | for validate ops | Sorted signature multiset — see [§6](#6-validation-expectations--signatures). An **empty array is a real assertion** (nothing may fire). |
| `computeExpected` | for `compute` | Sorted compute-outcome multiset — see [§7](#7-compute-expectations). |
| `divergences` | no | Per-strategy expectation overrides — see [§11](#11-strategy-divergences). |

Illustrative example (shape-normative; the values are not a captured expectation — captured cases are the canonical examples):

```jsonc
{
  "meta": {
    "id": "aggregate-min-low-claim-001",
    "kernelVersion": "30.8.1",
    "source": "curated",
    "sections": ["§9"],
    "tags": ["aggregates"],
    "notes": "MinValue over one filled row, below the bound"
  },
  // models/policy-claims.json: a Policy document with a repeatable Claims group,
  // a scale-2 number Amount, and a rule CLAIM_MIN with error field Claims-star Amount
  // storing the English condition  MinValue(Claims*/Amount) < 50
  "modelRef": "models/policy-claims.json",
  "document": {
    "Policy": { "Claims": [ { "Amount": 20.00 } ] }
  },
  "op": { "kind": "validateFull" },
  "expected": [
    "CLAIM_MIN|VALUE_ERROR|/Policy[1]/Claims[1]/Amount"
  ]
}
```

## 4. The document

A case's instance is **A12's canonical Document JSON** — the shape the A12 Kernel's document serializer reads and writes, specified in [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "The canonical Document JSON and `DocumentPointer` string". A runner feeds it to its own public document reader; it does not translate it first.

| Construct | Encoding |
|---|---|
| a root group | a **top-level key** — there is no wrapper object |
| a non-repeating group | a nested object keyed by child name |
| a repeating group | an **array**, whose INDEX is the repetition; an unfilled row is `{}` |
| an omitted group or field | a **missing key** |
| a field present with no value | `null` |
| a field value | native JSON for NUMBER and BOOLEAN/CONFIRM, a model-format string for the date family, the S-value for an enumeration |

Rules a runner must honor:

- **The reader is deliberately permissive, and a case may rely on it.** An object at a repeating group is one row at repetition 1, and an **array at a non-repeating group is rows 1..n** — which is how a case addresses an over-repetition so the formal gate can report it. Both readings are A12's (`DocumentV2JsonReader.repetitionsFromJson` never consults repeatability).
- **`null` is not an omission.** It is a present-but-valueless cell, which is how a case distinguishes an *instantiated-empty* cell from an absent one (the content-bearing-row semantics of KERNEL-FINDINGS §2 / interpreter findings IF53–IF54 hang on this distinction). `""` reaches the same evaluation state while keeping its own distinct raw text.
- **Committed documents are canonical**, i.e. the codec's fixed point: array-versus-object follows *declared* repeatability, and direct fields precede subgroups with each sorted by name. Because the reader is permissive this cannot fail at replay time, so it is guarded directly (`interpreter … CorpusDocumentCanonicalTest`).
- Values are **stored-form**: a runner passes them through without re-formatting. Reading normalizes exactly as A12's own ingestion does (a number to its plain form, a boolean case-insensitively) — see [KERNEL-FINDINGS](KERNEL-FINDINGS.md); every committed value is already in that form, so replay is unaffected by it.

> **History.** Until 2026-07-28 a case carried `placements[]` — an ordered list of `{kind, path, reps, value}` records, the serialized `PlacementSink` protocol. That was a shape this project invented for a concept A12 already defines, and it meant the corpus exercised an *internal* construction path while the public reader — the part a third-party reimplementation must get right — went untested by the corpus. Step 4 of the A12 shape consolidation converted every case through the codec itself. The `PlacementSink` protocol remains, in its proper place: capture builds a scenario with it *before* a case exists, then serializes the resulting document canonically.

## 5. The pointer format

Pointers in `expected`, `computeExpected`, and `op.relevant` all use one rendering, the engines' shared `formatPointer` algorithm:

- Split the entity path on `/`; render each segment prefixed by `/`.
- Append `[n]` (1-based) to a segment when it is **not the last** segment and a repetition index exists for its position; the **terminal segment is never indexed**.
- Fewer indices than non-terminal segments is legal — the un-indexed segments stay bare (a *partially known* pointer, e.g. a model-level anchor).

| Pointer | Reads as |
|---|---|
| `/Policy[1]/Claims[2]/Amount` | field `Amount` in `Claims` row 2 under `Policy` row 1 |
| `/Policy[1]/Claims` | the `Claims` group under `Policy` row 1 (terminal never indexed) |
| `/Policy/Claims/Amount` | no row context — partially known |

**Phantom rows are legal pointers.** Partial validation can fire on a relevant row that does not exist in the instance (interpreter finding IF54) — e.g. `/Policy[1]/Claims[1]/Amount` against a document with zero `Claims` rows. A runner MUST NOT require a pointer to resolve against the instance.

## 6. Validation expectations — signatures

Each `expected` entry is one fired message reduced to a signature:

```
<code>|<type>|<pointer>
```

| Part | Contract |
|---|---|
| `code` | The message code, opaque: an authored rule code, or a kernel formal-error code. |
| `type` | The polarity: `VALUE_ERROR` \| `OMISSION_ERROR` (the kernel's `IMessage.MessageType` names; the interpreter's `MsgType` maps `OMISSION → OMISSION_ERROR`, everything else `VALUE_ERROR`). |
| `pointer` | The error-entity pointer per [§5](#5-the-pointer-format). |

Excluded by design: **message text** (license + locale, §1) and **severity** (derivable from the model — the rule's declared severity).

**Comparison rule:** the runner renders every fired message as a signature, sorts the list by UTF-16 code-unit order (the native string order of Java, Kotlin, and JS — identical across replay targets, and equal to code-point order for the BMP-only names models use), and compares for **list equality against `expected`**. Duplicates are significant (a multiset, not a set). No filtering, no tolerance.

## 7. Compute expectations

Each `computeExpected` entry is one computed-field outcome:

```
<pointer>|VALUE|<value>     — computed a value
<pointer>|CLEARED           — computed nothing (the kernel cleared the field)
<pointer>|ERRORED           — an operand/computation error
```

- `pointer` uses the [§5](#5-the-pointer-format) format **with row indices** — per-row identity is normative. (This deliberately supersedes the historical differential shortcut of stripping `[n]` indices before comparing, which could not distinguish the right value on the wrong row.)
- `value` is the **exact stored-form string** the engine writes into the document. **No numeric normalization**: the stored form is itself kernel semantics — the computed string lands in the document and feeds downstream rules — so `"100.5"` vs `"100.50"` is a real divergence, not noise.
- Sorting and multiset equality as in [§6](#6-validation-expectations--signatures).

**Reporting granularity (what `computeExpected` contains).** The kernel's `IDocumentComputationResult` is a **five-channel result** ([KERNEL-FINDINGS](KERNEL-FINDINGS.md) §11 five-channel supplement); the expectation records the **three-channel change projection** we select from it — not report-all:

| outcome | reported when |
|---|---|
| `VALUE` | the computed value **differs from the input document's** value at that cell — a *typed*-value equality (`getComputedFieldInstancesWithChanges`) |
| `CLEARED` | the input document had the cell **filled** (`getClearedFieldInstances`) |
| `ERRORED` | always (`getComputedFieldInstancesWithErrors`) |

Canonical cases reach each engine's Document JSON reader independently. A **report-all engine** must project against the original canonical document before comparing: drop a `VALUE` equal to its typed source identity, drop a `CLEARED` on an unfilled source cell, keep `ERRORED`. The reference projection is `conformance.kernelCanonicalComputeGranularity`, reusing `Document.computedValueEquals`, which also governs public `ComputationReport` actions. Number identity includes coefficient and scale, including negative scale (`NumberResultSourceIdentityLawsTest`); equal amounts or plain text alone do not settle it. `CorpusCaptureTest.captureUsesCanonicalNumberIdentityAndExplicitV1KeepsItsStringProfile` distinguishes exact-scale equality from a changed-scale source, and `compute/order-name-seed-equal` locks an unchanged String's empty expectation.

Explicit V1 placement scenarios retain their raw String ingress and `conformance.kernelComputeGranularity` projection. Their equal Number seeds remain changes because a computed BigDecimal is not the source String. Capture serializes a placement scenario first, then tri-verifies that canonical JSON; it cannot verify V1 input and store a different ingress contract. Existing committed anchors are preserved; a newly exposed discrepancy requires an explicit case-specific comparison decision rather than baseline regeneration.

## 8. Operations

| `op` | Shape | Expectation key |
|---|---|---|
| Full validation | `{ "kind": "validateFull" }` | `expected` |
| Partial validation | `{ "kind": "validatePart", "relevant": [ { "path": "/Policy/Claims", "reps": [1, 1] }, … ] }` | `expected` |
| Computation | `{ "kind": "compute" }` | `computeExpected` |

`validatePart` semantics a runner must honor:

- Each `relevant` entry is a **concrete instance pointer** (path + one 1-based index per segment, the addressed segment included).
- A relevant **group** row makes everything beneath it relevant (the kernel's group expansion ⇄ the interpreter's ancestor-prefix match).
- A relevant pointer **may be phantom** (name a row absent from the instance) — the kernel evaluates relevant instances regardless of content (IF54), which is exactly the partial-only semantics these cases exist to pin.
- Partial cases carry `meta.tags: ["partial", …]`.

## 9. The clock

| Rule | Contract |
|---|---|
| Form | `"clock": "2026-07-06T12:00:00"` — one local wall-clock instant, `yyyy-MM-dd'T'HH:mm:ss`, **no timezone**. |
| Presence | Present exactly when evaluation touches `Today`/`Now`. A case whose model uses neither carries no `clock`. |
| Semantics | `Today` = the instant's date part; `Now` = the instant. The instant is local wall time — no timezone conversion crosses the contract boundary; a runner treats it as its local time. |
| Runner inability | An engine that cannot inject a clock **skips** clock cases (a reported SKIP, [§12](#12-runner-obligations--verdicts)) — it must not evaluate them against its real clock. |

Engine mapping for the in-repo runners: the interpreter takes `EvalClock(today = date part, now = the instant)`; kernel replay pins both via `DocumentProcessingConfig.currentDateForTest` — which only the modern `DocumentV2` service path honors, so capture and kernel replay MUST route through the modern path (the legacy `validateFull(IDocument, Locale)` entry cannot pin the clock).

## 10. Locale & language

- Evaluation runs at a fixed `Locale.US`-equivalent. Locale can only reach message *text*, which the contract excludes — `code`/`type`/`pointer` are locale-independent.
- Stored conditions are **canonical English** regardless of authoring language (the kernel persists English keywords; see [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "stored conditions are canonical English"). A runner needs no German lexer.

## 11. Strategy divergences

`expected`/`computeExpected` always record the **Groovy-dynamic anchor**. This is the **module-wide ratified oracle** (INTERPRETER-FINDINGS IF136, owner-decided 2026-07-19): `interpreter/CLAUDE.md`'s #1 rules make the same choice — on a legal-input split the interpreter matches Groovy-dynamic, and a **static-Java** codegen split is recorded here, never adopted. When another kernel strategy observably differs on the case, that fact is recorded — never adopted:

```jsonc
"divergences": [
  { "strategy": "kernel-java-static", "expected": [ "…full replacement multiset…" ] }
]
```

- `strategy` ∈ `kernel-java-static` (the static-JAVA codegen; further strategies — `kernel-static-groovy`, `kernel-js` — reserved).
- The entry carries the **full replacement** expectation (same key as the op requires), not a diff.
- A runner replaying a listed strategy asserts the override; absence of an entry means the anchor expectation applies to that strategy too.
- A divergence entry is a **characterization of the kernel**, kept current by capture ([§13](#13-reference-implementation-obligations)); an engine matching the anchor never consults it.

## 12. Runner obligations & verdicts

A conformance runner, per case:

1. Resolve `modelRef` against the corpus root and parse the model file (DM-JSON **including its stored condition strings** — a parse failure is a FAIL, not a SKIP: parser conformance is in scope by design; an unresolvable `modelRef` is a corpus defect, not a runner verdict).
2. Read the `document` through the engine's own **public** document reader ([§4](#4-the-document)) — the reader is part of what the corpus tests, so a runner must not bypass it with an internal construction path.
3. Inject `clock` if present ([§9](#9-the-clock)).
4. Execute `op`, render the outcome as signatures ([§6](#6-validation-expectations--signatures)/[§7](#7-compute-expectations)), sort, compare.
5. Report one verdict: **PASS** (multiset equal) · **FAIL** (any difference) · **SKIP** (a named construct the engine declaredly does not support, with the reason).

A SKIP is never silent and never counts as a pass; corpus results tally PASS/FAIL/SKIP per family. An engine claiming full conformance has zero FAILs and zero SKIPs.

## 13. Reference implementation obligations

What the in-repo reference capture + runners must satisfy (beyond the runner contract above):

| Obligation | Detail |
|---|---|
| **Capture source** | The kernel Groovy-dynamic service (the `RuntimeLaws` harness in `:adapter`), routed through the modern `DocumentV2` + `DocumentProcessingConfig` path ([§9](#9-the-clock)). |
| **Tri-verification before commit** | Every case is verified `interpreter == kernel-groovy-dynamic == kernel-java-static` at capture time; a java-static split becomes a `divergences` entry, an interpreter split blocks the case (it is a bug to fix first). |
| **Serializable capture scenario** | The capture seam's placement record is promoted to a public serializable type (today `FuzzDiagnostics.Place`, private). Capture-side only: a committed case carries A12 Document JSON, so this shape never reaches the corpus. |
| **One ingress regime on both legs** | Canonical replay and capture feed the same Document JSON bytes independently to the interpreter's reader and A12's own serializer on both kernel strategies. Explicit V1 placement APIs remain available for laws/fuzzing with their existing projection. `CorpusReplayTest.canonicalFullAndPartialCasesUseIndependentKernelJsonIngress` distinguishes full and partial serializer routes from V1 input; all committed anchors remain guarded by `everyCaseReplaysThreeWays`. |
| **Per-row compute identity** | The interpreter exposes repetition indices on its compute outcomes (internally present as `ComputedInstance`), and kernel compute paths are normalized to the [§5](#5-the-pointer-format) form — replacing the index-strip comparison ([§7](#7-compute-expectations)). |
| **Build discipline** | Corpus-reading tests run in a dedicated Gradle task with the corpus files as **declared inputs** (the `docGuardTest` pattern, [TESTING-SPEC](TESTING-SPEC.md)) — no false green, no full-suite re-runs on a case edit. |
| **Schema & id guard** | A machine-readable case schema (`corpus/case.schema.json`) plus a guard: every case validates, `meta.id` equals the file stem, ids unique, `partial` tag ⇔ `validatePart`, every `modelRef` resolves, every model file loads as a model, its `header.id` equals the file stem (unique workspace-item name), its date-field `format`s are SME-canonical, and no model file is orphaned. |
| **Kernel-upgrade guardian** | The corpus registers in the [KERNEL-UPGRADE](KERNEL-UPGRADE.md) coupling map: on a kernel bump, re-capture and diff — the diff **is** the behavior-change characterization; `meta.kernelVersion` moves with it. |

## 14. Licensing & publication

The committed corpus is own-domain original material under the project license; captured expectations are behavioral facts about the kernel (mechanism, not expression). Nothing from the EUPL `a12-kernel-mm` metamodels or the proprietary A12 Tools workspaces is persisted here — live-sweep findings are minimized and re-expressed in our own domain first. Publishing the corpus beyond this repository stays **open-with-trigger** (was fork F2 of the [archived program](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md)): publish **when an external consumer materializes**, and only after re-confirming provenance against the [licensing decision record](LICENSING.md).
