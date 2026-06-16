# Testing spec — the testing-approach map

The map of *how* a12-dmkits is tested — the kinds of test, what each one is for, where it lives, and which oracle backs it. This is the navigation layer; the enforced conventions stay in [`../CLAUDE.md`](../CLAUDE.md) ("Testing strategy") and the per-area detail stays in its own doc (linked below). Read this when you need to place a new test or recover the big picture without hunting across five files.

## The one principle: the real kernel is the oracle

There is **no mock kernel**. Every claim about how the A12 kernel parses, validates, or *evaluates* a rule is locked by a test that runs the **real engine** (`30.8.1`, pinned). A feature isn't done when the DSL renders the right string — it's done when the kernel *accepts* that string (F4) and, where behavior matters, *evaluates* it as intended (F-LAW). Tests are example-based and TDD-first (red/green): readable, intent-revealing cases drive each feature.

Consequence: the kernel is an unstable dependency, so the suite is also an **early-warning system** for kernel upgrades — many tests are deliberately "guardians" that fail loudly when a kernel bump shifts something. See [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

## Where tests live (module map)

```
:            (core+rulekit+modelkit)  src/test/…        — DSL render, F4 mechanics, accept/reject laws,
                                                          structural guardians, modelkit structure tests,
                                                          SiblingBoundaryTest (dm.modelkit ⊥ dm.rulekit)
:adapter     (opt-in)                 adapter/src/test/… — read round-trip, runtime laws, kernel-coupling canaries, corpus harnesses
:cli                                  cli/src/test/…     — verb I/O contract, batch, explain
```

modelkit folded into the root `:` project (2026-06-09 — no longer a `:modelkit` module); its tests live in the root test set alongside rulekit's. The aggregate `./gradlew test` runs core + rulekit + modelkit; `:adapter:test` and `:cli:test` are separate (the adapter is the opt-in kernel-coupling tier, kept out of core's `:test`).

## The kinds of test

| kind | question it answers | where | backing oracle |
|---|---|---|---|
| **Example / unit** | does the DSL render the right text, parenthesize, quote, type-check handles? | `dsl/RenderConditionTest` + one file per family under `dsl/operators/` (`PatternConditionTest`, `AggregateConditionTest`, …) | pure rulekit (no kernel) |
| **F4 mechanics** | does injection → validate → problem-mapping work? | `validate/RuleValidatorTest` | real kernel `checkConsistency` |
| **Law tier 1 — accept / reject** | does the kernel *accept* this condition (and reject the bad one with which `MVK_*`)? | `validate/laws/…LawsTest` (`NumberLawsTest`, `BooleanConfirmLawsTest`, …) via the `KernelLaws` harness | real kernel parse + `checkConsistency` |
| **Law tier 2 — fires / does-not-fire** | does a *valid* rule *evaluate* as intended on data? | `adapter/…/laws/…LawsTest` (`PolarityLawsTest`, `EmptyValueLawsTest`) via the `RuntimeLaws` oracle | real kernel `IDocumentRtService.validateFull` |
| **Read round-trip** | does render → kernel-parse → convert → render round-trip (F10)? | `adapter/…/ReadRoundTripTest` + per-construct `…ReadTest` | real kernel parser + the converter |
| **Structural-law guardians** | did the kernel *add/move/rename* a construct, code, slot, or category we must track? | see the guardian table below | reflective parity vs the kernel's own enums/accessors |
| **CLI self-describing & conformance** | does the CLI describe its own verbs/params/I-O, and does every mutating verb refuse misuse with a *structured* diagnostic (not a bare fail)? | the `cli` conformance suite — see below | the live picocli tree + a derived fuzzer |
| **Coverage harnesses** (opt-in) | what fraction of real foreign rules do we read? | `KernelMmReadCoverageTest`, `Corpus1ReadCoverageTest` | real corpora (gated; commit nothing) |
| **Catalog examples (N7)** | is every catalog `validExample` actually kernel-valid? | `catalog/CatalogExamplesAreKernelValidTest` | real kernel |
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
- **Tier 2 (fires/does-not-fire)** builds a **document instance** with specific field values, runs the kernel's runtime validation, and reads back *which rules fired* (`IMessage.getErrorCode()` / `getRulePath()`). Harness: `RuntimeLaws`. This is the only way to lock behavioral semantics — polarity, empty-as-0, null comparisons, path walk-up. Wiring + the V1 document-construction contract are in [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) ("Runtime behavioral-law oracle"). The same runtime is now also a **public seam** — `KernelAdapter.evaluate`/`compute` behind the `eval-doc`/`compute-doc` verbs — and `EvalDocSeamLawsTest` pins its nested-document walk against `RuntimeLaws`' known-good row pointers (and locks validate≠compute, the number-empty-→0-in-computation case, …).

Both tiers are the **executable form of [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md)**: every findings § (and MVK-ledger row) points at the `…LawsTest` that locks it, so a kernel upgrade that changes a documented behavior fails loudly instead of silently drifting.

## The structural-law guardians (anti-whack-a-mole)

Rather than discover kernel constraints by tripping over them, these sweep the kernel's *enumerable* surfaces and fail when something new appears:

| guardian | what it pins |
|---|---|
| `SlotParityTest` | every public `IRule`/`IComputation` accessor is supported or explicitly deferred |
| `MvkLedgerTest` | every `MVK_*` code is classified (erased / pre-checked / delegated / pending) — [`MVK-LEDGER.md`](MVK-LEDGER.md) |
| `SerializerTierParityTest` | the `BasicDocumentModelConsistencyCategory` write-gate enum (F6) is fully classified |
| `KernelConstructParityTest` | every parsetree construct-name enum member is cataloged or excluded (catches operators the kernel *adds*) |
| `adapter.RtSemanticsLedgerTest` | every runtime evaluation-core class is classified in [`RT-SEMANTICS-LEDGER.md`](RT-SEMANTICS-LEDGER.md) (catches evaluation semantics the kernel *adds*) |
| `GrammarProductionLedgerTest` | every condition-grammar parser production is classified in [`GRAMMAR-LEDGER.md`](GRAMMAR-LEDGER.md) (catches syntax *shapes* — opt-in, needs the sibling `../a12-kernel` checkout, skips when absent) |
| `KeywordParityTest` / `OperatorCatalogTest` | the operator catalog's keywords/shape stay aligned with the kernel bundle |
| `KernelVersionTest` | the running kernel version vs the catalog's `verifiedAgainst` floor (F11 skew INFO) |
| `ModelIndexNamePathTest` | element keying is by name-path (the F-IDX fix foreign-model read depends on) |

When any of these fails after a kernel bump, [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) maps the failure to its meaning and realignment.

## CLI self-describing surface & conformance ([`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md))

The `dmtool` CLI is the agent's entire interface (the N6 eval drives the bare binary), so it must be **self-describing** — an agent discovers the verbs, their params, each verb's I/O shape, and a worked example *in action*, all *from the tool* — and **diagnostic** — a misuse returns a structured, corrective envelope, never a bare stack trace or terse exit. These tests enforce both, and most are **derived from the live picocli command tree**, so they can't drift from the actual verbs:

| test | what it pins |
|---|---|
| `ManifestVerbTest` | every verb is in `manifest` with a description (`EXPECTED_VERBS` is the canonical set — a rename/removal can't silently drop a verb) |
| `ManifestTreeTest` | nested `target op` verbs resolve; bare target groups aren't runnable; `-m` is global |
| **`CliConformanceMatrixTest`** — the per-verb **matrix** | the single **derived** gate (folds the old `ManifestCompletenessTest`): one row per verb, one column per obligation — *described*, *params-described*, *schema-resolves* (every envelope verb's `schema` pointer resolves to real JSON; the non-envelope verbs are a reasoned `CONTRACT_EXEMPT` ledger), and *demonstrated*. No skip — this is exactly how `meta` (no schema pointer) slipped past the old check. A new column codifies the next anti-pattern. |
| `ParamKindManifestTest` | each `@ParamKind`-tagged param surfaces its kind in `manifest` (an agent learns "a FIELD_PATH that must exist" vs "a NEW_NAME you choose") — spot-checks the disambiguating kinds (`rename --to` = NEW_NAME vs `move --to` = GROUP_PATH) |
| `SchemaVerbTest` | `schema result`/`diagnostic` serve the structures; `schema <target> <op>` (and `schema <verb>` for an envelope cross-cutting verb like `meta`) gives the directional contract — incl. a verb's `docInput` (the `--doc` `DocumentInstance` shape) and `dataSchema` (its read payload, e.g. `Explanation`/`ModelCard`) where they exist; **every target-first verb's real output conforms to `result.schema.json`** (the F11 drift guard) |
| `SchemaExamplesGuardTest` | every published schema `examples` block is **CI-executed through the tool** (the `add` rule/computation specs and the `field-add` field spec via `--dry-run`, the `apply`/`batch` frames, the `document-instance` example via `model eval`) or statically conformed (`result`/`diagnostic`) — a stale example can't ship |
| **`VerbDemonstrationTest`** — the **demonstration** gate | every verb's worked example in `verb-examples.json` (the read-only invocation `manifest` surfaces as the verb's `example`, so a cold agent sees it *in action*) is **executed through the tool** against a fixture and must produce its documented outcome — the "in action" demo can't rot |
| `RkCodeParityTest` | the single-source `RkCode` registry (served by the `diagnostics` verb) stays bidirectionally in sync — every emitted code is registered, no dead rows |
| `NotFoundRefusalTest` | a not-found entity-ref on a mutating verb refuses with the structured `RK_NO_SUCH_*`, not a bare error |
| **`DiagnosticConformanceFuzzTest`** — the conformance **fuzzer** | derived from the live tree, it probes every mutating verb with a **bogus value for each entity-ref param** and asserts a **structured** envelope comes back (a code + a fix), not a bare fail. A new verb can't escape it, and it catches real regressions — e.g. it caught a `rule modify` change whose precondition *ordering* let a missing-rule slip through as a terse "nothing to change" instead of the structured `RK_NO_SUCH_RULE`. |

The bar (CLI-CONFORMANCE-SPEC): a cold agent given only the binary can self-serve. Two active enforcers make a new verb hold the contract automatically — the **matrix** proves it's fully surfaced (described, contract-discoverable, demonstrated), and the **fuzzer** proves its misuse is structured-diagnosed.

## Locked doc snippets (no-rot for the `java` code in docs)

A `java` code block shown to a reader in the markdown docs is hand-authored prose with no compiler behind it — so it drifts silently as the API moves, and a stale snippet actively *misleads*. `DocSnippetFreshnessTest` removes the rot for the **canonical** snippets (the §9-shipped examples in [`API-GUIDE.md`](API-GUIDE.md)): each locked block must be byte-identical — after indent/trailing-whitespace normalization — to a **tagged region of a real test that compiles and runs against the kernel**. Same two levers as the CLI side (`CliTourFreshnessTest`): **compiled + run ⇒ no-rot; locked-to-source ⇒ no-drift.**

This is *not* extract-and-compile of every fence. The README and many study blocks are deliberate **teasers** — `…` elisions, prose interleaving, `✅`/`🔮` annotations, or deliberately *non-compiling* illustrations (§2's "what you cannot write") — they stay hand-maintained. Only the *clean, canonical, runnable* snippets get a verbatim lock; pick the one example per shipped capability that is a real compile unit.

**Completeness — no shipped snippet escapes (the §9 ✅ contract).** A second guard in the same class, `everyShippedDocSnippetIsLockedOrTeaserMarked`, walks `API-GUIDE.md` and fails if any `java` block whose heading ancestry includes a ✅ section is *neither* `<!-- snippet: <id> -->` locked *nor* `<!-- teaser -->` marked. So a newly-shipped snippet can't silently dodge the lock — it must be classified, the same "every X accounted for" discipline as the CLI conformance matrix. The locked set earns its keep: locking the §6c rule-template pipeline surfaced three real defects in a snippet no one had run (missing `.name()`, missing message, missing the `de` locale of a bilingual model) — all now fixed in the doc.

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
| **completeness** | `everyShippedDocSnippetIsLockedOrTeaserMarked` — every ✅-section `java` block is locked or `<!-- teaser -->` |

## Opt-in corpus harnesses (gated, commit nothing)

These read the **real** model corpora to measure coverage; they skip when their volume/clone is absent, print a report, and never commit corpus content (proprietary A12 Tools workspaces; EUPL `a12-kernel-mm`):

| harness | corpus | gate | asserts |
|---|---|---|---|
| `KernelMmIntegrationTest` | `../a12-kernel-mm` | sibling clone present | all canonical metamodels load/expand/`checkConsistency`; every field classifies |
| `KernelMmReadCoverageTest` | `../a12-kernel-mm` | sibling clone present | read coverage ≥ a floor (regression gate) |
| `Corpus1ReadCoverageTest` | A12 Tools workspaces | `-Da12.corpus1.dir=…` | prints coverage only (proprietary) |

## Layout conventions (so hotspots never grow)

- **One file per operator family**; a new increment adds a *new* small file, it does not pile onto a monolith. `dsl/` keeps only the renderer/handle core; each family lives under `dsl/operators/`.
- **`validate/` keeps only F4 mechanics**; the kernel-law locks live under `validate/laws/` (tier 1) and `adapter/…/laws/` (tier 2).
- **Fixtures load once** via the shared `Fixtures` util (`subscription()`/`order()`/…) — `BoundModel` is an immutable snapshot (F0), safe to share, so a new file costs a few lines.

## Running

Build with an isolated, public-repos-only Gradle home on JDK 21 (the kernel must first be in `mavenLocal` via the sibling bootstrap kit — see the README "Build & test"):

```sh
ROOT="$(cd .. && pwd)"; export GRADLE_USER_HOME="$ROOT/.gradle-isolated"; export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew test :adapter:test :cli:test                      # full suite across modules
./gradlew :adapter:test --tests "*.PolarityLawsTest"        # one class
./gradlew :adapter:test --tests "*.KernelMmReadCoverageTest" -Da12.kernel.mm.dir=../a12-kernel-mm   # opt-in
./gradlew :adapter:test --tests "*.Corpus1ReadCoverageTest" -Da12.corpus1.dir="…/workspaces"        # opt-in
```

The self-sufficiency eval (N6) is **not** run from here — use the `run-rulekit-eval` skill (it loads the protocol guardrails at run time); method in [`EVAL-SPEC.md`](EVAL-SPEC.md), results in [`../eval/RESULTS.md`](../eval/RESULTS.md).
