# Kernel conformance — the plan

> **What this is.** The one plan for bringing the portable checker (`:consistency`), the interpreter (`:interpreter`) and the dmkits (rulekit, modelkit) into conformance with A12 Kernel `30.8.1`. Its **work-item table is the state carrier**: a landing updates its row in the same commit, and the next session reads the table and continues. Adopted 2026-09-26; **re-planned the same evening**, from fixing measured differences to rebuilding along A12's own structure ("Why neither mode converged").

## The goal

**Conformance with the leanest code and test base that achieves it.** Conformance is measured, never asserted. A generated population of inputs goes through the Kernel and through each component. Every disagreement is grouped into a **cluster** by its signature, and the goal is a committed ledger per oracle with **zero clusters in tiers 1 and 2** over the declared population. A cluster that remains is fixed, or declared out of scope in its ledger row with the reason.

"Lean" is part of the goal, not a clean-up after it. Code and tests that a rebuilt stage makes redundant are deleted in the landing that replaces them. Git keeps them, so nothing is lost.

## Why neither mode converged (measured 2026-09-26)

- **Hand-driven fixing had no finite target.** Batches driven by hand grids, peer reports and reviews found defects one neighbourhood at a time, while the reject-side differential over the committed models stayed green. Since 2026-09-12 that produced 236 new kernel test files and 197 KERNEL-FINDINGS entries.
- **The sweep made discovery systematic, but the fixes stayed local.** Each cluster was patched inside the family that diverged. The checker is built from inference: a parser deliberately looser than A12's grammar, an incompleteness heuristic, a dozen token scanners and about 130 families, ordered by raise points and withholding tables. That is 30 043 lines of main code, 34 032 lines in 208 test files, and 7 092 lines in 34 adapter checker tests. Widening the sweep to every truncation and separator insertion turned 11 divergent inputs into 788. Of those, 764 come from one end-of-input mechanism that no family can model, because none of them knows which construct the parser is in.

Patching those families is whack-a-mole. The checker is instead rebuilt along A12's check structure, which [CHECKER-RECONSTRUCTION-PROPOSAL](CHECKER-RECONSTRUCTION-PROPOSAL.md) models from source. In A12:

1. eight model rules run in a fixed order;
2. inside code generation, the default-variant element container is built, then every computation is checked, then every rule;
3. each condition is checked in one parse, whose actions run at rule completion and whose first failure ends it.

## The approach

1. **Model A12's structure from source, then confirm it on A12 itself.** A source-read model is a hypothesis. A trace of A12's own parser confirms it, and only confirmed rows are built.
2. **Rebuild each stage along the model, clean-room, and delete what it replaces.** A stage is replaced whole rather than adapted. Nothing is retro-fitted into the old families.
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
- **Family tests retire with their family.** The landing that deletes a family deletes its tests, after T1 has moved their inputs into P4.
- **Kernel-law tests stay while KERNEL-FINDINGS cites them.** They are A12 evidence, not tests of our code. A law class whose rows the population holds may retire once its finding cites the ledger instead.

## Working rules

1. **Discovery happens only through the sweep.** A user or peer report becomes a seed or an edit operator, never a hand grid.
2. **No fix goes into a family the rebuild replaces.** A cluster in a stage that has not been rebuilt waits for that stage. The one exception is an OVER-REJECT (a false error a user sees), which gets the smallest fix.
3. **A stage lands as one partition.** Its model rows are confirmed, and its acceptance matrix is written before code: target ledger keys, predicted outcome, discriminators. The old code and tests go in the same landing, the ledger is no worse, and `:consistency:check` is green on JVM and JS. CLAUDE.md's batching rule applies.
4. **Reviews go where the review gate puts them.** One design review goes after R2, because the confirmed model shapes everything after it. A terminal review goes at each stage's landing.
5. **A letter to a12-kernel-lean goes with a landing only when it measured a kernel fact their spec lacks.** A peer report is answered from the sweep.
6. **The convergence gate applies.** If a stage's cluster count grows over two consecutive landings, stop and re-partition.

## Work items — the state carrier

| id | item | exit criterion | state |
|---|---|---|---|
| **M0** | clean baseline | C5 committed, JS-only test defect fixed and guarded, C4b review settled, letter `-26n` sent | **done** 2026-09-26 (`13a02c3e1`, `6635b7664`, `acaf16a29`) |
| **M1** | the checker's frontier is known | `CheckerNearMissSweepTest` runs P1 + P2 through O1; ledger committed; counts recorded below | **done** 2026-09-26, widened in `197cb544c` (9 362 inputs, 788 divergent) |
| **R0** | the stage map | every `MVK_` code the checker reports is placed at the A12 stage and site that raise it, extending [MVK-LEDGER](MVK-LEDGER.md); each checker family is mapped to the stage it approximates, and so to the landing that deletes it | open |
| **R1** | the condition model | [CHECKER-RECONSTRUCTION-PROPOSAL](CHECKER-RECONSTRUCTION-PROPOSAL.md) §1–§3 and appendices A–B, every row naming its source | **drafted** 2026-09-26, source-read |
| **R2** | the trace | A12's own parser, instrumented through `checkSnippet`'s init hook (proposal §4), runs P2 and Appendix C. Every parse-stage signature is predicted by the model, and any that is not is fixed in the model first. Then the design review | open, **next** |
| **T1** | harvest the test fixtures (P4) | every model the checker tests check is recorded, committed as a population and replayed through O1. Its clusters are ledgered, and each test whose rows the ledger now holds is listed for deletion with its family | open |
| **R3** | the parse stage rebuilt | proposal §5: one grammar-exact parser with A12's event order, check actions at completion, post-parse in order. The ledger's parse-stage clusters are empty with no over-rejection, on JVM and JS. The syntax layer (`consistency/…/syntax`, 3 669 lines, 2026-09-26) and every family the stage covers are deleted with their tests | open |
| **R4** | the model and code-generation stages rebuilt | along R0: the default-variant container first, then the element, field-type, message and computation checks in A12's order. Their families and tests are deleted | open |
| **R5** | the report is the stage stream | raise points, positioned-code tables and withholding rules deleted; the tier-3 ledger is empty | open |
| **S1** | one condition parser in the repository | the interpreter parses with R3's parser. O2 shows no new signature, and `interpreter/…/parse/Parser.kt` is deleted | open, after R3 |
| **M2** | the interpreter's and the dmkits' frontier is known | O2 and O3 run on M1's accepted population; ledgers committed; counts recorded | open |
| **M3** | tier 1 is empty | no OVER-REJECT or WRONG-CLASS cluster in O1, and no tier-1 cluster in O2 or O3 | open; reached through R3–R5 and M2 |
| **M4** | tier 2 is closed | every SILENT cluster fixed or declared | open |
| **M5** | widen and repeat | the P3 populations added one at a time, each re-running M3 and M4 | open |
| **X1** | a12-kernel-lean `SPEC-2026-09-26-05` | its three rows become seeds (the sole-operand `NumberOfFilledGroups` arity, the overlap `In`-list gate order, a field message `$fïeld$` → `INVALID_PARAMETER`) and are answered from the sweep | open |
| **D1** | doc corrections carried from the batch-S review | [KF492](KERNEL-FINDINGS.md#kf492)'s "one row stays under-reported" names three rows, and the third is wrong-class; after R2, [KF491](KERNEL-FINDINGS.md#kf491)'s mechanism line and the note that comments ride the hidden channel are settled by the trace | open |

Batches G1, G2 and S (2026-09-26, [KF491](KERNEL-FINDINGS.md#kf491), [KF492](KERNEL-FINDINGS.md#kf492)) patched the old syntax layer. Their measured rows stay as seeds, and their code retires with R3.

## The open gaps, re-sorted (2026-09-26)

Each open row is a consequence of a missing structure, not an independent defect. So it is settled by the rebuild of the stage that owns it:

| gap | the structure that is missing | settled by |
|---|---|---|
| [CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) DG83 | a refused condition turns a message's parameter check into `INVALID_CONDITION` (proposal §1) | R3 + R5 |
| DG93 | a digit sequence is a name wherever the grammar reads `nameOhnePunkt` | R3 |
| DG94 | two families own one gate; not a divergence | deleted with those families in R3 |
| DG117, DG118 | the check actions type every operation bottom-up | R3 |
| DG113 | A12's path alphabet at the element stage | R4, with P3's name edits |
| DG114 | every check runs on the default-variant container | R4, with P3's variant filters |
| DG102 | a Java-regex-compatible pattern judge on JVM and JS, and typedef/custom index hosts | independent; P3 hosts, then its own batch |
| [DEV-API-GAPS](DEV-API-GAPS.md) DG69, DG71, DG75, DG115; [INTERPRETER-GAPS](INTERPRETER-GAPS.md) IG155, IG159 | typed-surface and evaluator capability gaps, outside the checker | M2's O2/O3 ledgers, then their own batches |

## Results

| date | oracle | seeds | compared | tier-1 clusters | tier-2 | tier-3 |
|---|---|---|---|---|---|---|
| 2026-09-26 | O1, conditions and rule messages (`CheckerNearMissSweepTest`, first freeze) | 139 admitted as written | 4 469 inputs, 362 divergent | 23 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G1 | 139 | 4 469 inputs, 75 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G2 | 139 | 4 469 inputs, 31 divergent | 6 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after the M1 review: edits resampled per seed, walked open paths fixed ([KF491](KERNEL-FINDINGS.md#kf491)) | 139 | 4 469 inputs, 21 divergent | 10 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch S ([KF492](KERNEL-FINDINGS.md#kf492)) | 139 | 4 469 inputs, 11 divergent | 7 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, P2 widened with every truncation and separator insertion; the sampled 4 469 unchanged | 139 | 9 362 inputs, 788 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
