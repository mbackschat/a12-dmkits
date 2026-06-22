# Probe report — task 07 (price an insurance policy), re-run 2026-06-22

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the freshly-rebuilt native binary (after this session's diagnostic-enricher root fix). The full calculation cluster — `Sum` aggregate, a tiered/`FirstFilledValue`-style discount cascade, a `Having`-filtered aggregate rule, decimals/scale + units, and a computed field that reads another computed field — authored kernel-valid end to end. **The dec-scale fix validated in the wild:** the `Sum` decimal-scale-coupling gotcha (catalog, #81) steered the agent to re-scale operand + computed field together, avoiding `MVK_INVALID_COMPARE_DEC_PLACES` proactively — and the agent confirmed subtraction across differing scales is *accepted*, i.e. the coupling is `==`/`!=`/assignment-only (matching the enricher hint's scope). One new highest-value finding (07-1, the apply terminal-gate diagnostic), root-fixed this session.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — the rebuilt gate-GREEN binary |
| start mode | **clean** (from-scratch policy model) |
| anti-cheat audit | **CLEAN 6/6** — `Policy_DM.json` mutated only by dmtool; hand-authored files were input specs; no raw `.dm.json` read (per-kind config via `field read`, the tiered computation via `computation explain`) |
| digest | **CLEAN · 0 RED FLAGS** — 5 nonzero exits, all legitimate (duplicate-name probes, one self-rerun that rolled back atomically, a pre-create path check) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 07-1 | a rejection at the **apply terminal gate** flattens to a cause-less `"the edited model is not kernel-valid"` (`code:null`, `where:{}`, `fix:null`) and doesn't attribute the failing op — unlike standalone `field add`, whose per-edit gate names the cause (probe-04 fix) | CLI (diagnostics) | **DEFECT (cause-less)** | **FIXED** — root-cause sweep of the probe-04 class: the cause-append now lives in one shared factory (`ModelConsistencyException.notKernelValid`) both gates call |
| 07-dryrun | `apply --dry-run` prints `committed:true` alongside `written:false` — only `written` is authoritative; `committed` reads as "persisted" when it means "the gate would accept" | CLI | minor DX (wording) | recorded — below the bar (semantics correct, naming momentarily confusing) |
| 07-comp-read | `computation read` echoes `{computation, converted, calculatedField}` but **no formula** — had to use `computation format` to confirm what persisted (unlike `field read`, which echoes config) | material | minor (read-gap) | **FIXED** — `computation read` now echoes the formula (commonPrecondition + each alternative's rendered operation) + messages (slot-parity sweep) |
| 07-vocab | `model describe` says `"kind":"NUMBER"`; `field read` says `"type":"NumberType"` for the same field | material | minor (vocab) | recorded |
| — | the `Sum` dec-scale gotcha (#81) steered past `MVK_INVALID_COMPARE_DEC_PLACES`; `apply` atomic rollback + per-op staging + `--dry-run` scale-probing all worked | — | **positive / prior fix confirmed** | — |

## 07-1 — minimal repro (verified) + the root fix

```
# a model with field /Doc/Amount, then apply adding Amount again (duplicate slips the per-op precheck,
# caught only by the TERMINAL kernel gate):
dmtool -m dup.dm.json apply [ {"target":"field","op":"add","group":"/Doc","name":"Amount","kind":"STRING"} ]
#  BEFORE: results[].summary "the terminal kernel gate rejected the sequence", diagnostic
#          summary "the edited model is not kernel-valid"  (code:null, where:{}, fix:null)  — cause-less
#  AFTER:  "...: Cannot have two child elements with the same name [Amount] in group '/Doc'."  — cause named
```

**Root cause (not the symptom):** two duplicated copies of the kernel-gate wrapper — `ModelDocument.validate` (per-edit) and `EditSession.validate` (session terminal, backs `apply`/`batch`). Probe-04 appended the cause to the first; the second stayed bare. The duplication *was* the defect, so the fix collapses both onto one `ModelConsistencyException.notKernelValid(cause)` factory — the cause-append can't drift between them again. Guard: `ApplyVerbTest.theApplyTerminalGateSurfacesTheKernelCauseNotABareNotValid` (the terminal-gate sibling) complements the probe-04 single-edit test.

## Replay log (verbatim, in order)
1. create a policy model with a policyholder and a repeatable list of coverages, each with a type and a premium amount. The total premium is the sum of all coverage premiums.
2. the applied discount is the loyalty rate when the holder is a member, otherwise the seasonal promo rate, otherwise nothing.
3. the total of the liability-type coverage premiums must not exceed the policy's liability cap.
4. premiums are euro amounts with two decimals, and the discount is a percentage between 0 and 100.
5. the net premium is the total premium minus the discount.

## Self-audit
CLEAN: every model change via dmtool verbs; hand-authored files were input spec JSONs; no raw `.dm.json` read. Turn 3's cap rule used the `Having` filter (`Sum(Coverages*/Premium Having [Coverages/Type] == "LIABILITY")`). Confirmed by the anti-cheat audit (6/6) and digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **07-1 — FIXED.** The apply/batch terminal gate now surfaces the kernel cause via the shared `ModelConsistencyException.notKernelValid` factory (root-cause sweep of the probe-04 cause-surfacing class — the un-swept sibling). Locked by `ApplyVerbTest`. KERNEL-INTEGRATION-NOTES updated.
- **07-comp-read — FIXED.** `computation read` now echoes the formula (the `commonPrecondition` + each alternative's rendered `operation`) + its messages — a slot-parity sweep of the read verbs, so the computed value is confirmable through `computation read` alone (no `computation format` needed). Locked by `ComputationVerbTest`.
- **07-dryrun / 07-vocab — recorded, below the immediate fix bar.** Minor DX/vocab; the agent itself rated them non-blocking.
- **(prior fixes confirmed in the wild)** the `Sum` dec-scale gotcha (#81) steered the agent past the scale-coupling trap; the dec-places coupling is `==`/`!=`/assignment-only (subtraction across scales accepted) — matching this session's enricher hint.
