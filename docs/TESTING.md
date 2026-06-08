# Testing approach

The map of *how* a12-dmkits is tested — the kinds of test, what each one is for, where it lives, and which oracle backs it. This is the navigation layer; the enforced conventions stay in [`../CLAUDE.md`](../CLAUDE.md) ("Testing strategy") and the per-area detail stays in its own doc (linked below). Read this when you need to place a new test or recover the big picture without hunting across five files.

## The one principle: the real kernel is the oracle

There is **no mock kernel**. Every claim about how the A12 kernel parses, validates, or *evaluates* a rule is locked by a test that runs the **real engine** (`30.8.1`, pinned). A feature isn't done when the DSL renders the right string — it's done when the kernel *accepts* that string (F4) and, where behavior matters, *evaluates* it as intended (F-LAW). Tests are example-based and TDD-first (red/green): readable, intent-revealing cases drive each feature.

Consequence: the kernel is an unstable dependency, so the suite is also an **early-warning system** for kernel upgrades — many tests are deliberately "guardians" that fail loudly when a kernel bump shifts something. See [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

## Where tests live (module map)

```
:            (core)      src/test/…        — DSL render, F4 mechanics, accept/reject laws, structural guardians
:adapter     (opt-in)    adapter/src/test/… — read round-trip, runtime laws, kernel-coupling canaries, corpus harnesses
:cli                     cli/src/test/…     — verb I/O contract, batch, explain
:modelkit                (tests run in the aggregate :test set)
```

The aggregate `./gradlew test` runs core + modelkit; `:adapter:test` and `:cli:test` are separate (the adapter is the opt-in kernel-coupling tier, kept out of core's `:test`).

## The kinds of test

| kind | question it answers | where | backing oracle |
|---|---|---|---|
| **Example / unit** | does the DSL render the right text, parenthesize, quote, type-check handles? | `dsl/RenderConditionTest` + one file per family under `dsl/operators/` (`PatternConditionTest`, `AggregateConditionTest`, …) | pure rulekit (no kernel) |
| **F4 mechanics** | does injection → validate → problem-mapping work? | `validate/RuleValidatorTest` | real kernel `checkConsistency` |
| **Law tier 1 — accept / reject** | does the kernel *accept* this condition (and reject the bad one with which `MVK_*`)? | `validate/laws/…LawsTest` (`NumberLawsTest`, `BooleanConfirmLawsTest`, …) via the `KernelLaws` harness | real kernel parse + `checkConsistency` |
| **Law tier 2 — fires / does-not-fire** | does a *valid* rule *evaluate* as intended on data? | `adapter/…/laws/…LawsTest` (`PolarityLawsTest`, `EmptyValueLawsTest`) via the `RuntimeLaws` oracle | real kernel `IDocumentRtService.validateFull` |
| **Read round-trip** | does render → kernel-parse → convert → render round-trip (F10)? | `adapter/…/ReadRoundTripTest` + per-construct `…ReadTest` | real kernel parser + the converter |
| **Structural-law guardians** | did the kernel *add/move/rename* a construct, code, slot, or category we must track? | see the guardian table below | reflective parity vs the kernel's own enums/accessors |
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
| `KeywordParityTest` / `OperatorCatalogTest` | the operator catalog's keywords/shape stay aligned with the kernel bundle |
| `KernelVersionTest` | the running kernel version vs the catalog's `verifiedAgainst` floor (F11 skew INFO) |
| `ModelIndexNamePathTest` | element keying is by name-path (the F-IDX fix foreign-model read depends on) |

When any of these fails after a kernel bump, [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) maps the failure to its meaning and realignment.

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
