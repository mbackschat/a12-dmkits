# Iteration-guard port — proposal and state carrier

**Status:** PROPOSAL, opened 2026-10-22 by the owner's decision to re-open both codes for the portable checker ([CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) DG146). The work-item table in §4 is this port's state, updated in the commit that lands each item.

## 1. What and why

A12 refuses an iterating rule whose condition can be true for a repetition nobody filled. It reports `MVK_NEG_CONDITION_IN_ITERATION` when the iteration is the rule's ordinary one, and `MVK_PARALLEL_ITERATION_INVALID` when it runs across parallel groups ([KF428](KERNEL-FINDINGS.md#kf428)). Until this port the portable checker reported neither, and said so in every report. Agents writing rules hit the first code on a first attempt in several of our evaluations ([eval/RESULTS.md](../eval/RESULTS.md)), so a Kernel-free author gets no feedback on a trap they demonstrably fall into.

**Required:** both codes, for rules in ordinary (non-H) models, in the checker's `commonMain`, with the finding A12 states and at the rule A12 names. **Never an over-rejection:** a model A12 admits must draw neither code, shown by differential before landing.

**Excluded:** the H-model codes (`MVK_NEG_CONDITION_IN_ITERATION_LFDNR`/`_ZEILE`, `MVK_UITERATION_INVALID`), which stay delegated. Computations are judged on the condition A12 joins them into, whose leading `FieldFilled` of the computed field guards them; three measured preconditions are admitted on both sides ([KF595](KERNEL-FINDINGS.md#kf595)).

## 2. The mechanism, as learned from source (clean-room: ours, not a port of the expression)

Read on 2026-10-22: `ParserService.checkNormalRule` and `IterationNotValidVisitor` (kernel 30.8.1, layer: the parser's post-parse checks). W4's differential confirmed each statement below on its rows, and corrected the exempt level.

- **Placement.** It runs after every other post-parse rule check. A12 stops at the first error per rule, so either code is withheld whenever an earlier class answers. The parallel code is decided before the ordinary one.
- **Which levels are judged.** A level counts when a reference outside a filter body (or `$`-marked inside one) iterates it. A level whose declared repetition count is exactly 2 and whose group declares an index field is exempt, for the level's own field as well as a deeper one ([KF595](KERNEL-FINDINGS.md#kf595); the source reading had suggested an exception at a field's own level, which the kernel did not bear out). Levels are tried shallowest first, and the first refused level is the one A12 names in the message.
- **The verdict over connectives.** Every leaf starts admitted, and some leaf kinds refuse depending on how their references iterate. An `And` is admitted when at least one child is admitted *and* iterates the judged level. An `Or` is admitted only when every child is admitted *and* iterates it. This is the account [KF537](KERNEL-FINDINGS.md#kf537) measured.
- **Leaf classes.** Negative presence over an iterating reference refuses (`FieldNotFilled` is read as `NoFieldFilled`, so it belongs here). Positive presence refuses when it counts references that do not iterate, at a threshold of one or two depending on the operator. Then there are the comparison arms: counts compared with `<`, `<=` or `!=` against a positive constant; sums and similar compared with `==`, `<=` or `>=` against zero; any count or sum against a constant over mixed iterating and non-iterating references. Finally `Invalid`, `CurrentRepetition` over the judged level, and the value-list forms. The verdict walk never enters a filter body, while the level collection and the parallel check do read `$`-marked references inside one.
- **Wildcards multiply.** A starred reference counts as as many references as its starred levels can repeat, which is why one starred operand can meet a threshold of two.

## 3. Design

The port builds on what the checker already reads, mapped by a read-only scout on 2026-10-22, and adds only the connective tree and the verdict walk.

| needed | reused from | added |
|---|---|---|
| the condition's connective structure | the recognizer's single pass | `syntax/ConditionTree.kt` (W1): `Junction`/`Call`/`Comparison`/`Custom`, brackets as written, filter bodies apart |
| which levels a reference iterates, stars or fixes | `starredLevelsOf`, `resolveEntity`, the declared `repeatability` and index field | `GuardReference`: one `GuardLevel` per group on its path, marked `ITERATED`/`PARALLEL`/`STARRED`/`FIXED` |
| which levels are parallel | `FilteredEntityIteration.markedLevelsOf`, A12's two passes ([KF376](KERNEL-FINDINGS.md#kf376)) | nothing; the guard reads the same marking, so the law is stated once |
| a group operand's count | `fieldsUnder`, A12's `expandToFieldList` | `GuardReference.fields`, measured on `FieldValuesNotUnique` only |
| A12's first-error stop | `ConditionExecution`'s `PostParseCheck` order | `PARALLEL_ITERATION_GUARD` then `ITERATION_GUARD`, after `ERROR_TARGET_ITERATION` |
| disclosure where the guard does not judge | `CheckProducer.Condition`'s `gaps` hook | `iterationGuardGaps`: a `CoverageGap` at the rule, replacing the report's model-wide one |

**Three-valued verdict.** `admitted()` answers true, false, or null where this reading has no verdict (an unmeasured leaf or operation, a mixed connective, a constant beyond the exact range whose value would decide). Null anywhere on the walk declines the whole rule, so the family is silent and the gap speaks. Declining is the only direction an unknown may take: a guess that refuses would be an over-rejection, which is the one outcome the port must never produce.

**Declined before the walk.** The guard declines a rule whose error target is a group, and a rule whose reference carries a `$` marker or a selection. It also declines a rule-context reference, a root group read as a condition, a reference the parallel reading refuses, a rule that iterates a level off the error field's path without iterating it jointly, and a rule where no reference naming the error field iterates while another does (A12 decides that error-field binding first, [KF417](KERNEL-FINDINGS.md#kf417)). A12 answers most of these with an earlier class; the rest are DG146.

**Not reused:** the interpreter's AST (the checker owns its syntax, [ARCHITECTURE](ARCHITECTURE.md)) and the interpreter's runtime polarity walk, which judges filled rows rather than the static question.

## 4. Work items (the state carrier)

| id | item | state | acceptance (oracle) |
|---|---|---|---|
| W1 | Condition tree from the recognizer: `And`/`Or` junctions over leaves that point at their `CallShape`/`ComparisonShape`, brackets kept as written, filter bodies kept apart. **Foundational: cold review after it lands** | landed 2026-10-22, cold-reviewed | `ConditionTreeTest`: nesting, brackets, a mix kept as written (A12 refuses it earlier as `MVK_BRACKET_MISSING`), connective spelling and case, filter bodies excluded, a late failure withholding the tree, and the positive-presence proof over the tree (`MessageParameterDecoderTest` and its three kernel differentials re-derive it with no verdict changed) |
| W2 | Per-reference iteration reading: for each leaf reference, which judged levels it iterates (ordinary, parallel, starred with its multiplier, fixed), plus the exempt-level rule | landed 2026-10-22 | `IterationGuard.kt`'s `GuardReference`; the exempt level measured with and without an index field, at 2 and at 50 repetitions ([KF595](KERNEL-FINDINGS.md#kf595)) |
| W3 | The law: two new `PostParseCheck` stages after `ERROR_TARGET_ITERATION`, parallel then ordinary; findings with A12's code and level path | landed 2026-10-22 | `IterationGuardTest`, `CheckFamilyWiringTest`, the TESTING-SPEC new-family checklist |
| W4 | Differential: leaf classes × iterate/star/fixed/parallel × `And`/`Or`/nesting × exempt levels × rule vs computation, plus the two sibling differentials with their NEGATIVE tolerance removed | landed 2026-10-22 | `CheckerIterationGuardAgreementTest` agrees on every row; `CheckerIterationBindingAgreementTest` and `CheckerUniquenessGuardAgreementTest` accept silence only where the checker names the rule in a gap. No over-rejection; the declined shapes are DG146 |
| W5 | Land: the per-rule gap replaces the report's model-wide one, MVK-LEDGER rows to checker-reported, [KF595](KERNEL-FINDINGS.md#kf595), parity corpus, letter to a12-kernel-lean | landed 2026-10-22; the letter is written and with the owner to carry | Doc guards, `CheckerLedgerDispositionGuardTest`, consumer parity |

## 5. Acceptance matrix

Each row names its oracle; a review finding either violates a row or is logged to DG146 / the owning ledger.

| # | claim | oracle | non-goal |
|---|---|---|---|
| A1 | Both codes, at A12's rule and level, on every measured shape | `CheckerIterationGuardAgreementTest`: A12 pinned per row, the checker agreeing | shapes outside the measured leaf set |
| A2 | No over-rejection anywhere | that differential; `CheckerIterationBindingAgreementTest` and `CheckerUniquenessGuardAgreementTest`, strict; the `:adapter:checkerSilenceGuardTest` sweeps | — |
| A3 | A declined rule draws neither code and carries a gap at its rule | the `DECLINED` rows; `IterationGuardTest` | judging those shapes |
| A4 | No silence without a gap where A12 refuses | the sibling differentials accept silence only beside the rule's gap | — |
| A5 | The model-wide gap is retired | the parity corpus | — |
| A6 | Shared code only | `:consistency:check` (JS compile, `PlatformCharacterTableJvmTest`) | — |
| A7 | One statement of the parallel marking | the guard calls `markedLevelsOf`; the filtered-entity differentials stay green | — |
| A8 | The new-family checklist (TESTING-SPEC) and its guards | `CheckFamilyWiringTest`, `CorrectiveTest`, `BuilderCheckerDivergenceGuardTest`, `CheckerLedgerDispositionGuardTest`, doc guards | — |

**Terminal review, round 1 (2026-10-22): the fix batch.** The review found no over-rejection. These are its in-scope findings and their disposition, each settled by kernel rows predicted before the run:

| finding | disposition |
|---|---|
| a bracketed operand or constant was not read through its bracket (silent, no gap) | read through; three rows |
| a constant with a spaced sign or beyond the exact range was not read (silent, no gap) | read from its digits; beyond the range it is a constant of unknown value that declines where its value would decide |
| "computations are not judged" generalized one row | judged on the joined condition; three rows; KF595 states the observation and marks the reading |
| judged rows did not assert the absence of a gap | every judged row now must carry none |
| unlocked arms | a row per arm; `SumOfProducts` declines, because every authorable row was answered earlier |
| the level order and parallel-first were untested | A12's message pinned; a row where both refuse |
| the gap text listed fewer causes than the code takes | non-exhaustive wording; DG146 owns the list |
| the comparison followed the kernel's block layout and `-1` sentinel | one orientation loop with mirrored operators |

**Review plan:** one cold review after W1 (the tree is the shape everything else stands on) and one terminal review before W5 lands.
