# Iteration-guard port — proposal and state carrier

**Status:** PROPOSAL, opened 2026-10-22 by the owner's decision to re-open both codes for the portable checker ([CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) DG146). The work-item table in §4 is this port's state, updated in the commit that lands each item.

## 1. What and why

A12 refuses an iterating rule whose condition can be true for a repetition nobody filled. It reports `MVK_NEG_CONDITION_IN_ITERATION` when the iteration is the rule's ordinary one, and `MVK_PARALLEL_ITERATION_INVALID` when it runs across parallel groups ([KF428](KERNEL-FINDINGS.md#kf428)). The portable checker reports neither today and says so in every report. Agents writing rules hit the first code on a first attempt in several of our evaluations ([eval/RESULTS.md](../eval/RESULTS.md)), so a Kernel-free author gets no feedback on a trap they demonstrably fall into.

**Required:** both codes, for rules in ordinary (non-H) models, in the checker's `commonMain`, with the finding A12 states and at the rule A12 names. **Never an over-rejection:** a model A12 admits must draw neither code, shown by differential before landing.

**Excluded:** the H-model codes (`MVK_NEG_CONDITION_IN_ITERATION_LFDNR`/`_ZEILE`, `MVK_UITERATION_INVALID`), which stay delegated. Computations stay out unless the kernel differential shows their preconditions reach the same check (measured in W4, not assumed).

## 2. The mechanism, as learned from source (clean-room: ours, not a port of the expression)

Read on 2026-10-22: `ParserService.checkNormalRule` and `IterationNotValidVisitor` (kernel 30.8.1, layer: the parser's post-parse checks). Each statement below is a hypothesis about composition until W4's differential confirms it.

- **Placement.** It runs after every other post-parse rule check. A12 stops at the first error per rule, so either code is withheld whenever an earlier class answers. The parallel code is decided before the ordinary one.
- **Which levels are judged.** A level counts when a reference outside a filter body (or `$`-marked inside one) iterates it. A level whose declared repetition count is exactly 2 and whose group declares an index field is exempt, except at a field's own last level. Levels are tried shallowest first, and the first refused level is the one A12 names in the message.
- **The verdict over connectives.** Every leaf starts admitted, and some leaf kinds refuse depending on how their references iterate. An `And` is admitted when at least one child is admitted *and* iterates the judged level. An `Or` is admitted only when every child is admitted *and* iterates it. This is the account [KF537](KERNEL-FINDINGS.md#kf537) measured.
- **Leaf classes.** Negative presence over an iterating reference refuses (`FieldNotFilled` is read as `NoFieldFilled`, so it belongs here). Positive presence refuses when it counts references that do not iterate, at a threshold of one or two depending on the operator. Then there are the comparison arms: counts compared with `<`, `<=` or `!=` against a positive constant; sums and similar compared with `==`, `<=` or `>=` against zero; any count or sum against a constant over mixed iterating and non-iterating references. Finally `Invalid`, `CurrentRepetition` over the judged level, and the value-list forms. The verdict walk never enters a filter body, while the level collection and the parallel check do read `$`-marked references inside one.
- **Wildcards multiply.** A starred reference counts as as many references as its starred levels can repeat, which is why one starred operand can meet a threshold of two.

## 3. What the checker has, and what it lacks

It has references with resolved targets, the rule's own iterating levels (`rulesOwnIterationLevels`), starred and parallel-level helpers in `Reference.kt`, and a post-parse stage order (`ConditionExecution.PostParseCheck`) that already reproduces A12's first-error stop. The recognizer recorded calls and comparisons only as flat shapes; W1 gave it the connective tree (`syntax/ConditionTree.kt`), built in the same recursive pass that folded the positive-presence proof, which now folds over the tree. The interpreter's AST is not available, because the checker owns its analysis syntax outright ([ARCHITECTURE](ARCHITECTURE.md)).

## 4. Work items (the state carrier)

| id | item | state | acceptance (oracle) |
|---|---|---|---|
| W1 | Condition tree from the recognizer: `And`/`Or` junctions over leaves that point at their `CallShape`/`ComparisonShape`, brackets kept as written, filter bodies kept apart. **Foundational: cold review after it lands** | landed 2026-10-22, cold-reviewed | `ConditionTreeTest`: nesting, brackets, a mix kept as written (A12 refuses it earlier as `MVK_BRACKET_MISSING`), connective spelling and case, filter bodies excluded, a late failure withholding the tree, and the positive-presence proof over the tree (`MessageParameterDecoderTest` and its three kernel differentials re-derive it with no verdict changed) |
| W2 | Per-reference iteration reading: for each leaf reference, which judged levels it iterates (ordinary, parallel, starred with its multiplier, fixed), plus the exempt-level rule | open | Unit rows per reading; the exempt level measured on the kernel with and without an index field, at 2 and at 3 repetitions |
| W3 | The law: two new `PostParseCheck` stages after `ERROR_TARGET_ITERATION`, parallel then ordinary; findings with A12's code and level path | open | Kernel differential (W4) agrees on every row; `CheckFamilyWiringTest` and the TESTING-SPEC new-family checklist |
| W4 | Differential: a generated grid (every leaf class × iterate/star/fixed/parallel × `And`/`Or`/nesting × exempt levels × rule vs computation), the existing law rows, and `CheckerIterationBindingAgreementTest` with its NEGATIVE tolerance removed | open | Zero OVER-REJECT rows; SILENT and WRONG-CLASS rows each named and dispositioned; the six silence sweeps (`:adapter:checkerSilenceGuardTest`) stay green |
| W5 | Land: retire the report's coverage gap and DG146, MVK-LEDGER rows to checker-enforced, KERNEL-FINDINGS entry for anything W4 measured new, parity corpus, letter to a12-kernel-lean | open | Doc guards, `CheckerLedgerDispositionGuardTest`, consumer parity |

**Review plan:** one cold review after W1 (the tree is the shape everything else stands on) and one terminal review before W5 lands.
