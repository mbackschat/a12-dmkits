# Kernel-runtime observation probe

The JVM-only, non-shipped route by which an external consumer asks the **real A12 kernel** a question and gets back a deterministic JSON artifact it can pin by SHA-256 and replay as evidence. Two kinds: a *runtime* question about a document — what fires, with what polarity, at what coordinates, and what its computations made of it — and a *model-level* question about the rule collection itself, which reads no document. It exists so either does not cost a purpose-written Kotlin test.

```
./gradlew :adapter:kernelProbe -Prequest=<request.json> -Pout=<artifact.json>
```

*(`./build.sh` is the same thing with the isolated Gradle home + JDK 21 pinned — see [BUILD-ENVIRONMENT](BUILD-ENVIRONMENT.md).)*

| | |
|---|---|
| Request shape | [`adapter/src/test/resources/kernel-probe/request.schema.json`](../adapter/src/test/resources/kernel-probe/request.schema.json) |
| Artifact shape | [`adapter/src/test/resources/kernel-probe/artifact.schema.json`](../adapter/src/test/resources/kernel-probe/artifact.schema.json) |
| Implementation | `adapter/src/test/java/…/adapter/laws/KernelRuntimeProbe.java` + `KernelProbeArtifact.java` |
| Locks | `adapter.laws.KernelRuntimeProbeTest` + `KernelProbeComputeTest` + `KernelProbeComputeResultsTest` + `KernelProbeCustomFieldTypeTest` + `KernelProbeCustomConditionTest` + `KernelProbeDocumentFidelityTest` + `KernelProbeMandatoryInformationTest` + `KernelProbeValidatePartTest` (kernel-backed), `adapter.laws.KernelProbeArtifactTest` + `KernelProbeComputeShapeTest` (shape), `GateTierWiringTest` (the task stays a measurement) |

## 1. Why a Gradle task and not a verb

Kernel runtime evaluation does **Groovy runtime code generation** and is not AOT-compilable. That is the whole reason dmtool's runtime verbs (`model eval`, `model compute`, `rule eval`) run the kernel-free `:interpreter` as the sole runtime eval engine, `Cli.JVM_ONLY_VERBS` is empty, Groovy is excluded from `nativeImageClasspath`, and the `--kernel` eval option was retired in Set V ([ARCHITECTURE](ARCHITECTURE.md) "The Groovy wall").

A runtime *verb* would undo all of that. A **test-scoped Gradle task** touches none of it: the probe's classpath is `sourceSets.test.runtimeClasspath`, so it cannot reach a published jar or the native image, and no gate depends on it. `GateTierWiringTest.theKernelRuntimeProbeIsAMeasurementAndNotAGate` is where that stays true.

Linkage was never the obstacle — `adapter/src/main/java` already links the kernel, which is what makes `rule check` a real kernel verdict. What was missing was a way to *reach* the runtime oracle without authoring Kotlin.

## 2. What it observes

Kernel runtime entry points over the supplied document, on **both** kernel codegen strategies:

| `engine` | what it is |
|---|---|
| `kernel-groovy-dynamic` | the production-normative anchor — the generated/cached Groovy md-runtime service |
| `kernel-java-static` | the kernel's own validator generated as Java and compiled in memory, run through the static rt-service |

**Which entry points is the request's choice** (`observe`, §3), because adding an observation must not change what an existing request produces:

| `observe` entry | scope | kernel call | artifact block |
|---|---|---|---|
| `validateFull` | per row | `IDocumentRtService.validateFull` | each engine block's `messages` |
| `compute` | per row | `IDocumentRtService.compute` | each engine block's `computations` |
| `computeResults` | per row | `IDocumentRtService.compute` (its RESULT, not the change projection) | each engine block's `computeResults` |
| `validatePart` | per row | `IDocumentRtService.validatePart` | each engine block's `partialMessages` |
| `mandatoryInformation` | **per model** | `IRuleCollectionMetaInformation.getMandatoryInformationFromRules` | the top-level `mandatoryInformation` |
| `dynamicEnumerations` | **per model** | `IRuleCollectionMetaInformation.getDynamicEnumerationStringValues` | the top-level `dynamicEnumerations` |
| `repetitionIndexes` | per row | `PartiallyKnownDocumentMultiPointer.repetitionIndexes()` of each address a message carries | each message's `repetitionIndexes`, and `request.observe` |

**A model-level observation reads no document, and the request contract follows from that rather than from a list.** `mandatoryInformation` derives its three name sets from the model's rule CONDITIONS, so it runs once for the whole request — not per row, not per strategy — and a request may ask for it with no `rows` at all. Its scope is carried on the `Observation` enum, and both halves of the request rule are derived from that: a request naming any per-row observation must carry rows and must observe `validateFull`; a request naming only model-level ones must carry none, because a row nothing looks at is the same silent no-op the `validateFull` rule refuses. Built for a12-kernel-lean's `EXP-2026-08-30-01`, which needs to compare the derived sets across two models differing in one rule literal — a question no document evaluation can reach. The kernel types stay behind `KernelAdapter.mandatoryInformation`, which returns plain `Set<String>`, so nothing about dmtool's kernel boundary or its native build changes.

**A strategy split is the finding, so it is never merged away.** Each row keeps one block per strategy and `enginesAgree` is *derived* from them; no caller can author it. On a legal-input split the ratified oracle is dynamic Groovy and the static-Java result is characterized, never adopted (INTERPRETER-FINDINGS IF136, [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md) §11). It is derived from **every** channel a block reports, not from the messages alone: two strategies that agree on every message and disagree on one computed outcome are a split, and a flag computed from one channel would have reported agreement its own row contradicts.

## 3. The request

Design the cases with the [witness-grid plan](TESTING-SPEC.md#witness-grid-design) before constructing the request. Keep that plan in the task's acceptance matrix; the request and artifact identify inputs and observations, not which competing explanation the grid can distinguish.

One document model plus one or more named documents. **Authoring stays on dmtool's structured verbs** — the probe grows no second authoring surface, so a rule or computation reaches the probe by being in the model file, added with `dmtool rule add` / `computation add`. The model is read exactly as the kernel's own deserializer reads it, with no reference resolution, so it must be self-contained (`dmtool model expand`).

Documents are **A12's canonical Document JSON** — the same shape `dmtool model seed` writes and `model eval --instance` reads ([`document-instance.schema.json`](../cli/src/main/resources/schema/document-instance.schema.json)) — read through A12's own `IDocumentV2Serializer`. So one document file feeds the interpreter and this probe alike, and a difference between them is a real difference rather than an ingress artifact.

```jsonc
{
  "model": "order.dm.json",                 // relative to THIS file, or absolute
  "locale": "en_US",                        // optional, default en_US — but see below
  "clock": "2026-08-11T00:00:00Z",          // optional: pins currentDateForTest on both strategies
  "observe": ["validateFull", "compute"],   // optional, default ["validateFull"]
  "rows": [
    { "name": "billing-filled", "document": "billing-filled.json" },
    { "name": "empty-order",    "documentInline": { "Order": {} } }
  ]
}
```

**`observe` exists so that adding an observation costs an existing consumer nothing.** Omitted, the probe observes validation alone and writes the artifact it wrote before `compute` was observable — *byte for byte*, so an already-pinned SHA-256 stays valid. `KernelProbeComputeShapeTest` pins that against output captured from the pre-change producer, not against a re-run of the current one.

**That sentence has two halves, and the second one was advertised for three weeks without an enforcement locus.** Not asking for an observation is free — the paragraph above, and mechanically checkable. *Asking* for one was not: until 2026-09-22 a raise in any channel replaced the whole engine block with its fault, so a request that added `computeResults` came back **without** the `messages` a `compute`-only request returns for the same row. a12-kernel-lean hit it on an over-capacity document, whose applied copy the kernel refuses to serialize while validating it perfectly well ([KF456](KERNEL-FINDINGS.md#kf456)). The rule is now the narrower one the dependency actually supports — *a published channel is a complete channel* — enforced in `KernelProbeArtifact.Engine`'s constructor, projected into this schema's `engines[]` conditionals, and locked both ways by `adapter.laws.KernelProbeOverCapacityFaultTest` (the surviving channel, and the schema still refusing a block that publishes the channel its own fault names).

**`validateFull` cannot be dropped whenever any row is observed.** An engine block's `messages` is a **required** key of `schemaVersion` 1, so a block whose list was empty *because nobody looked* would be indistinguishable from one where nothing fired — §5's failure mode, an empty observation that reads as an answer. `computations` avoids that by being **absent** when unobserved, and `messages` could have been made optional the same way; it was not, because that changes the shape of an artifact existing consumers already pin, which is the one thing this change is not allowed to do. So the refusal is a **compatibility** decision, not a claim that the ambiguity is unsolvable — and its cost is real: a consumer that wants only compute pays `validateFull` on both strategies for every row. `observe: ["compute"]` is refused with that reason, as is an unknown entry and an explicit empty array (an omitted key means "the default"; an empty one means a mistake). The rule is scoped to per-row observations because a model-level-only request observes no row at all — `observe: ["mandatoryInformation"]` with no `rows` is the shape that capability exists for, and supplying a row to it is refused from the other side. The published request schema encodes the same three cases, and `KernelProbeMandatoryInformationTest.theSchemaAndTheProducerAgreeOnTheModelLevelRules` fails if the two ever drift: an agent reads the schema before it runs the probe, so a schema that accepts what the producer rejects teaches the wrong shape.

**`customFieldTypes` makes a CUSTOM cell observable at all.** A filled cell whose declared `CustomFieldType` resolves to no validator raises `ValidatorException: Custom field validator '<name>' missing.` out of the **whole** `validateFull` call on both strategies, taking every other rule on that document with it — the kernel resolves from the per-call `DocumentProcessingConfig.customFieldTypeFactory` and falls back to the static `CustomFieldTypeService`, and both are empty unless a host registers one ([KF54](KERNEL-FINDINGS.md#kf54); the fault itself is pinned by `adapter.laws.KernelProbeFaultCaptureTest`). An **empty** cell short-circuits before the validator, so such a model observes cleanly right up to the row that fills one — which is what made a12-kernel-lean park three items on this and mark one route-closed rather than pending (their 2026-09-21 note §5).

```jsonc
"customFieldTypes": {
  "CouponCode": { "pattern": "[A-Z]{4}-[0-9]{4}" },   // every constraint optional; absent = unconstrained
  "LoyaltyId":  { "minLength": 4, "maxLength": 12 }
}
```

The shape is **dmtool's own `--predefined-types` registry**, deliberately — one concept, one spelling. Its imperative sibling (`--custom-field-types-js`) is **not** mirrored: an artifact is pinned by SHA-256, so every input must be reproducible from the request bytes alone. The validator applies `pattern`, then the type's lengths, then the **field's** declared `minLength`/`maxLength`, which the kernel hands over through `ICustomFieldTypeValidationParam`; each failure names its own gate in the message `code` (`customFieldType.pattern`, `field.maxLength`, …), which is A12's rule that the code is the validator's `getErrorKey()` rather than a fixed kernel code. Omitting the key declares nothing and reproduces the artifact byte for byte; an **empty object** is refused, because it reads as a default while leaving the cell exactly as unobservable as its absence does. *(Locked: `adapter.laws.KernelProbeCustomFieldTypeTest` — the host's key and text on both strategies, the accept/empty-cell liveness controls, both request refusals, and the schema parity.)*

**`customConditions` makes a `CustomCondition` rule observable, and records how the kernel calls it.** A `CustomCondition <Name>` leaf resolves only through the per-call `DocumentProcessingConfig.customConditionFactory`, so without a registration no rule using one is observable; a name the request does not declare stays unregistered. The behaviours are declarative, for the same reproducibility reason as `customFieldTypes`: a constant, or true exactly when the document holds a value at one pointer in A12's own `DocumentPointer` spelling.

```jsonc
"customConditions": {
  "AdjusterApproved": { "returns": true },             // a constant
  "ItemReceipted":    { "whenFilled": "Claim/Note" }    // true when that cell holds a value
}
```

The conditions are bound on every channel the request observes, and each engine block then carries `customConditionCalls`: every invocation in call order, each naming its `observation`, the condition `name`, the `relevant` set the kernel passed (`null` under full validation), the `formallyIncorrect` set, the `errorEntity` with the row's repetition indices, and the declared behaviour's `result`. Both sets are sorted, since the kernel hands over sets. A call that raised inside the probe's condition is recorded rather than omitted: `result` is `null` and `raised` carries the chain, and the three argument keys are absent only when projecting them is what raised. A request cannot make a declared behaviour raise, since both are total (`DocumentV2.fieldValue` answers `null` for a pointer the model lacks), so the failed form exists for the probe's own projection code. The block is present exactly when the request declares a condition, so an unchanged request keeps its bytes, and an empty list then means no rule reached one. Calls a raise interrupted are kept, because each one completed. Each entry needs exactly one of the two keys; an unknown key, a non-boolean `returns`, a pointer A12 cannot parse, a trailing wildcard `[0]` and an empty object are refused. *(Locked: `adapter.laws.KernelProbeCustomConditionTest` — every recorded `true` matched against a rule that fired on both strategies, each behaviour, the row-indexed error entity, a malformed cell in the formally incorrect set, `null` versus the caller's relevant set, the empty and the absent record, an undeclared name beside a declared one (A12 raises the lookup failure itself, catches it, and reports the rule under its own code as a missing condition, with the raise published as the kernel's), every refusal, and both schemas.)*

**`locale` is not cosmetic.** The kernel refuses a locale the model has not *declared*, outright — measured 2026-08-11: probing a `de_DE`-only model at the default gives `IllegalArgumentException: Locale 'en_US' is not supported in the document model '…'`. So a German-only model is unobservable without this key rather than merely reported in English. Only the `message` channel is locale-dependent; `code`, `type`, `severity`, and every address are not. Both directions are locked by `KernelRuntimeProbeTest.theRequestPinsAnEvaluationLocaleBecauseTheKernelRefusesAnUndeclaredOne`.

## 4. The artifact

A header naming the producer, then one entry per row in request order, then one block per strategy carrying that strategy's complete observation: its message list always, and its `computations` when the request asked for one. A model-level observation sits beside `rows` rather than inside it — see "The `mandatoryInformation` block" below — and `rows` is then the empty array, because it is a required key of `schemaVersion` 1 that consumers already read. Each message uses the **key names of a `model eval` `messages[]` entry** — `code`, `field`, `severity`, `type`, `message`, `referenced`, `fillToFix` — so one parser reads dmtool's runtime output and this artifact alike.

Addresses keep dmtool's published **`a12-message-address`** contract, decided per address on the typed pointer: canonical A12 when it names exactly one cell (no leading slash, the implicit `[1]` omitted), and the interpreter house form when it does not, because A12 defines no pointer string for an address carrying a wildcard, an unresolved level or a parallel-iteration level ([CLI-SPEC](CLI-SPEC.md) [§5](CLI-SPEC.md#verb-surface)). **Test the leading slash to tell them apart.** The canonical arm loses nothing: only A12's own implicit `[1]` goes unwritten, so every row above the first survives — which is what makes the probe able to answer a coordinate question at all (`/Order[1]/Items[0]/Count` from a starred group operand beside `Order/Billing/Street` from an unstarred one). Every coordinate above the last also survives on the house arm, including `0`, `-5` and the `-6` A12 leaves on a joined level ([KF607](KERNEL-FINDINGS.md#kf607)), **but the house arm drops the last coordinate**. On a field that coordinate names no row, since a field does not repeat. On a group it is the group's own row or sentinel, so a message reporting on `Lines/Slots` at `[1, 1, -5]` is published as `/Order[1]/Lines[1]/Slots` *(corrected 2026-10-24: this said "Nothing is lost")*. A request that needs that coordinate observes `repetitionIndexes` (below). **The probe JVM runs without `-ea`, and has to:** A12's message pointer asserts that no index is negative except `-5`, so with assertions on, A12's own accessor raises on a message carrying `-6`, and the whole run dies before it writes an artifact. The task sets `enableAssertions = false` explicitly, and `GateTierWiringTest` holds it there.

**The omitted `[1]` is the one part of that contract a consumer meets before reading this, so it is stated at the property too, and it is not changing.** A per-row outcome for the first repetition is written bare — `Basket/Lines/Total` is row 1, `Basket/Lines[3]/Total` is row 3 — and a12-kernel-lean's agent spent a second probe run establishing which row an unindexed entry named before asking us to emit `[1]` (their instrument note, 2026-09-04). We do not, because `[1]`-omission is **A12's own** canonical form (`DocumentPointerImpl.toString`; an absent bracket *means* repetition 1, [KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf1)) and this field's whole contract is that it can be pasted back into A12 — emitting an index A12 never writes is the interop rule's core defect, not a convenience. What was genuinely missing is that `outcomes[].field` only *cross-referenced* the message record's account, so a compute consumer never saw it; both properties now state it. Two consequences worth knowing rather than discovering twice: a bare level is repetition 1 and never "no repetition", and a **form-level outcome is therefore indistinguishable as a string from row 1** (`Basket/Subtotal` and `Basket/Lines/Total` are both bracket-free) — which levels repeat is a model fact this address does not restate. *(Locked: `adapter.laws.KernelProbeRepeatableAddressTest` — three repetitions, the bare/indexed pair, the form-level collision, and the same form in `formalErrorsInOperands`. `KernelProbeComputeTest`'s address row cannot carry this: its model has no repeatable level, so it locks the spelling and the sort only.)*

The probe's projector stays test-only and independent from the CLI implementation, but no longer drifts unchecked across the module boundary. `:adapter` publishes that projector through Gradle test fixtures, and `:cli`'s `MessageAddressPolicyEquivalenceTest` passes one shared table of typed pointers to it and to `A12Pointers.canonical`. This test-only edge avoids both bad alternatives: reversing the production dependency or publishing the house fallback as interpreter API.

**The arm is a projection of the coordinate, so never correlate the two.** The house arm is chosen when `toExactPointer()` returns `null` — a non-final level carrying a wildcard or a negative index, the final one a negative index, or an empty level name — and on the root, whose conversion raises *(corrected 2026-10-24: this named non-final wildcards and unresolved indexes only, and said "exactly")* — so "slash-led addresses are the ones with an unbound level" is true of every artifact this probe will ever write, by construction, and measures nothing. Read the vector, not the spelling, whenever the question is about coordinates. *(Written 2026-08-31, after a12-kernel-lean derived an address-KIND rule from that correlation across its artifacts and this project then recorded the agreement as corroboration in [KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf228); the rule itself holds — it was independently measured off `repetitionIndexes()` — but the correlation was never evidence for it.)*

### The `computations` block

Present on each engine block exactly when the request asked for `compute`; **absent means not observed**, never "nothing computed", which is why it is omitted rather than rendered empty.

```jsonc
"computations": {
  "outcomes": [                                     // sorted by `field`
    { "field": "Order/LineTotal",     "cleared": false, "errored": false, "value": "50.00" },
    { "field": "Order/LoyaltyPoints", "cleared": false, "errored": true,  "value": "10" },
    { "field": "Order/OrderTotal",    "cleared": true,  "errored": false }
  ],
  "formalErrorsInOperands": [
    { "code": "zahlFalscheAnzahlNachkommastellen", "field": "Order/ShippingFee", … }
  ]
}
```

**Three channels, and no pair of them collapses.** `errored` is not "no value": the kernel returns an out-of-range computed number and flags it ([KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf19)), so an errored outcome may still carry `value`. An instance is never both `errored` and `cleared`. `field` is one address channel in the same published form as a message's — the kernel's deprecated `getPath()` spelling is deliberately not published, because one artifact carries one address contract.

**`value`'s absence on a clear is a deliberate normalization, not a passthrough.** Measured 2026-08-12 on **both codegen strategies**: a cleared instance's `getStringValue()` is `Optional.of("")` — *present and empty*, and not an echo of the input cell it erased (`ComputeResultChannelsLawsTest`). The two-strategy reading was added after a peer review noticed the measurement had run on the shared dynamic engine alone; a normalization the artifact applies unconditionally cannot rest on one generator. Forwarding it would publish `""` for every clear and alias a clear with a genuinely computed empty string; dropping a constant that carries no information is what keeps the two apart. Whether a *computed* empty string is reachable at all is per result type and not settled here — [IF123](INTERPRETER-FINDINGS.md#if123) measures the empty-store gate on the kernel's `VkString` arm and records that `VkBigDecimal` has no empty arm — so the two-state contract is a guarantee of the **shape**, held whether or not a given kernel reaches both states.

### The `computeResults` block

Present on each engine block exactly when the request asked for `computeResults`; **absent means not observed**. It reads the *same* `compute()` call as `computations`, through the kernel's own **result** rather than through the change projection built out of it.

```jsonc
"computeResults": {
  "withoutErrors": [                                      // getComputedFieldInstancesWithoutErrors()
    { "field": "Order/LineTotal", "value": "10.00" }
  ],
  "errored": [                                            // …WithErrors(), each with its OWN message
    { "field": "Order/LoyaltyPoints", "value": "10",
      "errorMessage": { "code": "zahlZuGross", "field": "Order/LoyaltyPoints", … } }
  ],
  "applied": { "Order": { "LineTotal": 10.00, "Quantity": 2, "UnitPrice": 5.00 } }
}
```

**`withoutErrors` includes unchanged successes as well as changed successes; it excludes errored and cleared instances.** `getComputedFieldInstancesWithChanges()` is by the kernel's own javadoc a subset of `getComputedFieldInstancesWithoutErrors()` — the instances whose computed value *differs* from the document's — and `outcomes[]` is built from `WithChanges ∪ WithErrors ∪ Cleared`. So **a computation that successfully recomputed a value the document already held appears in no `computations` channel at all**, and only this block sees it. *(Measured on kernel 30.8.1, both strategies: a document seeding `LineTotal: 10.00` for a computation that yields `10.00` produces an empty `outcomes[]` for that field and a `withoutErrors` entry carrying `10.00`.)*

**`applied` is the only observable a separate-destination computation has.** The outcome channels report what was *computed*; nothing in them reports what the document then *holds*. This is A12's canonical Document JSON after `IDocumentComputationResult.applyTo(DocumentV2)`, written by the kernel's own `IDocumentV2Serializer` and embedded as an object rather than a quoted string. `applyTo(DocumentV2)` returns an updated copy and leaves the row's instance untouched, so asking for this cannot perturb the row's other channels.

**`errored[].errorMessage` closes the third channel** this spec used to name as unexposed — `IComputedFieldInstance.getErrorMessage()`, projected through the same `Message` shape the validation channel uses, so one parser reads both.

**Read this block with a `BigDecimal`-configured parser.** `applied` carries A12's own decimals with their scale, and A12's scale *is* the value — the runtime re-renders a NumberType's text from the stored `BigDecimal` and its length bound counts that text's characters ([KF1](KERNEL-FINDINGS.md#kf1)). A default Jackson mapper reads `4.00` into a `double` and hands you back `4.0`. The artifact's bytes are correct either way; your reader decides whether you can see it. *(Locked: `adapter.laws.KernelProbeComputeResultsTest` — the superset instance with its change-projection control, the applied destination, the per-instance message, both strategies, byte identity, and the scale asserted on the artifact's text.)*

**The same fidelity rule binds the request, and it did not always.** A `documentInline` row is re-serialized out of the parsed request before the kernel sees it, and until 2026-09-22 that parse used a default mapper — so an inline `5.00` reached the kernel as `5.0` while the identical document given by `document:` path reached it intact, because a path's bytes are never re-parsed. The two ingresses are now a locked control pair (`adapter.laws.KernelProbeDocumentFidelityTest`). If you pinned an artifact produced from an inline document carrying a scaled decimal before that date, re-run it: the observation may have been of a document you did not write. The write-back had the same defect for a negative scale until 2026-10-09: written in plain notation, an inline `1e3` reached the kernel as `1000`, scale 0 instead of −3, where a file delivers `1e3` and A12 stores `1E+3` in a String field ([KF1](KERNEL-FINDINGS.md#kf1)). A positive scale was never affected, so `0.0000001` reached it intact and is stored `1E-7` either way. The inline document is now written in its nodes' own notation (`adapter.laws.KernelProbeExponentFidelityTest`); re-run an inline artifact taken before that date if it carries an exponent that leaves a negative scale.

### What `formalErrorsInOperands` is, exactly

It is the kernel's **pre-computation formal validation of operand fields**, and the owner states its scope precisely (`IDocumentRtService.compute`): before any computation runs, *every non-computed field that is an operand of any computation* is formally validated, and **"Regardless of the formal errors found, computations will be performed. Any computations relying directly or indirectly on invalid fields will be skipped."**

So it is an **inventory, not a causal attribution** — emitted whether or not anything was skipped, and naming no edge to the outcome it explains. Intersecting a computation's declared operands with this inventory does not prove why its target cleared: an unmet precondition can bypass the malformed operand. Per-target causation is unavailable from these channels; use a [dependent-read discriminator](#distinguishing-cleared-targets) to observe the resulting read behavior. Three consequences, each of which a "cause of a clear" reading gets wrong:

- **It is a filter, not a copy of `messages`.** A field carrying the same formal-error code but which is an operand of *no* computation appears in `messages` and not here — measured on kernel 30.8.1 with two `zahlFalscheAnzahlNachkommastellen` in `messages` and one here. But "operand of" is **not** a runtime read trace: the operand set includes every field *contained by* a referenced group, which no evaluation need touch. The set is the kernel's `CalculationOperandsCollector`, locked by `adapter.laws.ComputationEagerPrepassLawsTest`.
- **`non-computed` is load-bearing.** A field cleared because the computation it read was itself ERRORED names nothing here — a computed field is outside this channel's scope by the owner's own sentence, and the kernel additionally erases the cause across the dependency edge (`adapter.laws.ComputedDependencyCauseErasureDiffTest`). That clear is reported as a clear, naming nothing, which is the kernel's answer rather than a gap in the exposure. The per-instance `getErrorMessage()` on the ERRORED producer is the channel that carries it, and since 2026-09-22 the `computeResults` block exposes it.
- **A formal error here does not imply anything was skipped.** In the worked artifact, `Order/ShippingFee`'s error sits in this channel while `Order/LineTotal` — which does not read it — computes normally.

### Distinguishing cleared targets

**Clearing reason and resulting formal state are unavailable in the Kernel result API.** A clean precondition skip and a poisoned computation both report `cleared: true, errored: false`; neither becomes an `errored` result just by requesting `computeResults`. That block carries messages for actual errored results, not silent poisoned clears. `formalErrorsInOperands` is not a per-target reason, and the applied empty value supplies none. The loss occurs inside the Kernel before the adapter: [KF240](KERNEL-FINDINGS.md#kf240) records the source boundary, existing locks and current batch measurement. Revisit exposing state only when the public Kernel result exposes it directly.

For a numeric target, the smallest reliable probe is one dependent numeric copy in the **same computation call**. Seed both targets to make their clears observable. A clean empty source reads as `0`; an invalid source poisons the reader and clears it too. This measures the downstream read state, not a unique cause. An empty target without a dependent is insufficient.

The following commands run from the repository root using the existing CLI authoring surface. They create a scratch model under `/tmp/dmkits-clear-probe` with `Target = [Source]` guarded by `FieldFilled(Gate)`, and `Reader = [Target]`:

```sh
mkdir -p /tmp/dmkits-clear-probe
bin/dmtool model new --id ClearProbe_DM --root Probe --locale en_US \
  -o /tmp/dmkits-clear-probe/ClearProbe_DM.json
for field in Source Gate Target Reader; do
  bin/dmtool -m /tmp/dmkits-clear-probe/ClearProbe_DM.json field add \
    --group /Probe --name "$field" --kind NUMBER
done
bin/dmtool -m /tmp/dmkits-clear-probe/ClearProbe_DM.json computation add - <<'JSON'
{"computedField":"/Probe/Target","commonPrecondition":"FieldFilled(Gate)","alternatives":[{"operation":"[Source]"}],"messages":[{"locale":"en_US","text":"Target computation."}]}
JSON
bin/dmtool -m /tmp/dmkits-clear-probe/ClearProbe_DM.json computation add - <<'JSON'
{"computedField":"/Probe/Reader","alternatives":[{"operation":"[Target]"}],"messages":[{"locale":"en_US","text":"Reader computation."}]}
JSON
cat > /tmp/dmkits-clear-probe/request.json <<'JSON'
{
  "model": "ClearProbe_DM.json",
  "observe": ["validateFull", "compute", "computeResults"],
  "rows": [
    {"name":"unmet", "documentInline":{"Probe":{"Source":"bad","Target":7,"Reader":7}}},
    {"name":"met", "documentInline":{"Probe":{"Source":"bad","Gate":1,"Target":7,"Reader":7}}},
    {"name":"invalid-guard", "documentInline":{"Probe":{"Source":"bad","Gate":"bad","Target":7,"Reader":7}}},
    {"name":"valid", "documentInline":{"Probe":{"Source":2,"Gate":1,"Target":7,"Reader":7}}}
  ]
}
JSON
./build.sh :adapter:kernelProbe \
  -Prequest=/tmp/dmkits-clear-probe/request.json \
  -Pout=/tmp/dmkits-clear-probe/artifact.json
jq '.rows[] | {name, enginesAgree, outcomes: .engines[0].computations.outcomes}' \
  /tmp/dmkits-clear-probe/artifact.json
```

The first two rows have the same source-error inventory and identical target outcomes. Their reader separates them (abbreviated projection):

```json
{"name":"unmet","enginesAgree":true,"outcomes":[{"field":"Probe/Reader","cleared":false,"errored":false,"value":"0"},{"field":"Probe/Target","cleared":true,"errored":false}]}
{"name":"met","enginesAgree":true,"outcomes":[{"field":"Probe/Reader","cleared":true,"errored":false},{"field":"Probe/Target","cleared":true,"errored":false}]}
```

The malformed-guard row also clears both targets and adds the guard error to the inventory. The valid row computes `2` for both. All four rows run in one request, reusing each strategy's compiled model. `dmtool model compute` uses the interpreter and expresses these results as `outcome: "cleared"` or `"value"`; it is not a Kernel measurement substitute. The example uses the existing observation fields unchanged.

**The `schemaVersion` stayed `1`, deliberately.** A consumer receives `computations` only by asking for it, so no artifact an existing consumer can obtain changes shape and no existing key changes meaning — while bumping would have broken the harder guarantee, that an unchanged request keeps producing bytes an already-pinned SHA-256 matches. **What that costs, stated rather than glossed:** `schemaVersion: 1` no longer determines the artifact's *shape*, only its key meanings, so §6's "an artifact carries its own version header" now means a consumer must also know what it asked for. The schema document itself was edited in place under an unversioned `$id`, so a cached copy and a re-fetched one differ. Both were judged cheaper than breaking a pin.

### The `partialMessages` block

Present exactly when the request asked for `validatePart`, beside `messages` inside each engine block. It carries what that strategy's `validatePart` raised under the row's own `relevant` coverage, sorted on the same total order as `messages`.

**What it reaches that `validateFull` cannot.** Two shapes, and both are properties of the CALLER rather than of the document — the same document answers differently depending on what was declared relevant, which is exactly why no full validation can express them: a **call-local partial coverage** (a set that admits some rules and excludes others), and the empty **`noneRelevant`** coverage. Added 2026-08-31 for a12-kernel-lean, whose filled-group-count unavailability rows are blocked on those two and nothing else.

**A row's `relevant` is required exactly when the observation is asked for, and refused otherwise** — the same both-directions rule the other observations carry. Without the coverage a `validatePart` would silently behave as a `validateFull`; without the observation the key is one nothing reads. An **empty array is not an omission**: it is the `noneRelevant` request, so `partialMessages` renders even when empty, and `Row.relevant` is an `Optional<Set<…>>` end to end rather than a set that happens to be empty.

**`reps` is one entry per path PART, not per repeatable part.** `DocumentPointerImpl.parsePathParts` refuses a mismatch with `fullName and repetition have different length`, so `/Order/Lines/Amount` needs three entries even though only `Lines` repeats; `0` is the kernel's wildcard for "every repetition of that element". **Omit `reps` entirely** for the all-wildcard coverage of the right length — the probe derives it from the path, which is what a consumer almost always wants and removes a hand-counting footgun that cost one measurement here.

`enginesAgree` covers this channel too: whether the static-JAVA codegen path gates a relevant set identically to dynamic-Groovy is precisely the kind of split this probe exists to surface, and omitting it would report agreement over an unread channel.

### The `mandatoryInformation` block

Present exactly when the request asked for it, at the artifact's top level rather than inside a row, because it is a property of the model's rule collection and not of any document. Three sorted name lists, keyed by the kernel's own getter names:

| key | kernel getter | what it holds |
|---|---|---|
| `mandatory` | `getMandatory()` | the fields the rule collection makes mandatory outright |
| `mandatoryForRootGroup` | `getMandatoryForRootGroup()` | the fields mandatory once their root group is present |
| `mandatoryRootGroups` | `getMandatoryRootGroups()` | the root groups the collection makes mandatory |

The kernel derives these from rule **conditions**, not from the fields a rule references: `FieldNotFilled(/A/a)` makes `/A/a` mandatory, `GroupNotFilled(/A)` makes a root group mandatory, and a starred negative quantifier makes neither, because it does not hold for every repetition. A field named only in an ordinary comparison appears nowhere. A **count** shape (`NumberOfFilledFields(…) < n`) names no field either, reaching only `mandatoryRootGroups` — and only in the `<` direction — so a consumer must not read an empty field set as "this model requires nothing in that group".

**Two shape facts a consumer joining these names against model paths needs, both measured rather than assumed** ([KF225](KERNEL-FINDINGS.md#kf225), locked in `KernelProbeMandatoryInformationTest`): the field sets spell paths with a leading slash and `mandatoryRootGroups` does not; and `GroupNotFilled` on a nested group yields the enclosing ROOT group, not the group named. The rule collection passed in is the kernel's own and **unfiltered**, so it includes the generated self-validation of every computation — filtering would insert an editorial choice of ours into a kernel measurement.

Each list is sorted. `IMandatoryInformation` exposes `Set<String>`, which carries no order contract, so an artifact pinned by SHA-256 cannot depend on one; nothing guaranteed is lost.

### The `evaluationRaises` list

Present inside an engine block exactly when A12 caught at least one exception while evaluating a rule in that block's completed channels. A12 reports such a rule as **one VALUE message** at the rule's first error address, whose text is a fixed prefix plus the exception's message: the generated rule body catches the raise and calls `MainValidatorController.addValidatorMessage(field, idx, code, e)` (`CodeGenRuleDefinition.st`, Java and Groovy dialects, kernel 30.8.1), so the message keeps neither the exception's class nor its cause. Each entry names its `observation`, its `origin`, and the cause `chain` in the `fault` chain's form. Added 2026-10-24 for a12-kernel-lean, whose record for a custom condition beside a group target was blocked on telling a kernel failure from a probe one.

**`origin` is decided by identity, not by reading text.** It is `probe` when some link of the chain is an exception one of the probe's declared conditions threw (A12 wraps those, so the probe's own is then the second link), and `kernel` otherwise. An unregistered name is `kernel`: A12 raises the missing lookup itself.

**The throwable reaches the probe through A12's logger, the only place it leaves the kernel.** `addValidatorMessage` hands it to `ValidationLogger.error`, the one Throwable-carrying error call in `kernel-core-runtime`'s main source, so the adapter test classpath registers its own SLF4J provider (`KernelRaiseCapture`), which collects that logger's throwables per thread while a channel runs and is a no-op logger everywhere else. **Absent therefore means "A12 caught none", not "nobody listened"**: the probe refuses to observe any row unless that provider is the one SLF4J bound and a class on the classpath carries the logger name it listens for. The name embeds the runtime's release (`rt._31_1` on kernel 31.1.1), so it is matched by pattern; the second refusal exists because the kernel 31.1.1 bump left the provider bound and the name stale, and every raise read as absent until `KernelProbeCustomConditionTest` noticed.

**What it cannot say.** The entries are in the order A12 caught them and are not keyed to messages, because A12 logs the exception without the rule's code. A raise in a channel that then faulted is not listed, for the reason that channel publishes nothing. `enginesAgree` covers the list, so the two strategies raising alike is a stated agreement.

**The `schemaVersion` stayed `1`** for the reason `computations` gave: the list is new, no existing key changes meaning, and an artifact whose rules all evaluated keeps its bytes. What changes is an artifact that already held a raise-born message: rerun, it gains the list, and its SHA-256 moves with it. *(Locked: `adapter.laws.KernelProbeRuleRaiseTest` — a kernel-origin raise on both strategies with a field-target control that is called and raises nothing, the absent list on a clean row, the probe origin and the failed call at the recorder, and the schema; the unregistered name in `KernelProbeCustomConditionTest`.)*

### The `dynamicEnumerations` block

The same terms as its sibling: top level, present exactly when asked for, absent otherwise, so no already-pinned artifact changes bytes. Two keys — `valuesByField`, each dynamic-enumeration field's full name mapped to the constant values the kernel extracted for it, and `diagnostics`, every problem the derivation reported, each with its `code`, `severity`, `rule` and the kernel's own `message`.

**It exists because `checkConsistency` has no route to the derivation.** `DynamicEnumerationService`, whose `DynEnumErrorType` declares twelve classes, is CONSTRUCTED at two sites in the 30.8.1 checkout: that facade method, and `ServiceFactory.getDynamicEnumerationService(...)`, which has no in-kernel caller at all and exists to hand a per-rule `checkDynamicEnumerationRule` to external consumers. Neither site sits on a `DocumentModelConsistencyRule`, so no `model check` / `rule check` / `--dry-run` route can produce any of the twelve however the model is shaped. Reported to us by a12-kernel-lean, 2026-09-07, who suggested exactly this shape; measured and locked here as [KF277](KERNEL-FINDINGS.md#kf277).

**`diagnostics` is NOT a defect list, and misreading it as one is the trap this block carries.** The derivation checks *every* rule and reports one problem for each that is not a dynamic enumeration **in form** — which is true of nearly every ordinary rule. Read the block as "which rules are dynamic enumerations, and why the rest are not". The measured discriminators, one per way of being malformed:

| rule shape | class reported |
|---|---|
| `FieldFilled(/Order/ProductCode) And [/Order/ProductCode] != "PREMIUM"` (the canonical form) | none — the field appears in `valuesByField` with `[PREMIUM]` |
| an ordinary single-leaf rule, e.g. `FieldFilled(/Order/ProductCode)` | `MVK_DYNENUM_INVALID_GATE` |
| an ordinary conjunction of two presence checks | `MVK_DYNENUM_INVALID_CALCOP` |
| the canonical form with `==` instead of `!=` | `MVK_DYNENUM_INVALID_CALCOP_OP` |
| a gate naming a field other than the rule's error field | `MVK_ERROR_WRONG_DYNENUM_FIELD` |
| a target field the shape forbids, e.g. a NUMBER with fractional digits | `MVK_DYNENUM_INVALID_TYPE` |
| a field reference on the right of `!=` with no safe guard securing it | `MVK_DYNENUM_INVALID_FIELD_REFERENCE` |

The count is **per rule, not per model**: two ordinary rules draw two diagnostics, and one canonical rule beside one malformed one draws exactly one while still extracting the canonical rule's values.

**Attribution is the kernel's own, and it usually names no rule.** The collection-wide call leaves `rule` null, because the kernel's problem carries the rule only where its message names one. The attributable question — "is *this* rule a well-formed dynamic enumeration?" — is `KernelAdapter.dynamicEnumerationDiagnostics(ruleFullName)`, which asks about one rule and stamps the answer with that rule's path. Passing a model-level path into the collection-wide call would overwrite the kernel's own attribution with something less specific, so it does not.

### The `repetitionIndexes` coordinates

A request that observes `repetitionIndexes` gets, on every message the artifact publishes, the kernel's own coordinates for each of its addresses: `PartiallyKnownDocumentMultiPointer.repetitionIndexes()`, verbatim, so the last coordinate the house arm drops is kept, and `0`, `-5` and `-6` stay what A12 put there. It is one object per message, written after `fillToFix`:

```json
"field": "/Order[1]/Lines[1]/Slots",
"repetitionIndexes": {"field": [1, 1, -5], "referenced": [[1, 1, -6, 1], [1, 1, 1]], "fillToFix": [[1, 1, -6, 1]]}
```

`referenced` and `fillToFix` are aligned by position with the address lists of the same name. Those lists then sort by address and, among equal addresses, by coordinates, because two house-form addresses can share a string and differ only in the coordinate it drops. Two messages equal on every string channel, such as two firings of one group-target rule, order by their vectors as numbers, so row 2 precedes row 10. Every message takes the same projection, including the operand formal errors and a result instance's error message, so like still compares with like. The value runs no kernel channel of its own, so it needs what the message channel needs: rows and `validateFull`.

**Why it is opt-in, and why that also decides when `request.observe` is written.** a12-kernel-lean pins artifacts by SHA-256 and asked for the vector on condition that an unchanged request keeps its bytes, and that an artifact carrying it records what was observed. The other observations announce themselves through the blocks they add; this one changes keys inside messages that every request has. So the artifact records `observe` exactly when it includes `repetitionIndexes`, the one observation nothing else would reveal, and it never records it otherwise, because that would rewrite every pinned artifact. *(Locked: `adapter.laws.KernelProbeParallelIterationPointerTest`, which measures `[1, 1, -5]` and the `-6` references on a real kernel run with the strings unchanged; `adapter.laws.KernelProbeRepetitionIndexesTest`, for the refusal, the tie order and the writer; and the existing byte pins, for unchanged requests.)*

### Determinism is structural

Two runs over identical input and an identical source tree produce **byte-identical** artifacts, which is what lets a consumer pin one by SHA-256. It is arranged rather than hoped for:

- messages, and computed outcomes, are each put in a total order over their published channels, never kernel emission order — an outcome sorts by `field` first, then by the remaining channels, with value *presence* ordered ahead of value text so an absent value and a computed empty string cannot tie;
- `referenced` and `fillToFix` are sorted, by address and then, when the request observes them, by coordinates — the channels are compared as sets everywhere they are compared, and an unstable order would break the pin;
- the header carries **no** clock reading, and a model whose rules read the current date needs `clock` in the request to reproduce;
- the renderer pins a `\n` indenter rather than inheriting the platform separator;
- the JVM default zone is pinned to `UTC`, because A12's consistency gate judges a DateTime constant against it rather than against the model's declared zone ([KF231](KERNEL-FINDINGS.md#kf231)): under Europe/Berlin a model whose constant falls into a daylight-saving gap is refused, under UTC it evaluates. The task passes `-Duser.timezone=UTC`, and a hand-over run under any other default refuses (§5), so the header needs no zone member.

`producer.source.state` says whether the **revision** or the **worktree** produced the observation: `CLEAN` means the tree matched `revision` exactly, `DIRTY` that it did not and the artifact is therefore not reproducible from `revision` alone. It is measured by [`scripts/SourceProvenance.java`](../scripts/SourceProvenance.java), the same program that stamps dmtool's own version resource.

## 5. Refusal

**A non-`CLEAN` working tree fails the run before anything else happens, and nothing is written.** A `DIRTY` artifact's `revision` addresses a tree that never existed anywhere and an `UNKNOWN` one addresses nothing at all, so neither can be pinned — which is the only thing the revision is for. Both refuse; `UNKNOWN` is deliberately not the lenient case, or the class stays open through the state that means "we could not tell". For a local exploration run whose artifact will not be handed over, `-PprobeAllowUnpinned=true` permits it and **leaves the stamp alone**: the artifact still declares the state it was produced under, so the escape cannot launder one. **A hand-over run under any JVM default zone but `UTC` refuses the same way**, before anything is read; the escape lifts it too. *(Enforced by `KernelRuntimeProbe.requirePinnedSource` and `requirePinnedTimeZone`, locked does-fire and does-not-fire by `adapter.laws.KernelProbePinnedSourceTest`; the Gradle task sets the enforcement flag explicitly rather than the probe inferring it from an absent property, so the in-process callers in `KernelRuntimeProbeTest` are unaffected. Added 2026-08-31 after a12-kernel-lean discarded a probe round on a `DIRTY` artifact — a window it cannot close from its side, since writing into this checkout is outside what its instructions permit.)*

**A kernel RAISE is the opposite case, and it is retained rather than refused.** A model whose declared `CustomFieldType` has no registered validator makes `validateFull` throw; the run used to die with the output path unwritten — losing the faulting row, the request's other rows, and the strategy that had not run yet — so the one observation this instrument could not pin was a loud kernel failure, which is itself something a spec makes claims about. It now **captures the raise per (row, strategy), keeps going, writes the artifact, and fails at the end.** The faulting block carries `fault` beside **the channels the raise did not reach**, and never the one it happened in: `messages` is absent, not `[]`, when the raise was *at or before* `validateFull`, for the same reason an unobserved `computations` block is omitted — an empty list beside a raise reads as "the kernel found no problem", and the record refuses the pair outright rather than merely not writing it. `fault.observation` names the locus: `validateFull`/`compute`/`validatePart`/`computeResults` for a channel that threw, `documentInstance` for a document that could not be deserialized, and `engineBuild` for a strategy whose engine could not be built, which rides **every** row of that strategy because that is the blast radius of one failed build.

*(**Corrected 2026-09-22.** The rule was the stronger "a faulted block carries no channel at all", justified by "a strategy that cannot validate a document is not one whose compute answer means anything". That is true in one direction and false in the other — a strategy that validated a document may still raise later — and applied symmetrically it cost a consumer observations they already had, which §3 promises it will not. Channels are read in request order, so the raise leaves the ones before it finished and the ones after it unread; only the one it happened in could be half-read, and only that one is suppressed. The pre-2026-09-22 fixture is unaffected, because its raise is in `validateFull`, the first channel — which is why `KernelProbeFaultCaptureTest`'s artifacts stayed byte-identical through the change and its `aBlockMayNotPublishTheChannelItRaisedIn` had to grow the surviving-channel case to stop being satisfied by the old rule.)* `enginesAgree` covers the fault like any other channel, so two strategies raising identically is a stated agreement — the claim a collapsed run could not make. **Two caveats, stated rather than engineered away.** `chain[].type` is machine-independent, `chain[].message` is the kernel's own text and is not normalized, so if a raise embeds environment-specific detail a fault-carrying artifact is byte-stable only as far as that text is. And a **request-level** cause reaches the same seam as a document-level one — the kernel reports an undeclared `locale` from inside `validateFull` — so it is captured the same way rather than classified, because the probe has measured no discriminator. The signature a consumer reads for that class is **every row faulting on every strategy at the same locus with the same chain** — and it is **one-directional**: a request-level cause always produces it, but producing it does not imply one, because a request whose every row happens to reach the same document-level raise produces the identical shape. (This estate's own fault fixture is that counter-example one row away: it carries a clean row only because one of its two rows leaves the custom cell empty.) The run fails naming the reason either way, and the artifact then says why nothing was observed instead of not existing. *(Locked by `adapter.laws.KernelProbeFaultCaptureTest`: the artifact exists before the failure, both strategies are observed, the chain reaches the kernel's own reason, a block never publishes the channel it raised in — while a channel the raise never reached survives — the clean row in the same request keeps its messages, and the fault-carrying artifact still satisfies the published schema. Added 2026-09-04 on a12-kernel-lean's instrument note.)*

A model the kernel's consistency oracle reports an `ERROR` on **fails the run with those diagnostics and writes nothing**, whatever the request asked to observe. This is the load-bearing failure mode: an inconsistent model evaluates to no messages at all, and a written artifact full of empty message lists would read as "nothing fired" — a wrong answer that looks like an answer. The gate is `KernelConsistencyOracle`, the same oracle `rule check` reports `KERNEL_CONFIRMED` from, so anything the probe accepts is something `rule check` accepts.

## 6. Producer and consumer responsibility

a12-dmkits owns kernel invocation, engine identity, and the artifact schema and its `schemaVersion`. A consumer owns pinning the bytes and replaying them. An artifact carries its own version header, so artifacts already produced stay valid evidence if the capability is later retired — at which point a runtime question returns to a cross-project experiment request.

## 7. Scope: what it deliberately is not

- **Not a CLI verb**, and never a change to the native profile, `Cli.JVM_ONLY_VERBS`, the Groovy exclusion, or the retired `--kernel` flag (§1).
- **Not an authoring surface.** No inline rule or computation injection: that would be a second way to spell a rule beside the typed builder and `rule-add.schema.json`, and the probe's input is a finished model. A computation reaches the probe by being *in* the model, added with `dmtool computation add`, exactly as a rule does.
- **Not a gate.** It asserts nothing, is absent from `check`, and its outputs are never cached.
- **Not new semantics.** It reports what the kernel reports.

## 8. Comparing the interpreter over a request

`./build.sh :adapter:probeDifferential -Prequest=<request.json>` evaluates the same request on the interpreter and on the dynamic Kernel, and prints one verdict per row and call: `ok` with the counts compared, `DIFF` with each side's own lines per channel (`K>` A12, `I>` the interpreter), or `UNSUPPORTED` with the rules the interpreter declines. It fails on anything but `ok`. This is the check behind every "reconciled" in an a12-kernel-lean letter; the probe re-run (`kernelProbe`) is the other half, comparing their artifact with ours.

- **One reading of a request.** It uses the probe's parser and the probe's consistency refusal, and runs under the probe's JVM setting (assertions off, `UTC`), so the two tools cannot read one request two ways. `GateTierWiringTest` locks the setting on both.
- **What it compares.** `validateFull` on messages, computed outcomes, operand errors and authored message texts, and `validatePart` on messages for a row carrying `relevant`, through the same `DocumentDifferential` the differential tests use. Messages are compared in `en_US`, whatever the request's `locale`.
- **What it refuses or names.** A request binding `customConditions` or `customFieldTypes` is refused before anything runs, since `DocumentDifferential` binds neither on the kernel side; compare such a request with the probe's artifact instead. An observed channel it does not compare (`computeResults`, `mandatoryInformation`, `dynamicEnumerations`, `repetitionIndexes`) is named on a `not compared:` line, never dropped silently.

*(Locked by `adapter.laws.ProbeRequestDifferentialTest`: a clocked request agreeing on both calls, a disagreement's per-channel lines with an agreeing positive control, the binding refusal, and the named channel.)*
