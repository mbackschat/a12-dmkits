# Portable consistency — implementation plan

> **Planning baseline — 2026-09-11.** Implements the reviewed [portable consistency proposal](PORTABLE-CONSISTENCY-PROPOSAL.md) at `f0cba2488`. This is the fresh plan following the restart; no implementation slice is claimed complete. The proposal owns the product contract and acceptance matrices. This plan owns execution order, prerequisites, review checkpoints and the next bounded task.

**Scope.** The checker validates the **rules and computations** of a DocumentModel, conforming to the Kernel for A12 models — including the type-compatibility laws operators impose on their operands. It must support the whole model structure as context, resolving references against it and reading its type facts, but it does not validate whether a group, field or type declaration is itself legal: that is whole-model consistency and a separate product. The only exclusion is a Kernel law that cannot fire for an A12 model. No slice, stage or record below widens this; the authority is the [proposal's scope](PORTABLE-CONSISTENCY-PROPOSAL.md#4-required-functionality-and-scope-boundaries).

**NFR #1 — the interpreter's performance is never compromised.** The proposal's highest-ranked non-functional requirement outranks every checker goal, including the checker's own H5 budgets: no comparable interpreter row regresses by more than 5% against the restart baseline, per row with no compensation between rows, and missing or inconclusive evidence fails rather than passes. Ordinary evaluation takes on no checker dependency, analysis preparation or retained source graph. Where a stage below could trade interpreter cost for checker convenience, the interpreter wins by default and the trade is escalated, not made in passing. Obligation and evidence rules: [H4](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner).

The fixed interpreter comparison baseline is `c8e3b99795fe9c2ce3e9db32632a6dd8f6537043`. The abandoned attempt at `6b4c6838737b5db7ad264ba7e207446b3cfcb27d` is read-only source/evidence material. Recover individual original-code changes only after identifying their owner and retained tests; do not restore its module graph, preview API, migration harness or performance qualification.

## 1. Working boundaries

- Build a standalone KMP checker with its own admission, syntax, binding and immutable analysis state. The default is a port of our original infrastructure. A neutral shared leaf is optional and starts with no permitted types until A establishes its bounded allowlist.
- Share selected model facts and measured constraints between designated Java façades and the checker. Preserve existing Java public contracts and deliberate authoring policies. Designate consumers explicitly; do not claim repository-wide consolidation.
- Keep interpreter execution preparation independent. Check both products' production closures and shipped-file ownership, including copied sources, declarations and generated output. Build-tool reuse does not transfer ownership of shipped API files.
- Preserve default Kernel checking in JVM/native dmtool. Portable checking is an explicitly selected consumer of the published checker; it never substitutes automatically for Kernel or vice versa.
- Start each port with the existing implementation and test owner. Translate our own code and reuse expectations. Add a test only for a named uncovered requirement, translation defect or platform difference; use red/green TDD for new behavior and fixes.
- Complete one finite slice before opening another. Freeze its families, fixtures, oracles and exclusions before implementation. An expanding acceptance denominator triggers the repository's convergence procedure, not another patch round.

Use the proposal's [scope boundaries](PORTABLE-CONSISTENCY-PROPOSAL.md#4-required-functionality-and-scope-boundaries) and [completion criteria](PORTABLE-CONSISTENCY-PROPOSAL.md#10-completion-criteria) when partitioning work; sequencing does not change either contract.

## 2. Execution order and landing boundaries

Rows below are dependency stages, not permission to combine every named slice into one commit. Each independently meaningful, verified feature lands with its documentation. B1–B3 and C1–C2 retain the proposal's separate acceptance scopes even where their delivery is interleaved with B or C. The detailed case denominator remains in [proposal §7](PORTABLE-CONSISTENCY-PROPOSAL.md#7-implementation-slices-and-finite-acceptance-matrices) and [§12](PORTABLE-CONSISTENCY-PROPOSAL.md#12-project-synergies-additional-features-and-focused-cleanups).

| Order | Slice / bounded work | Prerequisite and exit evidence | Review checkpoint |
|---|---|---|---|
| 1 | A: port/ownership boundary and finite profile inventory; A1–A4 as separate inventory tasks | Complete the records in §3 below. Freeze the first implementation family and performance workload matrix; identify unresolved evidence without counting it as coverage | Terminal foundation review of the inventory and its consumer/constraint boundaries before B begins |
| 2 | B foundation: B2 admission/phase separation and the minimum B3 source-preserving preparation needed for references | A's boundary is fixed. Reuse source admission/parser/workspace owners; retain invalid input, original numeric configuration and source identity. Establish Kernel-free module/test dependencies | One earlier foundation review of public source/snapshot and phase contracts; terminal review before the bounded foundation lands |
| 3 | B1 reference binding, C2 reference diagnostics, then B0's public reference slice | Land the selected shared resolver and corrective owner before B0 consumes them. Run the four-row reference grid, absence and precedence controls. JVM/common tests assert completed reference evidence and overall incomplete status; the API cannot emit VALID | Terminal review of the classified reference result, positive controls and designated Java adoption |
| 4 | Finish B/B3 context and preparation responsibilities; complete C2 diagnostic delivery | Extend the frozen source/fact set to includes, typedefs and repeated mounts; finish Java query reconciliation and parse-once controls. Apply A2's resource ownership decision | Terminal review for each remaining preparation/diagnostic contract; no second general architecture exercise |
| 5 | C/C1 local semantic families, each followed by its D stored-declaration consumer | Translate each selected responsibility and adopt it in its designated Java caller before the checker consumer lands. Retain Java tests, independent Kernel witnesses and policy separation. D covers rules, operations, every precondition/alternative, targets and relevant messages | Terminal review per frozen family; one earlier review only if it introduces a new foundational finding/syntax contract |
| 6 | E relational families | D exposes the required context. Implement the frozen iteration/filter/index and computation-dependency laws using existing evidence plus focused uncovered controls | Terminal review per finite relational family |
| 7 | F2 durable differential, building on the independent witnesses used since B0 | Consolidate A4's same inputs and profile-scoped expectations into the durable adapter lane; neither engine gates admission to the other. Retain complete Kernel observations separately | Terminal review of oracle independence and comparison exclusions |
| 8 | F standalone public delivery and qualification | B–E/C2/F2 supply the declared profile and full reports. Start with H5 budget ratification using §5's calibration record, before measuring the qualifying implementation. Execute Java, packed Node and browser acceptance, replaying B0's retained controls and F2's same corpus; run ownership/closure guards, API compatibility and H5 qualification | One earlier API/schema/publication-boundary review; terminal review of the actual packed consumers and qualification |
| 9 | F1 dmtool coexistence; F3 published-library integration, as separate commits | F provides the artifact and explicit result contract. F1 retains default/explicit Kernel in both launchers and adds explicit portable selection. F3 adds dmkits/schemakit checking and an integration façade outside both standalone cores | Terminal review for each integration; F1 includes packaged JVM/native identity and substitution controls |
| 10 | G Node CI example; H candidate checking | F's package can be consumed normally. G distinguishes invalid, incomplete and operational failure; H checks immutable candidates without persistence and agrees with fresh checking | Terminal review of each public consumer contract |
| 11 | J impact reports; I editor diagnostics, as separate slices | J uses E's dependency facts and H's candidates. I uses source-aware partial analysis and H's snapshots; neither depends on the other's implementation | Terminal review of affected/unaffected controls for J and source-location controls for I |
| 12 | L incremental checking | L uses B3/J and must match fresh full reports while demonstrating reuse. Complete the H/I/L browser integration once it lands | Terminal review per API, including stale-cache controls |
| 13 | M two-profile compatibility comparison | A3 has pinned two real versions and independent witnesses; both profiles' required implementations are present. Compare with separate checks under each profile | Terminal review of profile evidence and deterministic public differences |
| 14 | N combined availability and qualification | Every earlier slice passes its frozen matrix; all G–J/L/M exports and the browser integration work from shipped packages. Complete §5 below | Terminal whole-product contract review before declaring completion |

Packed delivery belongs to F. A2's earlier resource check is a bounded packed-resource experiment using existing tooling, not an early implementation of the full package estate. B0's JVM/common tests and F2's adapter lane do not depend on F. F owns their packed Node/browser corpus replay, using the same inputs rather than inventing another matrix.

## 3. First task: A, with a finite inventory contract

**Recommendation:** finish the following records before production translation. They settle what will be shared, what can be claimed and what will measure success. This is an inventory of selected responsibilities and the profile denominator, not a new general-purpose verification framework.

| Record | Required contents | Exit oracle |
|---|---|---|
| Port and consumer boundary | Enumerate selected source responsibilities, imports/callers, existing test owners, designated Java consumers and residual owners. Decide explicitly whether `ConditionBridge` is designated. Record proposed checker-owned types and every optional neutral-leaf type | Read every included source/caller; report search commands, matches found/read and anchored exclusions. A leaf with no established benefit stays absent. No reliance on orphaned `build/` output or archived modules as current source |
| Constraint/profile denominator | Map the laws and grammar productions the Kernel can raise on an A12 model’s rules and computations to current enforcement, Kernel firing sources, raw-input carriers, prerequisites, authoring policies, witnesses and exclusions. Include conversion/non-MVK and applicable generation-path restrictions | Every entry has a disposition; unresolved entries prevent a complete coverage claim. A code count is not a law count |
| A1 erased-bucket disposition | Re-derive the proposal's dated enumeration and disposition every matching row. Rows are in scope by default; an exclusion cites a Kernel path that is dead for A12 input | Reachability/acceptance claims have the appropriate Kernel evidence. Builder inability, renderer avoidance and an absent rulekit authoring surface are each barred as an exclusion basis |
| A2 diagnostic ownership | Map catalog guidance, `StructuralKernelFix`, applicable `RkCode` knowledge and consumer projections. Prefer direct resource reuse; use deterministic projection when delivery/size or Java-owned guidance requires it | Bounded packed Node resource check plus source/projection freshness and contextual guidance controls; acyclic graph, no new hand-maintained dictionary |
| A3 two-version basis | Record 30.8.1 and exactly one additional available real A12 version, artifact identities, accessible independent oracles and finite profile witnesses | Artifact/version availability and oracle access are verified. If the second version cannot be obtained, identify the concrete acquisition blocker; M/N remain blocked, while unrelated slices can proceed |
| A4 corpus ownership | Identify retained fixtures and typed authors, canonical sources, stable case ids, profile applicability and explicit accepted/rejected/incomplete expectations | Reuse one input owner across common replay, adapter differentials and packed consumers. Add only minimal raw-input mutations needed to reach shapes builders cannot author |
| H4 comparison matrix | Fixed restart revision, row/workload identities, hosts, collectors, matching runtime/launch rules, and the resolved-production-set plus built-artifact-digest comparison | Every required row has an existing measurement owner or a named missing observation. The mechanical closure/artifact comparison decides whether an affected sweep is necessary |
| H5 budget proposal | Workload identities, candidate budget recommendations and a bounded calibration-spike specification for cold, prepared and after-edit checking; include resources/release and combined use | A fixes the scenarios, collectors and calibration task described in §5. Sampling, reproducibility evidence and owner ratification are later prerequisites of F qualification, not claims discharged by this inventory |

Use the [proposal research sources](PORTABLE-CONSISTENCY-PROPOSAL.md#11-sources) as the starting map. Existing reference owners include [UndeclaredReferenceTest](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/UndeclaredReferenceTest.kt) and [ShortNameTierLawsTest](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/ShortNameTierLawsTest.java). These are reuse candidates with the exact limits recorded in proposal §3.4; neither is asserted to cover the new complete reference/report contract alone.

A produces the slice-specific acceptance records needed to start B, not empty tables to be filled during implementation. Preserve these in this plan while they are planning decisions; place the machine-readable profile/check registry with its consuming implementation. Keep measured results in a dated evidence document and link them, following [documentation discipline](DOC-DISCIPLINE.md). Do not create a second status tracker.

### Boundary record — ported responsibilities (measured 2026-09-11, partial)

Enumerated with `git grep -l <symbol> -- '*/src/main/*' '*/src/commonMain/*'` for callers and `git grep -l <symbol> -- '*Test.java' '*Test.kt'` for test files, both at `15af15abd`, excluding the native reachability-metadata JSON. **Read in full:** `ModelIndex`'s 27 public method signatures and the complete main-source caller lists for all five symbols. **Not read:** the bodies of the 81 `BoundModel` callers and the 552 test files — counted only. A test count is file mentions, not coverage.

| Responsibility | Source | Main callers | Test files | Modules | Disposition |
|---|---|---|---|---|---|
| Model queries and type facts | [ModelIndex](../core/src/main/java/io/github/mbackschat/a12/dm/core/ModelIndex.java), 401 lines, 27 public methods | 19 | 50 | 5 | Port the query behavior into the portable context |
| Expansion | [ModelReader](../core/src/main/java/io/github/mbackschat/a12/dm/core/ModelReader.java), 789 lines | 8 | 16 | 5 | Reuse the expansion behavior inside checker-owned preparation |
| Reference binding | [BoundModel](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/BoundModel.java), 679 lines | **81** | **552** | 8 | **Do not port the class.** Extract the binding responsibility beneath it; the façade and its API stay |
| Bare-reference resolution | [ParsedModel](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ParsedModel.kt), 181 lines | 4 | 0 by name | 2 | Stays in the interpreter under NFR #1; one half of the §12 duplication pair |
| Undeclared-reference query | `DmInterpreter.undeclaredReferences()` | 3 | 1 | 2 | Reuse the lookup logic in the checker; never call `DmInterpreter` |

**`ModelIndex` is the scope decision made concrete.** Of its 27 public methods, eleven are pure type facts — `kindOf`, `numberScale`, `numberMin`/`numberMax`, `stringMinLength`/`stringMaxLength`, `stringPattern`, `temporalFormat`, `stringNoValueValidation`, `enumValues`, `enumCategories` — and six more are structure facts (`isRepeating`, `maxRepetitions`, `isField`, `isGroup`, `nameOf`, `usageTypeOfGroup`). That is exactly the "support in full, do not validate" surface §4 names: the checker reads every one of them to decide a rule's type compatibility and never asks whether the declaration that produced them is itself legal. The port target is therefore already shaped correctly and needs no new concept.

**`BoundModel` settles the "responsibilities, not classes" rule empirically.** 81 main-source callers across eight modules and 552 test files make porting the class impossible and extracting beneath it the only available move — which is what [proposal §5.2](PORTABLE-CONSISTENCY-PROPOSAL.md#52-port-responsibilities-not-entire-public-classes) already prescribes, now with a measured basis rather than a preference. Its compiled Java API is the guard that the extraction preserved behavior.

**`ParsedModel` has no test file naming it**, so its behavior is covered indirectly — through `UndeclaredReferenceTest` and the interpreter's own lanes. That is a recall limit of the by-name search, not evidence that it is untested; B1 must identify its actual coverage owner before relying on it.

### A1 — method finding: the legacy exclusion is per-SITE, not per-code (measured 2026-09-12)

Under the conformance rule the 60 erased rows split into 52 that are **in scope by default** — needing no evidence, since exclusion is what requires it — and 8 whose ledger cell claims legacy/H-model. Only those 8 need work.

**A package-path heuristic looked like it would settle them, and a control refuted it.** The kernel separates legacy checks structurally (`…/check/bedingung/hdv/…`, `CheckAnzahlAngegebenOpHModelImpl`), so "every site under `/hdv/` or named `HModel`" was the obvious mechanical criterion. Measured with `grep -rl <code> a12-kernel --include='*.java'`, excluding `/build/` and the three enum declaration files:

| Code | Files | Under `hdv`/`HModel` |
|---|---|---|
| `MVK_CONDITION_WITH_WILDCARD` | 1 | 1 |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | 2 | 2 |
| `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` | 2 | 1 |
| `MVK_DIFFERENT_ROOT_GROUPS` | 3 | 2 |
| `MVK_INVALID_PARAMS_FOR_OP` | 3 | 2 |
| `MVK_INVALID_UNSPECIFIED_TYPE` | 1 | **0** |
| *control* `MVK_INVALID_ENTITY` (in scope) | 3 | **1** |
| *control* `MVK_FIELDNAME_NOT_UNIQUE` (in scope) | 1 | 0 |

Two rows kill the criterion. `MVK_INVALID_UNSPECIFIED_TYPE` is called "legacy-only" by the ledger and has **no** `hdv` file at all; `MVK_INVALID_ENTITY` is squarely in scope — it is §3.4's own worked witness — and **has** one. So the heuristic both over- and under-matches.

**The finding is that the unit of disposition is the firing SITE, not the code.** Most of these codes are referenced from several files, and a code can be raised from a legacy path and a live one. [Proposal §7](PORTABLE-CONSISTENCY-PROPOSAL.md#7-implementation-slices-and-finite-acceptance-matrices) already says one code can represent several laws firing from several sources; this measures it. A1 therefore reads each candidate's sites individually, and the ledger's "legacy-only" prose is a lead rather than a basis.

**Recall limits.** `grep -rl` counts **files mentioning the token**, not firing sites: a file may reference a code without raising it, and an indirection through a constant would not be matched. No check implementation body has been read yet, so no row above is dispositioned — this measures only that the cheap criterion fails.

### H4/H5 — the six boundaries mapped to existing collectors (measured 2026-09-12)

Read the collector estate before assigning commands, as §5 requires: `interpreter/build.gradle`'s registered tasks, `adapter/build.gradle`'s performance lanes, `scripts/perf*.sh` and `scripts/interpreter-*footprint*.sh`, and [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md)'s named NFRs (`NFR-S1`/`S2` startup, `NFR-P1`/`P2` warm throughput and scaling, `NFR-F1`/`F2` relative footprint).

| Proposal boundary | Existing owner | Status |
|---|---|---|
| Interpreter-only use | `interpreterPerformance`, `performanceJvmThroughput`, `performanceAggregateReport`, `interpreterFootprint`, `footprintJvmFork*`, `footprintAggregateReport`, `verifyInterpreterPerformanceClaims`, `syncInterpreterBrowserSize`; adapter's `perfGateTest`, `performanceEligibilityTest`, `interpreterNativePerformance` | **Fully owned.** This is H4's row set; it needs no addition |
| Resources and delivery | the footprint family above plus `syncInterpreterBrowserSize` and [interpreter-delivery-footprint.sh](../scripts/interpreter-delivery-footprint.sh) | **Collector exists, checker rows do not.** Add checker rows to the same collector rather than a parallel one |
| Cold standalone use | none for the checker; `NFR-S1`/`S2`'s startup harness is the pattern but measures the interpreter | **Named missing observation** |
| Prepared use | none for the checker; `NFR-P1`/`P2`'s throughput harness is the pattern | **Named missing observation** |
| After-edit use | none — nothing measures incremental re-analysis | **Named missing observation**, owned by L |
| Combined application | none — requires both products by definition | **Named missing observation** |

So one boundary is fully owned, one has a collector awaiting checker rows, and four are missing observations. Per [proposal §7](PORTABLE-CONSISTENCY-PROPOSAL.md#7-implementation-slices-and-finite-acceptance-matrices) each missing one earns a bounded addition to its existing owner — never a new benchmark framework.

**H4's mechanical closure comparison has its parts already.** The comparison that decides whether a checker-only edit requires an interpreter sweep is the interpreter's resolved production dependency set plus the digest of its built artifacts, and `interpreter/build.gradle` already carries a `sha256File` helper feeding `generateInterpreterReleaseChecksums`, which writes "one stable, sorted SHA-256 manifest for npm, Maven, and API-doc payloads". H4 reuses that manifest as the artifact-digest half and adds the resolved-configuration half beside it; it does not introduce a new digest mechanism.

**H5 stays a proposal, not a number.** No checker budget is asserted here: the four missing observations are exactly the workloads a budget would have to be measured over, and [H5](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) requires each budget to pass IF137's six-point gate — including owner ratification — **before** the implementation it gates is measured. The calibration spike §5 schedules after A therefore has a defined shape: add the four missing observations to their named owners above, run them against a disposable checker spike, and bring the measurements to the owner as candidate budgets. Any number written before that ratification would gate nothing.

### A4 — consistency corpus ownership (decided 2026-09-12)

**Reuse the conformance corpus's case convention; do not build a second corpus.** [`corpus/`](../corpus/README.md) already holds every property A4 asks for: 34 cases across 10 family directories, each carrying a stable `meta.id`, the oracle artifact identity in `meta.kernelVersion`, provenance in `meta.source`, `meta.sections` and `meta.tags`, and — the part that matters most for "one input owner" — a `modelRef` indirection into a shared `corpus/models/` tree rather than an inlined model. That is the reuse A4 requires across common replay, adapter differentials and packed consumers.

**One bounded schema change is needed, and it is the whole gap.** [`corpus/case.schema.json`](../corpus/case.schema.json) lists `['meta', 'modelRef', 'document', 'op']` as top-level **required**, and admits exactly three op kinds — `validateFull`, `validatePart`, `compute` — all of which evaluate a Document. A consistency case has **no Document by contract**. So A4 adds a consistency op kind and makes `document` conditional on the kind rather than unconditionally required, leaving every existing case valid and untouched.

**What a consistency case carries instead of a document:** the accepted / rejected / incomplete expectation, and for a rejected case the measured code. Everything else — id, model reference, kernel version, family, tags — keeps the existing convention, so the same tooling enumerates both kinds.

**Why not the other fixture trees.** They are measured and each has an owner with a different job: `cli/src/test/resources/models` (22 model files) serves the JUnit and eval lanes, `examples/models` (16) serves the published demos under their own CI gate, and `adapter/src/test/resources` (20) serves the Kernel differentials. [CLAUDE.md](../CLAUDE.md) keeps the demo and JUnit trees deliberately decoupled, so A4 does not repoint any of them; the corpus is the only tree already designed as a shared input owner with per-case oracle identity.

**Per-case `kernelVersion` is also M's hook.** Because each case already pins the artifact it was measured against, a second profile does not need a parallel corpus either — it needs cases whose expectation is recorded per pinned version. Recorded here so M reuses this owner rather than inventing one.

### A2 — diagnostic ownership and portable delivery (decided 2026-09-12)

**The delivery decision is forced by the medium each fact lives in, not by preference.**

| Owner | Holds | Medium | Delivery |
|---|---|---|---|
| [operators.json](../rulekit/src/main/resources/catalog/operators.json) | 110 operator entries — 97 carrying gotchas, 20 carrying plural correctives, 110 carrying a valid example — plus top-level `verifiedAgainst` and `emptyOperandDefaults` | JSON resource, 293 KB | **Packaged directly.** It is already data and already proven readable as Node data ([proposal §12](PORTABLE-CONSISTENCY-PROPOSAL.md#12-project-synergies-additional-features-and-focused-cleanups), 2026-09-08), so no projection and no second authoring lane |
| [StructuralKernelFix](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java) | code-level and cross-context correctives over 25 distinct `MVK_*` codes | Java source | **Deterministic generated projection** |
| [RkCode](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RkCode.java) | 52 enum constants; the constant name *is* the code minus the `RK_` prefix, each carrying its `Severity` and `DiagnosticSource` | Java source | **Deterministic generated projection**, applicable subset only |

A Node consumer cannot read Java source at all, which is precisely the condition the proposal names for preferring a projection over direct reuse. So the split is not a judgement call: the catalog ships as-is, the two Java-owned sources are generated into a resource the checker packages.

**Applicability is machine-decidable for `RkCode`, which matters because most of it must not ship as A12 semantics.** Each constant carries a `DiagnosticSource` and a `Severity`, and the `LINT`-sourced `WARNING` constants are our own advisory vocabulary rather than A12 rejections — exactly the authoring-policy class [§5.4](PORTABLE-CONSISTENCY-PROPOSAL.md#54-separate-three-kinds-of-checks) says must never be presented as a Kernel verdict. The projection therefore filters on those two fields rather than on a hand-kept list, so a new constant is classified by its own declaration.

**Reuse the established projection mechanism; do not invent one.** The repository already generates resources from typed sources through Gradle task pairs — root `dumpTemporalVocabulary` and `dumpKitApi`, `:cli:dumpOperatorIndex`, and the interpreter's `generate*Source` family. C2 extends that pattern rather than adding a fourth mechanism.

**Graph and guards.** The generator runs in the owning Java module at build time and emits a resource the checker packages, so there is no production edge from checker to rulekit and the build graph stays acyclic under H1/H2. A stale projection must fail a freshness/parity guard, which is the shape `DocSnippetFreshnessTest` already establishes for locked derived content. The 293 KB catalog is an input to H5's resources-and-delivery row and must be enumerated from the shipped package rather than estimated.

**Recall limits.** 25 and 52 are counts of distinct `MVK_*` tokens and of four-space-indented enum constants in those two files; neither is a verified count of the distinct codes each source ultimately *serves*, and the applicable-subset boundary is settled by the filter above rather than by these totals.

### A1 — erased-bucket disposition (per-site, measured 2026-09-12)

**Result: 55 of 60 in scope, 5 excluded as legacy.** The 52 rows with no legacy claim are in by default under the conformance rule. Of the 8 that claimed legacy, five survive the claim and three do not. Every disposition below cites the firing site read; sites are in `a12-kernel/kernel-tool/kernel-core-parser` unless noted.

| Code | Disposition | Basis, at the firing site |
|---|---|---|
| `MVK_CONDITION_WITH_WILDCARD` | legacy | sole site is `check/bedingung/hdv/CheckFormularBedingungImpl` |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | legacy | both sites are `check/…/hdv/` — `CheckFormularBedingungImpl`, `CheckAnzahlAngegebenOpHModelImpl` |
| `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` | legacy | raised by `CheckEntityUtils.checkRootGroupIsRepeatable`, whose **sole caller** is `check/bedingung/hdv/CheckFormularBedingungImpl:132`; its predicate reads `getMaxLfdNr`/`getMaxUsb` |
| `MVK_DIFFERENT_ROOT_GROUPS` | legacy | raised by `CheckEntityListenUtils.checkAllEntitiesInSameRootGroup`, whose two callers are both `hdv` — `CheckFeldListenOpHModelImpl:86`, `CheckZeilenBedingungImpl:119` |
| `MVK_ERROR_ITERATION_UNCHECKED` | legacy | `kernel-core-calc`'s `NormalisierteBerechnungsFormHelper` raises it at two sites, **both** guarded `if (isHModel && isIterable)`, each carrying the kernel's own comment that "for A12 DVs, the iteration check precondition is added automatically/implicity" |
| **`MVK_INVALID_PARAMS_FOR_OP`** | **in scope** | raised by `CheckEntityListenUtils.checkAllFieldsSameL`, which has three callers and **one is not legacy**: `check/operation/CheckFeldListenOpImpl:85` calls it **ungated** for `SUMME_VON_PRODUKTEN` |
| **`MVK_INVALID_UNSPECIFIED_TYPE`** | **in scope** | `check/entity/CheckEntitySpecImpl.checkUnspecifiedType` fires on any condition entity spec whose field type is `IUnspecifiedType`; no H-model gate anywhere in the path |
| **`MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS`** | **in scope** | `check/datum/CheckDatumKonstruktImpl.checkYearFormat(century, shortYear)` is reached whenever the construct carries a fourth parameter; no `isHModel` gate in `checkJahresParam` → `checkYearFormat` → `handleDatumExtractOpAtYearParam` |

**Three MVK-ledger rows carry a legacy basis that source-reading contradicts** — the three marked in scope above. Their ledger cells answer a different question (whether rulekit's typed API can author the shape) and may still answer it correctly, but the *stated reason* "legacy-only" does not hold at the firing site. Correcting those cells needs one more step this record deliberately does not take: a kernel probe, or a grammar reading establishing whether the four-parameter `DatumKonstrukt` production and `IUnspecifiedType` fields are reachable in an A12 model. Recorded rather than silently edited, because the evidence here is source-read and an accept/reject claim's terminal oracle is the kernel itself.

**Recall limits.** Firing sites were located with `grep -rl <code> a12-kernel --include='*.java'`, excluding `/build/` and the enum declaration files, then each raising method's callers were enumerated with `grep -rn <method>` under `internal/check`. Read: every site listed above and every caller named. Not established: whether a constant indirection raises any of these codes elsewhere, and whether the in-scope three are reachable from an authorable A12 model — which the conformance rule makes irrelevant to their disposition, since representability is never an exclusion basis.

### Checker-owned types (decided 2026-09-11)

With no neutral leaf, the checker owns every type on its own side of the boundary. Named here so the closure and shipped-file guards have a list to check rather than a judgement to make.

| The checker owns | Why it cannot be shared or borrowed |
|---|---|
| Its analysis AST | Retains raw spelling, grouping and source locations that execution lowering discards; the interpreter's AST is shaped for evaluation |
| Its parser | Follows from owning the AST; ported from our original source, reusing its test expectations |
| Its source and limit types | The archived attempt reached `interpreter/ModelPreparationLimits` into the checker's published JVM API, which is the coupling the shipped-file guard exists to catch |
| Its model context | The ported `ModelIndex` query behaviour, over checker-owned preparation |
| Its diagnostic and report types | The public result contract; projects existing diagnostic concepts without importing their Java carriers |
| Its numeric representation | See the design question below — not `Dec` |

### Neutral leaf — NOT EXTRACTED, allowlist stays empty (decided 2026-09-11)

Measured by reading the import lists of the two files a neutral syntax extraction would have to move. [Parser.kt](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/parse/Parser.kt) imports `UnsupportedConstructException`, `dec`, the five AST types (`ArithOp`, `CmpOp`, `Cond`, `Expr`, `ValueListQuantOp`) and `ConditionLanguage`. [Ast.kt](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ast/Ast.kt) imports `Dec`, `dec`, `decOrNull`, `DEC_ZERO` and `Kind`.

**So the leaf is not small, and its largest member is the interpreter's hottest primitive.** A neutral parser requires moving the whole AST, two model enums, an exception type and the decimal primitive — and `Dec` sits on every arithmetic path the interpreter runs, carrying `big.js` as its JS actual. Putting it behind a module boundary to serve the checker is precisely the trade NFR #1 above forbids taking in passing. [Proposal §5.1](PORTABLE-CONSISTENCY-PROPOSAL.md#51-standalone-checker-with-independent-preparation) already prefers a checker-owned port "if a shared implementation forces interpreter changes"; this one does, measurably.

**Consequence for the closure guard:** the permitted-type allowlist is **empty**, which makes the guard maximally simple — no shared leaf module exists, so any production edge from the checker into interpreter code is a failure with no exceptions to encode. Extraction stays available as a later reviewed change carrying its own H4/H5 evidence, never as a side effect of an implementation slice.

**One design question B inherits.** The checker still needs numeric handling for numeric literals and declared field bounds, and it must not reach for `Dec` to get it: `Dec` carries the kernel's rounding and precision model, while a checker comparing declared configuration needs the value's text preserved. B decides that representation on the [transport-value rule](../CLAUDE.md) — a lexical or `BigDecimal`-style handling, never an evaluation type.

### `ConditionBridge` designation — DESIGNATED (decided 2026-09-11)

Both resolvers were read in full. They are the same rule over different fact sources: candidate `base/name` first (declaring-group precedence), then a **unique** model-wide field short name only when the model's `fieldRefByShortNameAllowed` is on, with **ambiguity and absence both falling back to the candidate**. [ParsedModel](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ParsedModel.kt) reads the interpreter's `ModelDef` (`entityPaths`, `fieldsByShortName`) and expresses uniqueness as `singleOrNull()`; [ConditionBridge](../interpreter-bridge/src/main/kotlin/io/github/mbackschat/a12/dm/bridge/ConditionBridge.kt) reads `ModelIndex` (`elementPaths()`, `fieldPaths()` grouped by last segment) and expresses it as `matches.size == 1`. Its own doc comment already states the intent — "mirrors `ParsedModel`'s own resolver rather than inventing a second rule".

**Designate it**, for four reasons. It is an explicit mirror rather than an independent behaviour, so it is the duplication B1 exists to remove. It already reads `ModelIndex`, which is the fact source the checker's context ports, so the shared resolver's natural shape fits it without adaptation. It is JVM-only and sits off the interpreter's evaluation path, so NFR #1 is not engaged. And leaving it undesignated would have B1 — a consolidation slice — raise the implementation count from two to three.

**Residual owners after B1: two, deliberately.** The shared resolver serves the designated Java façades and the checker. `ParsedModel`'s resolver stays in the interpreter, owning resolution against the interpreter's own execution `ModelDef` inside its parse-once lazy AST caches; it stays because the interpreter takes no checker dependency under NFR #1, and because its fact source is the execution model rather than the analysis context. That is a documented duplication, not an oversight, and §12's consolidation claim must be read as holding the count at two.

**Two constraints B1 inherits from this reading.** The shared resolver must not force eager construction of the short-name map — `ConditionBridge` builds it lazily on first fallback use, and an eager shared owner would add that cost to every bridge construction. And B1's contract requires explicit resolved/missing/ambiguous outcomes while both current callers deliberately collapse ambiguity and absence into the candidate, which is the measured kernel-compatible behaviour; so B1 widens the shared **result type** and leaves each existing caller's collapsing projection intact, rather than changing what either caller does.

### A3 record — second profile basis (measured 2026-09-12)

**The second profile is `31.1.0`, and its source is already in the sibling — owner-approved 2026-09-12 because the sibling's remote is the public GitHub repository.** Measured by ref position only (`git log --oneline -1`, `git rev-list --count`), reading commit subjects rather than any `2026-ext0` file content:

| Ref | Commit | Release |
|---|---|---|
| `main` (checked out) | `cb66e51` | `2025.06-ext5` — kernel `30.8.1` |
| `origin/main` | `f10b920` | `2026.06-ext0` — kernel `31.1.0` |
| `origin/release/2025.06` | `cb66e51` | `2025.06-ext5` — kernel `30.8.1` |

Our checkout sits exactly **one commit behind `origin/main`**, fetched 2026-07-17.

**Source presence is not oracle access.** A3 needs a *runnable* kernel to execute consistency checks against, and `31.1.0` is not published to `mavenLocal`; producing it means running the bootstrap kit over `f10b920`, whose work items [KERNEL-31-UPGRADE-PROPOSAL](KERNEL-31-UPGRADE-PROPOSAL.md) Phase 0 enumerates. That is the remaining work for M, and it is bounded rather than open-ended.

**Do not reach for a machine Gradle-cache jar — that route is forbidden and was never qualified.** The abandoned attempt tried exactly this: its own plan at `6b4c683873` records that "the wider machine Gradle cache also contains facade jars for 30.7.1, 31.1.0 and 31.1.1" *(their measurement, quoted, not re-derived here)* while conceding that "a cached facade is not a verified transitive closure" and advertising no second profile. So it yielded nothing usable, and scanning the machine for A12 releases is precisely what [CLAUDE.md](../CLAUDE.md)'s sourcing rule now forbids. The sibling ref is the only admissible origin.

**Disposition.** The second profile is identified and its source approved; M and N remain blocked until a runnable `31.1.0` oracle is published from `f10b920`. Every other slice proceeds; nothing in A–L depends on it.

**M inherits one design question.** Its contract is to check *the same sources* under both profiles while explicitly excluding automatic model migration, and `31.1.0` carries a mandatory document-model format move (`28.4.0` → `29.4.0` per that proposal's analysis, quoted not re-measured) against our 65 committed `28.4.0` models. Those requirements collide across a format boundary, so "the same sources" needs defining first — dual-format fixtures, or an out-of-band migration whose output is pinned as a fixture.

### Decisions to resolve during A

- **Shared syntax:** default to checker-owned source. Extract a neutral leaf only when the enumerated closure and H4/H5 evidence support it; this avoids turning parser extraction into an interpreter redesign.
- **Java binding adoption:** investigate designating `ConditionBridge` alongside the relevant Java façades, because otherwise its resolver remains an additional owner. Record the actual decision and residual behavior after reading its closure; do not promise consolidation of `ParsedModel`.
- **Checker budgets:** specify the candidate recommendation and calibration task in A, then bring its measured record to the owner at the start of F under §5's schedule and the proposal's H5 contract.
- **Second A12 version:** recommend the closest available real version with independently executable evidence and a useful compatibility discriminator. Name the artifact before selecting it; a synthetic profile cannot substitute.

## 4. Verification and review protocol

Each implementation task records: the frozen behavior/family set, source/test owners, selected fixture ids, expected results, enforcement locus, failing control, targeted command and non-goals. New Kernel-semantic claims add the [witness grid](TESTING-SPEC.md#witness-grid-design) before fixtures. Translation reuses the original cases; shared Java/JS agreement is parity, not an independent oracle.

| Obligation | Test owner / required detector |
|---|---|
| Shared behavior and Java compatibility | Retained Java tests and applicable compiled API checks; designated-façade dependency and targeted mutation controls prove both consumers use the shared predicate |
| A12 decision/classification | Independent pinned Kernel calls in `:adapter`, using original canonical inputs; portable module tests remain Kernel-free |
| Truthful report and coverage | Common tests with explicit expected findings/gaps; controls for skipped checks, first-error short circuit, lost attribution, failed prerequisites and unsupported input. Full report projection repeats through F consumers |
| Profile manifest ↔ implementation registry ↔ executed witnesses | A defines the finite mapping; F owns the CI reconciliation and M extends it for the second profile. Controls must fail when a manifest claims an unregistered check or a required witness has no execution evidence. Runtime completeness tests must also fail when a required profile check is skipped, even if the remaining checks pass |
| Source fidelity and reuse | Instrument the checker parser/expansion owner for self-contained, repeated-mount, malformed and all computation-fragment cases; repeat queries and old snapshots must retain their contract |
| Standalone ownership | Compiled/installed closure plus complete shipped-file/provenance enumeration, in both directions, with forbidden-dependency/file positive controls; external consumers run without the other core or Kernel |
| Engine identity | F1's actual invocation and substitution controls; derive receipts from resolved published implementation identity, preserving requested engine and failure stage. Verify JVM/native artifacts, not backend presence alone |
| Application correctness | Proposal §12's expected results through typed packed APIs; fresh-check equivalence for H/J/L/M, explicit reuse for L, browser partial-text diagnostics for I |

Review tasks in §2 follow [CLAUDE.md's cold-review rules](../CLAUDE.md): one terminal review per bounded landing and at most one earlier foundational review. Use a fresh, same-model, read-only reviewer with the frozen diff, matrix and actual verification evidence; no implementation delegation. Where code follows an already-settled pattern, do not add another foundational review. Settle in-scope findings against their oracle; route adjacent findings to their owning task. A change to the acceptance denominator invokes repartitioning before more implementation.

Run affected retained tests and mandatory compatibility/doc checks under the [run discipline](AGENT-RUN-DISCIPLINE.md). Use [test-evidence.sh](../scripts/test-evidence.sh) to distinguish executed results from cached/stale output. Do not repeat the full estate per commit. Native rebuilding occurs in F1/final artifact qualification under the required recapture → build → gate sequence; it is not part of each checker development loop.

## 5. Performance and completion gates

A maps all six [proposal performance boundaries](PORTABLE-CONSISTENCY-PROPOSAL.md#performance) to existing collectors: cold standalone use, prepared use, after-edit use, resources/delivery, interpreter-only use and combined use. Start with the owners described in [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md), the [interpreter build/consumer tasks](../interpreter/build.gradle) and the adapter performance lanes. Reading these owners is required before assigning exact commands; a missing observation gets a bounded addition to its owner, not another harness.

For H4, A records the comparison inputs and collector commands. Apply the proposal's [hard acceptance conditions, H4](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) at each affected landing; N verifies the conversion to standing enforcement specified there. That section owns the numerical bar, evidence requirements, sweep trigger and re-basing rule.

For H5, A specifies a bounded disposable checker spike over its fixed workloads, using the existing collectors. Run that calibration task after A and before F; record the spike revision, exercised semantics, semantic preflight, sampling and reproducibility evidence. The spike supplies calibration evidence, not a shipped implementation or a new benchmark framework. At the start of F, submit the measured budget recommendations for owner ratification before measuring the qualifying implementation. Keep calibration and qualification records separate; do not derive budgets from the finished candidate's measurements. Apply the proposal's [H5 and IF137 requirements](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) to each number; the proposal owns the release bars and blocking conditions. F qualifies its boundaries, L qualifies after-edit work against the previously ratified budgets, and N assembles their evidence with the combined-use observations.

At N, execute the proposal's [final availability acceptance matrix](PORTABLE-CONSISTENCY-PROPOSAL.md#required-consolidation-and-application-slices) against the assembled package and per-slice evidence. Schedule the following closing work alongside that matrix:

- Public API/architecture/CLI documentation describes the implemented contracts with executed examples. Update the portable API guide as capabilities land; synchronize the F1 wording identified in the proposal. Archive the proposal and this plan together after graduation.
- Remove any migration-only guardrails actually introduced, after confirming the standing H4 anchor, H5 bars and durable F2 differential remain enforced. The abandoned harness stays retired; cleanup must not remove permanent acceptance.

No code implementation, semantic remeasurement, benchmark qualification or release is part of creating this plan. The next executable task is A's port/consumer boundary and inventory, followed by its terminal review.
