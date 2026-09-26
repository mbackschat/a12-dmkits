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
| **P1 seeds** | every rule in the committed model trees (`corpus/models`, `cli/…/models`, `src/test/resources/models`) and every catalog `validExample`, hosted as N7 hosts it (`CatalogExamplesAreKernelValidTest`) | M1 |
| **P2 near-misses** | single edits of each P1 seed: delete, duplicate or swap a token; substitute an operator or a model name; insert a connective, bracket, comma or star; and for messages, insert `$`, a parameter, a tab or a supplementary character. Deterministic, seeded, capped per seed | M1 |
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
| **M1** | **the checker's frontier is known** | `CheckerNearMissSweepTest` runs P1 + P2 through O1; its ledger is committed; the cluster count per tier is recorded below | **done** 2026-09-26: the conditions and rule messages of P1 + P2. The field arm (variant filter, element names, a field type's own message) is M5's first widening |
| **M2** | the interpreter's and dmkits' frontier is known | O2 and O3 run on M1's accepted population; ledgers committed; counts recorded | open |
| **M3** | tier 1 is empty | no OVER-REJECT or WRONG-CLASS cluster in O1, and no O2 or O3 tier-1 cluster | open. **Batch G (grammar) first**: 18 clusters, 340 of the 362 divergent inputs. A12's parser refuses the text (`MVK_UNEXPECTED_TOKEN`) where the checker's shape parser admits it and then names a semantic class (17 WRONG-CLASS) or nothing (1 SILENT). It subsumes DG83 and DG93. **Then batch R (residue)**: 5 WRONG-CLASS clusters about wildcards, tokens and the error field |
| **M4** | tier 2 is closed | every SILENT cluster is fixed or declared | open |
| **M5** | widen and repeat | P3 populations added one at a time, each re-running M3 and M4 | open |

## Triage of what was open on 2026-09-26

| item | oracle · tier | disposition |
|---|---|---|
| DG83, DG93 — per-operator argument slots in the shape parser | O1 · 1–2 | expected as P2 clusters; fixed when they surface |
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
