# Testing spec — the testing-approach map

The map of *how* a12-dmkits is tested — the kinds of test, what each one is for, where it lives, and which oracle backs it. This is the navigation layer; the enforced conventions stay in [`../CLAUDE.md`](../CLAUDE.md) ("Testing strategy") and the per-area detail stays in its own doc (linked below). Read this when you need to place a new test or recover the big picture without hunting across five files.

## The testing landscape — the four tiers

a12-dmkits is tested at four tiers. The first two are **deterministic gates** (red/green, in CI); the last two are **experience instruments** — they measure how an LLM agent fares with the tool, and the durable guards their findings earn graduate *down* into tier 1.

| tier | the question it answers | form & where it lives | run / spec |
|---|---|---|---|
| **1. Kernel-oracle tests** (JUnit) | does the DSL render, the kernel accept, the runtime evaluate, and the CLI describe itself — correctly? | the `:` + `:adapter` + `:cli` suites (**the rest of this doc**) | `./gradlew test :adapter:test :cli:test` |
| **2. Doc-drift demos** (showboat) | do the published `examples/` CLI demos still match the live CLI? | `examples/*.md` (showboat) + the `CliTourFreshnessTest` local lock | `uvx showboat@0.6.1 verify examples/<demo>.md` — [`CLI-TOUR-SPEC.md`](CLI-TOUR-SPEC.md) |
| **3. Self-sufficiency eval** (N6) | can a **cold** agent author/read rules from the bare surface (± skill)? — **scored, repeatable** | the Workflow runner over `eval/` | the `run-dmtool-eval` skill — [`EVAL-SPEC.md`](EVAL-SPEC.md) / [`EVAL-PRIMER.md`](EVAL-PRIMER.md) |
| **4. DX probe** | how does a **real multi-prompt session on the shipped product** actually go? — **deep, n=1** | the probe pipeline over `probe/` | `probe/*.sh` + main loop — [`PROBE-SPEC.md`](PROBE-SPEC.md) |

Tiers 1–2 are machine-enforced (a break fails the build/CI). Tiers 3–4 are **run-and-read**: an agent is in the loop, the deliverable is a measurement/report, and a finding's lasting guard graduates back into tier 1.

### Inside tier 1 — the JUnit layers & components

Tier 1 is not one thing. It mirrors a **rule's lifecycle** — *render → accept → fire → read back* — as a chain of layers, each living in its own **component (Gradle module)** and backed by the real kernel at the stage that needs it. `:cli` wraps the lot behind verbs; `:interpreter` is a **second** evaluator implementation checked against the kernel; and a band of guardians/catalog/corpus tests cuts across all components.

```mermaid
flowchart LR
  subgraph life["the rule lifecycle — each layer in its own module, backed by the real kernel"]
    direction LR
    R["render · : rulekit<br/>DSL → text · no kernel"]
    A["accept / reject · : validate<br/>parse + checkConsistency · laws T1"]
    F["fires / not · :adapter<br/>evaluate on data · laws T2"]
    RB["read back · :adapter<br/>text → AST → re-render"]
    R --> A --> F
    A --> RB
  end
  CLI[":cli<br/>verb I/O · self-describe · conformance · diagnostics"]
  INT[":interpreter<br/>sole runtime engine · native-safe evaluator"]
  CC["cross-cutting<br/>structural guardians · catalog N7 · corpus · doc-snippet locks"]
  CLI -.->|"wraps every layer"| A
  INT -.->|"differential vs kernel<br/>(DiffTest in :adapter)"| F
  CC -.->|"spans components"| life
```

#### The layers — a rule's lifecycle (what each proves)

| layer | proves | component · oracle | representative tests |
|---|---|---|---|
| **render** | the DSL emits the right kernel text — composition, parenthesization, quoting, handle kind-checks | `:` rulekit · *no kernel* | `dsl/RenderConditionTest` + `dsl/operators/*` |
| **accept / reject** | the kernel *accepts* a valid condition and *rejects* a bad one with the right `MVK_*` (+ F4 injection mechanics) | `:` validate · *real kernel* `checkConsistency` | `validate/laws/…LawsTest` (T1) · `validate/RuleValidatorTest` |
| **fires / does-not-fire** | a *valid* rule *evaluates* as intended on real data — polarity, empty-as-0, null compares | `:adapter` · *real runtime* `validateFull` | `adapter/…/laws/…LawsTest` (T2) |
| **read back** | render → kernel-parse → convert → render round-trips (F10) | `:adapter` · *real parser* + converter | `ReadRoundTripTest` + per-construct `…ReadTest` |

Two parts of tier 1 are **not** lifecycle stages: **modelkit structure** (the field/group/type-def/include builders + the structure ops, in `:`) and the **cross-cutting** guardians / catalog-N7 / corpus / doc-snippet locks (each has its own section below).

#### The components — Gradle modules (which *implementation* each exercises)

| module | implementation under test | tests live in |
|---|---|---|
| **the kits** (`:core`/`:rulekit`/`:modelkit`/`:edit` main) + root `:` (test host) | the DSL renderer/handles + modelkit structure + edit; accept/reject + the structural guardians against the real kernel. Stage-4 module cut: the kits' *main* moved to per-kit modules, but the cross-kit integration tests stay at root `src/test` (they span all four kits + share `Fixtures`/`ModelDrafts`), running with `testImplementation` on the kits + the `:adapter` oracle | `src/test/…` (root, the test host) |
| **`:adapter`** (opt-in) | the kernel-coupling **read** path + **runtime** behavior; kernel canaries; corpus | `adapter/src/test/…` |
| **`:interpreter`** | dmtool's **sole runtime eval engine**, clean-room and native-safe—validated differentially against the kernel on the exercised surface | `interpreter/src/commonTest/…` + the Kotlin `*DiffTest.kt` in `adapter/src/test/…` |
| **`:cli`** | the `dmtool` verb surface — I/O contract, self-describe, conformance, diagnostics | `cli/src/test/…` |

Since the Stage-4 module cut the kits are their own modules (`:core`/`:rulekit`/`:modelkit`/`:edit`), but their *main* is what moved — the cross-kit integration tests stay at root `src/test`, so root `:` is the test host. The aggregate `./gradlew test` runs that root suite (over all four kits, via the `:adapter` oracle at test scope); the kit modules' own `:test` are no-source; **`:adapter:test` and `:cli:test` are separate** (the adapter is the opt-in kernel-coupling tier, kept out of the root suite). The full per-kind breakdown + oracle is the kinds-of-test table below.

### eval vs probe — same agents, opposite questions

Both put an LLM agent in front of the tool, but they ask different things and must not be conflated:

- **The eval asks "*can it succeed?*"** — is the **self-describing surface** teaching enough that a cold agent reaches a correct artifact, *repeatably*? It scores the **final artifact** (kernel-valid AND gold-equivalent), runs **single-shot** tasks on the freshly-built **JVM** CLI, repeats **pass@k**, and yields a **number** that can gate CI. It discards the trajectory.
- **The probe asks "*how did it go — and is the success real?*"** — when a real agent drives the **shipped product** (the **native** binary + skill) across a **multi-prompt** session, where does it hit a **false success, friction, or get misled**? It reads the **trajectory** (an objective shim log + the produced models), runs **one deep session** (no k, no score), and yields a **diagnostic report**.

The gap that justifies two instruments: a model can **score eval-PASS yet have terrible DX** — the same task the eval marks correct may have leaked a stack trace or burned 50 recovery calls, invisible to a final-artifact score. The eval guards *capability of the surface*; the probe guards *experience of the delivered product*. (PROBE-SPEC's "why a second instrument" lists the three shipped defects the eval was structurally blind to: a native-only crash, the delivery layer, a multi-step silent drop.)

> **Terminology trap:** "probe" is overloaded. In **EVAL-SPEC** a *probe* is **one eval attempt** (the pass@k unit). The **probe harness** (`probe/`, PROBE-SPEC) is the **separate DX instrument** in tier 4. Same word, different things.

## The one principle: the real kernel is the oracle

There is **no mock kernel**. Every claim about how the A12 kernel parses, validates, or *evaluates* a rule is locked by a test that runs the **real engine** (`30.8.1`, pinned). A feature isn't done when the DSL renders the right string — it's done when the kernel *accepts* that string (F4) and, where behavior matters, *evaluates* it as intended (F-LAW). Tests are example-based and TDD-first (red/green): readable, intent-revealing cases drive each feature.

Consequence: the kernel is an unstable dependency, so the suite is also an **early-warning system** for kernel upgrades — many tests are deliberately "guardians" that fail loudly when a kernel bump shifts something. See [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

## The kinds of test

| kind | question it answers | where | backing oracle |
|---|---|---|---|
| **Example / unit** | does the DSL render the right text, parenthesize, quote, type-check handles? | `dsl/RenderConditionTest` + one file per family under `dsl/operators/` (`PatternConditionTest`, `AggregateConditionTest`, …) | pure rulekit (no kernel) |
| **Modelkit structure** | do the field/group/type-def/include builders + structure ops (add·remove·rename·move, the safe-delete gate) produce kernel-consistent structure? | `modelkit/…` + `edit/EditSessionTest` (root `:`) | real kernel `BasicConsistencyCheck` / `checkConsistency` |
| **F4 mechanics** | does injection → validate → problem-mapping work? | `validate/RuleValidatorTest` | real kernel `checkConsistency` |
| **Law tier 1 — accept / reject** | does the kernel *accept* this condition (and reject the bad one with which `MVK_*`)? | `validate/laws/…LawsTest` (`NumberLawsTest`, `BooleanConfirmLawsTest`, …) via the `KernelLaws` harness | real kernel parse + `checkConsistency` |
| **Law tier 2 — fires / does-not-fire** | does a *valid* rule *evaluate* as intended on data? | `adapter/…/laws/…LawsTest` (`PolarityLawsTest`, `EmptyValueLawsTest`) via the `RuntimeLaws` oracle | real kernel `IDocumentRtService.validateFull` |
| **Read round-trip** | does render → kernel-parse → convert → render round-trip (F10)? | `adapter/…/ReadRoundTripTest` + per-construct `…ReadTest` | real kernel parser + the converter |
| **Interpreter differential** | does the native-safe `:interpreter` evaluate a rule *identically to the kernel*? | Kotlin `adapter/…/laws/*DiffTest.kt` (`PolarityDiffTest`, `ParserDiffTest`, `InterpreterDiffTest`, …) | real kernel runtime, as the differential oracle |
| **Parser coverage sweep** | does the interpreter parser accept the legal DSL the kernel actually stores? | `adapter/…/laws/ParserCorpusSweepTest` — catalog `validExample`/`computationExample` + `PerfCatalog.fuzzConditions` **always-on 100%**; the `a12-kernel-mm` + A12 Tools corpora **opt-in 100%** (`assumeTrue`-skip). A residual failure must fix the parser or record a reason (IF55) | the pinned corpora as the population; parse = `parseCondition`/`parseExpression` |
| **Codegen tri-differential** | does the interpreter match the Groovy-dynamic runtime and static-JAVA generated validator on the generated cases? | the differential **fuzz** nets run a third engine (`RuntimeLaws.JavaKernelEngine`) via `FuzzDiagnostics`: `interp == kernel-groovy-dynamic == kernel-java-static` at `code|type|pointer` | real kernel; the ratified oracle (IF136) anchors on Groovy-dynamic — a static-Java split is recorded via the corpus `divergences` mechanism, never adopted; the module instructions and corpus contract now agree |
| **Conformance corpus replay** | does every committed case in the `corpus/cases` tree (semantics-as-data) replay identically across the interpreter, kernel groovy-dynamic, and kernel java-static — **and on both interpreter targets, JVM and JS**? | `adapter/…/laws/CorpusReplayTest` + `CorpusSchemaGuardTest` (the `corpusReplayTest` task) is the **3-engine** JVM leg; `interpreter/commonTest/…/conformance/CorpusReplayCommonTest` is the **interpreter-only** leg that runs under `:interpreter:check` on **jvm + js** (`jsNodeTest` — the first kernel-grounded JS oracle, WI-6), replaying via the shared kernel-free `InterpreterReplay`. Both declare the corpus files as inputs; cases authored/re-captured via `CorpusScenarios` → `CorpusCapture` → `CorpusGenerator` (`-PrunCorpusCapture`) | the committed cases; the groovy-dynamic leg re-runs the real kernel (also the freshness guard). A JS-vs-JVM interpreter split surfaces as a JS FAIL. Contract: [`CONFORMANCE-CORPUS-SPEC.md`](CONFORMANCE-CORPUS-SPEC.md) |
| **Property pack (kernel-free ∀-laws)** | does the interpreter obey the ∀-quantified invariants the differential can't *state* — laws over *sampled* inputs, not pinned cases? | `interpreter/commonTest/…/property/*PropertyTest` (seeded, hand-rolled, no framework) via [`PropertySupport`](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/property/PropertySupport.kt); runs under `:interpreter:check` on **jvm + js** | **no kernel** — these are self-consistency / equivalence laws of the interpreter (lazy≡eager typing, Kleene monotonicity, row-permutation equivariance, empty-row IF53/IF54, aggregate identities, hash-join≡nested-loop, determinism). See "Property pack" below |
| **Structural-law guardians** | did the kernel *add/move/rename* a construct, code, slot, or category we must track? | see the guardian table below | reflective parity vs the kernel's own enums/accessors |
| **CLI self-describing & conformance** | does the CLI describe its own verbs/params/I-O, and does every mutating verb refuse misuse with a *structured* diagnostic (not a bare fail)? | the `cli` conformance suite — see below | the live picocli tree + a derived fuzzer |
| **Coverage harnesses** (opt-in) | what fraction of real foreign rules do we read? | `KernelMmReadCoverageTest`, `Corpus1ReadCoverageTest` | real corpora (gated; commit nothing) |
| **Catalog examples (N7)** | is every catalog `validExample` actually kernel-valid? | `catalog/CatalogExamplesAreKernelValidTest` (incl. the correctives' reject probes — each rejected with its code) | real kernel |
| **Catalog facet probes** | is every catalog *facet claim* (the `semantics` machine tier + the `emptyOperandDefaults` header) true on the kernel **runtime**? Every claim carries embedded probes; each is (1) kernel-ACCEPTED and (2) fires ⟺ declared — so a facet value cannot rot ([CATALOG-SPEC §5](CATALOG-SPEC.md)) | `adapter/…/laws/CatalogFacetProbesTest` (execution) + `catalog/OperatorCatalogTest` (structural: a claim without a probe fails) | real kernel runtime (`RuntimeLaws`) |
| **Self-sufficiency eval (N6)** | can a cold LLM author/read rules from the CLI + skill alone? | the Workflow runner | clean subagent + judge — [`EVAL-SPEC.md`](EVAL-SPEC.md) |

## The two law tiers — the distinction people conflate

A rule can be **kernel-valid yet behave wrong** (the inverted-polarity trap is the canonical case). So "is it accepted?" and "does it do the right thing?" are *different questions* and get *different tiers*:

```
                    ┌─────────────────────────────┐      ┌──────────────────────────────────┐
   a condition  ──▶ │  TIER 1  accept / reject     │ ──▶  │  TIER 2  fires / does-not-fire     │
                    │  structural validity         │      │  runtime behavior on real data     │
                    │  validate/laws/  (KernelLaws) │      │  adapter/…/laws/  (RuntimeLaws)    │
                    │  parse + checkConsistency     │      │  IDocumentRtService.validateFull   │
                    └─────────────────────────────┘      └──────────────────────────────────┘
        catches: "this rule is well-formed"        catches: "this well-formed rule means what we think"
```

- **Tier 1 (accept/reject)** embeds a condition as a rule's `errorCondition` and asserts the kernel accepts it, or rejects it with a named `MVK_*` code. Harness: `KernelLaws.accepts/rejects`.
- **Tier 2 (fires/does-not-fire)** builds a **document instance** with specific field values, runs the kernel's runtime validation, and reads back *which rules fired* (`IMessage.getErrorCode()` / `getRulePath()`). Harness: `RuntimeLaws`. This is the only way to lock behavioral semantics — polarity, empty-as-0, null comparisons, path walk-up. Wiring + the V1 document-construction contract are in [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) ("Runtime behavioral-law oracle"). The same runtime is now also a **public seam** — `KernelAdapter.evaluate`/`compute` behind the `model eval`/`model compute` verbs — and `EvalDocSeamLawsTest` pins its nested-document walk against `RuntimeLaws`' known-good row pointers (and locks validate≠compute, the number-empty-→0-in-computation case, …).

Both tiers are the **executable form of [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md)**: every findings § (and MVK-ledger row) points at the `…LawsTest` that locks it, so a kernel upgrade that changes a documented behavior fails loudly instead of silently drifting.

### The property pack — the third kind: kernel-free ∀-laws (WI-4)

The two law tiers pin **cases** against the **kernel** oracle. The **property pack** (`interpreter/commonTest/…/property/`) is a distinct third kind: **∀-quantified laws** over *sampled* inputs, with **no kernel oracle** — they hold by the interpreter's own structure, so they compile and run on **JVM *and* JS** (the kernel is JVM-only; a case differential can't run on JS at all). Where a differential says "on THIS input, interp == kernel," a property says "on EVERY sampled input, this invariant holds."

They quantify over a deterministic seed stream × a few diverse models ([`PropertySupport`](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/property/PropertySupport.kt): flat · single-repeatable · nested, fill/empty/malformed values) — never wall-clock/platform randomness, so JVM and JS sweep identical samples. Hand-rolled `kotlin.test` (no property framework, no new dependency — the module is purity-linted). Each pins a fact the differential **cannot state**:

| Property | The ∀-law | Seam |
|---|---|---|
| **1 · lazy ≡ eager** | the production lazy-polarity walk (`firedType`) == the full eager typed walk on every (cond, doc) — truth, and polarity when firing | `Interpreter.lazyEagerConsistent` (internal) |
| **2 · Kleene monotonicity** | routing any operand to UNKNOWN never flips TRUE↔FALSE (⇒ branch-scoped suppression); the [`Kleene`] table + the `invalid` mechanism | public `eval` + `invalid` |
| **3 · row-permutation equivariance** | permuting a group's rows permutes the pointers; the code multiset is invariant | public `validate` + placement relabel |
| **4 · empty-row append** | IF53 (instantiated-empty row is content — per-row fires per row), IF46 (instantiated-count delta), IF54 (partial phantom fires) | public `validate`/`validatePart` |
| **5 · aggregate identities** | empty `Sum`/`Max`/`Min`/`NumberOfDifferentValues` = 0; operand-list substitutes empty, star skips it | public `Aggregates` + `evalExpression` |
| **6 · indexed correlation ≡ general scan** | each keyed/ordered `$`-correlation plan produces exactly the same tally as the naive nested scan on every sample | `Interpreter.correlationTallyBothWays` (internal) |
| **7 · determinism** | `validate`/`compute` twice ≡ once (reused *and* fresh engine) | public `validate`/`compute` |

The seams are the only concession: three `private`→`internal` methods on `Interpreter`/`Document`-adjacent code (annotated "WI-4 property seam"), so a property can compare two internal strategies (lazy vs eager, hash vs scan) the public surface hides. Two properties are deliberately scoped and say so in-test: property 2 excludes the **counting quantifiers** (there "operand UNKNOWN" is a tally change, not a Kleene leaf), and property 4 covers the settled IF53/IF54/IF46 deltas but not the *declared-range* clamp (IF49, locked by its own `:adapter` differential). The **exploratory polarity-soundness** property (an OMISSION implies a fill exists that changes the outcome) is out of scope — its statement needs a BEST_MODEL boundary first (proposal §8).

## The structural-law guardians (anti-whack-a-mole)

Rather than discover kernel constraints by tripping over them, these sweep the kernel's *enumerable* surfaces and fail when something new appears:

| guardian | what it pins |
|---|---|
| `SlotParityTest` | every public `IRule`/`IComputation` accessor is supported or explicitly deferred (the **authoring** side) |
| `adapter.InterpreterFieldConstraintParityTest` | every kernel `IStringType`/`INumberType` constraint accessor is classified for the **interpreter** model (modeled / deferred / out-of-scope) — the forcing-function whose absence let IF20 hide; catches a kernel-added field-type constraint the interpreter would otherwise silently drop |
| `adapter.InterpreterModelElementParityTest` | the companion over the **rule/computation/group** slots (`IRule`/`ICalculationInfo`/`IGroup`) — every accessor classified for the interpreter model (IF21 alternatives, IF23 severity, sort-info → eval-irrelevant (authoring shipped, finding 51), rounding-mode, custom-ref entities); catches a kernel-added model-element slot the interpreter would silently drop |
| `MvkLedgerTest` | every `MVK_*` code is classified (erased / pre-checked / delegated / pending) — [`MVK-LEDGER.md`](MVK-LEDGER.md) |
| `SerializerTierParityTest` | the `BasicDocumentModelConsistencyCategory` write-gate enum (F6) is fully classified |
| `KernelConstructParityTest` | every parsetree construct-name enum member is cataloged or excluded (catches operators the kernel *adds*) |
| `adapter.RtSemanticsLedgerTest` | every runtime evaluation-core class is classified in [`RT-SEMANTICS-LEDGER.md`](RT-SEMANTICS-LEDGER.md) (catches evaluation semantics the kernel *adds*) |
| `GrammarProductionLedgerTest` | every condition-grammar parser production is classified in [`GRAMMAR-LEDGER.md`](GRAMMAR-LEDGER.md) (catches syntax *shapes* — opt-in, needs the sibling `../a12-kernel` checkout, skips when absent) |
| `KeywordParityTest` / `OperatorCatalogTest` | the operator catalog's keywords/shape stay aligned with the kernel bundle |
| `DslCatalogBridgeTest` | the catalog's `dslEntry` map stays in bidirectional set-equality with the code-side `catalog:<Id>` authoring markers (G3) — every member resolves to a real DSL method, every operator is mapped or explicitly noted-empty; a stale/absent mapping fails, and the guard dumps the marker-derived projection to re-author from |
| `adapter.laws.CapabilityEvidenceMatrixTest` | the interpreter's capability × strategy × coverage-tier inventory (IF140): operator cells derive from the live catalog; the formal, computation, configuration, customization, platform, and assembly universe is independently closed; every cell names compile-resolving dynamic/static evidence or an explicit reasoned exclusion; omission fails, exclusions never inflate evidence, and the certificate summary is generated from the matrix |
| `KernelVersionTest` | the running kernel version vs the catalog's `verifiedAgainst` floor (F11 skew INFO) |
| `ModelIndexNamePathTest` | element keying is by name-path (the F-IDX fix foreign-model read depends on) |
| `adapter.ModelIndexParityTest` | the kernel-free `dm.core` reader's index == the kernel's, over fixtures + both opt-in corpora — the decoupling-Stage-2 parity net (kernel-free assembly/index vs the preserved `KernelModelOracle`); `core.ModelReaderTest` unit-tests the reader's expand/typedef/id-normalize |
| `ModelDocumentAuthoredFieldRuleStateTest` + `adapter.review.ModelDiffFieldCompletenessTest` / `ModelDiffRuleEnvelopeTest` / `ModelDiffReaderRiskTest` | Semantic-diff field/rule completeness: one typed authored-tree walk exposes every field type/requirement/metadata slot and rule metadata; the diff locks field constraint direction, rule targets/messages, and one inverse reader map for referenced removal/retype impact |
| `ModelDocumentAuthoredReferenceStateTest` + `adapter.review.ModelDiffTypedefTest` / `ModelDiffIncludeTest` | Semantic-diff reusable declarations/references: typed typedef config/metadata and authored include/import alias→reference state; exact-only typedef rename pairing, resolver-backed include retargets, and mount rewires stay distinct from expanded content |
| `adapter.review.ModelDiffConfigTest` | Semantic-diff model configuration: one stable `CONFIG model` element covers identity/version projection, locales, every scalar setting, supported characters, base year, labels, comment, annotations, and canonical roles; set-order noise and widening/tightening direction are locked |
| `adapter.review.RelocationMatcherCandidateTest` + `ModelDiffSimilarityTest` / `ModelDiffRenameTest` / `ModelDiffRefactorTest` | Semantic-diff relocation safety and cost: exact and rename-plus-edit matching remain conservative, fuzzy candidates require rename/move locality, oversized ambiguous buckets are refused before Cartesian scoring, and referrer grouping remains a non-destructive overlay |
| `adapter.review.ModelDiffLoadingTest` | Semantic-diff loading cost: each includes-bearing side performs one core index expansion plus one checked adapter preparation; a separate consistency preflight cannot silently restore the former duplicate kernel deserialize/expand |
| `adapter.review.ModelDiffTypedDeltaTest` + `cli.ModelDiffTypedDeltaCliTest` + `cli.DataSchemaConformanceTest` | Semantic-diff value contract: stable rendered strings remain unchanged while booleans, integers, decimals, and string lists expose sealed headless values and schema-valid tagged CLI objects; absent or malformed sides degrade safely |
| `adapter.review.ModelDiffSideResolverTest` + `cli.ModelDiffWorkspaceCliTest` / `ModelDiffSinceTest` | Semantic-diff workspace contract: base/head bind against distinct snapshots; two-file folder fallback and `--since` Git-ref dependencies are executable, while a self-contained diff proves it never indexes an unused workspace |
| `cli.StaleGapReferenceGuardTest` + `StaleGapReferenceClassifierTest` (`:cli:staleGapGuardTest`) | the deleted-interpreter-gap comment class (IF141): the live IG set derives from the ledger; source/build roots derive across the root and every current module; present-tense references to a deleted IG fail while explicit provenance/history remains legal; focused canaries lock clause-local classification, wrapped comments, mixed references, capitalization, and live/deleted ids |
| **`verifyKitPurity`** (Gradle task on root `:`, wired into `check` — *not* a JUnit test; peer of `verifyInterpreterPurity`/`verifySchemakitPurity`) | the four kit modules' `src/main` (`:core`/`:rulekit`/`:modelkit`/`:edit`) import or FQ-reference **no** `com.mgmtp.*` type — the kernel-decoupling regression net. Since the Stage-4 module cut the single root guard scans all four kit `src/main/java` trees. Comments (incl. Javadoc that explains the boundary) are stripped first, so only a real code reference fails. The kernel lives behind the SPI ports (`dm.core.spi.*` / `dm.rulekit.spi.*`) that `:adapter` implements |
| **`NoBackendLaneTest`** (the root `noBackendTest` source set + Gradle `Test` task, classpath = the kit modules `:core`/`:rulekit`/`:modelkit` (+ their transitive Jackson at runtime) + JUnit, and **NOT** `:adapter`/kernel) | the §2c degraded-mode contract, row by row, with no backend on the classpath: authoring/load/prechecks full; the consistency gate degrades to `STRUCTURAL_ONLY` + one `RK_NO_KERNEL_BACKEND` advisory and **never a fake green** (`ok()` can't read as `KERNEL_CONFIRMED` without a backend); condition read-back fails fast with `BackendRequiredException` — the Stage-3 acceptance bar |
| **`guardInputs`** non-empty assertion (root `:` `ext` helper on every `.optional()`-fileTree guard: root/cli `docGuardTest`, cli `semanticsMapGuardTest`, adapter `corpusReplayTest` — a `doFirst`, *not* a JUnit test) | **no guard runs against an empty input.** A moved/renamed folder silently empties an `.optional()` fileTree input, and the guard then goes green while checking nothing — the *silent-empty-fileTree false-green class* (kernel-decoupling §3b #17). The helper declares the inputs AND fails the build loudly when any named tree is empty/missing, so a future folder move is caught by a build failure, not a silent pass (it caught the Stage-4 cli catalog re-point) |
| **`PublicApiGuardTest`** + the `dumpKitApi` fixer (root `:`, in the `apiGuardTest` task; reflection dumper `PublicApiDumper` — *this* is a JUnit test, unlike the four rows above) | the five published kits' **public API surface** is frozen to a committed golden (`api/dmkits-<kit>.api`, one per kit). A 0.x **visibility** guard, not a japicmp no-break gate: any change to a `public`/`protected` type or member of `dmkits-{core,rulekit,modelkit,edit,schemakit}` fails the build with a "regenerate via `./gradlew dumpKitApi` and review the diff" message, so a surface change is deliberate and reviewed before first publish freezes consumer expectations. The dump is **deterministic** (types/members sorted, generic types rendered, implementation-only modifiers masked) so it can't flake; verifier + fixer share one dump path, and CI never runs the fixer, so a forgotten regenerate still fails. **Plus one HARD rule over the same type walk (Set W)**: `noThirdPartyTypeOnAnyPublishedSignature` — no `public`/`protected` kit signature may reference a type outside `java.*`/`javax.*`/`io.github.mbackschat.*` (does-fire-proven), so an `implementation`-scoped dep (Jackson) can never leak onto a consumer's compile classpath. [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §11 || **`-Xlint:removal -Werror`** (build config on the kernel-facing compilations — root `:` **TEST** (root has no main since the Stage-4 cut; its test compiles the kernel-oracle code) + `:adapter` + `:cli`, main+test — *not* a JUnit test) | a kernel API **marked for removal cannot compile** — the anti-recurrence ratchet for the retired A12K-3589 surface (`validateFull`/`compute`/`validatePart(IDocument, …)`, `CustomFieldTypeService`, …). Only the `removal` lint is promoted to an error, so the deliberately-retained plain-`@Deprecated` V1 document-construction API (its V2-native-builder migration is separate) still compiles. Kotlin diff tests reach the kernel only through the Java `RuntimeLaws` oracle, so the Java ratchet covers them. Surface + replacements: [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) "A12K-3589 kernel-API removal surface" |

When any of these fails after a kernel bump, [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) maps the failure to its meaning and realignment.

## CLI self-describing surface & conformance ([`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md))

The `dmtool` CLI is the agent's entire interface (the N6 eval drives the bare binary), so it must be **self-describing** — an agent discovers the verbs, their params, each verb's I/O shape, and a worked example *in action*, all *from the tool* — and **diagnostic** — a misuse returns a structured, corrective envelope, never a bare stack trace or terse exit. These tests enforce both, and most are **derived from the live picocli command tree**, so they can't drift from the actual verbs:

| test | what it pins |
|---|---|
| `ManifestVerbTest` | every verb is in `manifest` with a description (`EXPECTED_VERBS` is the canonical set — a rename/removal can't silently drop a verb) |
| `ManifestTreeTest` | nested `target op` verbs resolve; bare target groups aren't runnable; `-m` is global |
| **`CliConformanceMatrixTest`** — the per-verb **matrix** | the single **derived** gate (folds the old `ManifestCompletenessTest`): one row per verb, one column per obligation — *described*, *params-described*, *schema-resolves* (every directional-contract verb's `schema` pointer resolves to real JSON; result-envelope and registered artifact contracts are both covered, while verbs without a contract form the reasoned `CONTRACT_EXEMPT` ledger), and *demonstrated*. No skip — this is exactly how `meta` (no schema pointer) slipped past the old check. A new column codifies the next anti-pattern. |
| `ParamKindManifestTest` | each `@ParamKind`-tagged param surfaces its kind in `manifest` (an agent learns "a FIELD_PATH that must exist" vs "a NEW_NAME you choose") — spot-checks the disambiguating kinds (`rename --to` = NEW_NAME vs `move --to` = GROUP_PATH) |
| `SchemaVerbTest` | `schema result`/`diagnostic` serve the structures; `schema <target> <op>` (and `schema <verb>` for an envelope cross-cutting verb like `meta`) gives the directional contract — incl. a verb's `docInput` (the `--instance` `DocumentInstance` shape) and `dataSchema` (its read payload, e.g. `Explanation`/`ModelCard`) where they exist; **every target-first verb's real output conforms to `result.schema.json`** (the F11 drift guard) |
| `SchemaExamplesGuardTest` | every published schema `examples` block is **CI-executed through the tool** (the `add` rule/computation specs and the `field-add` field spec via `--dry-run`, the `apply`/`batch` frames, the `document-instance` example via `model eval`) or statically conformed (`result`/`diagnostic`) — a stale example can't ship |
| **`VerbDemonstrationTest`** — the **demonstration** gate | every verb's worked example in `verb-examples.json` (the read-only invocation `manifest` surfaces as the verb's `example`, so a cold agent sees it *in action*) is **executed through the tool** against a fixture and must produce its documented outcome — the "in action" demo can't rot |
| `RkCodeParityTest` | the single-source `RkCode` registry (served by the `diagnostics` verb) stays bidirectionally in sync — every emitted code is registered, no dead rows |
| `NotFoundRefusalTest` | a not-found entity-ref on a mutating verb refuses with the structured `RK_NO_SUCH_*`, not a bare error |
| **`DiagnosticConformanceFuzzTest`** — the conformance **fuzzer** | derived from the live tree, it probes every mutating verb with a **bogus value for each entity-ref param** and asserts a **structured** envelope comes back (a code + a fix), not a bare fail. A new verb can't escape it, and it catches real regressions — e.g. it caught a `rule modify` change whose precondition *ordering* let a missing-rule slip through as a terse "nothing to change" instead of the structured `RK_NO_SUCH_RULE`. |

The bar (CLI-CONFORMANCE-SPEC): a cold agent given only the binary can self-serve. Two active enforcers make a new verb hold the contract automatically — the **matrix** proves it's fully surfaced (described, contract-discoverable, demonstrated), and the **fuzzer** proves its misuse is structured-diagnosed.

## Locked doc snippets (no-rot for the `java` code in docs)

A `java` code block shown to a reader in the markdown docs is hand-authored prose with no compiler behind it — so it drifts silently as the API moves, and a stale snippet actively *misleads*. `DocSnippetFreshnessTest` removes the rot for the **canonical** snippets in [`API-GUIDE.md`](API-GUIDE.md): each locked block must be byte-identical — after indent/trailing-whitespace normalization — to a **tagged region of a real test that compiles and runs against the kernel**. Same two levers as the CLI side (`CliTourFreshnessTest`): **compiled + run ⇒ no-rot; locked-to-source ⇒ no-drift.**

This is *not* extract-and-compile of every fence. The README and many study blocks are deliberate **teasers** — `…` elisions, prose interleaving, `✅`/`🔮` annotations, or deliberately *non-compiling* illustrations (§2's "what you cannot write") — they stay hand-maintained. Only the *clean, canonical, runnable* snippets get a verbatim lock; pick the one example per shipped capability that is a real compile unit.

**Completeness — no snippet escapes.** A second guard in the same class, `everyDocSnippetIsLockedOrTeaserMarked`, walks `API-GUIDE.md` and fails if **any** `java` block is *neither* `<!-- snippet: <id> -->` locked *nor* `<!-- teaser -->` marked. It's independent of heading status markers — the guide's user-facing titles carry no `✅`/`🔮` (the project no longer tracks per-feature status; `API-FINDINGS.md` holds design rationale, not a status board) — so a new snippet can't silently dodge the lock; the same "every X accounted for" discipline as the CLI conformance matrix. The locked set earns its keep: locking the §6c rule-template pipeline surfaced three real defects in a snippet no one had run (missing `.name()`, missing message, missing the `de` locale of a bilingual model) — all now fixed in the doc.

**Source of truth = the test region; the doc block is a verified mirror.** That direction is the whole point: "the doc shows it" and "the kernel accepts it" cannot diverge.

```
  test method (compiles + runs vs kernel)            markdown doc
  // tag::transform[]                                <!-- snippet: transform -->
  Condition widened = …tier.is("PRO")…   ──sync────▶ ```java  (what the reader sees)
  // end::transform[]                     ◀═verify══  …tier.is("PRO")…   byte-equal
```

**Verification vs. sync — two triggers, never confused**

- **Verify** is the default: `DocSnippetFreshnessTest` is assert-only, runs in `:test` (locally *and* CI), edits nothing. Drift → build fails.
- **Sync** is autonomous and on demand: `./gradlew syncDocSnippets` re-runs the class with `-Dsnippets.fix=true`, flipping it to the *fixer*, which rewrites every locked doc fence in place from its canonical test region — no hand-copy. CI never runs the fixer, so a forgotten sync still fails the verify. Both share one extraction/normalization path, so they can't disagree.

**Lifecycle**

1. **Author** the snippet *as a real test* (a `…LawsTest`/feature test in the suite), wrapping the showable lines in `// tag::<id>[]` … `// end::<id>[]`. `javac` compiles it; the kernel runs it — proven, not plausible. Use the *real* API in the tagged lines (this lock turned the §5.1 example's private `tierIs(...)` test-helper into the genuine `tier.is(...)` API, so the doc now shows runnable code).
2. **Mark** the doc fence: put `<!-- snippet: <id> -->` on the line directly above the ```java fence (the fence body can start empty — the sync fills it).
3. **Register** a `Lock(id, docFile, testSourceFile)` row in `DocSnippetFreshnessTest`, then run `./gradlew syncDocSnippets` to populate the fence.
4. **Maintain**: change the API → edit the snippet *in the test*, then `./gradlew syncDocSnippets`. Two independent guards fire if you forget: the test fails to compile/pass (the API broke) or the verify fails (the doc is stale). **The test is canonical** — if the snippet itself is wrong, fix the test first, then sync; never hand-edit the doc to go green while the test is red.

| concern | mechanism |
|---|---|
| **doc marker** | `<!-- snippet: <id> -->` immediately above the ```java fence; the guard takes the first fence after it |
| **test marker** | `// tag::<id>[]` … `// end::<id>[]` around the showable lines of a passing test |
| **normalization** | drop blank edge lines, strip the common leading indent (so an 8-space method body equals a column-0 fence), trim trailing whitespace |
| **registry** | `DocSnippetFreshnessTest.LOCKS` — one `Lock` row per locked snippet |
| **verify** | `./gradlew test` (assert-only; CI) — fails on drift |
| **sync** | `./gradlew syncDocSnippets` (autonomous; rewrites fences from test regions in place) |
| **completeness** | `everyDocSnippetIsLockedOrTeaserMarked` — every `java` block is locked or `<!-- teaser -->` |

## Doc-drift demos (showboat / `examples/`) — tier 2

The `examples/*.md` files are **runnable CLI demos**: each interleaves prose, a `dmtool` command, and its **captured output**, and `showboat verify` re-runs every command and **exact-matches** the output against the live CLI. So a demo is documentation *and* a doc-drift regression test — a verb rename or output-shape change not carried into the demo fails. The demos cover the target-first verb surface end to end (read/inspect · rulekit edit · modelkit structure · `apply` · runtime eval · JSON-Schema interop · custom types · versions); the per-demo verb inventory is [`../examples/README.md`](../examples/README.md) and the method is [`CLI-TOUR-SPEC.md`](CLI-TOUR-SPEC.md).

**Two levers, because the showboat gate is CI-only:**
- **CI** runs `scripts/verify-demos.sh` (`.github/workflows/ci.yml`) — the **same** script developers run locally, and the authoritative drift gate. It builds the launcher, **discovers** every `examples/*.md` demo (so the set can't drift from a hand-kept CI list), and `uvx showboat@0.6.1 verify`s them **in parallel** (bounded by `VERIFY_JOBS`, default 6).
- **A high-churn value a demo *pins* can still drift in a *local* commit with CI red but unnoticed** (it happened — two new `RkCode`s shifted the tour's `diagnostics` count 31→33). So every live-derived value a demo pins also gets a **local `:cli:test` lock**: the verb list in `ManifestVerbTest.EXPECTED_VERBS`, and the `diagnostics` count + `manifest` list + `schema rule add` keys in `CliTourFreshnessTest` (runs the real verb in-process, fails naming the regen command). Add such an inner-loop guard whenever a demo starts pinning a new live value.

Audience note: these are **not** for cold-agent discoverability — the N6 eval agent never sees `examples/` (that's the self-describing surface's job). They serve the **CI drift-guard** and a **repo-equipped reader** learning a verb end to end.

## Opt-in corpus harnesses (gated, commit nothing)

These read the **real** model corpora to measure coverage; they skip when their volume/clone is absent, print a report, and never commit corpus content (proprietary A12 Tools workspaces; EUPL `a12-kernel-mm`):

| harness | corpus | gate | asserts |
|---|---|---|---|
| `KernelMmIntegrationTest` | `../a12-kernel-mm` | sibling clone present | all canonical metamodels load/expand/`checkConsistency`; every field classifies |
| `KernelMmReadCoverageTest` | `../a12-kernel-mm` | sibling clone present | read coverage ≥ a floor (regression gate) |
| `Corpus1ReadCoverageTest` | A12 Tools workspaces | `-Da12.corpus1.dir=…` | prints coverage only (proprietary) |
| `ParserCorpusSweepTest` (corpus half) | `../a12-kernel-mm` + A12 Tools workspaces | sibling clone / `-Da12.corpus1.dir=…` | every distinct stored condition/operation **parses** (100% — a residual fails, forcing triage; IF55). Its catalog+fuzz half is always-on |
| `RealCorpusSweepTest` (WI-7 tri-engine sweep) | `../a12-kernel-mm` + A12 Tools workspaces | `@Tag("corpus-sweep")` + `-PrunCorpusSweep` + sibling clone / `-Da12.corpus1.dir=…` | **report-and-triage** (not a green gate): pre-expand (`CorpusModelPreparer`) → `ModelLoader` → seeded instances → interpreter vs kernel groovy-dynamic (java-static best-effort) on `validateFull`+`compute`. Excludes+tallies unsupported rules, prints a license-safe class histogram; asserts only that it ran. Findings → INTERPRETER-GAPS (IG37–IG41; IG37 & IG38 since graduated to IF58–IF62), bounced to WI-T. The S2 prerequisites are locked by the CI fixtures `CorpusModelPreparerTest` + `ValidValuesConstraintTest` |

### Differential drill tools

`adapter.laws.RawAtomShrinkTest` (`-Datom.kernel/interp/model/seed/dump`) hunts compute KIND atoms, and `-Datom.valuediff=true` hunts the kind-equal VALUE-vs-VALUE class. `adapter.laws.ValidateUnitShrinkTest` (`-Dvf.side=kernel-only|interp-only`, `-Dvf.model/seed/sig/dump`, `-Dvf.kernelmm=true` for the kernel-mm corpus) hunts `validateFull` fired-code units with the sweep's unsupported-rule exclusion. `adapter.laws.MinimalReplayTest` (`-Dreplay.model=<scratch>`, `-Dreplay.op=validate|compute`) replays a dumped minimal through both engines; `CrashProbeTest` locates crash classes; `ModelShrinkTest` (`-Dig44.vcv`) remains the granularity-projected hunt. All honor `-Dcorpus.hostile=true`; `HostileValues` in adapter `CorpusSeeding.kt` deterministically makes roughly every fourth NUMBER cell violate one operand-validity class. The tools print only license-safe redacted shapes.

## Layout conventions (so hotspots never grow)

- **One file per operator family**; a new increment adds a *new* small file, it does not pile onto a monolith. `dsl/` keeps only the renderer/handle core; each family lives under `dsl/operators/`.
- **`validate/` keeps only F4 mechanics**; the kernel-law locks live under `validate/laws/` (tier 1) and `adapter/…/laws/` (tier 2).
- **Fixtures load once** via the shared `Fixtures` util (`subscription()`/`order()`/…) — `BoundModel` is an immutable snapshot (F0), safe to share, so a new file costs a few lines.

## Test tiers — the fast default vs. the heavy perf/fuzz lanes

The full kernel-backed suite was ~12 min, but **two categories accounted for ~10 of it**: the **perf lane** (`@Tag("perf")`—the regression gate, [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md)) and the **differential fuzzers** (the `*FuzzDiffTest` + `InterpreterKernelFuzzTest`, `@Tag("fuzz")`, ~2 min). The other ~2,000 unit/conformance tests run in ~2 min. So both heavy categories are tagged and excluded from the default run (`adapter/build.gradle`), dropping the routine gate to ~2–3 min. The perf lane is itself two tiers: `PerfGateTest`—sub-quadratic growth guards + the rich-model kernel canary, `scripts/perf.sh gate`, required in the maintainer landing process but currently CI-wired only through the nightly/manual tagged lane—and `PerfBenchTest`—the closed B-series dynamic-Groovy/generated-Java ratio sweep, `scripts/perf.sh bench`, for perf-relevant checkpoints. The declared inventory includes firing/low-firing validation and VALUE/CLEARED/ERRORED computation cases; `ScenarioInventoryTest` fails on omission or fingerprint drift, while common `PreparationWorkCounterTest` proves model-static AST/plan/dependency work stays once-per-engine on JVM+Node (IF171). `gate` accepts an optional method pattern; `bench` accepts one exact scenario id and rejects an unknown id. `scripts/perf.sh js` runs the compiled multiplatform tests directly under Node/Mocha and propagates that process's exit (IF170); the input-declared `perfScriptGuardTest` keeps the shell contract fresh without rerunning the whole adapter suite. These are diagnostic/catastrophic-regression lanes, not NFR certification. IF172's separate `scripts/interpreter-performance.sh` invokes exact typed-fixture/eligibility guards, independent JVM/Node/native first-answer processes, warm absolute/paired throughput, and fair-Java reporting into structured informative artifacts; it has no default-test or CI dependency and promotes no candidate number. IF173's delivery collector additionally unpacks the measured packed JVM runtime and compiles/runs an external Java consumer against only its `lib/*.jar`, binding both JVM byte bases to an executable public-library outcome.

| run | command | what runs |
|---|---|---|
| **default** (inner loop) | `./build.sh :adapter:test :cli:test` | everything **except** perf + fuzz (~2–3 min) |
| + fuzzers | `./build.sh :adapter:test -PrunFuzz` | adds the differential fuzzers |
| + perf | `./build.sh :adapter:test -PrunPerf` | adds the perf lane (BOTH tiers — gate + bench) |
| profile fixture/fidelity guard | `./build.sh :adapter:profileFixtureGuardTest` | validates the three committed profiles and their fixed-seed exact guarantees |
| profile synthesis growth canary | `./build.sh :adapter:profileSynthesisPerfTest` | opt-in in-process N/4N synthesis guard; typed target-model authoring and profile extraction stay outside timing, then the shared paired estimator applies the ratified `<9×` catastrophe floor |
| synthesized-model interpreter curve | `./build.sh :adapter:synthesizedModelPerfTest -Dperf.synth.profile=compact -Dperf.synth.op=validate` | opt-in exact N/4N interpreter/dynamic-Groovy/generated-Java curve over one registered committed synthesis profile, used as a diagnostic stress shape until fidelity is certified; synthesis and exact semantic preflight stay outside timing; reports load, prepare, first-answer, warm, and paired growth regimes |
| profile-shaped CLI canary | `./build.sh :adapter:profileCliPerfTest` | opt-in installed-JVM-process `model describe`, default JSON `model report`, `model report --tree --text`, and identical-model `model diff` paired catastrophe checks over the three committed profiles; synthesis and exact-cardinality preflight stay outside timing, work/cardinality counts come from the typed synthesized model rather than duplicated profile-JSON keys, and every child retains the 180-second liveness fuse |
| everything | `./build.sh :adapter:test -PallTests` | both |

**Nothing is dropped — CI runs them on a defined trigger:** the fuzzers on **every push/PR** (`-PrunFuzz` — correctness nets, interpreter == kernel), the perf gate **nightly + on `workflow_dispatch`** (`-PrunPerf` — a ratio gate, not a per-commit check). `GateTierWiringTest` fails the build if either lane is dropped from the CI workflow (F11 loud-detection). **Tagging rule:** a new perf benchmark gets `@Tag("perf")`, a new differential fuzzer `@Tag("fuzz")` — so it joins the right lane automatically.

**The fuzz lane is a codegen tri-differential (WI-1).** Every fuzz net (`InterpreterKernelFuzzTest`, `FullMessageFuzzDiffTest`, `ModelFuzzDiffTest`, `SiblingGroupFuzzDiffTest`, `ComputationFuzzDiffTest`, `PartialValidationFuzzDiffTest`) compares **three** engines — the interpreter, the kernel's Groovy-dynamic service, and the kernel's static-JAVA generated validator (`RuntimeLaws.JavaKernelEngine`, wired via `FuzzDiagnostics`) — asserting `interp == kernel-groovy-dynamic == kernel-java-static` at `code|type|pointer` (validateFull + `validatePart` + `compute`). This gates the per-language **codegen** the runtime differential structurally cannot see. The static-JAVA engine is cached per model (the in-memory javac compile is once-per-model; `ModelFuzzDiffTest`'s 40 fresh compiles cost ~47 s, inside the fuzz budget). Per S3 the Groovy-dynamic service is the **parity anchor**: an `interp≠kernel` split is an interpreter bug (WI-T handoff); a `kernel≠java-static` split is a language-execution artifact of the same generated logic — recorded and quarantined, never adopted as expected. `JavaStaticPartialBaselineDiffTest` / `KernelJavaBaselineDiffTest` pin that the static path fires (and gates `validatePart`) identically to Groovy.

**Parallelism keeps the per-push fuzz lane affordable.** `:adapter:test` sets `maxParallelForks` (cores-capped: `max(2, min(4, cores))`, override with `-PtestForks=N`), so the kernel-differential + fuzz classes run across **isolated JVM forks** (each fork loads its own kernel — no shared state to race, heap left at the default). That's what lets the fuzzers stay **per-push** rather than being deferred to nightly. The native-agent reflection capture runs `:cli:test` (which pins `maxParallelForks=1`), not `:adapter`, so the adapter fork setting needs no capture guard.

## Tests that read repo files at runtime → slim, task-specific inputs

A few guards read repo files Gradle can't infer as inputs: `CliTourFreshnessTest` reads `examples/*.md`; `CommandReferencesResolveTest` reads the source + public READMEs, `skill/SKILL.md`, the Codex `AGENTS.md`, and the operator catalog; `SharedReadmeRegionTest` reads both public READMEs; `DocsIndexGuardTest` reads the whole `docs/` (+ `docs/archived/`) md tree and its index [`docs/README.md`](README.md), failing the build if a doc isn't linked from the index or an index link doesn't resolve. If those files aren't declared inputs, the test task goes **up-to-date / restores from the build cache** on a docs-only change — a **false green locally** while CI (a fresh checkout) fails. It bit us: a stale `cli-discover.md` capture passed locally in `1s` (cached) and only CI caught the drift.

The fix is **not** to widen the main `test` task's inputs — that re-runs all ~620 tests on *any* `.md` edit. Instead these guards run in a dedicated **`docGuardTest`** task (`cli/build.gradle`) with exactly those files declared as **its** inputs, and `test dependsOn docGuardTest`. Result: a docs/demo edit re-runs **only** these guard classes; a code edit doesn't touch them; and `:cli:test` (CI, local, and the `-PrkNativeAgent` capture — via the dependency) always drives them. **Standing rule:** when a test reads a repo file at runtime, declare that file as an input on a task scoped to *just* that test — never on the broad suite. Keep test dependencies slim and specific.

The **conformance corpus** guards apply the same pattern in `:adapter`: `CorpusReplayTest` + `CorpusSchemaGuardTest` read the `corpus/cases` tree at runtime, so they run in a dedicated **`corpusReplayTest`** task (`adapter/build.gradle`) with the corpus tree declared as its inputs and are excluded from the forked main `:adapter:test` (which `dependsOn` it). A case edit re-runs only those guards — and can't restore from the cache into a false green. `PerfScriptContractTest` follows the same pattern in `perfScriptGuardTest`, whose sole runtime input is `scripts/perf.sh`. The profile lane does likewise: `profileFixtureGuardTest` declares the three committed profile artifacts and runs their canonical-format and fixed-seed fidelity checks.

The **semantics-map resolve-guard** applies the same pattern in `:cli`: `SemanticsMapResolveTest` reads [`docs/SEMANTICS-MAP.md`](SEMANTICS-MAP.md) plus every surface the map derives from — KERNEL-FINDINGS' `§n`-tagged headings, the operator catalog's `semantics` dimensions, the `corpus/cases` tree's `meta.sections`, and the **three lock-test source trees** — so it runs in the dedicated **`semanticsMapGuardTest`** task (`cli/build.gradle`) with exactly those files as its inputs (`test` dependsOn it). Editing the map, a findings heading, a corpus case, or a lock tree re-runs only this guard — and a **new `*Test` class in a lock tree fails the build until the map places it** (in a section row or the outside-the-taxonomy table), which is what keeps the map derivable rather than hand-curated.

The same task carries the **kernel-docs fold contract**: `KernelDocsFoldContractTest` reads [`docs/KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) + [`docs/KERNEL-SEMANTICS.md`](KERNEL-SEMANTICS.md) (already declared inputs of the task) and enforces the two-doc layer split mechanically — every finding heading carries a valid fold-status line (`folded → §n` / `not-foldable — reason` / `pending — reason`), every folded target names an existing canonical section, and the `§`-mirror between the two docs stays intact — so the fold-lag that once accreted 3:1 cannot silently re-accrete ([`DOC-DISCIPLINE.md`](DOC-DISCIPLINE.md) §1). It also pins the **canonical anchor set**: the 14 numbered sections' GitHub heading slugs (+ the field-types table) must exist and every intra-doc `](#…)` link must resolve — deleting **or renaming** a canonical heading fails the build naming the exact missing anchor, protecting everything that keys on the slugs (the fold markers, SEMANTICS-MAP, the doc's cross-links, a downstream semantic-export consumer's pinned references — DOC-DISCIPLINE §1).

The **root project** applies the same pattern for its two repo-file-reading guards, in its own **`docGuardTest`** task (root `build.gradle`, `test` dependsOn it): `DocSnippetFreshnessTest` reads `docs/API-GUIDE.md` (its test-source side needs no declaration — a region edit changes bytecode), and `DslCatalogBridgeTest` reads the dsl package's `catalog:<Id>` **Javadoc markers**, the sharpest form of the trap: a marker-only edit compiles to **identical bytecode**, so without the declared source-tree input the plain `test` task goes up-to-date and silently skips the very guard the edit needs.

The **public-API lock** applies the same pattern at root `:` in its own **`apiGuardTest`** task (root `build.gradle`, `test` dependsOn it): `PublicApiGuardTest` reads the committed goldens under `api/` at runtime, so `api/*.api` is declared as *that* task's inputs (never on the broad suite) — a golden edit re-runs only this guard and can't restore from the build cache into a false green. A kit's *public API changing* is picked up by a different mechanism: the guard dumps each kit's compiled `build/classes/java/main`, and those class dirs are already on `sourceSets.test.runtimeClasspath` via the `testImplementation project(':<kit>')` deps, so a signature change invalidates the task automatically. `guardInputs` (root `ext`, §3b #17) also fails the build loudly if `api/` empties. The fixer `dumpKitApi` regenerates the goldens in place (`-Dapi.fix=true`, `outputs.upToDateWhen { false }`).

The **interpreter's own** corpus replay (`CorpusReplayCommonTest`, WI-6) reads the same tree at runtime from **both** JVM and JS — but `:interpreter` has no repo-path convention and the Node test's working directory is not the repo root. So `interpreter/build.gradle` sets the corpus location via a **`CORPUS_DIR` environment variable** (the robust Gradle-provided path) on **both** `jvmTest` and `jsNodeTest`, and declares the corpus files as **inputs of those tasks** — so a case edit re-runs the replay on both targets and a no-change run stays cached. (The JVM actual also walks up to `settings.gradle` as an IDE fallback; the JS actual relies on the env var.)

The NFR-F1 production dependency boundary is a build-graph guard, not a JUnit concern. `:interpreter:check` runs `interpreterRuntimeClosurePolicyTest`, `interpreterRuntimeClosure`, and `interpreterJsRuntimeClosure`: detached Gradle graphs force real project/module identifiers and actual file/directory dependencies through the classifier/reconciler/policy path, while focused synthetic fixtures lock every forbidden class, exact-once reconciliation, and recursive production dependency/optional/peer npm declarations. The host tasks reconcile the live JVM/JS runtime files, account graph-only components, and write separate reports. `:adapter:kernelRuntimeClosureReport` first executes a minimal fresh kernel validation consumer, then derives and reconciles the informative comparison graph from that source set's necessary compile/runtime dependencies; it gates no numerical ratio.

The NFR-F2 cache-lifetime boundary is a deterministic JVM structural guard, separate from retained-heap calibration. `CacheLifetimeGuardTest` observes logical entries through an internal nullable seam on the actual `Document`, `ParsedModel`, and computation-step `OverlayViews` owners. It requires an exact post-warm plateau while the reused engine/document remain fenced and live, then uses weak references plus a reference queue to prove many discarded owners release and the weak observer returns to zero. Planted one-entry-per-evaluation and global-document retention controls must fail, so sensitivity to both leak classes is executable. It promotes no heap slack or numerical NFR bar.

The NFR-F2 host measurement is a local opt-in lane, not part of `check` or CI. [`scripts/interpreter-footprint.sh`](../scripts/interpreter-footprint.sh) requires a clean commit and at least five independent JVM plus five independent Node processes, then drives `:interpreter:interpreterFootprint` at exact `N, 2N, 4N, 8N` scales. Each worker must establish explicit GC, complete runtime/method metadata, positive retained and allocation observations, exact scenario semantics/cardinality, logical cache counts, and weak owner release. The three shared model artifacts are authored through modelkit/EditSession/rulekit; `:adapter:footprintFixtureGuardTest` reads only those committed files as its declared task-specific inputs and rejects drift, while `:interpreter:generateFootprintFixtureSource` embeds the same serialization into common tests for both hosts. The aggregate rejects missing/duplicate/inconsistent evidence and writes the raw forks plus informative median curves to `interpreter/build/reports/interpreter-footprint/footprint-report.json`; it applies no numerical threshold.

The NFR-F1 delivery measurement is another clean-commit local opt-in lane, not part of `check` or CI. [`scripts/interpreter-delivery-footprint.sh`](../scripts/interpreter-delivery-footprint.sh) injects one collector into the current and archived previous-release sources, rebuilds five functional bases, executes the native and native-equivalent trimmed-JVM CLIs against the same typed low-firing model/instance, and writes exact bytes/SHA identities plus previous-release deltas. Collector, runner, revision, and host/toolchain disagreement fail the comparison. The JS KLIB remains explicitly separate from production npm: until IG85 ships a non-empty export + `.d.ts` + non-repo consumer smoke, the report records that surface as unavailable and emits no fictional package bytes. All ratios/signals remain informative.

## Running

Build with an isolated, public-repos-only Gradle home on JDK 21 (the kernel must first be in `mavenLocal` via the sibling bootstrap kit — see the README "Build & test"):

```sh
ROOT="$(cd .. && pwd)"; export GRADLE_USER_HOME="$ROOT/.gradle-isolated"; export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew test :adapter:test :cli:test                      # full suite across modules
./gradlew :adapter:test --tests "*.PolarityLawsTest"        # one class
./gradlew :adapter:test --tests "*.KernelMmReadCoverageTest" -Da12.kernel.mm.dir=../a12-kernel-mm   # opt-in
./gradlew :adapter:test --tests "*.Corpus1ReadCoverageTest" -Da12.corpus1.dir="…/workspaces"        # opt-in
./gradlew syncDocSnippets                                   # rewrite drifted locked doc snippets (autonomous; never CI)
```

The repository-level [`gradle.properties`](../gradle.properties) enables Gradle's local build cache and sizes the Kotlin compiler daemon for overlapping cold JVM, JS, and adapter test compilation. The isolated Gradle home is intentionally shared across clean worktrees: [`build.sh`](../build.sh) anchors its default at Git's common directory/primary checkout, not the linked worktree's filesystem parent, so a worktree under `/private/tmp` does not create a second cache. Identical relocatable compiler outputs therefore survive a landing or worktree switch. [`scripts/verify-build-cache.sh`](../scripts/verify-build-cache.sh) is the structural smoke: a successful cold prime in one clean worktree, followed by a second worktree at the same commit where every named expensive compiler task must restore `FROM-CACHE`. Both builds use `--offline`, and no remote cache is configured, so network access cannot supply the result. It gates outcomes, not machine-dependent elapsed seconds. A truly cold run still owes the complete work; automation with a per-command time budget must invoke `:interpreter:check`, `:adapter:test`, and `:cli:test` separately rather than hiding all three behind one timeout.

**Tier 2 — doc-drift demos (showboat).** One command verifies them all — `./scripts/verify-demos.sh` builds the launcher, discovers every demo, and verifies them in parallel (exactly what CI runs). To exact-match a single demo by hand, put `bin/` on PATH so `dmtool` resolves:

```sh
./scripts/verify-demos.sh                                   # build + verify EVERY demo (parallel) — the CI gate
# — or a single demo by hand:
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$PWD/bin:$PATH"
./gradlew :cli:installDist                                  # the launcher the demos call
uvx showboat@0.6.1 verify examples/cli-tour.md              # exit 0 = still matches the live CLI
```

**Tiers 3–4 — the experience instruments are run-and-read, not a plain command:**

- **eval (N6)** — use the `run-dmtool-eval` skill (it loads the protocol guardrails at run time); method [`EVAL-SPEC.md`](EVAL-SPEC.md), results [`../eval/RESULTS.md`](../eval/RESULTS.md).
- **probe** — the main-loop pipeline: re-capture metadata + rebuild native + native-gate → `probe/prep-probe.sh` → spawn the subagent → `probe/audit-probe.sh` (STOP if SUSPECT) → `probe/digest-probe.sh` → analyze vs gold → `probe/reports/<date>-<task>.md`. **Never probe a stale native binary** (`prep-probe.sh` enforces it). Method [`PROBE-SPEC.md`](PROBE-SPEC.md), how-to [`../probe/README.md`](../probe/README.md).
