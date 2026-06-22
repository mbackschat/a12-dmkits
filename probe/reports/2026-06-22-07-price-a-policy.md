# Probe report — task 07 (price an insurance policy), 2026-06-22

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN.** A policy model (policyholder + repeatable coverages, a tiered-discount computation, a filtered-aggregate liability-cap rule, number field-properties, a net-premium computation) was authored kernel-valid end to end. Every non-zero exit was a legitimate, well-diagnosed rejection — twice the diagnostic named the exact fix. The one moment that *felt* like a defect (re-scaling a field broke a computation) is **correct kernel behavior** (decimal-scale coupling), now captured as a catalog gotcha.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) |
| start mode | **clean** (from-scratch policy model) |
| anti-cheat audit | **CLEAN** — `Policy_DM.json` mutated only by dmtool; hand-authored files were input spec JSONs; no raw `.dm.json` read (per-kind config confirmed via `field read` — the read-gap is closed) |
| digest | **CLEAN 6/6 · 0 RED FLAGS** (was a SUSPECT raw-read on the pre-fix binary; fix B closed it) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 07-scale | re-scaling a number operand (e.g. `Premium` → 2 decimals) breaks a previously-valid computation that sums it (`MVK_INVALID_COMPARE_DEC_PLACES` — the computed field's scale must match its operands') — correct kernel behavior, discovered reactively | material (catalog) | **DOCUMENTED (correct behavior)** | **FIXED** — Sum gotcha `9b34eea` |
| 07-modify-replace | `field modify` REPLACES the per-kind config block wholesale (not a merge); an under-specified `number:{…}` without `kind` is rejected — the diagnostic catches it perfectly, but `field modify --help` doesn't state the wholesale-replace | CLI | minor DX (diagnostic self-corrects) | not actioned — the `RK_APPLY_OP_FAILED` corrective + `schema field add` already state it; below the bar |
| 07-units | arithmetic mixing number `unit` traits (AMOUNT − PERCENT) is silently accepted — units are advisory, not enforced semantics | material | minor (by kernel design) | not actioned — `unit` is an advisory trait; noted for the modeller, not a defect |
| — | `apply` atomic rollback + per-op staging + `--dry-run` scale-probing all worked exactly as documented | — | **positive** | — |

## The decimal-scale coupling (07-scale) — minimal repro

On a clean model with a repeatable `/Doc/Lines` (`Amount` NUMBER) and a `/Doc/Total = Sum(/Doc/Lines*/Amount)`:

| change | result |
|---|---|
| add the `Sum` computation, both scale 0 | ✅ applied |
| re-scale `Amount` to 2 decimals, `Total` stays scale 0 | ❌ `MVK_INVALID_COMPARE_DEC_PLACES` — *"fractional digits of lhs (0) and rhs (2) are different"*, naming `/Doc/TotalComp` |
| same, re-scaling `Total` to 2 in the **same `apply`** | ✅ committed |

The diagnostic is excellent after the fact; the coupling is just non-obvious up front. (Reproduced against the binary while authoring the catalog gotcha.)

## Replay log (verbatim, in order)
1. create a policy model with a policyholder and a repeatable list of coverages, each with a type and a premium amount. The total premium is the sum of all coverage premiums.
2. the applied discount is the loyalty rate when the holder is a member, otherwise the seasonal promo rate, otherwise nothing.
3. the total of the liability-type coverage premiums must not exceed the policy's liability cap.
4. premiums are euro amounts with two decimals, and the discount is a percentage between 0 and 100.
5. the net premium is the total premium minus the discount.

## Self-audit
CLEAN: every model change via dmtool verbs; hand-authored files were input spec JSONs; no raw `.dm.json` read (per-kind config via `field read`, the tiered computation via `computation explain`). Turn 3's cap rule used the `Having` filter (`Sum(Coverages*/Premium Having [Coverages/Type] == "LIABILITY")`), exercising that operator as the task intends.

## Resolution (2026-06-22)
- **07-scale — FIXED (`9b34eea`).** `Sum` gains a decimal-scale-coupling gotcha (a computed field's scale must match its operands'; re-scaling one breaks a dependent computation — `MVK_INVALID_COMPARE_DEC_PLACES`), verified against the binary. The catalog (per-operator) is the right home for this — no skill change needed (the gotcha surfaces via `dmtool operators Sum`).
- **07-modify-replace / 07-units — not actioned.** Both are minor, by-design behaviors the agent itself classified "not a defect": the modify-replace is caught by an excellent corrective + stated in `schema field add`; `unit` is an advisory trait the kernel deliberately doesn't enforce. Recorded for honesty; below the fix bar.

## Resolution update (2026-06-22) — 07-scale drove a diagnostic-enricher ROOT fix

Re-examining 07-scale exposed that the catalog gotcha (proactive) was only half the four-lane placement: the **reactive** diagnostic an agent gets when it *hits* `MVK_INVALID_COMPARE_DEC_PLACES` was mis-stamped. The catalog-join (`DiagnosticEnricher`) was attaching the **comparison** operators' fix ("round one with RoundDown") to the **computation** case, where the real fix is to match the computed field's scale. Root-causing that surfaced the deeper flaw — `OperatorCatalog.byInvalidError` resolved a code→operator many-to-one map with `findFirst()`, stamping whichever operator was first in `operators.json` — and a **predicted, reproduced second instance**: `NumberOfFilledGroups(field)` was getting `GroupFilled`'s fix (`MVK_NO_FIELDS_ALLOWED`). Fixed at the root:

- the catalog-join is now **sound** — one documenter (or several that agree) attaches the single fix; several that **disagree** attach the **union** (the cross-operator `MVK_NO_FIELDS_ALLOWED` case, fixed with no per-code entry); `byInvalidError`/`findFirst` removed.
- `MVK_INVALID_COMPARE_DEC_PLACES` joined `StructuralKernelFix` as a **cross-context** code (its computed-field-scale trigger is invisible to the catalog) with the two-context hint (match the computed field's scale; or, for `==`/`!=`, `RoundDown` / `allowDifferingDecimals`). `diagnostics MVK_INVALID_COMPARE_DEC_PLACES` now resolves.
- locked by `DiagnosticEnricherTest` (the two-context hint + the catalog-derived `noCrossOperatorCodeSilentlyDropsACorrective` guard). The mechanism + the three-roots decision table live in KERNEL-INTEGRATION-NOTES ("The enricher lane at a glance").
