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
| 13 | N combined availability and qualification | Every earlier slice passes its frozen matrix; all G–J/L exports and the browser integration work from shipped packages. Complete §5 below | Terminal whole-product contract review before declaring completion |

Packed delivery belongs to F. A2's earlier resource check is a bounded packed-resource experiment using existing tooling, not an early implementation of the full package estate. B0's JVM/common tests and F2's adapter lane do not depend on F. F owns their packed Node/browser corpus replay, using the same inputs rather than inventing another matrix.

## 3. Task records — A's finite inventory contract, then each landed stage

**Recommendation:** finish the following records before production translation. They settle what will be shared, what can be claimed and what will measure success. This is an inventory of selected responsibilities and the profile denominator, not a new general-purpose verification framework.

| Record | Required contents | Exit oracle |
|---|---|---|
| Port and consumer boundary | Enumerate selected source responsibilities, imports/callers, existing test owners, designated Java consumers and residual owners. Decide explicitly whether `ConditionBridge` is designated. Record proposed checker-owned types and every optional neutral-leaf type | Read every included source/caller; report search commands, matches found/read and anchored exclusions. A leaf with no established benefit stays absent. No reliance on orphaned `build/` output or archived modules as current source |
| Constraint/profile denominator | Map the laws and grammar productions the Kernel can raise on an A12 model’s rules and computations to current enforcement, Kernel firing sources, raw-input carriers, prerequisites, authoring policies, witnesses and exclusions. Include conversion/non-MVK and applicable generation-path restrictions | Every entry has a disposition; unresolved entries prevent a complete coverage claim. A code count is not a law count |
| A1 erased-bucket disposition | Re-derive the proposal's dated enumeration and disposition every matching row. Rows are in scope by default; an exclusion cites a Kernel path that is dead for A12 input | Reachability/acceptance claims have the appropriate Kernel evidence. Builder inability, renderer avoidance and an absent rulekit authoring surface are each barred as an exclusion basis |
| A2 diagnostic ownership | Map catalog guidance, `StructuralKernelFix`, applicable `RkCode` knowledge and consumer projections. Prefer direct resource reuse; use deterministic projection when delivery/size or Java-owned guidance requires it | Bounded packed Node resource check plus source/projection freshness and contextual guidance controls; acyclic graph, no new hand-maintained dictionary |
| A3 single pinned profile | Record the one sibling-defined A12 version (`2025.06-ext5` / kernel `30.8.1`), its artifact identity, oracle access and finite witness matrix | Identity and oracle access verified against the sibling. A second A12 version is out of scope while M is deferred |
| A4 corpus ownership | Identify retained fixtures and typed authors, canonical sources, stable case ids, profile applicability and explicit accepted/rejected/incomplete expectations | Reuse one input owner across common replay, adapter differentials and packed consumers. Add only minimal raw-input mutations needed to reach shapes builders cannot author |
| H4 comparison matrix | Fixed restart revision, row/workload identities, hosts, collectors, matching runtime/launch rules, and the resolved-production-set plus built-artifact-digest comparison | Every required row has an existing measurement owner or a named missing observation. The mechanical closure/artifact comparison decides whether an affected sweep is necessary |
| H5 budget proposal | Workload identities, candidate budget recommendations and a bounded calibration-spike specification for cold, prepared and after-edit checking; include resources/release and combined use | A fixes the scenarios, collectors and calibration task described in §5. Sampling, reproducibility evidence and owner ratification are later prerequisites of F qualification, not claims discharged by this inventory |

Use the [proposal research sources](PORTABLE-CONSISTENCY-PROPOSAL.md#11-sources) as the starting map. Existing reference owners include [UndeclaredReferenceTest](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/UndeclaredReferenceTest.kt) and [ShortNameTierLawsTest](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/ShortNameTierLawsTest.java). These are reuse candidates with the exact limits recorded in proposal §3.4; neither is asserted to cover the new complete reference/report contract alone.

A produces the slice-specific acceptance records needed to start B, not empty tables to be filled during implementation. Preserve these in this plan while they are planning decisions; place the machine-readable profile/check registry with its consuming implementation. Keep measured results in a dated evidence document and link them, following [documentation discipline](DOC-DISCIPLINE.md). Do not create a second status tracker.

### Boundary record — ported responsibilities (measured 2026-09-12, corrected after review)

Enumerated with `git grep -l <symbol> -- '*/src/main/*' '*/src/commonMain/*'` and `git grep -l <symbol> -- '*Test.java' '*Test.kt'` at `15af15abd`, excluding `/build/` and the native reachability-metadata JSON. **Caller counts exclude each symbol's own declaring file and KDoc-only mentions** — the first pass counted both and overstated four of five rows.

| Responsibility | Source | Callers | Test files | Modules | Disposition |
|---|---|---|---|---|---|
| Model queries and type facts | [ModelIndex](../core/src/main/java/io/github/mbackschat/a12/dm/core/ModelIndex.java), 401 lines | 18 | 50 | 5 | Port the query behavior into the portable context |
| Expansion | [ModelReader](../core/src/main/java/io/github/mbackschat/a12/dm/core/ModelReader.java), 789 lines | 7 | 16 | 5 | Reuse the expansion behavior inside checker-owned preparation |
| Reference binding | [BoundModel](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/BoundModel.java), 679 lines | **80** | **552** | 8 | **Do not port the class.** Extract the binding responsibility beneath it; the façade and its API stay |
| Bare-reference resolution | [ParsedModel](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ParsedModel.kt), 181 lines | 2 | 0 by name | 2 | Stays in the interpreter under NFR #1 — one of four owners |
| Undeclared-reference query | `DmInterpreter.undeclaredReferences()` | 2 | 1 | 2 | Reuse the lookup logic in the checker; never call `DmInterpreter` |

**`ModelIndex` is the scope decision made concrete.** It declares **31** public signatures — 27 instance methods plus 4 statics. Eleven instance methods are pure type facts (`kindOf`, `numberScale`, `numberMin`/`numberMax`, `stringMinLength`/`stringMaxLength`, `stringPattern`, `temporalFormat`, `stringNoValueValidation`, `enumValues`, `enumCategories`) and six are structure facts (`isRepeating`, `maxRepetitions`, `isField`, `isGroup`, `nameOf`, `usageTypeOfGroup`) — exactly the "support in full, do not validate" surface §4 names. **One static must not be overlooked: `resolvePath(elementPath, reference)` is reference resolution, the subject of the frozen first family**, and an instance-only count of 27 hides it.

**`BoundModel` settles "responsibilities, not classes" empirically.** 80 callers across eight modules and 552 test files make porting the class impossible and extracting beneath it the only move — [proposal §5.2](PORTABLE-CONSISTENCY-PROPOSAL.md#52-port-responsibilities-not-entire-public-classes)'s prescription, now with a measured basis. Its compiled Java API guards that the extraction preserved behavior.

**`ParsedModel` has no test file naming it**, so its coverage is indirect. A recall limit of the by-name search, not evidence it is untested; B1 must identify its actual coverage owner.

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

**What a consistency case carries instead of a document:** the accepted / rejected / incomplete expectation, and for a rejected case the measured code. Everything else — `meta.id`, `modelRef`, `meta.kernelVersion`, `meta.sections`, `meta.tags` — keeps the existing convention, so the same tooling enumerates both kinds. Note the family is the **directory name only**: no case carries a `meta.family` and the schema does not model one, the normative taxonomy being `meta.sections`.

**Why not the other fixture trees — enumerated properly this time.** Counted as `.dm.json` models per tree (the first pass mixed three different quantities: two `models/`-scoped JSON counts and one whole-`test/resources` JSON count):

| Tree | Models | Owner's job |
|---|---|---|
| `cli/src/test/resources/models` | 18 | JUnit + eval lanes |
| `examples/models` | 14 | published demos, own CI gate |
| `adapter/src/test/resources/models` | 14 | Kernel differentials |
| `src/test/resources/models` (root module) | 7 | the `validate/laws` accept/reject tier — where the checker's own law locks live |
| `interpreter/src/commonTest/resources/**` | 47 | workspace-preparation, performance, footprint, retained-result KMP fixtures |

The first pass named only the first three and then asserted the corpus was "the only tree designed as a shared input owner" — a negative existential over a denominator two trees short, and the root laws tier is precisely the one a consistency checker's accept/reject cases are adjacent to. **The corpus is still the right owner** — it alone carries per-case oracle identity and a `modelRef` indirection — but that is now a comparison against five trees rather than an unmeasured claim of uniqueness. [CLAUDE.md](../CLAUDE.md) keeps the demo and JUnit trees deliberately decoupled, so A4 repoints none of them.

**Per-case `kernelVersion` stays useful even with M deferred.** It records which artifact each expectation was measured against, so a corpus case cannot silently outlive its oracle — and if cross-version comparison is ever revived, it needs cases keyed this way rather than a parallel corpus.

### A2 — diagnostic ownership and portable delivery (decided 2026-09-12)

**The delivery decision is forced by the medium each fact lives in, not by preference.**

| Owner | Holds | Medium | Delivery |
|---|---|---|---|
| [operators.json](../rulekit/src/main/resources/catalog/operators.json) | 110 operator entries — 97 carrying gotchas, 20 carrying plural correctives, 110 carrying a valid example — plus top-level `verifiedAgainst` and `emptyOperandDefaults` | JSON resource, 293 KB | **Packaged directly.** It is already data and already proven readable as Node data ([proposal §12](PORTABLE-CONSISTENCY-PROPOSAL.md#12-project-synergies-additional-features-and-focused-cleanups), 2026-09-08), so no projection and no second authoring lane |
| [StructuralKernelFix](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java) | code-level and cross-context correctives; **24** distinct `MVK_*` tokens appear in the file but `forCode` serves **21** keys, and `forMessage` serves a further class carrying no MVK code at all — so neither number is the served denominator | Java source | **Deterministic generated projection** |
| [RkCode](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RkCode.java) | 52 enum constants; the constant name *is* the code minus the `RK_` prefix, each carrying its `Severity` and `DiagnosticSource` | Java source | **Deterministic generated projection**, applicable subset only |

A Node consumer cannot read Java source at all, which is precisely the condition the proposal names for preferring a projection over direct reuse. So the split is not a judgement call: the catalog ships as-is, the two Java-owned sources are generated into a resource the checker packages.

**`RkCode` applicability is NOT machine-decidable from `Severity` + `DiagnosticSource` — corrected after review.** The first pass proposed filtering out the `LINT`-sourced `WARNING` constants as advisory. Measured across all 52: `ERROR/PRECHECK` 34, `WARNING/LINT` 9, `INFO/ENVIRONMENT` 3, `WARNING/PRECHECK` 2, `ERROR/ENVIRONMENT` 1, `ERROR/INTERNAL` 1, `ERROR/KERNEL` 1, `INFO/LINT` 1. That filter removes 9 of 52 — not "most" — and fails in **both** directions: it keeps `GOTCHA` (`INFO/LINT`, literally the catalog-gotcha advisory), both `PRECHECK/WARNING` constants that describe themselves as "an advisory, not a refusal", and the environment/internal constants; while discarding `COMPUTATION_FORBIDDEN_CONSTRUCT`, whose own text says the kernel rejects it with `MVK_ERROR_INVALID_LANGUAGE_FEATURE`.

So the applicable subset needs a **per-constant disposition** recorded with the projection, not a field predicate. This is the same over-and-under-match shape the A1 method finding refuted for package paths, and it went undetected because no constant outside `WARNING/LINT` was checked — a filter proposed without a positive control.

**Reuse the established projection mechanism; do not invent one.** The repository already generates resources from typed sources through Gradle task pairs — root `dumpTemporalVocabulary` and `dumpKitApi`, `:cli:dumpOperatorIndex`, and the interpreter's `generate*Source` family. C2 extends that pattern rather than adding a fourth mechanism.

**Graph and guards.** The generator runs in the owning Java module at build time and emits a resource the checker packages, so there is no production edge from checker to rulekit and the build graph stays acyclic under H1/H2. A stale projection must fail a freshness/parity guard, which is the shape `DocSnippetFreshnessTest` already establishes for locked derived content. The 293 KB catalog is an input to H5's resources-and-delivery row and must be enumerated from the shipped package rather than estimated.

**Recall limits.** 25 and 52 are counts of distinct `MVK_*` tokens and of four-space-indented enum constants in those two files; neither is a verified count of the distinct codes each source ultimately *serves*, and the applicable-subset boundary is settled by the filter above rather than by these totals.

### Constraint/profile denominator (measured 2026-09-12, disposition partial)

**The denominator is bounded and enumerated at source level.** Counted by section from [MVK-LEDGER](MVK-LEDGER.md) and by disposition column from [GRAMMAR-LEDGER](GRAMMAR-LEDGER.md):

| Source | Entries | In this profile? |
|---|---|---|
| `ParserErrorTypesEnum` — condition and expression parsing | 176 | **yes** — rules and computations |
| `CalculationErrorType` — computations | 18 | **yes** |
| `FehlertextErrorType` — error text | 1 | **yes** — the scope validates relevant messages |
| `MM_Common` — metamodel structure | 105 | **no** — structure is context, not subject matter |
| Grammar productions: 46 `rendered`, 22 `plumbing`, 16 `legacy`, 3 `entry`, 2 `directive` | 89 **rows**, covering the ledger's declared **119 productions** — 28 rows name two productions and one names three | the 16 `legacy` rows are the grammar-side counterpart of A1's exclusion, and GRAMMAR-LEDGER owns that bucket's definition. Rows and productions are different units; the other rows of this table count codes |
| `NoMetaModelCheckErrorType` — codegen | 26 *(reported by a12-kernel-lean and re-derived in [MVK-LEDGER](MVK-LEDGER.md); unclassified there, not dispositioned)* | **applicable subset yes** — `CodeGenerationRule.checkModel` calls `generateValidationCode`, so these surface when checking a stored model |
| `DynEnumErrorType` — dynamic-enum derivation | 12 *(proposal's figure, not re-derived)* | **candidate exclusion** — a separate derivation service, dispositioned in its own right rather than by absence from the four-source ledger |

The rule/computation MVK denominator is therefore **195 rows** (176 + 18 + 1), and 195 + 105 reconciles with the ledger's documented 300.

**Disposition is partial, and that is the finding rather than an omission.** A1 dispositioned the 60-row erased bucket per firing site. The remaining **135** in-scope rows carry ledger dispositions written to answer a different question — whether rulekit's typed API can author the shape — so each needs the same per-site treatment against "can this fire for an A12 model's rules or computations". Until they do, no complete coverage claim is available, which is exactly what this record's exit oracle requires.

**A code count is not a law count, and A1 measured why.** `MVK_INVALID_PARAMS_FOR_OP` is raised from three call sites, one legacy and one not; `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` is raised from a general utility whose only caller is legacy. So the 195 is a bound on *entries to disposition*, never a count of laws or of checks to implement, and the frozen implementation families in C/D/E take their size from measured law families rather than from this number.

### A1 — erased-bucket disposition (per-site, corrected after review 2026-09-12)

**Result: 55 in scope, 5 excluded as legacy.** The 52 rows with no legacy claim are in by default. Of the 8 claiming legacy, five survive — three on a cited gate, two measured on every carrier — and three do not. The count is stated as memberships rather than a headline for the reason the inversions below record: a re-derived total cannot catch errors that cancel, and this membership set has now moved twice on the same code.

| Code | Disposition | Basis, at the firing site |
|---|---|---|
| `MVK_DIFFERENT_ROOT_GROUPS` | legacy | raised by `CheckEntityListenUtils.checkAllEntitiesInSameRootGroup`; both callers are `hdv` **and** grammar-predicate-gated |
| `MVK_ERROR_ITERATION_UNCHECKED` | legacy | two raisers in `kernel-core-calc`'s `NormalisierteBerechnungsFormHelper`, both guarded `if (isHModel && isIterable)`, each carrying the kernel's comment that "for A12 DVs, the iteration check precondition is added automatically/implicity" |
| `MVK_INVALID_UNSPECIFIED_TYPE` | **legacy** | `CheckEntitySpecImpl.checkUnspecifiedType` is `private` with a **single** caller, `checkCommon:103`, whose immediately preceding line is `if (fullParser.isHModel()) {` |
| `MVK_INVALID_PARAMS_FOR_OP` | in scope | raised by `CheckEntityListenUtils.checkAllFieldsSameL`, called **ungated** from `check/operation/CheckFeldListenOpImpl:85` for `SUMME_VON_PRODUKTEN`, with un-predicated dispatch. A **second** raiser exists — `CheckEntityListenUtilsHModel.checkAllFieldsZIteration:171`, sole caller `bedingung/hdv/CheckZeilenBedingungImpl:140` — so the code has one legacy and one live raiser |
| `MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS` | in scope | no `isHModel` gate on the `checkJahresParam` → `checkYearFormat` → `handleDatumExtractOpAtYearParam` path. Fires when the construct carries a fourth parameter **and** the century or short-year param is itself a `datumExtractOp`; the grammar's other two `datumParam` arms route elsewhere |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | **in scope** (measured, both carriers) | both raisers open with `if (fullParser.isA12Model()) throw`, and an A12 model reaches both: a rule condition and a computation operation carrying the construct each report the code, once the model's `conditionLanguage` is the one whose lexer has the keyword |
| `MVK_CONDITION_WITH_WILDCARD` | **legacy** (measured) | `VordruckAngegeben(Order/*)` in an A12 model reaches the form-condition checker and draws `MVK_INVALID_ROOT_GROUP_CONSTR` from the name action, which pre-empts this code |
| `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` | **legacy** (measured) | same pre-emption, for `MindestensEinVordruckAngegeben(Order)` — the outer `formularBedingung` action that raises either code runs after the name action that refuses the construct outright |

**Two dispositions were inverted in the first pass, from the same two causes.** `MVK_INVALID_UNSPECIFIED_TYPE` was called in scope from reading the raising method without tracing its only caller. `MVK_INVALID_ROOT_GROUP_CONSTR` was called legacy on the **package path** — the criterion this record's own method finding had already shown to over- and under-match. The totals stayed at 55/5 only because the two errors cancelled, which is why re-deriving a headline cannot catch this class: **the memberships, not the count, are the claim.**

**The two unresolved rows are now measured, and the first measurement was wrong in a way worth keeping (2026-09-12).** Both are legacy, but not for the reason first recorded. The initial rows put the form constructs through `RuleValidator.check` and got `MVK_UNEXPECTED_TOKEN`, which read as a parser-level exclusion. Every row shared one unnamed condition: `DocumentModelDraft` stamps `conditionLanguage: en_US`, and these constructs exist only in the German terminal bundle, so the spellings were not keywords at all. Re-measured with the language as the sole variable, an A12 model with `de_DE` conditions **does** reach the checker, and the codes are pre-empted there by `MVK_INVALID_ROOT_GROUP_CONSTR` — which is the inner-action pre-emption the first pass had recorded as *refuted*. The mechanism was right; the experiment could not see it.

**So `MVK_INVALID_ROOT_GROUP_CONSTR` returns to in scope, and the correction that moved it out is itself corrected.** The row's original basis — its checker's first statement is the `isA12Model()` throw — was sound. Both carriers are measured under `de_DE`: `AnzahlAngegebenerVordrucke(Order) > 0` in a rule condition, and `{ AnzahlAngegebenerVordrucke(Order) }` as a computation's operation, which parses through the grammar's `operation` entry rule instead of `bedingung`. The second carrier still had to be run rather than inferred, because inside `einfacheOperation` the H-model siblings `feldListenOpHModel`/`feldOpHModel` ARE predicate-gated on `isHModel()` while `anzahlAngegebenOpHModel` deliberately is not. Its ledger cell is corrected in place, for the second time in one day.

**The transferable failure is the CONSTANT, not the conclusion.** Every row was individually clean, each with its own control, and the harness control (`FieldFilled(NoSuchField)` → `MVK_INVALID_ENTITY`) genuinely proved that ordinary conditions reach the semantic checks — it just could not see a variable that no row varied. The cheap falsifier existed the whole time and cost one line: build the same model with the other condition language. The lock now carries it, plus the control that keeps the German arm honest — a `zeilenBedingung` (`ZeilenLueckenlos(Code)`) is still refused at the parser in a German model, because the grammar predicate-gates that production on `isHModel()` while leaving `formularBedingung` un-predicated. So the parser layer is real and separable; it simply was not what refused these three. *(Locked: `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`, six arms; the vocabulary fact itself is [KF283](KERNEL-FINDINGS.md#kf283).)*

**An independent second source agrees on the legacy SET and expands it by none.** The kernel team's own `documentation/ba/parser_errors_en.adoc` carries a triage table of 103 undocumented codes bucketed `only for H-models` (45), `not relevant` (34), `ExtMP-exclusive` (16), `unclear` (6) and awaiting translation (2). Of the ledger's 60 erased rows, exactly **5** sit in the H-model bucket, and they are 5 of the 8 whose ledger note claims legacy — so the kernel team's view corroborates the ledger's legacy claims without adding any. Two cautions keep this as corroboration rather than evidence: the table answers *why is there no description*, not *can an A12 model reach this*; and it disagrees with measurement in two places — it buckets `MVK_INVALID_ROOT_GROUP_CONSTR` as `ExtMP-exclusive` rather than H-model-only, and it buckets `MVK_INVALID_PARAMS_FOR_OP` as H-model-only although that code has an ungated raiser at `CheckFeldListenOpImpl:85`.

**`MVK_INVALID_PARAMS_FOR_OP`'s open question is now specific rather than general (source, 2026-09-12).** Its live raiser is `CheckEntityListenUtils.checkAllFieldsSameL`, which compares `EntityRef.getLfdNr()` across a `SumOfProducts`' operands — and `getLfdNr()` is `indices.get(0)`, the **outermost level's index**, not an H-model-only quantity. The setter enumeration points the other way and is not sufficient on its own: `grep -rn "setLfdNr(" kernel-core-parser/src/main/java` (delombok and `build/` excluded) returns 9 call sites, all 9 read, every one under `hdv`/`…HModel`, and the setter carries the kernel's own comment "used only in H models and Vordruck-Sprachkonstrukte" — but `indices[0]` is *also* written by the general index path (`EntityRef:202`/`:206`) and by `CheckSemanticIndexImpl:110`, which A12 references do reach. **The probe that would settle it:** whether two `SumOfProducts` operands can legally differ at `indices[0]` after passing the checks that run before it in `CheckFeldListenOpImpl` — `checkAllEntitiesInSameGroup` and `checkAllEntitiesHaveWildcardOnLowestLevel`. Until that is authored and run, neither the ledger's legacy claim nor this record's in-scope claim is measured.

**Three MVK-ledger rows carry a legacy basis that measurement contradicts** — the three marked in scope, one of which is `MVK_INVALID_ROOT_GROUP_CONSTR`, whose ledger row is corrected in place (above). `MVK_INVALID_UNSPECIFIED_TYPE` is **not** among them: there the ledger was right and this record was wrong, and the first pass's proposed correction would have introduced an error. Correcting the other three still needs the kernel oracle, since accept/reject is terminal only there.

**Recall limits.** Sites located with `grep -rl <code> a12-kernel --include='*.java'` excluding `/build/` and the enum declaration files, then callers enumerated with `grep -rn`. Read: every site and caller named. The exclusion "the enum declaration files" was stated as three; the tree holds **seven** files declaring `MVK_*` constants, though for these eight codes only one is ever matched. Constant indirection is not ruled out.

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

**So the leaf is not small: a neutral parser requires moving the whole AST, two model enums, an exception type and the decimal primitive.** `Dec` carries `big.js` as its JS actual, and its reach through the evaluator is wide — `ExprEval` operates on `Dec` directly and even the polarity-arithmetic path carries many `Dec` references. *That reach is an enumeration over the evaluator, not something the two import lists establish; the import lists establish only that extraction forces interpreter changes* — which is by itself decisive under [proposal §5.1](PORTABLE-CONSISTENCY-PROPOSAL.md#51-standalone-checker-with-independent-preparation)'s "prefer a checker-owned port if a shared implementation forces interpreter changes" and under NFR #1.

**Consequence for the closure guard:** the permitted-type allowlist is **empty**, so any production edge from the checker into interpreter code is a failure with no exceptions to encode. **Enforcement locus:** that guard does not exist and cannot until a checker module does — it lands with **B**'s Kernel-free module/test dependency setup (stage 2) as the mirror of the existing `verifyInterpreterPurity` lint, which guards only the interpreter→kernel direction. Until then this is declared intent, not an enforced invariant. Extraction stays available as a later reviewed change carrying its own H4/H5 evidence.

**One design question B inherits.** The checker still needs numeric handling for numeric literals and declared field bounds, and it must not reach for `Dec` to get it: `Dec` carries the kernel's rounding and precision model, while a checker comparing declared configuration needs the value's text preserved. B decides that representation on the [transport-value rule](../CLAUDE.md) — a lexical or `BigDecimal`-style handling, never an evaluation type.

### Bare-reference resolution has FOUR owners; `ConditionBridge` DESIGNATED (corrected after review 2026-09-12)

`git grep -ln 'fieldsByShortName\|shortNamesAllowed' -- '*/src/main/*' '*/src/commonMain/*'` — all four read:

| Implementation | Module | Uniqueness test |
|---|---|---|
| [ParsedModel](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ParsedModel.kt) | `:interpreter` | `singleOrNull()` |
| [ConditionBridge](../interpreter-bridge/src/main/kotlin/io/github/mbackschat/a12/dm/bridge/ConditionBridge.kt) | `:interpreter-bridge` (JVM) | `matches.size == 1` |
| [ReferenceFragmentRemapper](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/workspace/ReferenceFragmentRemapper.kt) `appendShortName` | `:interpreter` | `matches.size != 1` |
| [ConditionReferenceRemapper](../core/src/main/java/io/github/mbackschat/a12/dm/core/ConditionReferenceRemapper.java) | `:core` | `matches.size() != 1` |

All four implement the same rule shape: candidate `base/name` first (declaring-group precedence), then a **unique** model-wide field short name only when the flag is on, with ambiguity and absence both falling back to the candidate.

**The fourth is the one that matters, and the first pass missed it entirely.** `ConditionReferenceRemapper` is invoked from `ModelReader` at lines 501, 543 and 589 — inside the **Expansion** responsibility the boundary table designates for reuse. It is therefore the owner B1 would most want to designate, and an arithmetic that counted two owners could not have seen it.

**Designate `ConditionBridge` and `ConditionReferenceRemapper`.** Both sit outside the interpreter, and the second already lives in a designated responsibility. **Residual owners after B1 are three, not two**: the shared resolver, plus `ParsedModel` and `ReferenceFragmentRemapper`, which stay inside the interpreter under NFR #1 because their fact source is the execution model.

**Two first-pass claims do not survive, and B1 must not inherit them.**

- **The laziness boundary does not exist.** `ConditionBridge.resolveBareReference` materializes the full `groupBy` over every field path on the **first bare reference**, before testing `shortNameRefsAllowed` — not on first fallback use. "The shared resolver must not force eager construction" was derived from a mechanism that is not there; any cost claim here needs measuring rather than reading.
- **"The same rule over different fact sources" is an equivalence claim and stays a HYPOTHESIS.** Two divergences are visible in source: short-name keys use `path.trimEnd('/').substringAfterLast('/')` in the interpreter's `Model.kt` but omit the `trimEnd` in `ConditionBridge` and `ReferenceFragmentRemapper`; and the candidate sets differ — `ModelDef.entityPaths` is built from fields, repeatables, group paths **plus rule anchor groups and computation groups/targets** with ancestors, while `ModelIndex.elementPaths()` is `reader.paths()`. B1 owes a differential over a model that separates those sets before one implementation may replace another.

### Stage-1 freezes (2026-09-12)

**First implementation family: reference binding (B1).** Its cases are [proposal §3.4](PORTABLE-CONSISTENCY-PROPOSAL.md#34-reuse-existing-static-reference-checking-distinguish-it-from-execution-preparation)'s four rows plus declaring-group precedence, relative and absolute references, and applicable indexed/category references. Fixture authoring follows [ShortNameTierLawsTest](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/ShortNameTierLawsTest.java), which builds through `DocumentModelDraft` and carries **raw bare-name condition strings on purpose**, because the claim is about a path form the typed renderer never emits — exactly the typed-valid-model-plus-minimal-raw-mutation discipline A4 requires.

**One fixture gap blocks B0's positive control.** §3.4's four-row model is **not committed**: `git ls-files` finds no `StaticReferenceReviewProbe`, and the proposal already records that probe as session evidence rather than a regression lock. So B0 must land that model as an A4 corpus consistency case before it can assert row 2 — the matrix's only Kernel *acceptance* row, and the one the [testing spec](TESTING-SPEC.md)'s all-refusals argument makes mandatory.

**Performance workload identities: the existing four.** `interpreter/build.gradle` defines `performanceModelFixtures` as `B1`, `B2`, `MANY_RULES_48` and `MANY_RULES_192`, authored once through modelkit/`EditSession`/rulekit in `:adapter`, committed, then inlined into KMP test source by `generatePerformanceFixtureSource`. Those four names are the frozen workload identities, and the checker's rows attach to them rather than to a new set.

**Why not select workloads from the committed corpus.** Measured: `corpus/models` spans 6.4–24.0 KB — a 3.7× range, with 17 of 23 files clustered at 23.6–24.0 KB and six below 11 KB. So it does spread, contrary to the first pass's claim of a single cluster; what it lacks is a *rule-count* ladder, which is the axis `MANY_RULES_48` → `MANY_RULES_192` supplies. Declaration-count spread across the corpus was never measured — only file size was — so the reason to prefer the existing workloads is that they vary the axis we need, not that the corpus is uniform.

### A3 record — single pinned profile (decided 2026-09-12)

**The project keeps one A12 version, and A3's two-version basis is withdrawn with M.** The pinned profile is whatever the sibling `a12-kernel` checkout carries — today `2025.06-ext5` / kernel `30.8.1` — recorded with its artifact identity and oracle access. No second version is required, sought, or admissible while M is deferred; the reasoning is in [proposal §4](PORTABLE-CONSISTENCY-PROPOSAL.md#4-required-functionality-and-scope-boundaries)'s deferral record.

**What was measured before the deferral, kept because it is the evidence a revival would start from.** The sibling's `origin/main` is `f10b920` "Release 2026.06-ext0 - 31.1.0" and its local `main` is `cb66e51`, one commit apart, fetched 2026-07-17 — so a second version's *source* was never the obstacle. A runnable oracle was: `31.1.0` is not in `mavenLocal`, and publishing it means bootstrap Phase 0 over `f10b920`, carrying an unresolved a12-base version identity (`30.0.1` upstream against the kernel's pinned `30.0.0`). Those costs, plus the `28.4.0` → `29.4.0` format move, are what made the deferral the better trade.

**One route stays forbidden regardless.** The abandoned attempt scanned the wider machine Gradle cache and found facade jars for "30.7.1, 31.1.0 and 31.1.1" *(its measurement, quoted)* while conceding "a cached facade is not a verified transitive closure" and advertising no second profile. Machine-scanning for A12 releases is ruled out by [CLAUDE.md](../CLAUDE.md)'s sourcing rule; the sibling is the only admissible origin, and it defines the single version.

### B2 record — preparation reports what the Kernel's reader raises (decided 2026-09-12, corrected after review)

**Landed:** admission (well-formed source, retained tree, authored numeric scale, source identity, A12's comment parity), the declared element set at its canonical path, `content.modelConfig.fieldRefByShortNameAllowed`, the §5.4 category separation, and B2's five acceptance cases. Kernel-free module and test dependencies are guarded by `verifyConsistencyPurity`, which now runs on the CI lane — a Kotlin Multiplatform module registers no `test` task, so the workflow's unqualified `test` reached neither it nor any checker test until `:consistency:check` was named explicitly.

**The canonical path is the join of `name` segments, NOT the element's `id` — corrected before landing.** The first implementation read `id` as the path, which is the claim [KERNEL-INTEGRATION-NOTES](KERNEL-INTEGRATION-NOTES.md#dm-json-shape-for-hand-authoring-fixtures) already carries a dated correction against: `id` is an opaque identifier in a namespace disjoint from the path, and keying by it once resolved ~0% of a foreign model's references. The Kernel derives the path by appending `getName()` (`DocumentModelVisitorWithFullNames:56`); `ElementDeserializer` merely *stores* `id`, so it could never have settled the question it was cited for.

**The mechanism of that error is the one this project keeps a table about, so it is recorded rather than just fixed.** A fixture sweep found `id == path` on 934 elements of 52 models — and every model in that set was one we authored, where the two are equal by habit. The counterexample class was committed in this repository the whole time (`src/test/resources/models/idmismatch.dm.json` with hash ids, plus ten `workspace-preparation` opaque-id fixtures), and the selection excluded it. One constant across every row, no row on the other side of the axis, and the agreement of 934 of them established nothing. The prior-art search missed it too, for the adjacent reason: it grepped the terms the new work used (`ElementDeserializer`, `envelope`, `discriminator`) and the record states the fact in different words.

**A declaration is retained whole, not projected.** `DeclaredElement.declaration` is the entire authored node — envelope, element-level `annotations`, and the two descriptions, all of which `ElementSerializerUtil.serializeElementBase` writes and a per-kind projection would drop. A checker-side record of a rule's parts would be the parallel A12 model [proposal §5.3](PORTABLE-CONSISTENCY-PROPOSAL.md#53-retain-invalid-input-long-enough-to-diagnose-it) forbids, and it is also what would lose an *invalid* declaration.

**Where the Kernel's reader raises, preparation diagnoses — and nothing makes it raise.** A declaration missing `type` or `name` cannot be descended into (one names the envelope, the other places the subtree); a missing `id` is reported yet the element is still placed, because `id` no longer locates it. A kind outside the Kernel's closed four — including a mis-cased `"group"`, which hits its `default:` arm — is reported rather than passing as an inert declaration, since input A12 refuses being analysed here without a word is the direction no consumer can detect. Every array and boolean member is read through a diagnosing accessor, so no well-formed JSON escapes as an exception; that class was the alternative to a verdict or a coverage gap, and [proposal §6](PORTABLE-CONSISTENCY-PROPOSAL.md#6-public-contract) admits neither.

**Only a measured A12 constraint, violated at ERROR, rejects a model.** §5.4's three categories are `FindingCategory`, and `Finding.justifiesRejection` is the single locus that grants the power to one of them; an `AUTHORING_POLICY` or `EVALUATOR_SUPPORT` finding stated at ERROR does not reject, and an `A12_CONSTRAINT` finding at WARNING does not either. The ERROR threshold is **ours**, not a Kernel rejection: `checkConsistency` returns `void` and emits notifications, and every dmkits consumer draws the line at ERROR independently. Whether A12 treats a WARNING-only model as acceptable is an `:adapter` probe nobody has run.

**`ConsistencyStatus` has no `Valid`, and the control is behavioural rather than the compiler.** An exhaustive `when` over the sealed type forces a later `Valid` variant to *edit* the test, not to fail it, so the guard iterates what `statusOf` actually returns for every category × severity instead. `complete` is a constant `false` on both variants and carries no information yet — the coverage evidence that makes it informative is F's report.

**Source identity is EXTRACTED, not asserted.** `ModelSource` has no id parameter: `admit` reads `header.id` and rejects a source without one (`SOURCE_HAS_NO_IDENTITY`), matching `WorkspaceModelSource`, which takes the same shape for the same reason — A12 puts a model's identity there and every cross-source reference resolves through it, so a caller-supplied id is an echo rather than the retention this stage's exit evidence names. It also closes the case where any well-formed JSON was admitted as if it were a model.

**Still open, logged rather than folded in.** `PreparedModel` collides by name with `:interpreter`'s unrelated type. `Finding.at` is a flat path where [proposal §6](PORTABLE-CONSISTENCY-PROPOSAL.md#6-public-contract) and `rulekit.validate.Diagnostic` both carry a structured `where` with a span. Type-definition libraries, includes and non-model JSON all prepare to an empty fact base with no coverage gap. The purity guard checks import text over four named source sets, two of which do not exist, and has no zero-scan refusal — `verifyCompiledPurity`, already used by `:schemakit`, is the next tier and needs no published package. These are F's and B1's to settle; none violates a B2 acceptance item.

### B1 record — reference binding classifies three outcomes, not two (2026-09-12)

**A12 rejects an undeclared name and an ambiguous one DIFFERENTLY, so the checker must too.** `MVK_INVALID_ENTITY` and `MVK_FIELDNAME_NOT_UNIQUE` carry different correctives — declare the field, versus qualify the reference so it names one — and the interpreter's existing static helper reports both as the same unresolved path ([proposal §3.4](PORTABLE-CONSISTENCY-PROPOSAL.md#34-reuse-existing-static-reference-checking-distinguish-it-from-execution-preparation)). `Reference` is therefore a three-arm sum: `Resolved`, `Undeclared`, `Ambiguous`.

**The tier rule is the Kernel's, not a re-derivation.** The declaring group is tried first and wins outright; only on a miss does the model-wide fallback apply, and only when `fieldRefByShortNameAllowed` is on — the flag gates the second tier alone, which is why it changes the verdict for a name the group lacks and changes nothing for one it has. The fallback indexes **fields**, not every element; a resolver that indexed groups too would resolve a group short name and never be noticed, so that is its own control.

**The grid's accept-side row had no committed oracle until now.** `ShortNameTierLawsTest` holds the ambiguous pair, where the flag only chooses between two rejections — which cannot show the fallback SUCCEEDING, so on its own it leaves open whether the flag enables a lookup or retitles a refusal. `ShortNameUniqueTierLawsTest` adds the unique pair (`MVK_INVALID_ENTITY` → accepted), moving exactly one thing: the sibling group declares `Other` rather than a second `Code`. §3.4 had recorded those rows as executed session evidence and explicitly not locked.

### C2 record — the reference rejections, and where their correctives live (2026-09-12)

**Both codes ride the finding, and both are A12's.** `Reference.Undeclared` → `MVK_INVALID_ENTITY`, `Reference.Ambiguous` → `MVK_FIELDNAME_NOT_UNIQUE`, each `A12_CONSTRAINT` at ERROR because each is a locked Kernel verdict rather than a policy of ours. The explain names the ambiguous candidates, since a reader cannot qualify a reference to one of them without seeing them.

**A gap in the corrective owner was fixed in the owner.** `StructuralKernelFix` had a hint for `MVK_INVALID_ENTITY` and none for `MVK_FIELDNAME_NOT_UNIQUE`, so the CLI's enricher said nothing on the harder of the two — and it is the harder one precisely because its corrective is the *opposite*: qualify the reference rather than declare the field, and turning `fieldRefByShortNameAllowed` off converts this code into the other rather than fixing anything. That hint now exists, which serves every JVM consumer.

**Sharing that guidance with the portable report is C2's remaining piece, deliberately not improvised.** The checker is Kernel-free and carries no project dependency, so it cannot read `StructuralKernelFix`; restating the prose in Kotlin would mint the second live statement the doc rules forbid. The portable findings therefore carry the code and the observation and no `fix` text, rather than advertising guidance with nothing behind it. The single-owner mechanism — a shared resource both sides read, or generation from the Java owner — belongs with F's report contract, which is where the `fix`/`explain` fields become public.

### B0 record — the reference class that needs no parser, and the one that does (2026-09-12)

**Two reference classes, and only one of them is behind the syntax layer.** A12 declares a rule's error target and a computation's target as **structured members** (`errorEntityRelPath`, `computedFieldRelPath`), not as DSL text, so `declaredReferences()` resolves them with no condition parser at all. The references inside condition and operation text are the other class, and they wait for syntax.

**The relative path resolves from the declaring ELEMENT, not from its group**, which matters because the two readings agree on almost everything anyone writes: a rule at `/Order/Check` reaches its sibling as `../Code`, and dropping that step names a child of the rule. `ErrorFieldRelativePathLawsTest` locks it, and the row that discriminates is the only one in this repository where a group-relative resolver would have disagreed with A12.

**Two neighbouring gates were measured in the same pass, because each rejects a path that resolves perfectly well** and a reader seeing only the rejection would blame the path: the condition must also reference the target (`MVK_ERROR_FIELD_NOT_REFERENCED`), and that gate is why the lock's rows vary the error field together with the condition's own reference rather than the path alone — varying the path alone moved two variables and produced three wrong expectations before the Kernel said so. With the reference present, a target inside a repeatable group is ADMITTED, which bounds the second gate rather than leaving it as a guess.

**Consequence for the checker's contract:** `declaredReferences()` answers "does this path name a declared element" and nothing more. A resolvable path is necessary and not sufficient for A12 admission, so this slice's findings must never be read as a rule-level verdict — the remaining gates belong to D.

**The first public operation is `check(ModelSource): ConsistencyReport`.** It admits, prepares, resolves the structural references and reports — INVALID when one does not resolve, INCONCLUSIVE otherwise, never VALID, because `ConsistencyStatus` has no such arm and the coverage registry that would earn it does not exist. `findings` and `diagnostics` stay separate arrays on purpose: a finding is A12's verdict on a declaration, a diagnostic is the checker saying it could not read something, and folding them together is precisely the conflation `ModelLoader.loadAndCheck` makes on the Java side.

**`checksCompleted` is evidence, and every report also states what it could NOT check.** `CheckFamily` has one constant today, so "the reference check completed" would read as "the analysis completed" if nothing said otherwise; each report therefore carries an explicit gap naming the unimplemented families, and each unreadable declaration becomes a gap of its own rather than a silent omission. An unreadable source reports at the SOURCE level with no check completed, instead of inventing a rule location for it.

**The syntax layer is the next partition, and it is not small.** The Kernel's condition lexer resolves operator keywords *dynamically per language* (`ID` carries `setTypeInLexer`, driven by the multi-language handler), so a faithful checker-owned tokenizer needs the per-language keyword catalog rather than fixed literals — and the neutral-leaf record already rules out sharing the interpreter's parser. That is B0's remaining half and slice C's foundation, with its own acceptance matrix.

### B0 record — the syntax layer reads condition references without a parser (2026-09-12)

**Acceptance matrix, all measured on `referencesIn(text, language)`.** A keyword is not a reference and its argument is; an INFIX keyword is not a reference either, which is the case a structural guess gets wrong because `And` is a bare name with no parentheses; a string literal and a comment contribute nothing however condition-shaped their content; a path is kept whole; a quoted name IS a reference whatever it spells; a category after `->` is not one; `@From` is a single terminal, so its second half is not a name. Non-goals are stated on the function: `$` is dropped because it selects an outer repetition of the same declared element, an all-digit name is read as a constant, and nothing here decides whether the text parses.

**The keyword table is PER LANGUAGE, and that is a correctness requirement rather than completeness.** A model declares `content.modelConfig.conditionLanguage.code`, the Kernel resolves a name against that language's terminals alone, and the two vocabularies are not translations of one set ([KF283](KERNEL-FINDINGS.md#kf283)). A merged table reads a German model's field named `And` as an operator and drops the reference with no diagnostic — a silent false negative, which is the worst shape a checker can have. The table is generated from rulekit's catalog plus the terminals that catalog deliberately omits, declared with their reason in `consistency/build.gradle`, and `consistency.ConditionKeywordProjectionTest` pins the union to the Kernel's own bundles in both directions so neither list can drift.

**Adjacency is the discriminator a token kind cannot supply.** `[A] / [B]` is a division and `A/B` is a path, and the lexer tells them apart only by the whitespace the grammar's `entity` rule does not admit — so `Token` carries a span and the extractor reads a maximal run of touching path tokens. That is also why the tokenizer keeps a quoted name in its own kind: the Kernel keeps the escape in the token TYPE (`NameOhnePunktMitQuotes` is a different terminal from the keyword the name spells), and collapsing it onto an ordinary name would make `'And'` unrecoverable.

**What this does not yet do.** The extracted references are not bound: wiring them through `PreparedModel.resolve` alongside `declaredReferences()` — with the declaring element's path and the model's condition language reaching the extractor — is the remainder of B0 proper, and the §3.4 grid's condition rows land with it.

### Decisions to resolve during A

- **Shared syntax:** default to checker-owned source. Extract a neutral leaf only when the enumerated closure and H4/H5 evidence support it; this avoids turning parser extraction into an interpreter redesign.
- **Java binding adoption:** investigate designating `ConditionBridge` alongside the relevant Java façades, because otherwise its resolver remains an additional owner. Record the actual decision and residual behavior after reading its closure; do not promise consolidation of `ParsedModel`.
- **Checker budgets:** specify the candidate recommendation and calibration task in A, then bring its measured record to the owner at the start of F under §5's schedule and the proposal's H5 contract.

## 4. Verification and review protocol

Each implementation task records: the frozen behavior/family set, source/test owners, selected fixture ids, expected results, enforcement locus, failing control, targeted command and non-goals. New Kernel-semantic claims add the [witness grid](TESTING-SPEC.md#witness-grid-design) before fixtures. Translation reuses the original cases; shared Java/JS agreement is parity, not an independent oracle.

| Obligation | Test owner / required detector |
|---|---|
| Shared behavior and Java compatibility | Retained Java tests and applicable compiled API checks; designated-façade dependency and targeted mutation controls prove both consumers use the shared predicate |
| A12 decision/classification | Independent pinned Kernel calls in `:adapter`, using original canonical inputs; portable module tests remain Kernel-free |
| Truthful report and coverage | Common tests with explicit expected findings/gaps; controls for skipped checks, first-error short circuit, lost attribution, failed prerequisites and unsupported input. Full report projection repeats through F consumers |
| Profile manifest ↔ implementation registry ↔ executed witnesses | A defines the finite mapping and F owns the CI reconciliation. Controls must fail when a manifest claims an unregistered check or a required witness has no execution evidence. Runtime completeness tests must also fail when a required profile check is skipped, even if the remaining checks pass |
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
