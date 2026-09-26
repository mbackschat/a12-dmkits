# Kernel conformance — the plan

> **What this is.** The one plan for bringing the portable checker (`:consistency`), the interpreter (`:interpreter`) and the dmkits (rulekit, modelkit) as close to A12 Kernel `30.8.1` as a finite amount of work allows. Its **work-item table is the state carrier**: a landing updates its row in the same commit, and the next session reads the table and continues. Adopted 2026-09-26.
>
> **What it replaces.** Batch-by-batch fixing driven by hand-written kernel grids, peer reports and reviews. That mode has no end state; the evidence is in "Why the old mode did not converge" below.

## The goal

**Conformance is a number, not a queue.** A generated population of inputs goes through the Kernel and through each component, and every disagreement is grouped into a **cluster** by its signature. The goal is a committed ledger per oracle that reaches **zero clusters in tiers 1 and 2** over the declared population. A cluster that remains is either fixed or declared out of scope with its reason, in the ledger row itself.

"As close as possible" is reached by **widening the population**, never by hand-hunting inside it: each widening (M5) re-runs the same oracles and adds the clusters it finds.

## Why the old mode did not converge (measured 2026-09-26)

- **No finite target.** Every observed difference was a must-fix defect, including which class A12 names first on a multi-defect model. A12's check has 306 ledgered `MVK_` codes behind ordered gates, early stops and withholding, so "done" receded with every measurement.
- **Discovery by hand, one neighbourhood at a time.** The reject-side differential (`CheckerClassAgreementTest`) runs 13 hand-written mutation kinds over the committed models. It was green with empty ledgers, while hand grids kept finding defects: DG61 on 2026-09-22, DG113 on 2026-09-26. The interpreter's generated nets vary documents and group shapes, but their rules use 6 of the catalog's 110 operators. The one topic that closed for good, the path walk (813 → 0), was the one where a generated grid enumerated the space first.
- **Every fact paid the full ceremony.** Each one got a kernel test, a mirror test, a finding, gap rows, a letter, mutants and a review. Since 2026-09-12 that produced 236 new kernel test files and 197 new KERNEL-FINDINGS entries, and 124 checker commits landed after the checker's delivery plan was archived complete on 2026-09-19.

## The instrument

**One generator, three oracles, one clustered ledger per oracle.**

| population | contents | status |
|---|---|---|
| **P1 seeds** | every rule in the committed model trees (`corpus/models`, `cli/…/models`, `src/test/resources/models`) and every catalog `validExample`, hosted on N7's scheme (`CatalogHosting`) and asked of A12 again there, since the hosting is not N7's own | M1 |
| **P2 near-misses** | single edits of each P1 seed: delete, duplicate or swap a token; substitute an operator or a model name; insert a connective, bracket, comma, star or sign; and on a rule with a message, append `$`, an unresolvable parameter, `$$`, a tab or a supplementary character to its first message. Deterministic, seeded per seed, 30 condition edits per seed. On top of that sample, and never reshuffling it: every truncation after a token, and a `/` or a `.` written straight after every token, where A12's parser meets the end of input or a path separator it cannot continue from | M1 |
| **P3 widenings** | element-name edits, computations, typedef and custom index hosts, deeper and sibling shapes, and, locally only, the reference corpora (`a12-kernel-mm`, the A12 Tools workspaces, never committed) | M5 |

| oracle | compares | tiers |
|---|---|---|
| **O1 static** | `RuleValidator.check` (the Kernel) against the checker's `check`, probe rule injected into its host | 1 OVER-REJECT, 1 WRONG-CLASS, 2 SILENT, 3 EXTRA |
| **O2 runtime** | on every P1/P2 input the Kernel ACCEPTS: Kernel `RuntimeLaws` (dynamic Groovy is normative) against the interpreter, over `SeedGenerator` documents, on `code\|type\|pointer` | 1 any signature difference |
| **O3 authoring** | on every accepted input: the adapter reads the rule into typed form and renders it; the render must be accepted and equivalent, and the read must not refuse | 1 refusal or loss, 2 non-canonical render |

**Tiers.** Tier 1 is correctness: the component is wrong, not incomplete. Tier 2 is coverage: the component says nothing where A12 refuses, which the checker's report already discloses as `INCONCLUSIVE`. Tier 3 is order: the checker names A12's class plus classes A12's early stop never reached. It is measured, and worked only after tiers 1 and 2 are empty.

**Cluster key** = `verdict · A12's classes · the component's classes`. The edit operator is deliberately not part of the key, because one mechanism shows under many edits. The live run writes each cluster's count and three example mutants to `build/`, and the ledger holds the keys. A new key fails the run as a new defect, and a vanished key fails it until its row is deleted. This is `CheckerPathGridAgreementTest`'s mechanism, generalized.

## Working rules

1. **Discovery happens only through the sweep.** A user or peer report becomes a seed or an edit operator, never a hand grid.
2. **Largest tier-1 cluster first**, then tier 2 by size. A tier-2 cluster below 3 mutants after M4 is declared, not worked.
3. **A batch is the set of ledger clusters one mechanism explains**, and it follows CLAUDE.md's batching rule. Before its first run, its acceptance matrix lists:
   - the ledger keys it targets;
   - the prediction: those keys vanish and no new key appears;
   - one example mutant per key as the discriminator against a rival fix.

   One sweep-lane run and one doc-guard run verify it, and it lands in 1–3 commits. The ledger row is the lock, so there is no per-fact kernel test and mirror pair. A KERNEL-FINDINGS entry is written only for a mechanism that is not obvious from the fix, at skeleton weight.
4. **A letter to a12-kernel-lean goes with a batch only when that batch measured a kernel fact their spec lacks**; most cluster fixes are checker-side and send none. **A cold review goes where CLAUDE.md's review gate puts it:** on the sweep itself (a new guard) and at a milestone's landing, never on a cluster fix the sweep turned red to green.
5. **A checker change is verified on JVM and JS** (`:consistency:check`), because a JVM-only green shipped a JS-only defect on 2026-09-26.
6. **The convergence gate applies to milestones.** If a milestone's cluster count grows over two consecutive landings, stop and re-partition instead of patching.

## Milestones — the work-item table

| id | milestone | exit criterion | state |
|---|---|---|---|
| **M0** | clean baseline | C5 committed; the JS-only lone-surrogate test defect fixed and its class guarded; C4b's tier-1 review item fixed and its false claims corrected; hand batches C7–C10 folded into this plan; letter `-26n` sent | **done** 2026-09-26 (`13a02c3e1`, `6635b7664`, `acaf16a29`) |
| **M1** | **the checker's frontier is known** | `CheckerNearMissSweepTest` runs P1 + P2 through O1; its ledger is committed; the cluster count per tier is recorded below | **done** 2026-09-26: the conditions and rule messages of P1 + P2. The field arm (a variant filter, a field type's own message) lands before M3 closes, because DG114 is tier 1. Element-name edits are M5's first widening (DG113) |
| **M2** | the interpreter's and dmkits' frontier is known | O2 and O3 run on M1's accepted population; ledgers committed; counts recorded | open |
| **M3** | tier 1 is empty | no OVER-REJECT or WRONG-CLASS cluster in O1, and no O2 or O3 tier-1 cluster | open. **Batch G (grammar)**: where A12's parser refuses a text the checker's shape parser admits, or a reference class stands where the parse stopped. G1 landed: one walk of the `entity` production for both readers, empty argument lists, the infix language rows ([KF491](KERNEL-FINDINGS.md#kf491)). G2 landed: a bare entity spec is never an arithmetic operand, `->` needs a name, an arithmetic chain that never closes raises no class. S landed: each typed argument slot read from its production, constants never called, `CustomCondition`'s name slot ([KF492](KERNEL-FINDINGS.md#kf492)); a digit sequence leading an entity slot stays open (DG93). **Batch R (residue)**, next: the wildcard, token and error-field clusters |
| **M4** | tier 2 is closed | every SILENT cluster is fixed or declared | open |
| **M5** | widen and repeat | P3 populations added one at a time, each re-running M3 and M4 | open |

## Triage of what was open on 2026-09-26

| item | oracle · tier | disposition |
|---|---|---|
| DG93 — per-operator argument slots in the shape parser | O1 · 1 | batch S typed the slots; a digit sequence leading an entity slot remains, which needs the reference scanner |
| DG83 — a message class beside a condition the checker leaves to A12 | O1 · 1–2 | not seen as a cluster yet: the message arm appends to the first message of an admitted rule only, so it never pairs a message class with a condition the checker leaves to A12 |
| DG94 — fold `FILTER_WILDCARD`'s star gate into the bracket family | O1 · 3 | worked only if a cluster shows it |
| DG102 residue — a constant key over a typedef or custom index field | O1 · 2 | needs typedef hosts: M5 |
| DG113 — element-name family from A12's path alphabet (three rows) | O1 · 1–2 | the name-edit operator in M5, first widening |
| DG114 — field families judge a field an `ONLY` filter deletes (measured on one family of twenty) | O1 · 1 | the sweep's field arm, before M3 closes |
| IG155 — a keyed group composed with a star | O2 · 1 | M2 surfaces it or confirms its reach |
| IG159 — field-type message rendering | O2 · 1 | M2, message-text signature |
| DG69, DG71, DG75 — typed operands for an index-selected field | O3 · 1 | M2 |
| DG115 — modelkit drops a pattern message it cannot place | O3 · 1 | M2; a silent loss |
| the C4b review of `1eec90e28..43bbfcfcf` (20 items) | O1 · 1 (a variant-filtered field), claims | M0 measured the over-rejection (now DG114), corrected the false and vacuous claims, and logged DG115; the mutant and missing-row items become sweep seeds, not hand grids |

## Results

| date | oracle | seeds | compared | tier-1 clusters | tier-2 | tier-3 |
|---|---|---|---|---|---|---|
| 2026-09-26 | O1, conditions and rule messages (`CheckerNearMissSweepTest`, first freeze) | 139 admitted as written | 4 469 inputs, 362 divergent | 23 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G1 | 139 | 4 469 inputs, 75 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch G2 | 139 | 4 469 inputs, 31 divergent | 6 WRONG-CLASS, 0 OVER-REJECT | 4 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after the M1 review: edits resampled per seed, walked open paths fixed ([KF491](KERNEL-FINDINGS.md#kf491)) | 139 | 4 469 inputs, 21 divergent | 10 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, after batch S ([KF492](KERNEL-FINDINGS.md#kf492)) | 139 | 4 469 inputs, 11 divergent | 7 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
| 2026-09-26 | O1, P2 widened with every truncation and separator insertion; the sampled 4 469 unchanged | 139 | 9 362 inputs, 788 divergent | 11 WRONG-CLASS, 0 OVER-REJECT | 3 SILENT | 0 EXTRA |
