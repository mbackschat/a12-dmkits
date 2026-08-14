# Session handoff — 2026-08-14, the group-operand class

> **What this is.** A one-session handoff: what landed, the references to pick up, and what is next. **Delete this file once its "next" list is drained** — it is scaffolding, not a durable surface. Everything durable is already in the specs, findings and ledgers this file points at.

## State

**52 commits unpushed, tree clean.** Full estate green as of the checkpoint: `:interpreter:check :cli:check :test :schemakit:test` in 10m 47s, `:adapter:test` isolated in 5m 6s, all 13 demos matching, perf gate green alone (22 growth observations 4.55×–5.76× against the 9.0 floor, zero anchor breaches).

Six commits landed after that checkpoint (`2ea48c74`, `27a0e210`, `acf648be`, `d63aca5d`, `055cb77a`, `9958d698`), each on targeted green plus the doc guards. **A fresh full-estate run is the first thing a next session should do** — those six touched rulekit's public API, the interpreter's quantifier tally, the cli move path, and the build's test wiring.

## What the session was

One defect class, swept across every component that touches it: **an operand may name a GROUP, and expansion is opt-in at every site that resolves one.** Twenty-five interpreter instances were already measured before this session ([IF287](INTERPRETER-FINDINGS.md#if287)); this session took the class into rulekit's typed surface, the adapter's converter and the CLI.

### The headline: the delete-a-live-field pipeline is closed, and it is now an observation

It was a mechanism in a ledger row. Measured:

```
field remove /Order/ShippingAddress/Street     # read only through AllFieldsFilled(/Order/ShippingAddress)
→ outcome: applied · verification: KERNEL_CONFIRMED · written: true · diagnostics: []
```

The field gone, the rule silently checking one fewer member, the envelope certifying it. The terminal kernel check has nothing to object to because the rule still names a group that still exists.

**The complementary correction matters as much:** removing a field a rule names *directly* was never data loss — that rule dangles and the kernel rejects the write. The group-reached direction is the whole defect. The row had blamed the ungated `apply` path; measuring showed that path only produced worse diagnostics.

### Landed

| what | where to read it |
|---|---|
| Reachability primitive beside the exact `whereUsed`; `model usage`, `ModelReport` dead-field set, `FieldDiffProjector`, `field modify` retype advisory | `KernelAdapter.referrersReaching` / `isReadByAnything`; CLI-SPEC §6 |
| Delete gates on both routes (`FieldRemoval.plan` shared by the verb and `apply`, which ran **no gate at all**); `GroupRemoval` widened to ancestor reach | `cli.ApplyFieldRemoveGateTest` |
| `rule eval` reported `PASSED` where the kernel SUPPRESSED — group path vs field path compared with `equals` | `adapter.laws.RuleTestGroupOperandSuppressionTest` |
| `group extract` gate missed computations **and** refused the subtree root (`"/Order/Address".startsWith("/Order/Address/")` is false) | `cli.group.GroupExtractComputationGateTest` |
| Converter mixed-scope arm dropped the star its group-only twin kept | `adapter.MixedScopeStarPreservationTest` |
| `move` now warns when it changes a group operand's expansion (`RK_MOVE_CHANGES_GROUP_EXPANSION`) | `cli.StarReferenceRenameGateTest`, CLI-SPEC §6 |
| Typed surface: `NumberOfValueInFields` and the six DATE extrema take a starred group | `DG34` closed; `ValueInFieldsGroupOperandLawsTest`, `DateExtremaGroupOperandLawsTest` |
| Interpreter: two filtered stars now evaluate (kernel admits and answers; we degraded) | `adapter.laws.TwoFilteredStarsQuantifierDiffTest` |
| `EntitySource.path` hoisted — the accessor whose absence caused two instances | `Ast.kt` |
| Perf record prunes itself at write time | `PerfObservations.pruneToRetentionBound` |
| `-Pfast` extended to cli and root lanes | TESTING-SPEC.md:366–375 |
| Shipped-gap retirement guarded | `GapLedgerTemplateGuardTest.noShippedItemRemainsInAnOpenSection` |

### Measured negatives — as valuable as the fixes

- **Partial validation over a group operand:** the expansion runs before the per-operand pass and could plausibly move the extent or relevance verdict. It does not, under either relevance shape (`PartialValidationGroupOperandDiffTest`).
- **`IterationScopes`' starred-operand drop is unreachable** — `starRefs` runs first and catches every star. Deliberately *not* "fixed": an unreachable branch with a test costs more than the gap.
- **`ReferenceRewriter`'s advertised `*`-reference refusal:** measured FALSE. The rename carries the starred reference across. Resolved by deleting the advertisement.
- **`Min`/`Max` over a group:** refused by the kernel on the rule *and* computation carriers (`MinMaxGroupOperandComputeCarrierLawsTest`).

## References to pick up

- [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md#if287) IF287 — the class, its twenty-five instances, and the two enumeration lessons.
- [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md#kf187) KF187 — per-carrier group-operand admission. **Read this before probing any new carrier**; two carriers disagree with their own family member, so "the family admits it" is never a safe inference.
- [`a12-kernel-lean-spec-corrections-2026-08-12.md`](a12-kernel-lean-spec-corrections-2026-08-12.md) — the outbound handback, now in-repo. Its 2026-08-14 revisions are **not yet reachable from `origin/main`**; re-check before citing them to the peer.
- [`CLI-SPEC.md`](CLI-SPEC.md) §6 — the refactor safety table, now carrying the reachability contract and the move advisory.
- [`TESTING-SPEC.md`](TESTING-SPEC.md) — the `-Pfast` loop table. **Use it.** The module-wide run belongs to the checkpoint, once.

## What is next

Open ledger rows, in the order I would take them:

1. **CG15** — the converter arms that still report *unsupported* or lose a star. Highest value: `:1147` converts `NumberOfFilledGroups(Group*)` through `model.group(getFullName())`, dropping the star its own comment promises, so the round trip renders a form the kernel rejects. Also needs the typed surface (`Dsl.numberOfFilledGroups` takes a `GroupRef`, which carries no star). **Measure each shape's admission against KF187 first.**
2. **CG16** — `ModelDiff`'s group arm, `RelocationMatcher.refsOf` discarding `groupPaths()`, and `rule check --suggest-error-field` steering an author off the only valid picks. One unmeasured claim in that row: whether `rule deps` and `where-used` produce different *answers*. Half is settled by reading (different primitives); the answer difference was never probed.
3. **DG35** — the three plural value-list quantifiers. **This is a type decision, not overloads:** `ValueListOperand` permits `FieldOperand, EnumCategoryAccess`; `AggregateOperand` permits `FieldOperand, GroupListRef`; disjoint. The tempting widening also admits a group into `FieldListEntityListCondition`'s slots, a carrier whose admission is unmeasured. Measure that carrier, then decide.
4. **IG136(a)** — a perf scenario that actually routes the expansion. **This was in flight when the session ended.** The existing `GroupScopedFillPerfTest` uses `AtLeastOneFieldFilled(/Order/Items*)`, which routes through `fillScope`, not `expandGroupStars` — that is why I re-opened the item after closing it on a wrong basis. The scenario needs `NumberOfFilledFields(<group>*)`. Useful argument for the "path is reached" gate: a non-expanding read returns 0, so `PerfHarness.assertSemanticAgreement` passing *is* the evidence the expansion ran. Measure with `./scripts/perf.sh gate` **alone**, and hoist setup to lazy fields.
5. **IG136(b)** — two polarity residuals in `PolarityWalk.legacyStarFill`: a non-numeric non-DATE group kind, and `firstFilledEntityKind`'s order-dependence on a mixed-kind group. Establish admission first; several neighbours turned out refused.
6. **IG137(a)–(d)** — perf tooling ergonomics. Take **(b)** first: `scripts/perf-report.sh` does not exist, and its absence is why a maintainer hand-rolls `awk` over the committed JSONL, which already produced one wrong number.
7. Two consolidations: the **path-containment predicate** now has three implementations across three modules (the relation that produced two defects in one day), and the remaining **`pathOf` call sites** need a one-line disposition each ("scalar slot, cannot receive a star" for most).

## Two habits worth carrying

**Enumerate before fixing.** Every time a defect was found by symptom, the cheap search returned more of the same class — `grep ctx.filled` beside `evalExpr` returned five more sites, four of which were reachable from the question an earlier audit had asked and answered for one function. The `getFullName()` sweep after the converter fix found the `NumberOfFilledGroups` sibling.

**The cold review earned its cost.** It found four real holes in the reachability slice, including one error code with two definitions in two verbs, and a missing union row that meant a first-ancestor-wins implementation would have passed the whole test file. Also: I dispatched it and kept editing the same tree — "frozen diff" has to mean frozen.
