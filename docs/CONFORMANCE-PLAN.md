# Kernel conformance — the plan

> **What this is.** The one plan for bringing the portable checker (`:consistency`), the interpreter (`:interpreter`) and the dmkits (rulekit, modelkit) into conformance with A12 Kernel `30.8.1`. Its **work-item table is the state carrier**: a landing updates its row in the same commit, and the next session reads the table and continues. Adopted 2026-09-26; **re-planned the same evening**, from fixing measured differences to rebuilding along A12's own structure ("Why neither mode converged").

## The goal

**Conformance with the leanest code and test base that achieves it.** Conformance is measured, never asserted. A generated population of inputs goes through the Kernel and through each component. Every disagreement is grouped into a **cluster** by its signature, and the goal is a committed ledger per oracle with **zero clusters in tiers 1 and 2** over the declared population. A cluster that remains is fixed, or declared out of scope in its ledger row with the reason.

"Lean" is part of the goal, not a clean-up after it. Code and tests that a rebuilt stage makes redundant are deleted in the landing that replaces them. Git keeps them, so nothing is lost.

## Why neither mode converged (measured 2026-09-26)

- **Hand-driven fixing had no finite target.** Batches driven by hand grids, peer reports and reviews found defects one neighbourhood at a time, while the reject-side differential over the committed models stayed green. Since 2026-09-12 that produced 236 new kernel test files and 197 KERNEL-FINDINGS entries.
- **The sweep made discovery systematic, but the fixes stayed local.** Each cluster was patched inside the family that diverged. The checker is built from inference: a parser deliberately looser than A12's grammar, an incompleteness heuristic, a dozen token scanners and about 130 families, ordered by raise points and withholding tables. That is 30 043 lines of main code, 34 032 lines in 208 test files, and 7 092 lines in 34 adapter checker tests. Widening the sweep to every truncation and separator insertion turned 11 divergent inputs into 788. Of those, 765 occupy the four EOF class-confusion keys (the previous 764 count was corrected against the committed ledger on 2026-09-27); the family heuristics do not know which construct the parser is in.

Patching those families is whack-a-mole. The checker is instead rebuilt along A12's check structure, which [CHECKER-RECONSTRUCTION-PROPOSAL](CHECKER-RECONSTRUCTION-PROPOSAL.md) models from source. In A12:

1. eight model rules run in a fixed order;
2. inside code generation, the default-variant element container is built, then every computation is checked, then every rule;
3. each condition is checked in one parse whose actions run as its rules complete, a few mid-rule, and whose first failure ends it.

## The approach

1. **Model A12's structure from source, then confirm it on A12 itself.** A source-read model is a hypothesis. A trace of A12's own parser confirms it, and only confirmed rows are built.
2. **Replace a mechanism along the model where the evidence shows it pays, clean-room, and delete what it replaces.** The syntax mechanism goes first (R3a), because 765 of the 788 divergent inputs occupy the four EOF class-confusion keys (corrected against the committed ledger on 2026-09-27). A check action or a model stage is rebuilt only where R0 shows a family duplicates another or cannot follow A12's order; being replaced whole is not a goal in itself. Nothing is retro-fitted into the old syntax layer.
3. **One conformance oracle per component, over a generated population.** The ledger is the lock (test-base policy below).

## The instrument

**One generator, three oracles, one clustered ledger per oracle.**

| population | contents | status |
|---|---|---|
| **P1 seeds** | every rule in the committed model trees (`corpus/models`, `cli/…/models`, `src/test/resources/models`) and every catalog `validExample`, hosted on N7's scheme (`CatalogHosting`) and asked of A12 again there | built |
| **P2 near-misses** | single edits of each seed, deterministic and seeded per seed: 30 sampled condition edits (delete, duplicate or swap a token; substitute an operator or a name; insert a connective, bracket, comma, star or sign) and message edits. Beside the sample, and never reshuffling it, every truncation after a token and a `/` or `.` after every token | built (`NearMissPopulation`) |
| **P3 widenings** | element-name edits, computations, typedef and custom index hosts, variant filters, deeper and sibling shapes; locally only, the reference corpora (`a12-kernel-mm`, the A12 Tools workspaces, never committed) | M5 |
| **P4 harvested fixtures** | every model the checker's own tests check, recorded through their shared helpers and replayed. The hand-written grids become population, so their tests can retire | T1 |

| oracle | compares | tiers |
|---|---|---|
| **O1 static** | `RuleValidator.check` (the Kernel) against the checker's `check` | 1 OVER-REJECT, 1 WRONG-CLASS, 2 SILENT, 3 EXTRA |
| **O2 runtime** | on every input the Kernel accepts: Kernel `RuntimeLaws` (dynamic Groovy is normative) against the interpreter, over `SeedGenerator` documents, on `code\|type\|pointer` | 1 any signature difference |
| **O3 authoring** | on every accepted input, the adapter reads the rule into typed form and renders it. The render must be accepted and equivalent, and the read must not refuse | 1 refusal or loss, 2 non-canonical render |

**Tiers.** Tier 1 is correctness: the component is wrong. Tier 2 is coverage: it is silent where A12 refuses, which the report discloses as `INCONCLUSIVE`. Tier 3 is order: classes beyond A12's early stop. It is worked only after tiers 1 and 2 are empty, and the rebuild is expected to empty it by construction.

**Cluster key** = `verdict · A12's classes · the component's classes`. The edit operator is not part of the key, because one mechanism shows under many edits. The run writes counts and examples to `build/`; the committed ledger (`adapter/src/test/resources/checker-near-miss/clusters.tsv`) holds the keys. A new key fails the run, and so does a vanished key until its row is deleted.

## Test-base policy

- **A conformance claim is locked by a ledger, not by a mirror test.** A per-fact checker test that restates what the sweep compares retires once its input is in the population.
- **A component test earns its place only by locking a contract the sweep cannot state.** Examples are the parser's decision procedure (the proposal's Appendix B), the report's public shape, and JVM/JS parity.
- **A test retires only against an equivalent assertion.** A landing that deletes tests carries a finite matrix: each deleted test's claim kind (class, site, order, or runtime) and the assertion that now carries it. A class-set sweep over the same inputs replaces a class claim only, never a site, order or runtime claim.
- **Kernel-law tests stay while KERNEL-FINDINGS cites them.** They are A12 evidence, not tests of our code. One retires only when an assertion of the same claim kind replaces it and its finding cites that instead.

## Working rules

1. **Discovery happens only through the sweep.** A user or peer report becomes a seed or an edit operator, never a hand grid.
2. **No fix goes into a family the rebuild replaces.** A cluster in a stage that has not been rebuilt waits for that stage. The one exception is an OVER-REJECT (a false error a user sees), which gets the smallest fix.
3. **A stage lands as one partition.** Its model rows are confirmed, and its acceptance matrix is written before code: target ledger keys, predicted outcome, discriminators. The old code and tests go in the same landing, the ledger is no worse, and `:consistency:check` is green on JVM and JS. CLAUDE.md's batching rule applies.
4. **Reviews go where the review gate puts them.** One design review goes after R2, because the confirmed model shapes everything after it. A terminal review goes at each stage's landing.
5. **A letter to a12-kernel-lean goes with a landing only when it measured a kernel fact their spec lacks.** A peer report's kernel claims are measured as kernel laws on our own fixture, which answers the peer. The checker's side of those claims enters the sweep through a seed or a population, never a hand grid.
6. **CLAUDE.md's convergence gate applies as written:** two consecutive rounds adding blockers to one slice, or any round changing its acceptance denominator, stop the slice for re-partitioning. The 2026-09-26 design review was such a round, and it split R3 into R3a and R3b.

## Work items — the state carrier

**Resume here (2026-09-27).** R0 is complete. R3a's contract and deletion matrix (proposal §5) have had their one cold review. T1 is next, scoped to that matrix, and then R3a itself; no replacement parser has landed yet. R0 opens R3b narrowly for DG94/DG117/DG118 and R4 for default-variant preparation, DG113 and DG83; each still needs its own acceptance matrix. R5 needs an order oracle, S1 needs NFR #1 evidence, and M2 is independent of the rebuild. Letter `-2026-09-27`, delivered to a12-kernel-lean, answered the one entry pending in their ledger, `SPEC-2026-09-26-05`; read their ledger for its disposition and for any new entry before starting.

| id | item | exit criterion | state |
|---|---|---|---|
| **M0** | clean baseline | C5 committed, JS-only test defect fixed and guarded, C4b review settled, letter `-26n` sent | **done** 2026-09-26 (`13a02c3e1`, `6635b7664`, `acaf16a29`) |
| **M1** | the checker's frontier is known | `CheckerNearMissSweepTest` runs P1 + P2 through O1; ledger committed; counts recorded below | **done** 2026-09-26, widened in `197cb544c` (9 362 inputs, 788 divergent) |
| **R0** | the stage map | every `MVK_` code the checker reports is placed at the A12 stage and site that raise it, extending [MVK-LEDGER](MVK-LEDGER.md); each checker family is mapped to the stage it approximates, and so to the landing that deletes it. That includes whether a field-level family reads the model as given or code generation's default-variant container, measured with P3's variant filters, since only the message family of [KF490](KERNEL-FINDINGS.md#kf490) has been | **done** 2026-09-27; 215 code rows, 110 families, 139 variant inputs; R3b/R4 scope bounded below |
| **R0a** | field-filter evidence | the frozen 123-input population, both oracles and the class-set ledger; findings scoped to measured carriers | **done** 2026-09-27 ([KF494](KERNEL-FINDINGS.md#kf494), `FieldVariantStageLawsTest`); cold review corrected mixed coded/codeless notification handling |
| **R0b** | complete the stage map | every reported `MVK_` and every `CheckFamily`, plus field name-uniqueness and variant-filter-binding witnesses omitted from R0a; settle R3b/R4 scope | **done** 2026-09-27 ([KF495](KERNEL-FINDINGS.md#kf495)); 16 added witnesses; cold review corrected target-kind attribution and killed a denominator mutant |
| **R1** | the condition model | [CHECKER-RECONSTRUCTION-PROPOSAL](CHECKER-RECONSTRUCTION-PROPOSAL.md) §1–§3 and appendices A–B, every row naming its source | **done** 2026-09-26; its syntax-failure part confirmed by R2, its action order and stage order source-read; corrected by the design review (message route, variant scope, mid-rule actions, match count, decision 74) |
| **R2** | the trace | A12's own parser, instrumented through `checkSnippet`'s init hook (proposal §4), runs P2 and Appendix C. Every parse-stage signature is predicted by the model, and any that is not is fixed in the model first. Then the design review | **done** 2026-09-26 (`A12ParseTraceTest`, [KF493](KERNEL-FINDINGS.md#kf493)): 9 063 conditions, no unpredicted signature, one model sentence corrected, six mutants of its claims killed. The design review of 2026-09-26 kept the measurements, corrected the model and split R3 |
| **R3a-C** | syntax contract and deletion matrix | proposal §5 specifies entries, verdict, demand/prediction, completed steps, computation positions, finite acceptance and assertion-preserving deletion; one cold review before code | **done** 2026-09-27; one cold review settled family routing, reducer-host separation, delayed-EOF ownership and the six exact target keys |
| **T1** | harvest the test fixtures (P4) | every model the checker tests check is recorded, committed as a population and replayed through O1; its clusters are ledgered. Scoped first to the tests R3a's deletion matrix names | open, **next**; 13 methods, 77 fixed logical rows plus catalog/external populations |
| **R3a** | the syntax verdict replaced | proposal §5: one parser, exact in A12's behaviour and original in expression, deciding acceptance and each failure's kind, class and offending token. It replaces `ShapeParser` and `IncompleteInput` and supplies the reference scanner with the steps a walked path completed; the scanners are otherwise unchanged, and so are the families and the report assembly. The sweep's syntax-class clusters are empty with no new over-rejection; the trace's rows are reproduced at class and token; JVM and JS are green; the deletion matrix's tests are gone | open, after R0 and the contract in §5 |
| **R3b** | check actions on recognized structure | conditional: a family is re-expressed only where R0 shows duplication or an order it cannot follow, with A.2's rows measured first and an order oracle | open, bounded to DG94/DG117/DG118; own action/order matrix required |
| **R4** | the model and code-generation stages | conditional on R0's map: container preparation, the non-meta-model checks, model validation, computations, rule preparation, message checks and notification conversion, each rebuilt only where the map shows a family cannot follow A12's stage | open, bounded to default-variant preparation, DG113 and DG83; partition matrices required |
| **R5** | the report is the stage stream | an order oracle beside the class sweep (order, span, multiplicity, and the kernel's notification deduplication), and only then are raise points, positioned-code tables and withholding rules deleted | open, after R3b |
| **S1** | one condition parser in the repository | the interpreter parses with R3a's parser only if NFR #1 holds: no interpreter row, time or memory, JVM or JS, regresses more than 5 %, measured as proposal §5 sets out. Sharing cost the interpreter more than 5 % in parsing before (owner's report, 2026-09-27). The interpreter's dependency gate must also admit the leaf that holds the parser | open; the parsers stay separate until that measurement exists |
| **X2** | DG114's over-rejection | the plan's one exception to working rule 2: the measured field family judges only the fields code generation's default-variant container keeps | **done** 2026-09-26; the other field families moved to R0 |
| **M2** | the interpreter's and the dmkits' frontier is known | O2 and O3 run on M1's accepted population; ledgers committed; counts recorded | open |
| **M3** | tier 1 is empty | no OVER-REJECT or WRONG-CLASS cluster in O1, and no tier-1 cluster in O2 or O3 | open; reached through R3a–R5 and M2 |
| **M4** | tier 2 is closed | every SILENT cluster fixed or declared | open |
| **M5** | widen and repeat | the P3 populations added one at a time, each re-running M3 and M4 | open |
| **X1** | a12-kernel-lean `SPEC-2026-09-26-05` | its three rows (the sole-operand `NumberOfFilledGroups` arity, the overlap `In`-list gate order, a field message `$fïeld$` → `INVALID_PARAMETER`) are measured on our own fixture and answered | **done** 2026-09-26: all three confirmed on the kernel ([KF185](KERNEL-FINDINGS.md#kf185), [KF380](KERNEL-FINDINGS.md#kf380), [KF490](KERNEL-FINDINGS.md#kf490)), answered in letter `-26q`; the checker's side of those shapes needs P3's count-placement and overlap-list hosts, so it waits for M5 |
| **D1** | doc corrections carried from the batch-S review | KF492's under-report sentence, KF491's mechanism line and KF76's comment note, each corrected in place from the trace | **done** 2026-09-26 |

Batches G1, G2 and S (2026-09-26, [KF491](KERNEL-FINDINGS.md#kf491), [KF492](KERNEL-FINDINGS.md#kf492)) patched the old syntax layer. Their measured rows stay as seeds, and their code retires with R3a.

## The open gaps, re-sorted (2026-09-26)

Each open row is a consequence of a missing structure, not an independent defect, and it names the oracle that can settle it (corrected by the design review of 2026-09-26):

| gap | the structure that is missing | settled by |
|---|---|---|
| [CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) DG83 | a condition that fails withholds its rule's whole message check (proposal §1) | the message stage's place in R0's map; R3a supplies the syntax verdict it needs |
| DG93 | a digit sequence is a name wherever the grammar reads `nameOhnePunkt` | R3a |
| DG94 | two families own one gate; not a divergence | R3b, if it deletes those families; the report's public family list changes with it |
| DG117, DG118 | the check actions type every operation bottom-up | R3b, with computation carriers in the population before either is called closed |
| DG113 | A12's path alphabet in code generation's prechecks | R4, with P3's name edits |
| DG102 | a Java-regex-compatible pattern judge on JVM and JS, and typedef/custom index hosts | its own batch, a dependency of the semantic-index checks; P3 supplies the hosts |
| [DEV-API-GAPS](DEV-API-GAPS.md) DG69, DG71 | a kernel-admitted keyed shape reads as `Opaque` | O3, only if it asserts typed coverage: an opaque read renders back unchanged and would pass |
| DG75 | a wrong class on a refused authoring | not O3, which sees accepted inputs only; a rejected-authoring comparison |
| DG115 | modelkit drops a message at `build()` | not O3, which reads the already-built model; a comparison at the authoring boundary |
| [INTERPRETER-GAPS](INTERPRETER-GAPS.md) IG155 | a keyed group with a star declined | O2, with the hosts and documents it needs |
| IG159 | a field type's message text | O2 only if it compares message text: equal `code\|type\|pointer` hides it |

## Results

### R0 acceptance matrix (planned 2026-09-27, before measurement)

R0 has two evidence partitions: R0a freezes the 123-input variant-container experiment below; R0b completes the source inventory (reported code → raising site → stage, family → stage → replacement) and the remaining field-family witnesses. Neither rewrites parser actions. Each landing has one terminal cold review; R3a's contract has its separately requested foundational review. The partition was made on 2026-09-27 under the convergence gate when enumeration exposed field-bearing `ELEMENT_NAME_UNIQUENESS` and `VARIANT_FILTER` outside the initial witness list: their inputs must not silently enlarge R0a.

| obligation | oracle | prediction | discriminator / acceptance |
|---|---|---|---|
| Complete reported-code inventory, including codes outside the original ledger's enums | `CheckerReportedCodes.bySource()` against the stage rows | every reported `MVK_` code has a raising site and stage | exact denominator equality; membership-only literals and generated correctives do not count |
| Complete family inventory | `CheckFamily` against the family rows | every family maps to its actual stage(s), including reader/build refusals | exact denominator equality; mixed-stage families are not forced into one stage |
| Field declarations after container conversion | `checkConsistency`, via the adapter on own-domain order models | each code-generation declaration defect disappears under `ONLY`, remains under absent/`ALL_EXCEPT` filters | same defect, changing only filter mode; valid declaration controls under all three modes prevent silence from masquerading as admission |
| Reader/build defects before container conversion | same ingress and oracle, preserving unreadable/build-failure outcomes | an unknown field type and an excessive Number scale still fail under `ONLY` | separates filtering of a built container from filtering raw JSON |
| Original-model Number limit check | same oracle, original Number declaration with an excessive value bound | the outer model rule still reports under `ONLY` | separates original-model checks from code generation's metamodel validation |
| Checker exposure | P3 variant-filter population through O1, with a clustered ledger | existing families may over-reject filtered declarations | every experimental input reaches both oracles; no hand-written checker expectation substitutes for the sweep |

The declaration witnesses cover `FIELD_TYPE_BINDING` (unspecified and unknown type), `TEMPORAL_CONFIG`, the field arms of `ELEMENT_NAME_ALPHABET` and `FIELD_NAME_RESERVED_SEGMENT`, `ENUM_CATEGORY_ALIGNMENT`, `ENUM_VALUE_CHARACTERS`, `ENUM_TEXT_FORBIDDEN_CHARACTERS`, `MODEL_TEXT_REPERTOIRE`, `HINT_LIST_VALUES`, `ENUM_S_VALUE_DOMAIN`, `STRING_PATTERN_ADMISSION`, `STRING_PATTERN_MESSAGE`, `FIELD_TYPE_MESSAGE_TEXT`, `STRING_TYPE_PARAMETERS`, `NUMBER_TYPE_DIGITS` (consistency and build arms), `ENUM_VALUE_LABELS`, and the field arm of `STRING_SLOT_BINDING`. Group ordering receives a separate filtered-key witness because deleting its referent should change the class, not silence the group declaration.

Axes: vary defect present/absent and filter absent/`ONLY`/`ALL_EXCEPT`; hold the model's condition language English, locale `en_US`, and variant name `premium` fixed. Use inline types and a fixed Order root. Typedef, nested-group and multilingual transfers remain unmeasured unless a row explicitly adds them. A source site establishes placement; a variant row establishes the behavior on its carrier, not every gate sharing the family name. Predictions are recorded per witness in the population before its first run. Any missed prediction is retained with its observed result and the narrowed conclusion.

### R0b acceptance matrix (planned 2026-09-27, before measurement)

The source denominator is every currently reported `MVK_` code and every live `CheckFamily`; the executable guard derives both, rather than trusting the written counts. Raising sites are sourced from the proposal's action inventory, the kernel's stage orchestration and error sites, and the metamodel's rule carriers. Exact set equality guards inventory coverage, not the truth of a stage assignment; the terminal cold review checks that separately.

| obligation | oracle | prediction | discriminator / acceptance |
|---|---|---|---|
| Complete code and family placement | live-source denominator guard plus source reads and cold review | 215 reported codes and 110 families each have one inventory row, with mixed stages preserved | omit or duplicate a row and the guard fails; the reader, container, metamodel, parse, message and serializer stages remain distinct |
| Future family denominator | focused disposition guard with a temporary final `R0_REVIEW2` enum member and no trailing comma | unchanged guard incorrectly passes; corrected guard refuses the unlisted member, and the unmodified enum passes | digits and optional final comma cannot hide a live family; temporary mutant removed before landing |
| Field name uniqueness | kernel law and P3 differential, six inputs: collision/control × absent/`ONLY`/`ALL_EXCEPT` | two fields sharing a name fail when kept; filtering one with `ONLY` removes the collision | one changed field name, IDs held fixed; differs from an original-model name-uniqueness check |
| Field filter binding | same oracles, ten inputs: absent, valid `ONLY`, valid `ALL_EXCEPT`, explicit null, unknown mode, absent mode, string filter, null/object/numeric mode | first four admitted; remaining six cannot load/build | deletion applies only after the filter can be bound; explicit null is a positive control |
| Replacement scope | stage map against the existing DG94/DG117/DG118 witnesses and KF494 | R3a replaces syntax machinery; R3b opens for duplicated/actions-dependent mechanisms; R4 opens for the default-variant view and named stage mismatches | stage membership alone is not permission to rewrite a family; R3a keeps family and report contracts |

R0b adds only these 16 inputs to R0a's 123, with separate kernel assertions for each partition and all 139 through the same class-set differential. It does not change production behavior, order/span/multiplicity contracts, the interpreter, or the two parser entry contracts still owned by R3a's design. Each missed prediction is recorded before its conclusion changes. One terminal cold review precedes landing.

### R0a — frozen variant population (2026-09-27)

R0a's finite denominator is 20 declaration witnesses × defect/control × absent/`ONLY`/`ALL_EXCEPT`, plus three filtered-index rows: 123 inputs. Its acceptance is the kernel prediction test and the class-set differential against the committed variant ledger. It does not change checker behavior or assert that every gate within a family uses one stage. R0b still owns the complete code/family map and the two missing field-family witnesses; R0 remains open until that landing.

Predictions corrected after the run: an excessive Number scale is codeless metamodel rejection, removed by `ONLY`, not an ingress refusal; duplicate enum values leave a codeless serializer rejection after filtering; an object-valued field name leaves a codeless empty-name serializer rejection after filtering. The rejected pattern witness is `a{2}+`, not `a{2}` (admitted); supported-character restrictions use A12's range string `A-M`, not a regex. These corrections narrow the original hypotheses rather than preserving them as stage conclusions.

The ledger records 27 divergent inputs in 21 clusters: 15 over-rejections, seven silent cases and five wrong-class cases. The over-rejections justify opening R4 for a shared default-variant view; they do not justify rewriting every model stage. The cold review found that the initial oracle discarded a codeless notification beside keyed ones; the duplicate-enum rows reproduced this, and normalization now preserves each notification before taking the class set. R3b's opening decision remains R0b's responsibility. The original-model Number bound and filtered enum duplicates prevent a blanket suppression of field findings from being a valid fix.

### R0b — complete stage inventory (2026-09-27)

The 16 predictions passed unchanged. The combined 139-input population has 28 divergences in 21 clusters: 16 over-rejections, seven silent cases and five wrong-class cases. The stage inventory covers 215 reported codes and 110 families; independent cold review also traversed all 50 matching metamodel codes across 61 carrier paths. The original MVK ledger tests still pass.

Cold review corrected a copied computation-target explanation and the family scanner's exclusion of a legal final enum member. The temporary `R0_REVIEW2` member without a trailing comma passed the old guard and failed the corrected guard; it was removed before landing. Review also supplied the omitted filter-setup and list-category action sites without widening the inventory's claim to every helper caller. KF329's “before checkConsistency” wording was corrected to distinguish the adapter's build-failure classification from stage timing. The same obsolete timing wording remains in the existing `ComputedTargetKind.kt` and `ComputedFieldTargetKindLawsTest` comments; it is adjacent documentation debt for their owning R4 partition, not a basis for this map.

R3b opens for the duplicated star gate (DG94) and bottom-up operation types (DG117/DG118), with an action/order oracle still required. R4 opens for shared default-variant preparation and the named non-meta path and condition/message boundaries (DG113/DG83). Neither decision authorizes a wholesale rewrite. The field population's original-model counterexamples remain mandatory controls.

| date | oracle | seeds | compared | tier-1 clusters | tier-2 | tier-3 |
|---|---|---|---|---|---|---|
| 2026-09-26 | O1, conditions and rule messages (`CheckerNearMissSweepTest`, first freeze) | 139 admitted as written | 4 469 inputs, 362 divergent | 23 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G1 | 139 | 4 469 inputs, 75 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G2 | 139 | 4 469 inputs, 31 divergent | 6 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after the M1 review: edits resampled per seed, walked open paths fixed ([KF491](KERNEL-FINDINGS.md#kf491)) | 139 | 4 469 inputs, 21 divergent | 10 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch S ([KF492](KERNEL-FINDINGS.md#kf492)) | 139 | 4 469 inputs, 11 divergent | 7 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, P2 widened with every truncation and separator insertion; the sampled 4 469 unchanged | 139 | 9 362 inputs, 788 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
