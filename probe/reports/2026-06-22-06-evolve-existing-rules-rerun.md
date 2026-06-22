# Probe report — task 06 (evolve existing rules), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the release-candidate native binary, maintaining the seeded `order-ruled` fixture. The whole maintain/evolve surface — a per-row count rule (with the empty-number-as-0 guard), SKU uniqueness, the property-vs-rule split for conditional requiredness, a rush-priority per-row rule, and a rename-with-reference-rewrite — authored kernel-valid; every non-success was correct behavior the agent recovered from. One minor (borderline) skill note; the rest are positive confirmations of prior fixes.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — release candidate, native gate GREEN |
| start mode | **fixture** — seeded from `cli/src/test/resources/models/order-ruled.dm.json` |
| anti-cheat audit | **CLEAN 6/6** — `order-ruled.dm.json` mutated only by dmtool; no hand-edit; no raw `.dm.json` read |
| digest | **CLEAN · 0 RED FLAGS** — 2 nonzero exits, both legitimate (the `MVK_NEG_CONDITION_IN_ITERATION` reject it recovered from with a `GroupFilled` guard; a `field read --path` locator slip the corrector fixed) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 06-F1 | the skill tells you to use `field read` to confirm a config persisted, but never shows the **positional** `field read /Group/Field` form, so the agent guessed `--path` (one wasted call, instantly corrected by the locator corrector) | skill | minor / borderline | recorded — the agent itself notes `--help` carries it and the diagnostic corrects it (four-lane: diagnostic-carried → likely no skill note); a one-line skill example is the only candidate |
| — | the enriched diagnostics carried the fix verbatim each time: `MVK_NEG_CONDITION_IN_ITERATION` named the `GroupFilled(<group>) And …` guard; `RK_UNGUARDED_NUMBER_COMPARISON` spelled out the decide-by-intent choice; the catalog's `FieldValueIncludedInValueList(Priority,"EXPRESS","OVERNIGHT")` was the literal `validExample` | — | **prior fixes confirmed in the wild** | — |
| — | the skill's "is this even a rule?" gate drove the T3 split correctly (unconditional "required" → the field property; "only when express on" → a conditional-requiredness rule); the polarity table pre-empted `True`/`False` casing | — | **positive** | — |
| — | `where-used` + `field rename --dry-run` made the rename safe + auditable (saw the 2 referrers, previewed the rewrite, confirmed it landed + re-validated) | — | **positive** | — |

## Replay log (verbatim, in order)
1. each line item must have a count greater than zero.
2. no two line items may have the same SKU.
3. the backorder quantity should be required. — actually, only require it when express shipping is on.
4. when the order priority is EXPRESS or OVERNIGHT, every line item must have a SKU.
5. rename the line-item SKU field to ProductSku everywhere.

## Self-audit
CLEAN: every mutation via dmtool verbs (`field modify`, `apply`, `rule add`, `field rename`); the `*.json` files were rule/field input specs; no raw `.dm.json` read. Confirmed by the audit (6/6) and digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **CLEAN re-run on the release binary** — no defect; the maintain/refactor surface (rules-over-repeats, conditional requiredness, rename-rewrite) and the enriched diagnostics all behaved as specified.
- **06-F1 — recorded, borderline.** The locator slip is diagnostic-corrected (the #80 corrector fired) and `--help` carries the form; per the four-lane placement rule it likely warrants no skill note. (Note: the *sibling* gap — `where-used --field` getting NO corrector — is filed in the 04 re-run as 04-whereused-locator, the actionable root-cause item.)
