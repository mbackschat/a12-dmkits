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
| Locks | `adapter.laws.KernelRuntimeProbeTest` + `KernelProbeComputeTest` + `KernelProbeMandatoryInformationTest` + `KernelProbeValidatePartTest` (kernel-backed), `adapter.laws.KernelProbeArtifactTest` + `KernelProbeComputeShapeTest` (shape), `GateTierWiringTest` (the task stays a measurement) |

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
| `validatePart` | per row | `IDocumentRtService.validatePart` | each engine block's `partialMessages` |
| `mandatoryInformation` | **per model** | `IRuleCollectionMetaInformation.getMandatoryInformationFromRules` | the top-level `mandatoryInformation` |

Not `validatePart` — a probe that guessed at more surface than it was asked for would be inventing contract (§7).

**A model-level observation reads no document, and the request contract follows from that rather than from a list.** `mandatoryInformation` derives its three name sets from the model's rule CONDITIONS, so it runs once for the whole request — not per row, not per strategy — and a request may ask for it with no `rows` at all. Its scope is carried on the `Observation` enum, and both halves of the request rule are derived from that: a request naming any per-row observation must carry rows and must observe `validateFull`; a request naming only model-level ones must carry none, because a row nothing looks at is the same silent no-op the `validateFull` rule refuses. Built for a12-kernel-lean's `EXP-2026-08-30-01`, which needs to compare the derived sets across two models differing in one rule literal — a question no document evaluation can reach. The kernel types stay behind `KernelAdapter.mandatoryInformation`, which returns plain `Set<String>`, so nothing about dmtool's kernel boundary or its native build changes.

**A strategy split is the finding, so it is never merged away.** Each row keeps one block per strategy and `enginesAgree` is *derived* from them; no caller can author it. On a legal-input split the ratified oracle is dynamic Groovy and the static-Java result is characterized, never adopted (INTERPRETER-FINDINGS IF136, [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md) §11). It is derived from **every** channel a block reports, not from the messages alone: two strategies that agree on every message and disagree on one computed outcome are a split, and a flag computed from one channel would have reported agreement its own row contradicts.

## 3. The request

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

**`validateFull` cannot be dropped whenever any row is observed.** An engine block's `messages` is a **required** key of `schemaVersion` 1, so a block whose list was empty *because nobody looked* would be indistinguishable from one where nothing fired — §5's failure mode, an empty observation that reads as an answer. `computations` avoids that by being **absent** when unobserved, and `messages` could have been made optional the same way; it was not, because that changes the shape of an artifact existing consumers already pin, which is the one thing this change is not allowed to do. So the refusal is a **compatibility** decision, not a claim that the ambiguity is unsolvable — and its cost is real: a consumer that wants only compute pays `validateFull` on both strategies for every row. `observe: ["compute"]` is refused with that reason, as is an unknown entry and an explicit empty array (an omitted key means "the default"; an empty one means a mistake). The rule is scoped to per-row observations because a model-level-only request observes no row at all — `observe: ["mandatoryInformation"]` with no `rows` is the shape that capability exists for, and supplying a row to it is refused from the other side. The published request schema encodes the same three cases, and `KernelProbeMandatoryInformationTest.theSchemaAndTheProducerAgreeOnTheModelLevelRules` fails if the two ever drift: an agent reads the schema before it runs the probe, so a schema that accepts what the producer rejects teaches the wrong shape.

**`locale` is not cosmetic.** The kernel refuses a locale the model has not *declared*, outright — measured 2026-08-11: probing a `de_DE`-only model at the default gives `IllegalArgumentException: Locale 'en_US' is not supported in the document model '…'`. So a German-only model is unobservable without this key rather than merely reported in English. Only the `message` channel is locale-dependent; `code`, `type`, `severity`, and every address are not. Both directions are locked by `KernelRuntimeProbeTest.theRequestPinsAnEvaluationLocaleBecauseTheKernelRefusesAnUndeclaredOne`.

## 4. The artifact

A header naming the producer, then one entry per row in request order, then one block per strategy carrying that strategy's complete observation: its message list always, and its `computations` when the request asked for one. A model-level observation sits beside `rows` rather than inside it — see "The `mandatoryInformation` block" below — and `rows` is then the empty array, because it is a required key of `schemaVersion` 1 that consumers already read. Each message uses the **key names of a `model eval` `messages[]` entry** — `code`, `field`, `severity`, `type`, `message`, `referenced`, `fillToFix` — so one parser reads dmtool's runtime output and this artifact alike.

Addresses keep dmtool's published **`a12-message-address`** contract, decided per address on the typed pointer: canonical A12 when it names exactly one cell (no leading slash, the implicit `[1]` omitted), and the interpreter house form when it does not, because A12 defines no pointer string for an address carrying a wildcard or an unresolved level ([CLI-SPEC](CLI-SPEC.md) [§5](CLI-SPEC.md#verb-surface)). **Test the leading slash to tell them apart.** Nothing is lost: only A12's own implicit `[1]` goes unwritten, so every wildcard and every row above the first survives verbatim — which is what makes the probe able to answer a coordinate question at all (`/Order[1]/Items[0]/Count` from a starred group operand beside `Order/Billing/Street` from an unstarred one).

**The omitted `[1]` is the one part of that contract a consumer meets before reading this, so it is stated at the property too, and it is not changing.** A per-row outcome for the first repetition is written bare — `Basket/Lines/Total` is row 1, `Basket/Lines[3]/Total` is row 3 — and a12-kernel-lean's agent spent a second probe run establishing which row an unindexed entry named before asking us to emit `[1]` (their instrument note, 2026-09-04). We do not, because `[1]`-omission is **A12's own** canonical form (`DocumentPointerImpl.toString`; an absent bracket *means* repetition 1, [KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf1)) and this field's whole contract is that it can be pasted back into A12 — emitting an index A12 never writes is the interop rule's core defect, not a convenience. What was genuinely missing is that `outcomes[].field` only *cross-referenced* the message record's account, so a compute consumer never saw it; both properties now state it. Two consequences worth knowing rather than discovering twice: a bare level is repetition 1 and never "no repetition", and a **form-level outcome is therefore indistinguishable as a string from row 1** (`Basket/Subtotal` and `Basket/Lines/Total` are both bracket-free) — which levels repeat is a model fact this address does not restate. *(Locked: `adapter.laws.KernelProbeRepeatableAddressTest` — three repetitions, the bare/indexed pair, the form-level collision, and the same form in `formalErrorsInOperands`. `KernelProbeComputeTest`'s address row cannot carry this: its model has no repeatable level, so it locks the spelling and the sort only.)*

The probe's projector stays test-only and independent from the CLI implementation, but no longer drifts unchecked across the module boundary. `:adapter` publishes that projector through Gradle test fixtures, and `:cli`'s `MessageAddressPolicyEquivalenceTest` passes one shared table of typed pointers to it and to `A12Pointers.canonical`. This test-only edge avoids both bad alternatives: reversing the production dependency or publishing the house fallback as interpreter API.

**The arm is a projection of the coordinate, so never correlate the two.** The house arm is chosen exactly when `toExactPointer()` returns `null`, which is exactly when a non-final level carries a wildcard or an unresolved index — so "slash-led addresses are the ones with an unbound level" is true of every artifact this probe will ever write, by construction, and measures nothing. Read the vector, not the spelling, whenever the question is about coordinates. *(Written 2026-08-31, after a12-kernel-lean derived an address-KIND rule from that correlation across its artifacts and this project then recorded the agreement as corroboration in [KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf228); the rule itself holds — it was independently measured off `repetitionIndexes()` — but the correlation was never evidence for it.)*

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

### What `formalErrorsInOperands` is, exactly

It is the kernel's **pre-computation formal validation of operand fields**, and the owner states its scope precisely (`IDocumentRtService.compute`): before any computation runs, *every non-computed field that is an operand of any computation* is formally validated, and **"Regardless of the formal errors found, computations will be performed. Any computations relying directly or indirectly on invalid fields will be skipped."**

So it is an **inventory, not a causal attribution** — emitted whether or not anything was skipped, and naming no edge to the outcome it explains. A consumer that wants the link joins the two channels itself and leans on the kernel's skip guarantee for the join; the edge is not published because the kernel does not publish it. Three consequences, each of which a "cause of a clear" reading gets wrong:

- **It is a filter, not a copy of `messages`.** A field carrying the same formal-error code but which is an operand of *no* computation appears in `messages` and not here — measured on kernel 30.8.1 with two `zahlFalscheAnzahlNachkommastellen` in `messages` and one here. But "operand of" is **not** a runtime read trace: the operand set includes every field *contained by* a referenced group, which no evaluation need touch. The set is the kernel's `CalculationOperandsCollector`, locked by `adapter.laws.ComputationEagerPrepassLawsTest`.
- **`non-computed` is load-bearing.** A field cleared because the computation it read was itself ERRORED names nothing here — a computed field is outside this channel's scope by the owner's own sentence, and the kernel additionally erases the cause across the dependency edge (`adapter.laws.ComputedDependencyCauseErasureDiffTest`). That clear is reported as a clear, naming nothing, which is the kernel's answer rather than a gap in the exposure. The per-instance `getErrorMessage()` on the ERRORED producer is the channel that would carry it, and it is **not** exposed today.
- **A formal error here does not imply anything was skipped.** In the worked artifact, `Order/ShippingFee`'s error sits in this channel while `Order/LineTotal` — which does not read it — computes normally.

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

### Determinism is structural

Two runs over identical input and an identical source tree produce **byte-identical** artifacts, which is what lets a consumer pin one by SHA-256. It is arranged rather than hoped for:

- messages, and computed outcomes, are each put in a total order over their published channels, never kernel emission order — an outcome sorts by `field` first, then by the remaining channels, with value *presence* ordered ahead of value text so an absent value and a computed empty string cannot tie;
- `referenced` and `fillToFix` are sorted — the channels are compared as sets everywhere they are compared, and an unstable order would break the pin;
- the header carries **no** clock reading, and a model whose rules read the current date needs `clock` in the request to reproduce;
- the renderer pins a `\n` indenter rather than inheriting the platform separator.

`producer.source.state` says whether the **revision** or the **worktree** produced the observation: `CLEAN` means the tree matched `revision` exactly, `DIRTY` that it did not and the artifact is therefore not reproducible from `revision` alone. It is measured by [`scripts/SourceProvenance.java`](../scripts/SourceProvenance.java), the same program that stamps dmtool's own version resource.

## 5. Refusal

**A non-`CLEAN` working tree fails the run before anything else happens, and nothing is written.** A `DIRTY` artifact's `revision` addresses a tree that never existed anywhere and an `UNKNOWN` one addresses nothing at all, so neither can be pinned — which is the only thing the revision is for. Both refuse; `UNKNOWN` is deliberately not the lenient case, or the class stays open through the state that means "we could not tell". For a local exploration run whose artifact will not be handed over, `-PprobeAllowUnpinned=true` permits it and **leaves the stamp alone**: the artifact still declares the state it was produced under, so the escape cannot launder one. *(Enforced by `KernelRuntimeProbe.requirePinnedSource`, locked does-fire and does-not-fire by `adapter.laws.KernelProbePinnedSourceTest`; the Gradle task sets the enforcement flag explicitly rather than the probe inferring it from an absent property, so the in-process callers in `KernelRuntimeProbeTest` are unaffected. Added 2026-08-31 after a12-kernel-lean discarded a probe round on a `DIRTY` artifact — a window it cannot close from its side, since writing into this checkout is outside what its instructions permit.)*

**A kernel RAISE is the opposite case, and it is retained rather than refused.** A model whose declared `CustomFieldType` has no registered validator makes `validateFull` throw; the run used to die with the output path unwritten — losing the faulting row, the request's other rows, and the strategy that had not run yet — so the one observation this instrument could not pin was a loud kernel failure, which is itself something a spec makes claims about. It now **captures the raise per (row, strategy), keeps going, writes the artifact, and fails at the end.** The faulting block carries `fault` and **no channel key at all**: `messages` is absent, not `[]`, for the same reason an unobserved `computations` block is omitted — an empty list beside a raise reads as "the kernel found no problem", and the record refuses the pair outright rather than merely not writing it. `fault.observation` names the locus: `validateFull`/`compute`/`validatePart` for a channel that threw (the first one to throw ends the block — a strategy that cannot validate a document is not one whose compute answer means anything), `documentInstance` for a document that could not be deserialized, and `engineBuild` for a strategy whose engine could not be built, which rides **every** row of that strategy because that is the blast radius of one failed build. `enginesAgree` covers the fault like any other channel, so two strategies raising identically is a stated agreement — the claim a collapsed run could not make. **Two caveats, stated rather than engineered away.** `chain[].type` is machine-independent, `chain[].message` is the kernel's own text and is not normalized, so if a raise embeds environment-specific detail a fault-carrying artifact is byte-stable only as far as that text is. And a **request-level** cause reaches the same seam as a document-level one — the kernel reports an undeclared `locale` from inside `validateFull` — so it is captured the same way rather than classified, because the probe has measured no discriminator. The signature a consumer reads for that class is **every row faulting on every strategy at the same locus with the same chain** — and it is **one-directional**: a request-level cause always produces it, but producing it does not imply one, because a request whose every row happens to reach the same document-level raise produces the identical shape. (This estate's own fault fixture is that counter-example one row away: it carries a clean row only because one of its two rows leaves the custom cell empty.) The run fails naming the reason either way, and the artifact then says why nothing was observed instead of not existing. *(Locked by `adapter.laws.KernelProbeFaultCaptureTest`: the artifact exists before the failure, both strategies are observed, the chain reaches the kernel's own reason, a faulted block publishes only `engine` and `fault`, the clean row in the same request keeps its messages, and the fault-carrying artifact still satisfies the published schema. Added 2026-09-04 on a12-kernel-lean's instrument note.)*

A model the kernel's consistency oracle reports an `ERROR` on **fails the run with those diagnostics and writes nothing**, whatever the request asked to observe. This is the load-bearing failure mode: an inconsistent model evaluates to no messages at all, and a written artifact full of empty message lists would read as "nothing fired" — a wrong answer that looks like an answer. The gate is `KernelConsistencyOracle`, the same oracle `rule check` reports `KERNEL_CONFIRMED` from, so anything the probe accepts is something `rule check` accepts.

## 6. Producer and consumer responsibility

a12-dmkits owns kernel invocation, engine identity, and the artifact schema and its `schemaVersion`. A consumer owns pinning the bytes and replaying them. An artifact carries its own version header, so artifacts already produced stay valid evidence if the capability is later retired — at which point a runtime question returns to a cross-project experiment request.

## 7. Scope: what it deliberately is not

- **Not a CLI verb**, and never a change to the native profile, `Cli.JVM_ONLY_VERBS`, the Groovy exclusion, or the retired `--kernel` flag (§1).
- **Not an authoring surface.** No inline rule or computation injection: that would be a second way to spell a rule beside the typed builder and `rule-add.schema.json`, and the probe's input is a finished model. A computation reaches the probe by being *in* the model, added with `dmtool computation add`, exactly as a rule does.
- **Not `validatePart`.** It is a real second gap — `RepetitionNotUnique` partial validation needs every composite-key cell relevant, and no route reaches it — but it stays out until asked for on its own terms, for the reason in §2.
- **Not a gate.** It asserts nothing, is absent from `check`, and its outputs are never cached.
- **Not new semantics.** It reports what the kernel reports.
