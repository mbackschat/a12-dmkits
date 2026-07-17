# Conformance corpus — case format & runner contract

The portable, kernel-independent record of A12 document-model **runtime semantics as data**: each case is one JSON file holding a reference to a document model, one document instance, one operation, and the kernel-verified expected outcome — the models themselves are **standalone DM-JSON files** the cases share. Any engine that claims kernel-compatible evaluation — the in-repo interpreter (JVM and JS), a kernel codegen strategy, or a third-party reimplementation — demonstrates it by replaying the corpus and matching the expectations. The contract below is what a runner implements; the *why* behind the program lives in the [formalization proposal §7](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md).

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
| `placements` | yes | The document instance as an ordered placement list — see [§4](#4-placements). |
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
  "placements": [
    { "kind": "GROUP", "path": "/Policy", "reps": [1] },
    { "kind": "GROUP", "path": "/Policy/Claims", "reps": [1, 1] },
    { "kind": "FIELD", "path": "/Policy/Claims/Amount", "reps": [1, 1, 1], "value": "20.00" }
  ],
  "op": { "kind": "validateFull" },
  "expected": [
    "CLAIM_MIN|VALUE_ERROR|/Policy[1]/Claims[1]/Amount"
  ]
}
```

## 4. Placements

A placement list is the serialized form of the engine-neutral `PlacementSink` protocol (`interpreter/…/gen/PlacementSink.kt`): one group row or field cell at a time, each addressed by **one 1-based repetition index per path segment — the addressed segment included**.

| `kind` | Meaning | `reps` | `value` |
|---|---|---|---|
| `GROUP` | Instantiate a group row | One index per path segment; the last index is the new row's | — |
| `FIELD` | A field cell with a value | One index per path segment (the field's own trailing index is `1` — fields do not repeat) | The stored-form value string |
| `EMPTY` | A **present-but-valueless** field cell | As `FIELD` | — |

Rules a runner must honor:

- **Every group row is placed explicitly — the root and non-repeatable groups included** (their single row carries index `1`). A case's placements are self-contained; nothing is implied.
- **Order matters: parent row before its contents.** A `GROUP` placement precedes every placement addressed beneath that row. Replay is sequential.
- `EMPTY` materializes the field instance without a value — it is how a case distinguishes an *instantiated-empty* cell from an absent one (the content-bearing-row semantics of KERNEL-FINDINGS §2 / interpreter findings IF53–IF54 hang on this distinction).
- `value` is the stored-form string exactly as a document holds it (`"20.00"`, `"13.07.2026"`, `"true"`, an enum key, …). Runners pass it through verbatim; no re-formatting.

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

**Reporting granularity (what `computeExpected` contains).** The expectation records what the kernel's `IDocumentComputationResult` *reports*, which is **delta-vs-input**, not report-all:

| outcome | reported when |
|---|---|
| `VALUE` | the computed value **differs from the input document's** value at that cell — a *typed*-value equality (`getComputedFieldInstancesWithChanges`) |
| `CLEARED` | the input document had the cell **filled** (`getClearedFieldInstances`) |
| `ERRORED` | always (`getComputedFieldInstancesWithErrors`) |

On a placement-built input the cell values are raw strings, so the typed change-equality can hold only for a **STRING-kind** computed target (a number/date/boolean computes to a typed value, never equal to the raw seed string) — kernel-probe-verified, [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "compute reporting granularity". A **report-all engine** (the interpreter reports every computed instance — its library contract) must **project its outcome to this granularity before comparing**: drop a `VALUE` equal to the cell's placement value when the target is string-kind, drop a `CLEARED` on a cell no placement filled, keep `ERRORED`. The reference projection is `conformance.kernelComputeGranularity`; the committed guard case is `compute/order-name-seed-equal` (a computed STRING equal to its seed → an **empty** `computeExpected` that must still PASS).

## 8. Operations

| `op` | Shape | Expectation key |
|---|---|---|
| Full validation | `{ "kind": "validateFull" }` | `expected` |
| Partial validation | `{ "kind": "validatePart", "relevant": [ { "path": "/Policy/Claims", "reps": [1, 1] }, … ] }` | `expected` |
| Computation | `{ "kind": "compute" }` | `computeExpected` |

`validatePart` semantics a runner must honor:

- Each `relevant` entry is a **concrete instance pointer** in placement addressing (path + one index per segment, as [§4](#4-placements)).
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

`expected`/`computeExpected` always record the **Groovy-dynamic anchor**. When another kernel strategy observably differs on the case, that fact is recorded — never adopted:

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
2. Build the instance by replaying `placements` in order ([§4](#4-placements)).
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
| **Serializable placements** | The capture seam's placement record is promoted to a public serializable type (today `FuzzDiagnostics.Place`, private). |
| **Per-row compute identity** | The interpreter exposes repetition indices on its compute outcomes (internally present as `ComputedInstance`), and kernel compute paths are normalized to the [§5](#5-the-pointer-format) form — replacing the index-strip comparison ([§7](#7-compute-expectations)). |
| **Build discipline** | Corpus-reading tests run in a dedicated Gradle task with the corpus files as **declared inputs** (the `docGuardTest` pattern, [TESTING-SPEC](TESTING-SPEC.md)) — no false green, no full-suite re-runs on a case edit. |
| **Schema & id guard** | A machine-readable case schema (`corpus/case.schema.json`) plus a guard: every case validates, `meta.id` equals the file stem, ids unique, `partial` tag ⇔ `validatePart`, every `modelRef` resolves, every model file loads as a model, its `header.id` equals the file stem (unique workspace-item name), its date-field `format`s are SME-canonical, and no model file is orphaned. |
| **Kernel-upgrade guardian** | The corpus registers in the [KERNEL-UPGRADE](KERNEL-UPGRADE.md) coupling map: on a kernel bump, re-capture and diff — the diff **is** the behavior-change characterization; `meta.kernelVersion` moves with it. |

## 14. Licensing & publication

The committed corpus is own-domain original material under the project license; captured expectations are behavioral facts about the kernel (mechanism, not expression). Nothing from the EUPL `a12-kernel-mm` metamodels or the proprietary A12 Tools workspaces is persisted here — live-sweep findings are minimized and re-expressed in our own domain first. Publishing the corpus beyond this repository stays **open-with-trigger** (was fork F2 of the [archived program](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md)): publish **when an external consumer materializes**, and only after re-confirming provenance against the [licensing decision record](LICENSING.md).

## 15. The portable evidence-capture boundary

The source-maintained capture capability adopted from [`A12-DMKITS-CAPTURE-PROPOSAL.md`](A12-DMKITS-CAPTURE-PROPOSAL.md): a consumer (a12-kernel-lean) authors a closed scenario set — models, instances, operations, probes, a predeclared observation requirement, and **no expected behavior** — and the tool executes it through the registered runners into one deterministic, language-neutral **evidence packet**; separate named policies later classify the same packet without rewriting it. The packet — never a kernel-linked API — is the cross-project interface. Observation is separated from judgment by design: capture records what every runner exposed; a verifier decides whether a named projection satisfies a named policy.

**Placement.** Adapter-internal developer tooling: the `capture` source set inside `:adapter` (Gradle-invoked, `io.github.mbackschat.a12.dm.adapter.capture.*`), with **no published component** — not a dmtool verb, not in dmtool-release, not on the interpreter's dependency surface. The self-tests live in `:adapter`'s test source set and drive the real command entry.

### 15a. Schema identities (immutable, closed)

| id | what it names |
|---|---|
| `capture-scenario-set-v1` | the consumer request: scenario-set identity/version, `compute-observation-v1` as the one operation schema, the pinned capability identity, the `local-wallclock-v1` world profile (locale `en_US`; per-case wall-clock instant; timezone stays runner-local allowlisted identity), digest-bound model refs, required runners, the qualification policy, the predeclared observation requirement (channels + comparison projections), and explicit cases (placements in corpus placement vocabulary + probe pointers). |
| `evidence-packet-v1` | the packet envelope (`PACKET.json`): schema ids, the digest-referenced in-packet capabilities copy, the kernel behavior anchor **plus the explicit resolved closure** (every semantics-affecting artifact — the kernel groups + the Groovy runtime — coordinate/version/SHA-256), capture source state + **runtime identity** (Java specification + exact runtime version, vendor, VM — which also binds the java-static route's in-memory `javac`) + the recorded closure justification, scenario identity + request digest, world, runner descriptors, input digests (supplied AND adapted), legality artifacts, per-case observation refs + statuses. |
| `compute-observation-v1` | one runner's per-case observation: the six channels (`withoutErrors` · `changedSubset` · `withErrors` · `cleared` · `formalErrorsInOperands` · `appliedState`), each multiplicity-preserving and enumeration-order-recording, typed `valueV2` and rendered form as separate fields, per-channel/per-field availability (`available`/`notExposedByRunner`/`notApplicable`) — plus the **consumed-document binding**: the count + canonical SHA-256 of the placement stream the runner ACTUALLY applied, recorded at its own document-building boundary (`CaseDocumentIdentity`; each placement recorded AS it is fed to the engine, never copied from the request). The verifier recomputes the supplied identity from the copied request and requires supplied == consumed (v1 adapts nothing), so a placement dropped or changed during placement-to-document adaptation is caught even when it hits both kernel routes identically (`ConsumedDocumentBindingTest`). A non-success run binds no consumed document — only a completed run testifies to what it consumed. |
| `capture-receipt-v1` | `RECEIPT.json`: the closed, sorted artifact inventory (relative path + role + bytes + SHA-256), every file including `PACKET.json`, itself excluded; its own SHA-256 is printed for out-of-band handoff. Symlinks and unclassifiable files refuse the build. |
| `a12-dmkits-capture-capabilities-v1` | the machine-readable capability output — **transport capability, never semantic support** — schemas, input-scope versions, runner ids/roles/per-channel fidelity, projection ids, policy requirements (qualifying runners + mandatory projections per policy, and the `requiredChannels` semantics), commands. Locked byte-for-byte to a committed golden (`adapter/src/test/resources/capture/capabilities-v1.json`), so an in-place mutation under an existing id fails the build — **and copied into every packet** as `capabilities/capabilities.json` (receipt role `capability-declaration`, envelope digest-referenced), so the packet's capability and fidelity claims are self-contained: a consumer never combines an unbound packet with a separately trusted checkout file (`CapabilitiesBindingTest`; the verifier cross-checks the copy against its own registry emission — §15e). |
| `qualification-report-v1` · `packet-diff-report-v1` · `report-diff-report-v1` | the verifier/diff output schemas (§15d). |

A published id is immutable: schemas are closed (unknown members rejected), so any added or reinterpreted member mints a new id. Requests pin explicit ids and are rejected on an unknown version — never "latest".

**The V1 repair record (2026-07-16, Set Z2).** The first capture handback (`c0c29b76`) was REJECTED by the a12-kernel-lean source audit **before any packet or schema escaped externally** — no packet was handed over, and the sole consumer's draft scenario pack still carried a placeholder digest. The V1 identities above were therefore **repaired in place** as a deliberate pre-acceptance correction, with their compatibility fixtures updated in the same commits (never a silent additive change, and no broken-V1 compatibility line was minted): `capture-scenario-set-v1` kept its member set (the qualification gate tightened acceptance semantics only); `evidence-packet-v1` gained the capabilities reference, the kernel closure, and the runtime identity; `compute-observation-v1` gained the consumed-document binding; `a12-dmkits-capture-capabilities-v1` gained the policy-requirements shape; `capture-receipt-v1` gained the `capability-declaration` role; `qualification-report-v1` gained the request binding. **V1 FROZE with the external client's successful smoke run at `3ebe7564`** — the freeze record below.

**The V1 freeze record (2026-07-17).** a12-kernel-lean's exact external smoke run at the accepted baseline `3ebe7564` successfully completed `capture` and `captureVerify` for the `string-direct-cascade-v1` request (SHA-256 `4c5d4911ecacde819618b3b921b0bd30aa34b514cb0bbf3f0d2b735f21a0fd43`, model SHA-256 `3d21add02d259a8d1ad2e14475582513aec2f4e60176f1c02c81d40de88a895d`), printing packet-receipt SHA-256 `f84e38fa6307cda755dbf660e31044db54d5e1ff0e4332c7d416de096c034463` and qualification-receipt SHA-256 `2a0db1f4f6a16fb576c9dbe21944ac950b522ecc2b056336aae2470ce43cc9df`; those artifacts were **deleted**, so the run retained no kernel evidence — but it exercised the published capability tuple end-to-end, and **that smoke run IS the freeze event**: one published capability tuple has one immutable meaning and byte identity, whether or not the run's packet was retained. **V1 is FROZEN at the smoke-run identity** — every §15a identity at its `3ebe7564` closed shape, the canonical capabilities bytes at SHA-256 `b87d381e7f43446bc886292766e34beab72ca3ec179be5f0f614975e66d603ca`. The freeze is machine-locked: `CapabilitiesTest.theFrozenV1CapabilitiesDigestHolds` pins the golden's digest, so even a deliberate golden regeneration fails CI. From here on ANY changed closed shape mints a `-v2` — the pre-acceptance in-place repair lane (Set Z2, above) is closed.

**The rejected Set AB capture-lane exposure (2026-07-17; reverted in Set Z4).** Set AB flipped the interpreter runner's `absentVsPresentEmpty` to `available` on the strength of IF126 (`Document.cellStateAt`) and regenerated the committed capabilities golden **in place** (to `5bde25f5722acb1e14d9caa4d8757eca442f30a5c2f887774dcc886d4a282ee3`), reading the freeze event as "the first *retained* client run". That reading was wrong: the smoke run had already frozen V1, so the change mutated an externally exercised published identity in place — the Set Z3 handback built on it was **REJECTED by the client on version identity** (its observations passed their independent audit; the capability identity did not). Set Z4 restored the frozen contract: the capabilities bytes back to `b87d381e…`, the lane again declares `absentVsPresentEmpty = notExposedByRunner` and reports `noValueIndeterminate` applied states (still in the closed `compute-observation-v1` vocabulary), and the leak guard `FrozenV1InterpreterObservationTest` pins — on a state-rich scenario where `cellStateAt` provably distinguishes — that the richer internal interpreter state cannot change the frozen V1 adapter output (does-fire-proven by wiring the rich read through and watching it fail). The interpreter-side improvement itself (IF126) is real and stays; **exposing it through capture is the recorded future route `a12-dmkits-capture-capabilities-v2`** — deliberately NOT minted as part of the correction. Set AB's M26 mutation evidence is preserved outside the frozen boundary receipt (§15f).

**Prompt-2 ACCEPTANCE (2026-07-17, Set Z4 at `c992afd6`).** a12-kernel-lean **ACCEPTED** the corrected handback after independent audits and a fresh `captureVerify` replay of their own: the frozen restoration verified byte-for-byte (capabilities `b87d381e…`, capture source ≡ `3ebe7564`, the 40-mutation receipt), both final packets byte-identical (receipt `7e387442…`), the qualification reproduced (`f581d82d…`, verdict SATISFIED), all five kernel-route comparisons agreeing across all six required channels, and the successor scenario receipt's nine mutations + rejected-predecessor link confirmed. `capture-scenario-mutation-receipt-v2` was **explicitly accepted** as a versioned *process-assurance* record (it mints no capture-capabilities V2 and alters no frozen transport contract). Their one non-blocking clarification, adopted as the canonical reading: the **immutable** qualification profile's historical wording ("the interpreter public read surface lacks absent-versus-empty") is interpreted as the **frozen V1 capture-lane/projection limitation** — since IF126 the broad claim is stale, and §15c above carries the living statement; the returned artifact is never altered. The handback artifacts stay retained at `build/handback/string-direct-cascade-v1-v1/` until a12-kernel-lean confirms ferrying.

**The authoring example.** A complete, runnable scenario root is committed at [`adapter/capture-examples/scenario-example/`](../adapter/capture-examples/scenario-example/scenarios.json) — machine-readable as-is, no extraction from test code. It doubles as the self-tests' canonical valid fixture and the mutation harness's scratch scenario (ONE source), and `ScenarioExampleTest` runs it through the real `validate-scenarios` command with its declared digest checked against the committed model bytes — an example that cannot rot unexecuted.

**Expectation-free by construction.** The scenario schema has **no expected-result member at all**; a request carrying `expected`/`computeExpected` is refused with the explicit no-oracle diagnostic (expected outcomes belong in a separately reviewed conformance artifact produced after observation). The v1 input-support identity `non-repeatable-compute-targets-v1` rejects a computation whose target or scope lies under a repeatable group (checked kernel-free via `ModelIndex.computations()`).

### 15b. The one structural decoder — including the request-bound qualification contract

`validate-scenarios` and `capture` share **one** acceptance pipeline (`schema.ScenarioSets.load` → the closed `ScenarioDecoder`): strict JSON (duplicate keys rejected, decimals exact — never through binary floating point), closed member sets at every level, digest-bound refs resolving only inside the scenario root (traversal and symlinks rejected), registry-checked runner/policy/projection/channel ids, and the kernel-free model-structure gates. Both commands therefore make the same pre-execution acceptance decision — guarded by the decoder-parity test, and structural rejection collects **every** problem with its location before refusing.

**The qualification contract is enforced at decode time — before any runner can execute** (`PolicyRequirements`, the one policy-requirements registry; guarded by `VerifierPolicyBindingTest` + the parity battery). Three gates, all knowable from the compile-time registry: (1) every projection the declared policy's verdict rests on must be **predeclared** in `comparisonProjections` (confirmed → the kernel-route projection; conformance → kernel-route + portable; a comparison is never selected after observations are known); (2) every **qualifying runner** of the declared policy (confirmed → the two kernel routes; conformance → those + the interpreter; characterization → none) must be listed in `requiredRunners`; (3) **the exact semantics of global `requiredChannels`**: each required channel must be registry-declared `available` on every qualifying runner — a fidelity incompatibility refuses **before execution**, fail closed. Runners requested beyond the qualifying set (triangulation) keep their declared fidelity gaps — echoed, never bridged, never a refusal. Per-runner or per-field requirements exceed what `capture-scenario-set-v1` can express and take a **new request schema**, never a silent extension. Because the gate lives in the one decoder, the verifier's re-decode of the packet's copied request enforces it too.

### 15c. Runners & the kernel-free DTO boundary

The COMPILE-TIME registry (`dto.RunnerId` + the `runner.Runners` exhaustive switch — no runtime plugin discovery):

| runner id | role | route |
|---|---|---|
| `kernel-groovy-dynamic` | **anchor** | the kernel's md-runtime dynamic (Groovy) service |
| `kernel-java-static` | **cross-route confirmation** | the kernel's own generated-JAVA validator, compiled in-memory, through the kernel's static rt-service |
| `a12-dmkits-interpreter` | **triangulation** | the kernel-free interpreter — never the oracle |

The two kernel names provably invoke **distinct registered routes** (`RouteDistinctnessTest`, a does-fire guard over the resolved rt-service implementations) — but the claim stays *cross-route agreement within one kernel implementation*, never independent engines. Both kernel routes observe through ONE shared mapper (`runner.kernel.KernelRouteSupport`), so a cross-route difference is a route difference, never a mapping one; kernel types TERMINATE in `capture.runner.kernel`, interpreter types in `capture.runner.interpreter`, and the neutral zone (dto/schema/json/packet) references neither engine — enforced by the **`verifyCapturePurity`** Gradle lint (wired into `:adapter:check`; the `verifyInterpreterPurity` pattern, does-fire-proven).

Per (case, runner) execution is **fresh-state**: a new engine/evaluation context, a fresh document from the placements, the explicitly injected clock/locale — no cache crosses cases or routes (`ReorderEquivalenceTest`: a reordered full run and a filtered single-case run observe identically). Each run carries an explicit status — `success` / `unsupported` / `timeout` / `technicalFailure` — where successful-EMPTY output is data inside `success`, never a failure encoding; a failed run carries no semantic channels, and `technicalFailure` detail is fixed sanitized text (never an exception message, class name, or stack frame — those go to stderr only).

Fidelity is declared per runner (`dto.RunnerFidelity`, echoed into capabilities + packets): the kernel routes expose all six channels with typed + rendered values, structured causes, and the absent/present-empty applied distinction; the interpreter exposes rendered values only, no changed subset (report-all granularity), no structured causes, no operand formal errors, and `noValueIndeterminate` applied states for valueless probes (`absentVsPresentEmpty = notExposedByRunner`). That last declaration is a **frozen-contract line, no longer a capability gap**: since IF126 the interpreter's own public `Document.cellStateAt` DOES separate absent from present-empty, but the frozen V1 lane deliberately does not consume it — `FrozenV1InterpreterObservationTest` pins (does-fire-proven) that the richer internal state cannot change the frozen V1 output, and exposing it is the future `a12-dmkits-capture-capabilities-v2` (§15a). The frozen V1 source's in-code IG72 references are the historical name of that gap (graduated to IF126) — the source stays byte-identical to the freeze baseline by design. Missing transport is declared, never bridged.

### 15d. Commands, determinism, legality

The maintained command surface (`cli.CaptureTool` — ONE entry serving the Gradle launchers, the self-tests, and the mutation suite, so tests drive the real command):

| command | Gradle | what it does |
|---|---|---|
| `capabilities` | `./build.sh :adapter:captureCapabilities` | print the machine-readable capability output |
| `validate-scenarios` | `./build.sh :adapter:captureValidateScenarios -Pcapture.scenarios=<file>` | structural acceptance through the SAME decoder capture runs — no engine, nothing written |
| `capture` | `./build.sh :adapter:capture -Pcapture.scenarios=<file> -Pcapture.output=<dir> [-Pcapture.development]` | validate → legality gate → run the required runners → write the packet; prints the out-of-band `RECEIPT.json` SHA-256 |
| `verify` · `diff-packets` · `diff-reports` | `:adapter:captureVerify` · `:adapter:captureDiffPackets` · `:adapter:captureDiffReports` | §15e |

**Determinism.** Packets are directory-shaped and reviewable: UTF-8, LF, final newline, canonical member/array ordering from the schema builders, exact numbers (decimals as strings — never binary floating point), no timestamps or volatile host metadata in the packet identity. Two executions at the same source/kernel/model/input/world produce **byte-identical** contents (`CaptureDeterminismTest`, the double-capture guard). One case+runner diffs without rewriting unrelated observations.

**Caller-owned output; the corpus is never written.** Both modes require an explicitly NEW or EMPTY caller-owned output directory, refuse the committed `corpus/` tree (promotion is a separate, reviewed, explicitly authorized action — deliberately not a v1 command), keep temp products in ignored storage, and leave source bytes and visible status exactly as found.

**Clean vs `--development`.** Default capture requires a CLEAN committed checkout (the packet binds the git revision + a classified tree-state digest); `--development` runs against modified source for red/green and mutation work — its packet records `workingTree: dirty` + `developmentMode: true` and is **permanently ineligible** for every portable qualification policy.

**Staged finalization under the pre/post closure-identity check.** The complete `ClosureIdentity` — source revision + tree state (which binds the in-repo capture AND interpreter implementation closures), every resolved semantics-affecting classpath artifact (the kernel groups + the Groovy runtime, each with exact bytes hashed — one coordinate is never assumed to cover the whole execution), and the JVM runtime identity (which binds the in-memory `javac`) — is resolved **before** execution; all artifacts are written into a staging directory; the identity is resolved **again** after every runner completes; on ANY difference finalization is refused, the staged tree is discarded, and no packet exists (`ClosureIdentityTest`, injectable-probe-proven). `PACKET.json` (binding the verified identity + the closure justification) and `RECEIPT.json` are published only after the post-run check succeeds — in both modes.

**Legality before runtime (§6).** Every model runs the kernel's authoring/consistency acceptance surface (the same `checkConsistency` gate `RuleValidator`/`model check` route through, via the adapter's `ConsistencyOracle`) BEFORE any runner executes; the per-model `capture-legality-v1` artifact records the validator identity, the verdict, severity+code+text-digest per notification (never kernel message text — the IF3 rule), and BOTH the supplied and adapted digests (v1 adapts nothing — bound explicitly equal, so the gate provably inspected the same bytes runtime consumed). An ILLEGAL model yields a characterization packet: its cases are marked `executed: false` and no observation artifact exists (`LegalityGateTest`).

**The metadata leak guard (§2).** Every GENERATED artifact (envelope, legality, observations, receipts) is scanned at write time: no absolute local path, username, home directory, hostname, kernel class name (the kernel's Maven *coordinate* is sanctioned identity), or stack-trace frame — a hit refuses the capture. The COPIED consumer inputs (request + models) are exempt by design and never scanned or rewritten (`MetadataLeakGuardTest`).

### 15e. Qualification policies & the verifier

**Capture once, qualify separately — under the request-DECLARED policy.** `verify` applies the packet's **request-declared** qualification policy to a packet verified in TWO layers (`VerifiedPacket`). **Byte integrity:** the receipt file's SHA-256 must equal the supplied out-of-band digest; the live inventory must equal the stored receipt exactly — additions, removals, renames, byte drift, symlinks all refuse. **The closed relational validation** (`PacketRelations` + `ObservationShape`, `PacketRelationsTest`): the envelope must *describe* exactly the packet it travels with — `scenario.requestSha256`/id/version recomputed against the copied request bytes; operation/capabilities/world/runner descriptors against the re-decoded request and the compile-time registry (roles, routes); the capabilities copy byte-equal to the verifier's own registry emission; input refs and supplied/adapted digests against the request *and* the copied model bytes; legality refs/digests/verdicts against the (closed-parsed) legality artifacts, whose inner verdicts must match their own notifications; case entries against the request cases with `executed` ⟺ legal and exactly one observation per required runner; every observation artifact closed-parsed (`compute-observation-v1` member sets at every level, availability/status/state vocabularies, channel availability + granularity EQUAL to the registry fidelity — forged fidelity refuses), its caseId/runner/status/consumed-model/consumed-document claims cross-checked (supplied == consumed placement identity per Finding 3); and **exactly-once accounting** — every receipt artifact referenced by the envelope, every referenced artifact inventoried, orphans refuse. A receipt-consistent but internally contradictory packet therefore refuses before any policy runs. Only then does the policy engine write a SEPARATE digest-bound report directory — `PROFILE.json` (the pinned policy+projection definitions, a pure function of the tool, digest-bound by the report) + `REPORT.json` + its own `RECEIPT.json`. **A CLI policy that mismatches the declared one is refused fail-closed with no report** (`VerifierPolicyBindingTest` — a policy is never selected after observations are known; the CLI flag is an explicit confirmation, not a choice). Comparisons run for exactly the **predeclared** projections — the policy's mandatory ones gate the verdict, additionally predeclared ones are informational, and a projection never predeclared is never compared. The report **binds the declared contract**: the exact request digest, the observation-requirement id + version + required channels + predeclared projections, the declared policy, the upstream receipt digest, and the profile digest — changing the declared requirement's identity or contents changes the request bytes and therefore the bound report identity. **Verification never adds to or rewrites the packet** (`VerifierTest.verificationNeverWritesIntoThePacket`).

| policy | requires | on the interpreter |
|---|---|---|
| `characterization-v1` | schema/inventory integrity, legality artifacts present, truthful runner availability (an observation artifact per required runner on every executed case). May RETAIN runner failures and cross-route disagreements; can never promote a corpus case. | informational |
| `kernel-route-confirmed-v1` | kernel-legal scenario, both kernel routes successful, equality under EVERY field of `compute-projection-kernel-route-v1` per case. Claims ONLY exact cross-route agreement on that projection — one kernel implementation, never independent engines. | reported when predeclared, not required |
| `dmkits-projected-conformance-v1` | additionally: the interpreter successful, and available AND equal under `compute-projection-dmkits-portable-v1` — an unavailable required field/channel fails CLOSED; excluded fields are named with rationale in the profile, so the claim is never "full rich-result conformance". | required |

**Every portable policy rejects a development-mode / dirty-source packet BEFORE semantic comparison** (`VerifierTest`). Every comparison in a report names its two runners, projection id + version, included fields, unavailable fields, and per-channel equal/divergent results — there is **no global `agrees` Boolean**, so agreement at one granularity can never masquerade as agreement on attempted values, causes, or application shape. A cross-route split stops route confirmation and stays retained under characterization (`VerifierDivergenceTest`).

**Diffs.** `diff-packets` verifies both directories' receipt self-consistency, then reports added/removed/changed artifacts classified as `input` / `provenance` / `legality` / `observation` (the latter named per case + runner); `diff-reports` separates `policy-profile` drift from `qualification-result` drift (`DiffCommandsTest`). Both write `DIFF.json` + `RECEIPT.json` into a new caller-owned directory and never touch their inputs.

**Corpus promotion is deliberately NOT a v1 command** — when a concrete corpus client justifies it, a corpus-specific promotion command must reverify packet + qualifying report, use an explicitly versioned rich-to-corpus projection, require separate human authorization, and emit the updated corpus receipt. Capture and verify refuse the corpus tree today.

### 15f. Mutation sensitivity & the deferred map

The proposal's §"Required tests and mutation sensitivity" list is implemented for the BOUNDARY by the committed harness [`adapter/capture-mutations/run-mutations.py`](../adapter/capture-mutations/run-mutations.py) (machine-checkable receipt: [`adapter/capture-mutations/mutation-receipt.json`](../adapter/capture-mutations/mutation-receipt.json), `capture-mutation-receipt-v1`): every mutation is applied → its PREDICTED narrow guard runs (a targeted JUnit class/method, the `verifyCapturePurity` lint, or the REAL command in `--development` mode) → the guard FIRES → the exact source bytes are restored (pre/post classified tree digests, proven equal) → the run finishes with the natural full capture gate green. Mutant packets are disposable and qualification-ineligible by construction.

| proposal item | mutation | fired guard |
|---|---|---|
| illegal model reaching runtime | M01, M16 (gate inspects different bytes) | `LegalityGateTest` |
| omitted kernel route / hidden cross-route divergence | M02 (+ artifact-level in `VerifierDivergenceTest`) | `VerifierDivergenceTest` |
| errored attempted value / structured cause dropped (transport half) | M03 | `ErroredComputationObservationTest` |
| absent vs present-empty collapsed | M04 | `AppliedStateDistinctionTest` |
| deprecated/mutating application route | M05 | `KernelRouteObservationTest` |
| interpreter disagreement blocking characterization / mislabeled confirmation | M06 (+ `VerifierDivergenceTest`'s artifact case) | `VerifierDivergenceTest` |
| unavailable fidelity treated as equality | M07 (a forged availability is refused at the relational layer — A-tier; the policy backstop is unit-guarded) | `ProjectionFailClosedTest` |
| the two kernel labels selecting one route | M08 | `RouteDistinctnessTest` |
| failure serialized as successful-empty | M09 | `RunnersStatusTest` |
| validation and capture deciding differently | M10 | `DecoderParityTest` |
| state leaking across reordered/filtered runs | M11 | `ReorderEquivalenceTest` |
| qualified capture before clean commit | M12 (the clean gate) | `CaptureCommandTest` |
| second identical run differing | M13 | `CaptureDeterminismTest` |
| output entering the committed corpus | M14 | `CaptureCommandTest` |
| machine-specific value in generated metadata (inputs never falsely rejected) | M15 (+ real-command refusal step) | `CaptureCommandTest` + the LeakGuard refusal |
| verifier mutating the packet | M17 | `VerifierTest` |
| unknown version / in-place schema mutation | M18 + A06 | `CapabilitiesTest` / the decoder refusal |
| kernel type crossing the DTO boundary | M19 | `verifyCapturePurity` |
| development packet qualifying | M20 | `VerifierTest` |
| packet/report member, digest, symlink, path drift | A01–A04 (real `verify` over mutated artifacts) | the integrity refusals |
| expected-result member in a request | A05 (real `validate-scenarios`) | the no-oracle refusal |
| a CLI policy mismatching the request-declared one qualifying | M21 | `VerifierPolicyBindingTest` |
| a mandatory projection selected after observations (not predeclared) | M22 | `VerifierPolicyBindingTest` |
| a required channel unavailable on a qualifying runner executing anyway | M23 | `VerifierPolicyBindingTest` |
| a placement changed/dropped during placement-to-document adaptation | M24 | `ConsumedDocumentBindingTest` |
| a closure-identity change between execution and finalization finalizing | M25 | `ClosureIdentityTest` |
| a receipt-CONSISTENT internally contradictory packet verifying (request digest/identity claims, legality-verdict lies, observation case/runner/status/consumed-document claims, unknown nested members, removed associations, orphan artifacts, tampered capabilities copy) | A07–A15 (renewed-receipt mutations through the real `verify`; + the 14-case JUnit battery) | the relational refusals (`PacketRelationsTest`) |

**Receipt honesty guard:** a stale guard reference (Gradle's "No tests found") FAILS the harness loudly — a filter matching nothing is never recorded as a fired guard (the false-positive class caught during Set Z2's own run).

**M26 — experimental, future-v2, outside the frozen receipt.** Set AB's interpreter-runner cell-state-mapping mutation is NOT part of the frozen 40-mutation boundary receipt: the rich `cellStateAt` mapping it mutates exists only on the rejected Set AB lane (§15a), so it cannot run against frozen V1. Its fired-guard evidence is preserved verbatim in [`adapter/capture-mutations/mutation-receipt-experimental-m26-future-v2.json`](../adapter/capture-mutations/mutation-receipt-experimental-m26-future-v2.json) for the future `a12-dmkits-capture-capabilities-v2` exposure; the frozen-V1 leak guard is `FrozenV1InterpreterObservationTest` (§15c).

**The scenario-semantic tier (2026-07-17, Set Z3 → Z4 — the deferred map EXECUTED).** The formerly deferred scenario-semantics mutations run against the exact external `string-direct-cascade-v1` pack through the committed harness [`adapter/capture-mutations/run-scenario-mutations.py`](../adapter/capture-mutations/run-scenario-mutations.py). The mechanism: each mutation corrupts ONE semantic aspect in capture or interpreter source → the REAL `capture --development` runs the external scenario → the REAL `diff-packets` against a same-revision reference development packet must report EXACTLY the predicted per-case/per-runner observation drift (prediction recorded before execution; a structured field assertion pins the drift's shape; bytes restored with pre/post digests; mutant packets never see portable qualification; natural gate green). The five-case matrix SEPARATES: every mutation's drift confined itself to exactly the predicted cases/runners — in both executions. *Receipt lineage (the generic boundary receipt above stays untouched by both):* the Set Z3 receipt ([`scenario-mutation-receipt-string-direct-cascade-v1.json`](../adapter/capture-mutations/scenario-mutation-receipt-string-direct-cascade-v1.json), `capture-scenario-mutation-receipt-v1`) is **rejected version-identity history** — it bound the in-place-mutated capabilities `5bde25f5…` (§15a) — and stays committed untouched; the corrected Set Z4 run writes the **successor** receipt [`scenario-mutation-receipt-string-direct-cascade-v1-r2.json`](../adapter/capture-mutations/scenario-mutation-receipt-string-direct-cascade-v1-r2.json) (`capture-scenario-mutation-receipt-v2` — adds the explicit `predecessor` reference with the pinned digest `045aa25e…`, and the harness refuses to run unless the capabilities golden matches the frozen `b87d381e…`).

| deferred item | mutation | predicted = observed drift |
|---|---|---|
| accepted-equal vanishing from all-clean | S01 | `source-abc-mid-abc` × both kernel routes |
| accepted-equal entering the changed subset | S02 | `source-abc-mid-abc` × both kernel routes |
| source-absent stale cascade (`"OLD-X"` vs `"-X"`) | S03 | `source-absent-mid-old` × interpreter |
| rejected attempted value reaching the dependent | S04 | `source-abcd-mid-old` × interpreter |
| attempted value dropped/altered (semantic half — M03 covers transport) | S05 | `source-abcd-mid-old` × both kernel routes |
| cause code (`stringZuLang`) dropped/altered | S06 | `source-abcd-mid-old` × both kernel routes |
| cause message type (`VALUE_ERROR`) dropped/altered | S07 | `source-abcd-mid-old` × both kernel routes |
| cause pointer (`/Cascade[1]/Mid`) dropped/altered | S08 | `source-abcd-mid-old` × both kernel routes |
| absent vs present-empty collapsed (scenario half — M04 covers the unit level) | S09 | `source-absent-mid-old` + `source-abcd-mid-old` × both kernel routes |

The final qualified recapture from clean committed HEAD (the handback ritual — its clean-gate mechanics are M12) is the run itself, not a mutation. **Covered by an existing guard, no capture-side mutation:** the compact legacy corpus signature (`corpusReplayTest` — this program never touched that surface, byte-compatible by construction).

**Recorded follow-up — the duplicated javac in-memory bootstrap (Set Z′ gate, 2026-07-16).** The ~90-line in-memory `javac` bootstrap for the java-static route exists twice: in `RuntimeLaws` (the test-estate oracle) and in the capture `JavaStaticRunner`. Deliberate for now — the proposal's "stop extending `RuntimeLaws`" intent argued against coupling the proven test estate to the *unproven* capture boundary mid-program. The de-dup direction is the oracle **delegating to the capture runner** (one bootstrap, capture-owned), not the reverse. *Re-open trigger:* the external `string-direct-cascade-v1` run lands (the boundary is then proven), or a third copy of the bootstrap appears. **Trigger fired (2026-07-17, the accepted Z4 run) — and re-parked onto the v2 lane (master ruling):** the boundary is proven, but the bootstrap sits inline in the now-frozen V1 capture source (byte-identical to `3ebe7564` by design), so extracting a shared unit would spend the byte-identity proof for a hygiene win while both copies are frozen-stable (kernel pinned 30.8.1, the 31.1.0 upgrade deferred — drift risk ≈ 0). The de-dup rides `a12-dmkits-capture-capabilities-v2`, whose lane changes capture source anyway; a kernel bump or a third copy re-opens it sooner.
